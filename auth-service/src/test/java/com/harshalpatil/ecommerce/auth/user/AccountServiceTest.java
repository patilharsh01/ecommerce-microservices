package com.harshalpatil.ecommerce.auth.user;

import com.harshalpatil.ecommerce.auth.token.SigningKey;
import com.harshalpatil.ecommerce.auth.token.TokenProperties;
import com.harshalpatil.ecommerce.auth.token.TokenService;
import com.harshalpatil.ecommerce.common.security.Role;
import com.harshalpatil.ecommerce.platform.web.ConflictException;
import com.harshalpatil.ecommerce.platform.web.UnauthorizedException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountServiceTest {

    private final UserRepository users = mock(UserRepository.class);
    private final PasswordEncoder encoder = spy(new BCryptPasswordEncoder(4));
    private final Map<String, User> byEmail = new HashMap<>();
    private AccountService accounts;

    @BeforeEach
    void setUp() {
        TokenProperties properties = new TokenProperties("iss", "aud", Duration.ofMinutes(5), null);
        accounts = new AccountService(users, encoder, new TokenService(new SigningKey(properties), properties),
                new SimpleMeterRegistry());
        when(users.existsByEmail(anyString())).thenAnswer(call -> byEmail.containsKey(call.<String>getArgument(0)));
        when(users.findByEmail(anyString())).thenAnswer(call -> Optional.ofNullable(byEmail.get(call.<String>getArgument(0))));
        when(users.saveAndFlush(any())).thenAnswer(call -> {
            User user = call.getArgument(0);
            ReflectionTestUtils.setField(user, "id", (long) byEmail.size() + 1);
            byEmail.put(user.getEmail(), user);
            return user;
        });
    }

    @Test
    void registrationAlwaysCreatesACustomerWithANormalisedEmailAndHashedPassword() {
        AuthResponse response = accounts.register(new RegisterRequest("  Asha  ", "  Asha@Example.COM ", "s3cret-pass"));

        User saved = byEmail.get("asha@example.com");
        assertThat(saved.getRole()).isEqualTo(Role.CUSTOMER);
        assertThat(saved.getName()).isEqualTo("Asha");
        assertThat(saved.getPasswordHash()).startsWith("$2").doesNotContain("s3cret-pass");
        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.tokenType()).isEqualTo("Bearer");
    }

    @Test
    void emailsAreUniqueCaseInsensitively() {
        accounts.register(new RegisterRequest("Asha", "asha@example.com", "s3cret-pass"));

        assertThatThrownBy(() -> accounts.register(new RegisterRequest("Imposter", "ASHA@example.com", "another-pass")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void loginSucceedsWithTheRightPasswordOnly() {
        accounts.register(new RegisterRequest("Asha", "asha@example.com", "s3cret-pass"));

        assertThat(accounts.login(new LoginRequest("ASHA@example.com", "s3cret-pass")).user().email())
                .isEqualTo("asha@example.com");
        assertThatThrownBy(() -> accounts.login(new LoginRequest("asha@example.com", "wrong-pass")))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid email or password");
    }

    @Test
    void unknownEmailGetsTheSameAnswerAndStillPaysForAHashComparison() {
        assertThatThrownBy(() -> accounts.login(new LoginRequest("nobody@example.com", "whatever-pass")))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid email or password");

        verify(encoder, times(1)).matches(any(), anyString());
    }
}
