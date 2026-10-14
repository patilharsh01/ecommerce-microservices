package com.harshalpatil.ecommerce.platform.web;

import com.harshalpatil.ecommerce.common.security.IdentityHeaders;
import com.harshalpatil.ecommerce.common.security.Role;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Builds an {@link AuthenticatedUser} from the identity headers set by the gateway.
 * A parameter of type {@code AuthenticatedUser} requires a caller (401 otherwise);
 * {@code Optional<AuthenticatedUser>} is for endpoints that also serve anonymous callers.
 */
class AuthenticatedUserArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return AuthenticatedUser.class.equals(parameter.nestedIfOptional().getNestedParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest request, WebDataBinderFactory binderFactory) {
        Optional<AuthenticatedUser> user = resolve(request);
        if (Optional.class.equals(parameter.getParameterType())) {
            return user;
        }
        return user.orElseThrow(() -> new UnauthorizedException("No authenticated user on the request"));
    }

    private static Optional<AuthenticatedUser> resolve(NativeWebRequest request) {
        String userId = request.getHeader(IdentityHeaders.USER_ID);
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new AuthenticatedUser(Long.valueOf(userId),
                    parseRoles(request.getHeader(IdentityHeaders.USER_ROLES))));
        } catch (NumberFormatException ex) {
            throw new UnauthorizedException("Malformed user id header");
        }
    }

    private static Set<Role> parseRoles(String header) {
        Set<Role> roles = EnumSet.noneOf(Role.class);
        if (header == null) {
            return roles;
        }
        Arrays.stream(header.split(","))
                .map(String::trim)
                .forEach(value -> Arrays.stream(Role.values())
                        .filter(role -> role.name().equals(value))
                        .findFirst()
                        .ifPresent(roles::add));
        return roles;
    }
}
