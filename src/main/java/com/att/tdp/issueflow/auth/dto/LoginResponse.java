package com.att.tdp.issueflow.auth.dto;

/**
 * Success response for {@code POST /auth/login}. {@code tokenType} is hard-coded
 * to {@code "Bearer"} (we don't support any other scheme); {@code expiresIn} is
 * in seconds, matching the OAuth2 convention so clients don't need a parser.
 */
public record LoginResponse(
        String accessToken,
        String tokenType,
        long expiresIn
) {
}