package com.att.tdp.issueflow.auth;

import com.att.tdp.issueflow.user.Role;
import com.att.tdp.issueflow.user.User;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jws;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Plain unit tests — no Spring context. The point is that signing and
 * verification round-trip cleanly, tampered tokens fail, and expired tokens
 * fail. Everything else (HTTP wiring, security chain) is covered elsewhere.
 */
class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-32-chars-minimum-xx-xx";

    private JwtService normalService;

    /** Build a real User. {@link User#getId()} is null because we never persist
     * — fine, since the test only cares about round-trip claim integrity, and
     * the {@code uid} claim accepts null. */
    private User newUser() {
        return User.builder()
                .username("alice")
                .email("alice@example.com")
                .fullName("Alice")
                .role(Role.DEVELOPER)
                .passwordHash("$2a$10$irrelevant")
                .build();
    }

    @BeforeEach
    void setup() {
        normalService = new JwtService(new JwtProperties(SECRET, 3600, "issueflow-test"));
    }

    @Test
    void issuedTokenParsesBack() {
        String token = normalService.issue(newUser());
        Jws<Claims> jws = normalService.parse(token);
        Claims claims = jws.getPayload();

        assertEquals("alice", claims.getSubject());
        assertEquals("DEVELOPER", claims.get("role", String.class));
        assertEquals("issueflow-test", claims.getIssuer());
        // jti must be present — the deny-list relies on it.
        assertNotNull(claims.getId());
    }

    @Test
    void tamperedSignatureThrows() {
        String token = normalService.issue(newUser());
        // Flip the last char of the signature segment to break the HMAC.
        String tampered = token.substring(0, token.length() - 1)
                + (token.charAt(token.length() - 1) == 'A' ? 'B' : 'A');
        assertThrows(JwtException.class, () -> normalService.parse(tampered));
    }

    @Test
    void expiredTokenThrows() throws InterruptedException {
        // 1-second expiry — sleep just past it. Cheaper than mocking the clock
        // for this single assertion, and JJWT's expiry check is wall-clock-driven.
        JwtService shortLived =
                new JwtService(new JwtProperties(SECRET, 1, "issueflow-test"));
        String token = shortLived.issue(newUser());
        Thread.sleep(1500);
        assertThrows(JwtException.class, () -> shortLived.parse(token));
    }

    @Test
    void differentSecretFailsVerification() {
        // A token issued by one instance must not validate under another secret.
        JwtService otherKey = new JwtService(
                new JwtProperties("totally-different-secret-32-chars-min-x", 3600, "issueflow-test"));
        String token = normalService.issue(newUser());
        assertThrows(JwtException.class, () -> otherKey.parse(token));
    }
}
