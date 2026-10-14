package com.harshalpatil.ecommerce.auth.token;

import com.harshalpatil.ecommerce.auth.user.User;
import com.harshalpatil.ecommerce.common.security.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenServiceTest {

    private final TokenProperties properties = new TokenProperties("http://auth-service", "ecommerce-api", Duration.ofMinutes(60), null);
    private final SigningKey signingKey = new SigningKey(properties);
    private final TokenService tokens = new TokenService(signingKey, properties);

    @Test
    void issuesAnRs256TokenThatVerifiesWithThePublishedPublicKey() throws Exception {
        TokenService.IssuedToken issued = tokens.issue(user(42L, Role.CUSTOMER));

        Jwt jwt = NimbusJwtDecoder.withPublicKey(signingKey.publicJwk().toRSAPublicKey()).build().decode(issued.value());

        assertThat(jwt.getSubject()).isEqualTo("42");
        assertThat(jwt.getHeaders()).containsEntry("alg", "RS256").containsEntry("kid", signingKey.publicJwk().getKeyID());
        assertThat(jwt.getIssuer()).hasToString("http://auth-service");
        assertThat(jwt.getAudience()).containsExactly("ecommerce-api");
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("CUSTOMER");
        assertThat(jwt.getClaimAsString("email")).isEqualTo("user42@example.com");
        assertThat(jwt.getExpiresAt()).isCloseTo(Instant.now().plus(Duration.ofMinutes(60)), within());
        assertThat(jwt.getId()).isNotBlank();
    }

    @Test
    void tokenSignedByAnotherKeyIsRejected() throws Exception {
        String forged = new TokenService(new SigningKey(properties), properties).issue(user(1L, Role.ADMIN)).value();

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(signingKey.publicJwk().toRSAPublicKey()).build();

        assertThatThrownBy(() -> decoder.decode(forged)).isInstanceOf(JwtException.class);
    }

    @Test
    void persistedKeySurvivesARestartWithTheSameKeyId(@TempDir Path directory) {
        TokenProperties persistent = new TokenProperties("i", "a", Duration.ofMinutes(5), directory);

        String first = new SigningKey(persistent).publicJwk().getKeyID();
        String second = new SigningKey(persistent).publicJwk().getKeyID();

        assertThat(second).isEqualTo(first);
        assertThat(Files.exists(directory.resolve("jwt-signing-key.pem"))).isTrue();
    }

    @Test
    void publishedJwkContainsNoPrivateMaterial() {
        assertThat(signingKey.publicJwk().isPrivate()).isFalse();
        assertThat(signingKey.publicJwk().toJSONObject()).doesNotContainKeys("d", "p", "q", "dp", "dq", "qi");
    }

    private static org.assertj.core.data.TemporalUnitOffset within() {
        return new org.assertj.core.data.TemporalUnitWithinOffset(5, java.time.temporal.ChronoUnit.SECONDS);
    }

    private static User user(Long id, Role role) {
        User user = new User("User " + id, "user" + id + "@example.com", "hash", role);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
