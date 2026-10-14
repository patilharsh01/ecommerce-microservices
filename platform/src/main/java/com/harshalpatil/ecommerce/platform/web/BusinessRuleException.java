package com.harshalpatil.ecommerce.platform.web;

import org.springframework.http.HttpStatus;

/** Well-formed request that breaks a business rule (422 Unprocessable Content). */
public class BusinessRuleException extends ApiException {

    public BusinessRuleException(String code, String message) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, code, message);
    }
}
