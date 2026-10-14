package com.harshalpatil.ecommerce.order.api;

import com.harshalpatil.ecommerce.common.security.Role;
import com.harshalpatil.ecommerce.order.catalog.CatalogProduct;
import com.harshalpatil.ecommerce.order.catalog.PricingService;
import com.harshalpatil.ecommerce.order.domain.Order;
import com.harshalpatil.ecommerce.order.domain.OrderLineItem;
import com.harshalpatil.ecommerce.order.domain.OrderRepository;
import com.harshalpatil.ecommerce.order.domain.OrderStatus;
import com.harshalpatil.ecommerce.order.saga.OrderSagaOrchestrator;
import com.harshalpatil.ecommerce.order.saga.SagaProperties;
import com.harshalpatil.ecommerce.platform.web.AuthenticatedUser;
import com.harshalpatil.ecommerce.platform.web.BusinessRuleException;
import com.harshalpatil.ecommerce.platform.web.ConflictException;
import com.harshalpatil.ecommerce.platform.web.ForbiddenException;
import com.harshalpatil.ecommerce.platform.web.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderServiceTest {

    private static final AuthenticatedUser ALICE = new AuthenticatedUser(7L, Set.of(Role.CUSTOMER));
    private static final AuthenticatedUser BOB = new AuthenticatedUser(8L, Set.of(Role.CUSTOMER));
    private static final AuthenticatedUser ADMIN = new AuthenticatedUser(1L, Set.of(Role.ADMIN));

    private final OrderRepository orders = mock(OrderRepository.class);
    private final PricingService pricing = mock(PricingService.class);
    private final OrderSagaOrchestrator orchestrator = mock(OrderSagaOrchestrator.class);
    private OrderService service;

    @BeforeEach
    void setUp() {
        service = new OrderService(orders, pricing, orchestrator,
                new SagaProperties(Duration.ofSeconds(30), Duration.ofSeconds(5), 50), mock(OrderStatsQuery.class),
                mock(PlatformTransactionManager.class));
        when(pricing.lookup(anyCollection())).thenReturn(Map.of(
                1L, new CatalogProduct(1L, "KB-1", "Keyboard", new BigDecimal("1000.00"), 5, true),
                2L, new CatalogProduct(2L, "MS-1", "Mouse", new BigDecimal("500.00"), 5, true),
                3L, new CatalogProduct(3L, "OLD-1", "Retired gadget", new BigDecimal("10.00"), 5, false)));
        when(orders.saveAndFlush(any())).thenAnswer(call -> {
            Order order = call.getArgument(0);
            ReflectionTestUtils.setField(order, "id", 100L);
            return order;
        });
    }

    @Test
    void placesAPricedOrderAndStartsTheSaga() {
        OrderService.Placement placement = service.place(ALICE, "key-00000001", request(item(1L, 2), item(2L, 1)));

        assertThat(placement.replayed()).isFalse();
        assertThat(placement.order().totalAmount()).isEqualByComparingTo("2500.00");
        assertThat(placement.order().status()).isEqualTo(OrderStatus.PENDING);
        verify(orchestrator).start(any(Order.class));
    }

    @Test
    void sameProductTwiceIsMergedIntoOneLine() {
        service.place(ALICE, null, request(item(1L, 1), item(1L, 2)));

        ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
        verify(orders).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getItems()).singleElement()
                .satisfies(line -> assertThat(line.getQuantity()).isEqualTo(3));
    }

    @Test
    void retryWithTheSameKeyReturnsTheOriginalOrderWithoutPlacingAgain() {
        service.place(ALICE, "key-00000002", request(item(1L, 1)));
        Order original = capturedOrder();
        when(orders.findByCustomerIdAndIdempotencyKey(7L, "key-00000002")).thenReturn(Optional.of(original));

        OrderService.Placement retry = service.place(ALICE, "key-00000002", request(item(1L, 1)));

        assertThat(retry.replayed()).isTrue();
        assertThat(retry.order().id()).isEqualTo(100L);
        verify(orders, org.mockito.Mockito.times(1)).saveAndFlush(any());
    }

    @Test
    void reusingAKeyForADifferentOrderIsRejected() {
        service.place(ALICE, "key-00000003", request(item(1L, 1)));
        Order original = capturedOrder();
        when(orders.findByCustomerIdAndIdempotencyKey(7L, "key-00000003")).thenReturn(Optional.of(original));

        assertThatThrownBy(() -> service.place(ALICE, "key-00000003", request(item(1L, 5))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("already used");
    }

    @Test
    void unknownAndRetiredProductsAreRejectedBeforeAnythingIsSaved() {
        assertThatThrownBy(() -> service.place(ALICE, null, request(item(99L, 1))))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("99");
        assertThatThrownBy(() -> service.place(ALICE, null, request(item(3L, 1))))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("Retired gadget");
        verify(orders, never()).saveAndFlush(any());
    }

    @Test
    void mergedQuantitiesStillRespectThePerProductLimit() {
        assertThatThrownBy(() -> service.place(ALICE, null, request(item(1L, 60), item(1L, 60))))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("At most 100");
    }

    @Test
    void customersCannotSeeOrCancelOtherCustomersOrders() {
        Order alicesOrder = placedOrderOf(ALICE);
        when(orders.findById(100L)).thenReturn(Optional.of(alicesOrder));

        assertThatThrownBy(() -> service.get(100L, BOB)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.cancel(100L, BOB)).isInstanceOf(NotFoundException.class);
        assertThat(service.get(100L, ADMIN).id()).isEqualTo(100L);
    }

    @Test
    void finishedOrdersCannotBeCancelled() {
        Order order = placedOrderOf(ALICE);
        order.stockRejected("none left");
        when(orders.findById(100L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.cancel(100L, ALICE)).isInstanceOf(ConflictException.class);
    }

    @Test
    void onlyAdminsMayListEveryonesOrders() {
        assertThatThrownBy(() -> service.list(ALICE, true, null, Pageable.unpaged()))
                .isInstanceOf(ForbiddenException.class);
    }

    private Order capturedOrder() {
        ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
        verify(orders, org.mockito.Mockito.atLeastOnce()).saveAndFlush(saved.capture());
        return saved.getValue();
    }

    private Order placedOrderOf(AuthenticatedUser customer) {
        Order order = Order.place(customer.id(), List.of(new OrderLineItem(1L, "Keyboard", 1, new BigDecimal("1000.00"))),
                null, "fp", Duration.ofSeconds(30));
        ReflectionTestUtils.setField(order, "id", 100L);
        return order;
    }

    private static PlaceOrderRequest request(PlaceOrderRequest.Item... items) {
        return new PlaceOrderRequest(List.of(items));
    }

    private static PlaceOrderRequest.Item item(Long productId, int quantity) {
        return new PlaceOrderRequest.Item(productId, quantity);
    }
}
