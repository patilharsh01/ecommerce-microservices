package com.harshalpatil.ecommerce.notification.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Notification {
    private Long orderId;
    private Long customerId;
    private String subject;
    private String message;
    private Instant sentAt;
}
