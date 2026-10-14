package com.harshalpatil.ecommerce.common.security;

/**
 * Headers the API gateway sets after verifying a JWT. The gateway strips any
 * client-supplied copies first, and services are reachable only through the gateway,
 * so downstream services can trust these values without re-verifying the token.
 */
public final class IdentityHeaders {

    public static final String USER_ID = "X-User-Id";
    public static final String USER_ROLES = "X-User-Roles";

    private IdentityHeaders() {
    }
}
