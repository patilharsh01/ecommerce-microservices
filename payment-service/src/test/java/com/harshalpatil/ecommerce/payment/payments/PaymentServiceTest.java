package com.harshalpatil.ecommerce.payment.payments;

import com.harshalpatil.ecommerce.common.messaging.OrderMessage;
import com.harshalpatil.ecommerce.common.messaging.Topics;
import com.harshalpatil.ecommerce.common.messaging.payment.PaymentCompleted;
import com.harshalpatil.ecommerce.common.messaging.payment.PaymentDeclined;
import com.harshalpatil.ecommerce.common.messaging.payment.PaymentRefunded;
import com.harshalpatil.ecommerce.common.messaging.payment.ProcessPaymentCommand;
import com.harshalpatil.ecommerce.common.messaging.payment.RefundPaymentCommand;
import com.harshalpatil.ecommerce.common.security.Role;
import com.harshalpatil.ecommerce.payment.gateway.PaymentGateway;
import com.harshalpatil.ecommerce.payment.gateway.PaymentGateway.ChargeResult;
import com.harshalpatil.ecommerce.platform.messaging.OutboxWriter;
import com.harshalpatil.ecommerce.platform.web.AuthenticatedUser;
import com.harshalpatil.ecommerce.platform.web.NotFoundException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentServiceTest {

    private final PaymentRepository repository = mock(PaymentRepository.class);
    private final PaymentGateway gateway = mock(PaymentGateway.class);
    private final OutboxWriter outbox = mock(OutboxWriter.class);
    private final Map<Long, Payment> stored = new HashMap<>();

    private PaymentService payments;

    @BeforeEach
    void setUp() {
        payments = new PaymentService(repository, gateway, outbox, new SimpleMeterRegistry());
        when(repository.findByOrderId(any())).thenAnswer(call -> Optional.ofNullable(stored.get(call.<Long>getArgument(0))));
        when(repository.existsByOrderId(any())).thenAnswer(call -> stored.containsKey(call.<Long>getArgument(0)));
        when(gateway.voidCharge(any(), any())).thenReturn(BigDecimal.ZERO);
        when(repository.save(any())).thenAnswer(call -> {
            Payment payment = call.getArgument(0);
            stored.put(payment.getOrderId(), payment);
            return payment;
        });
    }

    @Test
    void approvedChargeIsRecordedAndAnnounced() {
        ProcessPaymentCommand command = ProcessPaymentCommand.of(1L, 7L, new BigDecimal("999.00"));

        payments.recordCharge(command, ChargeResult.approved("PAY-1"));

        assertThat(stored.get(1L).getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(lastReply()).isInstanceOfSatisfying(PaymentCompleted.class, completed -> {
            assertThat(completed.paymentReference()).isEqualTo("PAY-1");
            assertThat(completed.amount()).isEqualByComparingTo("999.00");
        });
    }

    @Test
    void declinedChargeIsRecordedWithItsReason() {
        payments.recordCharge(ProcessPaymentCommand.of(2L, 7L, new BigDecimal("1.00")), ChargeResult.declined("Insufficient funds"));

        assertThat(stored.get(2L).getStatus()).isEqualTo(PaymentStatus.DECLINED);
        assertThat(lastReply()).isInstanceOfSatisfying(PaymentDeclined.class,
                declined -> assertThat(declined.reason()).isEqualTo("Insufficient funds"));
    }

    @Test
    void secondChargeForTheSameOrderRepeatsTheFirstOutcome() {
        payments.recordCharge(ProcessPaymentCommand.of(3L, 7L, BigDecimal.TEN), ChargeResult.approved("PAY-3"));

        assertThat(payments.needsCharge(3L)).isFalse();
        payments.recordCharge(ProcessPaymentCommand.of(3L, 7L, BigDecimal.TEN), null);

        assertThat(lastReply()).isInstanceOfSatisfying(PaymentCompleted.class,
                completed -> assertThat(completed.paymentReference()).isEqualTo("PAY-3"));
    }

    @Test
    void refundOfACompletedPaymentGivesTheMoneyBack() {
        payments.recordCharge(ProcessPaymentCommand.of(4L, 7L, new BigDecimal("50.00")), ChargeResult.approved("PAY-4"));

        payments.refund(RefundPaymentCommand.of(4L, "saga timed out"));

        verify(gateway).refund("PAY-4", new BigDecimal("50.00"), "refund-order-4");
        assertThat(stored.get(4L).getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(lastReply()).isInstanceOfSatisfying(PaymentRefunded.class,
                refunded -> assertThat(refunded.amountRefunded()).isEqualByComparingTo("50.00"));
    }

    @Test
    void refundBeforeTheChargeVoidsTheOrderSoTheLateChargeIsRefused() {
        payments.refund(RefundPaymentCommand.of(5L, "customer cancelled"));
        assertThat(stored.get(5L).getStatus()).isEqualTo(PaymentStatus.VOIDED);
        assertThat(lastReply()).isInstanceOfSatisfying(PaymentRefunded.class,
                refunded -> assertThat(refunded.amountRefunded()).isZero());

        assertThat(payments.needsCharge(5L)).as("gateway must not be called").isFalse();
        payments.recordCharge(ProcessPaymentCommand.of(5L, 7L, BigDecimal.TEN), null);

        assertThat(stored.get(5L).getStatus()).isEqualTo(PaymentStatus.VOIDED);
        assertThat(lastReply()).isInstanceOf(PaymentDeclined.class);
        verify(gateway, never()).charge(any(), any(), any());
    }

    @Test
    void refundWithNoPaymentRecordVoidsAChargeTheGatewayMadeAnyway() {
        // The charge succeeded at the provider, but saving its outcome failed and the command was dead-lettered.
        when(gateway.voidCharge("order-9", "refund-order-9")).thenReturn(new BigDecimal("75.00"));

        payments.refund(RefundPaymentCommand.of(9L, "saga timed out"));

        assertThat(stored.get(9L).getStatus()).isEqualTo(PaymentStatus.VOIDED);
        assertThat(stored.get(9L).getAmount()).isEqualByComparingTo("75.00");
        assertThat(lastReply()).isInstanceOfSatisfying(PaymentRefunded.class,
                refunded -> assertThat(refunded.amountRefunded()).isEqualByComparingTo("75.00"));
    }

    @Test
    void refundOfADeclinedPaymentRefundsNothing() {
        payments.recordCharge(ProcessPaymentCommand.of(6L, 7L, BigDecimal.TEN), ChargeResult.declined("no"));

        payments.refund(RefundPaymentCommand.of(6L, "cleanup"));

        verify(gateway, never()).refund(any(), any(), any());
        assertThat(lastReply()).isInstanceOfSatisfying(PaymentRefunded.class,
                refunded -> assertThat(refunded.amountRefunded()).isZero());
    }

    @Test
    void customersOnlySeeTheirOwnPaymentsAndOthersLookMissing() {
        payments.recordCharge(ProcessPaymentCommand.of(8L, 7L, BigDecimal.TEN), ChargeResult.approved("PAY-8"));

        assertThat(payments.findByOrder(8L, new AuthenticatedUser(7L, Set.of(Role.CUSTOMER))).reference()).isEqualTo("PAY-8");
        assertThat(payments.findByOrder(8L, new AuthenticatedUser(1L, Set.of(Role.ADMIN))).reference()).isEqualTo("PAY-8");
        assertThatThrownBy(() -> payments.findByOrder(8L, new AuthenticatedUser(99L, Set.of(Role.CUSTOMER))))
                .isInstanceOf(NotFoundException.class);
    }

    private OrderMessage lastReply() {
        ArgumentCaptor<OrderMessage> captor = ArgumentCaptor.forClass(OrderMessage.class);
        verify(outbox, atLeastOnce()).send(eq(Topics.ORDER_SAGA_REPLIES), captor.capture());
        return captor.getValue();
    }
}
