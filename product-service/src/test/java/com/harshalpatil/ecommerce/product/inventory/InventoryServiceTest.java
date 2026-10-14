package com.harshalpatil.ecommerce.product.inventory;

import com.harshalpatil.ecommerce.common.messaging.OrderMessage;
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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Decision logic of the inventory participant, with the database and Kafka mocked out. */
class InventoryServiceTest {

    private final ProductRepository products = mock(ProductRepository.class);
    private final StockReservationRepository reservations = mock(StockReservationRepository.class);
    private final OutboxWriter outbox = mock(OutboxWriter.class);
    private final Map<Long, StockReservation> savedReservations = new HashMap<>();
    private final Map<Long, Product> catalog = new HashMap<>();

    private InventoryService inventory;

    @BeforeEach
    void setUp() {
        inventory = new InventoryService(products, reservations, outbox, new ConcurrentMapCacheManager(),
                new SimpleMeterRegistry());
        when(reservations.findById(any())).thenAnswer(call -> Optional.ofNullable(savedReservations.get(call.<Long>getArgument(0))));
        when(reservations.save(any())).thenAnswer(call -> {
            StockReservation reservation = call.getArgument(0);
            savedReservations.put(reservation.getOrderId(), reservation);
            return reservation;
        });
        when(products.findAllByIdForUpdate(anyCollection())).thenAnswer(call -> call.<Collection<Long>>getArgument(0).stream()
                .map(catalog::get).filter(java.util.Objects::nonNull).toList());
        addProduct(1L, "Keyboard", 10);
        addProduct(2L, "Mouse", 1);
    }

    @Test
    void reservesEveryLineAndRepliesStockReserved() {
        inventory.reserve(ReserveStockCommand.of(100L, List.of(new OrderLine(1L, 3), new OrderLine(2L, 1))));

        assertThat(catalog.get(1L).getStockQuantity()).isEqualTo(7);
        assertThat(catalog.get(2L).getStockQuantity()).isZero();
        assertThat(savedReservations.get(100L).getStatus()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(lastReply()).isInstanceOf(StockReserved.class);
    }

    @Test
    void shortageOnAnyLineHoldsNothingAtAll() {
        inventory.reserve(ReserveStockCommand.of(101L, List.of(new OrderLine(1L, 3), new OrderLine(2L, 5))));

        assertThat(catalog.get(1L).getStockQuantity()).as("first line must not be decremented").isEqualTo(10);
        assertThat(catalog.get(2L).getStockQuantity()).isEqualTo(1);
        assertThat(savedReservations.get(101L).getStatus()).isEqualTo(ReservationStatus.REJECTED);
        assertThat(lastReply()).isInstanceOfSatisfying(StockReservationFailed.class,
                failed -> assertThat(failed.reason()).contains("Not enough stock for Mouse"));
    }

    @Test
    void duplicateProductLinesAreSummedBeforeChecking() {
        inventory.reserve(ReserveStockCommand.of(102L, List.of(new OrderLine(2L, 1), new OrderLine(2L, 1))));

        assertThat(catalog.get(2L).getStockQuantity()).isEqualTo(1);
        assertThat(lastReply()).isInstanceOf(StockReservationFailed.class);
    }

    @Test
    void unknownAndInactiveProductsAreRejected() {
        inventory.reserve(ReserveStockCommand.of(103L, List.of(new OrderLine(99L, 1))));
        assertThat(lastReply()).isInstanceOfSatisfying(StockReservationFailed.class,
                failed -> assertThat(failed.reason()).contains("does not exist"));

        catalog.get(1L).deactivate();
        inventory.reserve(ReserveStockCommand.of(104L, List.of(new OrderLine(1L, 1))));
        assertThat(lastReply()).isInstanceOfSatisfying(StockReservationFailed.class,
                failed -> assertThat(failed.reason()).contains("no longer sold"));
    }

    @Test
    void releasePutsHeldStockBack() {
        inventory.reserve(ReserveStockCommand.of(105L, List.of(new OrderLine(1L, 4))));

        inventory.release(ReleaseStockCommand.of(105L, "payment declined"));

        assertThat(catalog.get(1L).getStockQuantity()).isEqualTo(10);
        assertThat(savedReservations.get(105L).getStatus()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(lastReply()).isInstanceOfSatisfying(StockReleased.class, released -> assertThat(released.stockReturned()).isTrue());
    }

    @Test
    void secondReleaseIsANoOpButStillAnswers() {
        inventory.reserve(ReserveStockCommand.of(106L, List.of(new OrderLine(1L, 4))));
        inventory.release(ReleaseStockCommand.of(106L, "cancelled"));

        inventory.release(ReleaseStockCommand.of(106L, "cancelled again"));

        assertThat(catalog.get(1L).getStockQuantity()).as("stock returned only once").isEqualTo(10);
        assertThat(lastReply()).isInstanceOfSatisfying(StockReleased.class, released -> assertThat(released.stockReturned()).isFalse());
    }

    @Test
    void releaseBeforeReserveLeavesATombstoneThatRefusesTheLateReserve() {
        inventory.release(ReleaseStockCommand.of(107L, "saga timed out"));
        assertThat(lastReply()).isInstanceOf(StockReleased.class);

        inventory.reserve(ReserveStockCommand.of(107L, List.of(new OrderLine(1L, 2))));

        assertThat(catalog.get(1L).getStockQuantity()).as("late reserve must not hold stock").isEqualTo(10);
        assertThat(lastReply()).isInstanceOf(StockReservationFailed.class);
    }

    @Test
    void repeatedReserveForTheSameOrderRepeatsTheOutcomeWithoutTakingStockTwice() {
        inventory.reserve(ReserveStockCommand.of(108L, List.of(new OrderLine(1L, 2))));

        inventory.reserve(ReserveStockCommand.of(108L, List.of(new OrderLine(1L, 2))));

        assertThat(catalog.get(1L).getStockQuantity()).isEqualTo(8);
        assertThat(lastReply()).isInstanceOf(StockReserved.class);
    }

    private OrderMessage lastReply() {
        ArgumentCaptor<OrderMessage> captor = ArgumentCaptor.forClass(OrderMessage.class);
        verify(outbox, org.mockito.Mockito.atLeastOnce()).send(eq(Topics.ORDER_SAGA_REPLIES), captor.capture());
        return captor.getValue();
    }

    private void addProduct(Long id, String name, int stock) {
        Product product = new Product("SKU-" + id, name, null, new BigDecimal("100.00"), stock);
        ReflectionTestUtils.setField(product, "id", id);
        catalog.put(id, product);
    }
}
