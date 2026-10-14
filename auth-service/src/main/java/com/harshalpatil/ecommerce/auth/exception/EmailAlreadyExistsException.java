package com.harshalpatil.ecommerce.auth.exception;

public class EmailAlreadyExistsException extends RuntimeException {
    public EmailAlreadyExistsException(String email) {
        super("An account already exists with email: " + email);
    }
}
