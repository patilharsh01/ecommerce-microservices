package com.harshalpatil.ecommerce.auth.user;

import com.harshalpatil.ecommerce.common.security.Role;

import java.time.Instant;

public record UserResponse(Long id, String name, String email, Role role, Instant createdAt) {

    static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getName(), user.getEmail(), user.getRole(), user.getCreatedAt());
    }
}
