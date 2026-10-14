package com.harshalpatil.ecommerce.notification.notifications;

import com.harshalpatil.ecommerce.common.messaging.inventory.StockReserved;
import com.harshalpatil.ecommerce.common.messaging.order.OrderCancelled;
import com.harshalpatil.ecommerce.common.messaging.order.OrderConfirmed;
import com.harshalpatil.ecommerce.common.messaging.order.OrderPlaced;
import com.harshalpatil.ecommerce.common.security.Role;
import com.harshalpatil.ecommerce.platform.web.AuthenticatedUser;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationServiceTest {

    @Test
    void placedOrderEmailMentionsAmountInRupees() {
        Notification notification = NotificationService.toNotification(
                OrderPlaced.of(5L, 7L, new BigDecimal("123456.50"), 3)).orElseThrow();

        assertThat(notification.getType()).isEqualTo(Notification.Type.ORDER_PLACED);
        assertThat(notification.getCustomerId()).isEqualTo(7L);
        assertThat(notification.getBody()).contains("₹1,23,456.50").contains("3 item(s)");
    }

    @Test
    void confirmationIncludesThePaymentReference() {
        Notification notification = NotificationService.toNotification(
                OrderConfirmed.of(5L, 7L, new BigDecimal("10.00"), "PAY-ABC")).orElseThrow();

        assertThat(notification.getSubject()).isEqualTo("Order #5 is confirmed");
        assertThat(notification.getBody()).contains("PAY-ABC");
    }

    @Test
    void cancellationExplainsWhyAndReassuresAboutCharges() {
        Notification notification = NotificationService.toNotification(
                OrderCancelled.of(5L, 7L, "Payment declined: card limit")).orElseThrow();

        assertThat(notification.getBody()).contains("Payment declined: card limit").contains("not been charged");
    }

    @Test
    void sagaInternalsDoNotNotifyAnyone() {
        assertThat(NotificationService.toNotification(StockReserved.of(5L))).isEmpty();
    }

    @Test
    void customersCannotReadSomeoneElsesNotificationsByPassingTheirId() {
        NotificationRepository repository = mock(NotificationRepository.class);
        when(repository.findByCustomerId(any(), any())).thenReturn(Page.empty());
        NotificationService service = new NotificationService(repository, mock(NotificationSender.class));

        service.list(new AuthenticatedUser(7L, Set.of(Role.CUSTOMER)), 99L, null, Pageable.unpaged());

        verify(repository).findByCustomerId(eq(7L), any());
        verify(repository, never()).findByCustomerId(eq(99L), any());
    }
}
