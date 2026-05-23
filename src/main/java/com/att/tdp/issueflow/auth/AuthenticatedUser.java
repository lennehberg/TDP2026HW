package com.att.tdp.issueflow.auth;

import java.security.Principal;

/**
 * Principal stashed on the {@code Authentication} by {@link JwtAuthenticationFilter}.
 * Carries both the {@code uid} (numeric user id from the JWT) and the username
 * so downstream code can pick whichever it needs without a second DB hit.
 * <p>
 * Implements {@link Principal#getName()} so {@code Authentication.getName()}
 * still returns the username — keeps existing call sites
 * (e.g. {@code AuthController.me}) untouched.
 */
public record AuthenticatedUser(Long id, String username) implements Principal {

    @Override
    public String getName() {
        return username;
    }
}
