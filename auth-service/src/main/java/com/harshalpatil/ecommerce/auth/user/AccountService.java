package com.harshalpatil.ecommerce.auth.user;

import com.harshalpatil.ecommerce.auth.token.TokenService;
import com.harshalpatil.ecommerce.common.security.Role;
import com.harshalpatil.ecommerce.platform.web.ConflictException;
import com.harshalpatil.ecommerce.platform.web.NotFoundException;
import com.harshalpatil.ecommerce.platform.web.UnauthorizedException;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
public class AccountService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokens;
    private final MeterRegistry meterRegistry;
    /** Compared against when the email is unknown, so "no such user" and "wrong password" take equally long. */
    private final String dummyHash;

    public AccountService(UserRepository users, PasswordEncoder passwordEncoder, TokenService tokens,
                          MeterRegistry meterRegistry) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.meterRegistry = meterRegistry;
        this.dummyHash = passwordEncoder.encode("timing-equaliser-not-a-real-password");
    }

    /** Self-registration always creates a CUSTOMER; admins are provisioned out of band. */
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = normalise(request.email());
        if (users.existsByEmail(email)) {
            throw new ConflictException("EMAIL_TAKEN", "An account with this email already exists");
        }
        User user = users.saveAndFlush(new User(request.name().trim(), email,
                passwordEncoder.encode(request.password()), Role.CUSTOMER));
        meterRegistry.counter("auth.registrations").increment();
        return respond(user);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        User user = users.findByEmail(normalise(request.email())).orElse(null);
        String hash = user == null ? dummyHash : user.getPasswordHash();
        boolean matches = passwordEncoder.matches(request.password(), hash);
        if (user == null || !matches) {
            meterRegistry.counter("auth.logins", "outcome", "rejected").increment();
            throw new UnauthorizedException("Invalid email or password");
        }
        meterRegistry.counter("auth.logins", "outcome", "accepted").increment();
        return respond(user);
    }

    @Transactional(readOnly = true)
    public UserResponse me(Long userId) {
        return users.findById(userId).map(UserResponse::from).orElseThrow(() -> new NotFoundException("User", userId));
    }

    @Transactional
    public void ensureAdmin(String name, String email, String password) {
        String normalised = normalise(email);
        if (!users.existsByEmail(normalised)) {
            users.save(new User(name, normalised, passwordEncoder.encode(password), Role.ADMIN));
        }
    }

    private AuthResponse respond(User user) {
        TokenService.IssuedToken token = tokens.issue(user);
        return new AuthResponse(token.value(), "Bearer", token.expiresAt(), UserResponse.from(user));
    }

    private static String normalise(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
