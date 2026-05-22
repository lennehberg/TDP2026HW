package com.att.tdp.issueflow.auth;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Externalised JWT configuration. Bound from {@code issueflow.jwt.*} in
 * application.yaml so the production secret never lives in source control —
 * tests override with a dummy secret in {@code src/test/resources/application.yaml}.
 * <p>
 * {@code secret} is constrained to >= 32 chars: HS256 keys shorter than 256 bits
 * make JJWT throw {@code WeakKeyException}, so we fail fast at startup instead.
 */
@Validated
@ConfigurationProperties("issueflow.jwt")
public record JwtProperties(
        @NotBlank @Size(min = 32, message = "HS256 secret must be >= 32 chars (256 bits)") String secret,
        @Min(60) long expiresInSeconds,
        @NotBlank String issuer
) {
}