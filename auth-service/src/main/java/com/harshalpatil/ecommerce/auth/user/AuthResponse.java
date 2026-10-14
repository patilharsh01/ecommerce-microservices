package com.harshalpatil.ecommerce.auth.user;

import java.time.Instant;

public record AuthResponse(String accessToken, String tokenType, Instant expiresAt, UserResponse user) {
}
