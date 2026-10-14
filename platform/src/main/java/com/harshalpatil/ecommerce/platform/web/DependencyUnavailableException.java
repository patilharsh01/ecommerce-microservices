package com.harshalpatil.ecommerce.platform.web;

import org.springframework.http.HttpStatus;

/** A downstream service needed to answer the request is down or its circuit breaker is open. */
public class DependencyUnavailableException extends ApiException {

    public DependencyUnavailableException(String dependency, Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE",
                dependency + " is temporarily unavailable, please retry shortly");
        initCause(cause);
    }
}
