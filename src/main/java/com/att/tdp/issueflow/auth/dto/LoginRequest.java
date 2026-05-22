package com.att.tdp.issueflow.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Credentials for {@code POST /auth/login}. Both fields are required — Bean
 * Validation rejects blanks before the controller method runs, so {@code AuthService}
 * never has to defensively null-check.
 */
public record LoginRequest(
        @NotBlank String username,
        @NotBlank String password
) {
}
