package com.harshalpatil.ecommerce.auth.token;

import com.nimbusds.jose.jwk.JWKSet;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@Tag(name = "Authentication")
class JwksController {

    private final SigningKey signingKey;

    @GetMapping("/.well-known/jwks.json")
    @Operation(summary = "Public keys for verifying access tokens (RFC 7517)")
    ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .body(new JWKSet(signingKey.publicJwk()).toJSONObject());
    }
}
