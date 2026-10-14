package com.harshalpatil.ecommerce.platform.web;

import org.springframework.http.HttpStatus;

/** The request is valid but clashes with the resource's current state. */
public class ConflictException extends ApiException {

    public ConflictException(String code, String message) {
        super(HttpStatus.CONFLICT, code, message);
    }
}
