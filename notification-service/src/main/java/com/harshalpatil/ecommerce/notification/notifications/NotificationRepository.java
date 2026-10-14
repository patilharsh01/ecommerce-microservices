package com.harshalpatil.ecommerce.notification.notifications;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Page<Notification> findByCustomerId(Long customerId, Pageable pageable);

    Page<Notification> findByCustomerIdAndOrderId(Long customerId, Long orderId, Pageable pageable);

    Page<Notification> findByOrderId(Long orderId, Pageable pageable);
}
