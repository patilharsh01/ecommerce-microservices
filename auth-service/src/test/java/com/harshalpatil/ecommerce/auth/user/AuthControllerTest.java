package com.harshalpatil.ecommerce.auth.user;

import com.harshalpatil.ecommerce.common.security.Role;
import com.harshalpatil.ecommerce.platform.web.PlatformWebAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@ImportAutoConfiguration(PlatformWebAutoConfiguration.class)
class AuthControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AccountService accounts;

    @Test
    void aRoleSmuggledIntoTheRegistrationBodyIsIgnored() throws Exception {
        when(accounts.register(any())).thenReturn(new AuthResponse("token", "Bearer", Instant.now(),
                new UserResponse(5L, "Mallory", "mallory@example.com", Role.CUSTOMER, Instant.now())));

        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Mallory","email":"mallory@example.com","password":"password-1","role":"ADMIN"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.role").value("CUSTOMER"));

        ArgumentCaptor<RegisterRequest> request = ArgumentCaptor.forClass(RegisterRequest.class);
        verify(accounts).register(request.capture());
        assertThat(request.getValue().email()).isEqualTo("mallory@example.com");
    }

    @Test
    void weakPasswordsAndBadEmailsAreRejected() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"","email":"not-an-email","password":"short"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.length()").value(3));
    }
}
