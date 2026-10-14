package com.harshalpatil.ecommerce.platform.web;

import com.harshalpatil.ecommerce.common.security.Role;

import java.util.Set;

/**
 * The caller, as established by the API gateway. Declare a controller parameter of this type
 * to receive it; a request without identity headers is rejected with 401 before the
 * controller runs.
 */
public record AuthenticatedUser(Long id, Set<Role> roles) {

    public AuthenticatedUser {
        roles = Set.copyOf(roles);
    }

    public boolean isAdmin() {
        return roles.contains(Role.ADMIN);
    }

    public void requireAdmin() {
        if (!isAdmin()) {
            throw new ForbiddenException("This operation requires the ADMIN role");
        }
    }

    /** Owners can see their own resources; admins can see everyone's. */
    public boolean canAccess(Long ownerId) {
        return isAdmin() || id.equals(ownerId);
    }
}
