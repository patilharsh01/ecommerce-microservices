package com.harshalpatil.ecommerce.auth.user;

import com.harshalpatil.ecommerce.platform.web.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication")
class AuthController {

    private final AccountService accounts;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a customer account and sign in")
    AuthResponse register(@Valid @RequestBody RegisterRequest request) {
        return accounts.register(request);
    }

    @PostMapping("/login")
    @Operation(summary = "Exchange email and password for an access token")
    AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return accounts.login(request);
    }

    @GetMapping("/me")
    @Operation(summary = "The signed-in user")
    UserResponse me(AuthenticatedUser user) {
        return accounts.me(user.id());
    }
}
