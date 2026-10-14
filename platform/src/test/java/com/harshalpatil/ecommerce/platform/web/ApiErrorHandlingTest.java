package com.harshalpatil.ecommerce.platform.web;

import com.harshalpatil.ecommerce.common.security.IdentityHeaders;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiErrorHandlingTest {

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new TestController())
            .setControllerAdvice(new ApiExceptionHandler())
            .setCustomArgumentResolvers(new AuthenticatedUserArgumentResolver())
            .build();

    @Test
    void resolvesTheCallerFromGatewayHeaders() throws Exception {
        mvc.perform(get("/me").header(IdentityHeaders.USER_ID, "42").header(IdentityHeaders.USER_ROLES, "CUSTOMER,ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.roles.length()").value(2));
    }

    @Test
    void optionalCallerIsEmptyForAnonymousRequests() throws Exception {
        mvc.perform(get("/whoami")).andExpect(status().isOk()).andExpect(content().string("anonymous"));
        mvc.perform(get("/whoami").header(IdentityHeaders.USER_ID, "9")).andExpect(content().string("user 9"));
    }

    @Test
    void requestWithoutIdentityIsUnauthorizedProblem() throws Exception {
        mvc.perform(get("/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void unknownRolesAreIgnoredRatherThanTrusted() throws Exception {
        mvc.perform(get("/admin-only").header(IdentityHeaders.USER_ID, "1").header(IdentityHeaders.USER_ROLES, "SUPERUSER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void apiExceptionsBecomeProblemDetailsWithCodeAndInstance() throws Exception {
        mvc.perform(get("/things/7"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Thing 7 was not found"))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.instance").value("/things/7"));
    }

    @Test
    void validationErrorsListEveryOffendingField() throws Exception {
        mvc.perform(post("/things").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\",\"quantity\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.length()").value(2));
    }

    @Test
    void bodyErrorsAreReportedTheSameWayWhenOtherParametersAreAlsoConstrained() throws Exception {
        mvc.perform(post("/things-with-header").header("X-Tag", "ok").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"quantity\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.length()").value(2));
        mvc.perform(post("/things-with-header").header("X-Tag", "not ok!").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"fine\",\"quantity\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("tag"));
    }

    @Test
    void lostUpdateIsAConflictNotAServerError() throws Exception {
        mvc.perform(get("/race"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
    }

    @Test
    void unexpectedErrorsDoNotLeakInternals() throws Exception {
        mvc.perform(get("/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret"))));
    }

    @RestController
    static class TestController {

        @GetMapping("/me")
        AuthenticatedUser me(AuthenticatedUser user) {
            return user;
        }

        @GetMapping("/whoami")
        String whoami(java.util.Optional<AuthenticatedUser> user) {
            return user.map(u -> "user " + u.id()).orElse("anonymous");
        }

        @GetMapping("/admin-only")
        void adminOnly(AuthenticatedUser user) {
            user.requireAdmin();
        }

        @GetMapping("/things/{id}")
        void thing(@PathVariable long id) {
            throw new NotFoundException("Thing", id);
        }

        @PostMapping("/things")
        void create(@Valid @RequestBody NewThing thing) {
        }

        @PostMapping("/things-with-header")
        void createTagged(@org.springframework.web.bind.annotation.RequestHeader("X-Tag")
                          @jakarta.validation.constraints.Pattern(regexp = "[a-z]+") String tag,
                          @Valid @RequestBody NewThing thing) {
        }

        @GetMapping("/race")
        void race() {
            throw new OptimisticLockingFailureException("row version changed");
        }

        @GetMapping("/boom")
        void boom() {
            throw new IllegalStateException("database password is secret123");
        }
    }

    record NewThing(@NotBlank String name, @Min(1) int quantity) {
    }
}
