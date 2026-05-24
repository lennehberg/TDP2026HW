package com.att.tdp.issueflow.auth;

import com.att.tdp.issueflow.user.User;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * Issues and verifies JWTs against the HS256 secret in {@link JwtProperties}.
 * <p>
 * Claim layout:
 * <ul>
 *   <li>{@code sub} — username (login identifier)</li>
 *   <li>{@code uid} — numeric user id (lets us look up by id without a second DB hit)</li>
 *   <li>{@code role} — "ADMIN" or "DEVELOPER" (drives Spring authorities)</li>
 *   <li>{@code jti} — random UUID per token, used as the deny-list PK
 *       by {@link RevokedTokenRepository} so logout can invalidate a still-
 *       valid (signature + expiry) token</li>
 *   <li>{@code iat}/{@code exp}/{@code iss} — standard RFC 7519 claims</li>
 * </ul>
 */
@Service
public class JwtService {

    private final JwtProperties props;
    private final SecretKey signingKey;

    public JwtService(JwtProperties props) {
        this.props = props;
        // HS256 needs a >=256-bit key. JwtProperties already validates the
        // minimum length; encoding to UTF-8 bytes gives us a deterministic key
        // derived from the secret string.
        this.signingKey = Keys.hmacShaKeyFor(props.secret().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Mint a signed token for the given user. Caller is responsible for
     * persisting the {@code jti} elsewhere if revocation tracking is needed
     * beyond logout.
     */
    public String issue(User user) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(props.expiresInSeconds());
        return Jwts.builder()
                .subject(user.getUsername())
                .issuer(props.issuer())
                .id(UUID.randomUUID().toString())   // jti — used by RevokedToken deny-list
                .claim("uid", user.getId())
                .claim("role", user.getRole().name())
                .issuedAt(java.util.Date.from(now))
                .expiration(java.util.Date.from(expiry))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Parse and verify a token. Throws on bad signature, wrong issuer, or
     * expiry — callers (the filter) should catch {@link io.jsonwebtoken.JwtException}
     * and treat it as "unauthenticated" rather than 500-ing.
     */
    public Jws<Claims> parse(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .requireIssuer(props.issuer())
                .build()
                .parseSignedClaims(token);
    }

    /** Seconds until expiry — used by /auth/login responses. */
    public long expiresInSeconds() {
        return props.expiresInSeconds();
    }
}
