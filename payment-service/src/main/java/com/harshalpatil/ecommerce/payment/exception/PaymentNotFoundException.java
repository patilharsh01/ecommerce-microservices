package com.harshalpatil.ecommerce.payment.exception;

public class PaymentNotFoundException extends RuntimeException {
    public PaymentNotFoundException(Long orderId) {
        super("No payment recorded yet for order: " + orderId);
    }
}
