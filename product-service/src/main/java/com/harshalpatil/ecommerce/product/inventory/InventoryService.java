package com.harshalpatil.ecommerce.product.inventory;

import com.harshalpatil.ecommerce.common.messaging.Topics;
import com.harshalpatil.ecommerce.common.messaging.inventory.OrderLine;
import com.harshalpatil.ecommerce.common.messaging.inventory.ReleaseStockCommand;
import com.harshalpatil.ecommerce.common.messaging.inventory.ReserveStockCommand;
import com.harshalpatil.ecommerce.common.messaging.inventory.StockReleased;
import com.harshalpatil.ecommerce.common.messaging.inventory.StockReservationFailed;
import com.harshalpatil.ecommerce.common.messaging.inventory.StockReserved;
import com.harshalpatil.ecommerce.platform.messaging.OutboxWriter;
import com.harshalpatil.ecommerce.product.catalog.Product;
import com.harshalpatil.ecommerce.product.catalog.ProductRepository;
import com.harshalpatil.ecommerce.product.catalog.ProductService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Inventory's side of the order saga. Runs inside the inbox transaction, so the stock change,
 * the reservation record, the reply in the outbox and the "message processed" marker all
 * commit together or not at all.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(propagation = Propagation.MANDATORY)
public class InventoryService {

    private final ProductRepository products;
    private final StockReservationRepository reservations;
    private final OutboxWriter outbox;
    private final CacheManager cacheManager;
    private final MeterRegistry meterRegistry;

    /**
     * All-or-nothing: either every line is held or none is. Products are locked in id order
     * (see {@link ProductRepository#findAllByIdForUpdate}), checked, and only then decremented,
     * so a shortage on the last line never leaves earlier lines decremented.
     */
    public void reserve(ReserveStockCommand command) {
        Long orderId = command.orderId();
        Optional<StockReservation> existing = reservations.findById(orderId);
        if (existing.isPresent()) {
            log.info("Order {} already has a {} reservation; repeating the outcome", orderId, existing.get().getStatus());
            replyWithOutcome(existing.get());
            return;
        }

        SortedMap<Long, Integer> requested = mergeLines(command.lines());
        Map<Long, Product> locked = products.findAllByIdForUpdate(requested.keySet()).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));

        Optional<String> shortage = findShortage(requested, locked);
        if (shortage.isPresent()) {
            reservations.save(StockReservation.rejected(orderId, shortage.get()));
            outbox.send(Topics.ORDER_SAGA_REPLIES, StockReservationFailed.of(orderId, shortage.get()));
            count("inventory.reservations", "outcome", "rejected");
            log.info("Rejected stock reservation for order {}: {}", orderId, shortage.get());
            return;
        }

        requested.forEach((productId, quantity) -> locked.get(productId).take(quantity));
        reservations.save(StockReservation.reserved(orderId, requested));
        evict(requested.keySet());
        outbox.send(Topics.ORDER_SAGA_REPLIES, StockReserved.of(orderId));
        count("inventory.reservations", "outcome", "reserved");
        log.info("Reserved {} product line(s) for order {}", requested.size(), orderId);
    }

    /**
     * Compensation. Always answers {@link StockReleased} so the orchestrator can finish, and
     * leaves a tombstone if nothing was reserved yet, so a reserve that shows up later is refused.
     */
    public void release(ReleaseStockCommand command) {
        Long orderId = command.orderId();
        StockReservation reservation = reservations.findById(orderId).orElse(null);

        if (reservation == null) {
            reservations.save(StockReservation.releasedBeforeReserved(orderId, command.reason()));
            outbox.send(Topics.ORDER_SAGA_REPLIES, StockReleased.of(orderId, false));
            count("inventory.releases", "stock_returned", "false");
            log.info("Release for order {} arrived before any reservation; recorded a tombstone", orderId);
            return;
        }

        if (reservation.getStatus() != ReservationStatus.RESERVED) {
            outbox.send(Topics.ORDER_SAGA_REPLIES, StockReleased.of(orderId, false));
            count("inventory.releases", "stock_returned", "false");
            log.info("Nothing to release for order {} (reservation is {})", orderId, reservation.getStatus());
            return;
        }

        Map<Long, Integer> held = reservation.getLines().stream()
                .collect(Collectors.toMap(ReservedLine::productId, ReservedLine::quantity));
        products.findAllByIdForUpdate(held.keySet())
                .forEach(product -> product.putBack(held.get(product.getId())));
        reservation.release(command.reason());
        evict(held.keySet());
        outbox.send(Topics.ORDER_SAGA_REPLIES, StockReleased.of(orderId, true));
        count("inventory.releases", "stock_returned", "true");
        log.info("Released {} product line(s) for order {}: {}", held.size(), orderId, command.reason());
    }

    private void replyWithOutcome(StockReservation reservation) {
        Long orderId = reservation.getOrderId();
        switch (reservation.getStatus()) {
            case RESERVED -> outbox.send(Topics.ORDER_SAGA_REPLIES, StockReserved.of(orderId));
            case REJECTED -> outbox.send(Topics.ORDER_SAGA_REPLIES, StockReservationFailed.of(orderId, reservation.getReason()));
            case RELEASED -> outbox.send(Topics.ORDER_SAGA_REPLIES,
                    StockReservationFailed.of(orderId, "Order was cancelled before stock could be reserved"));
        }
    }

    /** Duplicate product ids are summed; sorted by id to match the lock order. */
    private static SortedMap<Long, Integer> mergeLines(List<OrderLine> lines) {
        return lines.stream().collect(Collectors.toMap(OrderLine::productId, OrderLine::quantity, Integer::sum, TreeMap::new));
    }

    private static Optional<String> findShortage(Map<Long, Integer> requested, Map<Long, Product> available) {
        for (Map.Entry<Long, Integer> line : requested.entrySet()) {
            if (line.getValue() <= 0) {
                return Optional.of("Invalid quantity " + line.getValue() + " for product " + line.getKey());
            }
            Product product = available.get(line.getKey());
            if (product == null) {
                return Optional.of("Product " + line.getKey() + " does not exist");
            }
            if (!product.isActive()) {
                return Optional.of(product.getName() + " is no longer sold");
            }
            if (product.getStockQuantity() < line.getValue()) {
                return Optional.of("Not enough stock for " + product.getName() + ": requested " + line.getValue()
                        + ", available " + product.getStockQuantity());
            }
        }
        return Optional.empty();
    }

    /** Deferred until commit because the cache manager is transaction-aware. */
    private void evict(Collection<Long> productIds) {
        Cache cache = cacheManager.getCache(ProductService.CACHE);
        if (cache != null) {
            productIds.forEach(cache::evict);
        }
    }

    private void count(String name, String tag, String value) {
        meterRegistry.counter(name, tag, value).increment();
    }
}
