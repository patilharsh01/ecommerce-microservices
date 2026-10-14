package com.harshalpatil.ecommerce.auth.user;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Provisions the first administrator from configuration ({@code SECURITY_ADMIN_EMAIL} and
 * {@code SECURITY_ADMIN_PASSWORD}, a secret in a real deployment), because self-registration
 * can only ever create customers. Does nothing when no admin is configured.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class AdminAccountSeeder implements ApplicationRunner {

    private final AccountService accounts;
    private final Environment environment;

    @Override
    public void run(ApplicationArguments args) {
        String email = environment.getProperty("security.admin.email");
        if (!StringUtils.hasText(email)) {
            return;
        }
        String password = environment.getProperty("security.admin.password", "");
        if (password.length() < 8) {
            throw new IllegalStateException("security.admin.password must be at least 8 characters");
        }
        accounts.ensureAdmin(environment.getProperty("security.admin.name", "Store Admin"), email, password);
        log.info("Admin account {} is provisioned", email);
    }
}
