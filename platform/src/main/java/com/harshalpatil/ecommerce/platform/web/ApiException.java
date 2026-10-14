package com.harshalpatil.ecommerce.platform.web;

import org.springframework.http.HttpStatus;

/**
 * Base for errors that map to a specific HTTP status. Rendered as an RFC 9457 problem
 * document whose {@code code} property is a stable, machine-readable error identifier.
 */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    protected ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
