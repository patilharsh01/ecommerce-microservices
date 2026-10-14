package com.harshalpatil.ecommerce.platform.web;

import org.springframework.http.HttpStatus;

public class NotFoundException extends ApiException {

    public NotFoundException(String resource, Object id) {
        super(HttpStatus.NOT_FOUND, "NOT_FOUND", resource + " " + id + " was not found");
    }
}
