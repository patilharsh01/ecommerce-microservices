package com.harshalpatil.ecommerce.order.exception;

public class ProductUnavailableException extends RuntimeException {
    public ProductUnavailableException(Long productId) {
        super("Product " + productId + " could not be found or product-service is unavailable");
    }
}
