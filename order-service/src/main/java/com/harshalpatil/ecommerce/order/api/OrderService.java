package com.harshalpatil.ecommerce.order.api;

import com.harshalpatil.ecommerce.order.catalog.CatalogProduct;
import com.harshalpatil.ecommerce.order.catalog.PricingService;
import com.harshalpatil.ecommerce.order.domain.CancellationCause;
import com.harshalpatil.ecommerce.order.domain.Order;
import com.harshalpatil.ecommerce.order.domain.OrderLineItem;
import com.harshalpatil.ecommerce.order.domain.OrderRepository;
import com.harshalpatil.ecommerce.order.domain.OrderStatus;
import com.harshalpatil.ecommerce.order.saga.OrderSagaOrchestrator;
import com.harshalpatil.ecommerce.order.saga.SagaProperties;
import com.harshalpatil.ecommerce.platform.web.AuthenticatedUser;
import com.harshalpatil.ecommerce.platform.web.BusinessRuleException;
import com.harshalpatil.ecommerce.platform.web.ConflictException;
import com.harshalpatil.ecommerce.platform.web.NotFoundException;
import com.harshalpatil.ecommerce.platform.web.PageResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class OrderService {

    private final OrderRepository orders;
    private final PricingService pricing;
    private final OrderSagaOrchestrator orchestrator;
    private final SagaProperties sagaProperties;
    private final OrderStatsQuery statsQuery;
    private final TransactionTemplate transaction;
    private final TransactionTemplate readOnlyTransaction;

    public OrderService(OrderRepository orders, PricingService pricing, OrderSagaOrchestrator orchestrator,
                        SagaProperties sagaProperties, OrderStatsQuery statsQuery,
                        PlatformTransactionManager transactionManager) {
        this.orders = orders;
        this.pricing = pricing;
        this.orchestrator = orchestrator;
        this.sagaProperties = sagaProperties;
        this.statsQuery = statsQuery;
        this.transaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction.setReadOnly(true);
    }

    /**
     * Places an order and starts its saga.
     * <p>
     * With an {@code Idempotency-Key}, a retried request (client timeout, double click, flaky
     * mobile network) returns the order created the first time instead of a duplicate; reusing
     * the key for a <em>different</em> request is an error. Two concurrent requests with the
     * same key are settled by the unique index on (customer_id, idempotency_key).
     * <p>
     * Pricing calls product-service and therefore happens <em>before</em> the transaction:
     * no database connection is held while waiting on the network.
     */
    public Placement place(AuthenticatedUser customer, String idempotencyKey, PlaceOrderRequest request) {
        Map<Long, Integer> quantities = mergeLines(request.items());
        String fingerprint = fingerprint(quantities);

        if (idempotencyKey != null) {
            Placement replay = readOnlyTransaction.execute(status -> findReplay(customer, idempotencyKey, fingerprint));
            if (replay != null) {
                return replay;
            }
        }

        List<OrderLineItem> items = price(quantities);
        try {
            return transaction.execute(status -> {
                Order order = orders.saveAndFlush(Order.place(customer.id(), items, idempotencyKey, fingerprint,
                        sagaProperties.stepTimeout()));
                orchestrator.start(order);
                return new Placement(OrderResponse.from(order), false);
            });
        } catch (DataIntegrityViolationException ex) {
            if (idempotencyKey == null) {
                throw ex;
            }
            // A concurrent request with the same key won the insert; answer with its order.
            Placement replay = readOnlyTransaction.execute(status -> findReplay(customer, idempotencyKey, fingerprint));
            if (replay == null) {
                throw ex;
            }
            return replay;
        }
    }

    public OrderResponse get(Long orderId, AuthenticatedUser user) {
        return readOnlyTransaction.execute(status -> OrderResponse.from(findAccessible(orderId, user)));
    }

    public PageResponse<OrderSummaryResponse> list(AuthenticatedUser user, boolean allCustomers, OrderStatus status,
                                                   Pageable pageable) {
        if (allCustomers) {
            user.requireAdmin();
        }
        return readOnlyTransaction.execute(tx -> {
            Page<Order> page;
            if (allCustomers) {
                page = status == null ? orders.findAll(pageable) : orders.findByStatus(status, pageable);
            } else {
                page = status == null ? orders.findByCustomerId(user.id(), pageable)
                        : orders.findByCustomerIdAndStatus(user.id(), status, pageable);
            }
            return PageResponse.of(page, OrderSummaryResponse::from);
        });
    }

    /** Customer-initiated cancellation; only possible while the saga is still running. */
    public OrderResponse cancel(Long orderId, AuthenticatedUser user) {
        return transaction.execute(status -> {
            Order order = findAccessible(orderId, user);
            if (!OrderStatus.IN_FLIGHT.contains(order.getStatus())) {
                throw new ConflictException("ORDER_NOT_CANCELLABLE",
                        "Order " + orderId + " is " + order.getStatus() + " and can no longer be cancelled");
            }
            orchestrator.cancel(order, CancellationCause.CUSTOMER_REQUEST,
                    user.isAdmin() && !user.id().equals(order.getCustomerId())
                            ? "Cancelled by an administrator" : "Cancelled by the customer");
            return OrderResponse.from(order);
        });
    }

    public OrderStatsResponse stats(AuthenticatedUser user) {
        user.requireAdmin();
        return readOnlyTransaction.execute(status -> statsQuery.load());
    }

    private Placement findReplay(AuthenticatedUser customer, String idempotencyKey, String fingerprint) {
        return orders.findByCustomerIdAndIdempotencyKey(customer.id(), idempotencyKey)
                .map(existing -> {
                    if (!Objects.equals(existing.getRequestFingerprint(), fingerprint)) {
                        throw new BusinessRuleException("IDEMPOTENCY_KEY_REUSED",
                                "This Idempotency-Key was already used for a different order");
                    }
                    return new Placement(OrderResponse.from(existing), true);
                })
                .orElse(null);
    }

    private List<OrderLineItem> price(Map<Long, Integer> quantities) {
        Map<Long, CatalogProduct> catalog = pricing.lookup(quantities.keySet());

        List<Long> unknown = quantities.keySet().stream().filter(id -> !catalog.containsKey(id)).toList();
        if (!unknown.isEmpty()) {
            throw new BusinessRuleException("UNKNOWN_PRODUCT", "No such product(s): " + unknown);
        }
        List<String> inactive = quantities.keySet().stream().map(catalog::get).filter(product -> !product.active())
                .map(CatalogProduct::name).toList();
        if (!inactive.isEmpty()) {
            throw new BusinessRuleException("PRODUCT_UNAVAILABLE", "No longer sold: " + String.join(", ", inactive));
        }

        return quantities.entrySet().stream()
                .map(line -> {
                    CatalogProduct product = catalog.get(line.getKey());
                    return new OrderLineItem(product.id(), product.name(), line.getValue(), product.price());
                })
                .toList();
    }

    private Order findAccessible(Long orderId, AuthenticatedUser user) {
        return orders.findById(orderId)
                .filter(order -> user.canAccess(order.getCustomerId()))
                // Another customer's order is indistinguishable from a missing one.
                .orElseThrow(() -> new NotFoundException("Order", orderId));
    }

    /** Same product twice in one request becomes one line with the summed quantity. */
    private static Map<Long, Integer> mergeLines(List<PlaceOrderRequest.Item> items) {
        Map<Long, Integer> merged = items.stream().collect(Collectors.toMap(PlaceOrderRequest.Item::productId,
                PlaceOrderRequest.Item::quantity, Integer::sum, LinkedHashMap::new));
        merged.forEach((productId, quantity) -> {
            if (quantity > OrderLimits.MAX_UNITS_PER_PRODUCT) {
                throw new BusinessRuleException("QUANTITY_LIMIT", "At most " + OrderLimits.MAX_UNITS_PER_PRODUCT
                        + " units of product " + productId + " per order");
            }
        });
        return merged;
    }

    private static String fingerprint(Map<Long, Integer> quantities) {
        String canonical = quantities.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(line -> line.getKey() + "x" + line.getValue())
                .collect(Collectors.joining(","));
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is always available", ex);
        }
    }

    /** @param replayed true when an earlier request with the same idempotency key created the order */
    public record Placement(OrderResponse order, boolean replayed) {
    }
}
