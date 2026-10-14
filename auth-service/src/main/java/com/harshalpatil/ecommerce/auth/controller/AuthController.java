package com.harshalpatil.ecommerce.auth.controller;

import com.harshalpatil.ecommerce.auth.dto.AuthResponse;
import com.harshalpatil.ecommerce.auth.dto.LoginRequest;
import com.harshalpatil.ecommerce.auth.dto.RegisterRequest;
import com.harshalpatil.ecommerce.auth.dto.UserResponse;
import com.harshalpatil.ecommerce.auth.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    /**
     * Reached only through api-gateway, which has already verified the JWT and
     * injected the caller's id — this endpoint never re-checks the token itself.
     */
    @GetMapping("/me")
    public ResponseEntity<UserResponse> me(@RequestHeader("X-User-Id") Long userId) {
        return ResponseEntity.ok(authService.getById(userId));
    }
}
