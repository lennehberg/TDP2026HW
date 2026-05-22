package com.att.tdp.issueflow.auth;

import com.att.tdp.issueflow.auth.dto.LoginRequest;
import com.att.tdp.issueflow.auth.dto.LoginResponse;
import com.att.tdp.issueflow.common.exception.NotFoundException;
import com.att.tdp.issueflow.user.User;
import com.att.tdp.issueflow.user.UserRepository;
import com.att.tdp.issueflow.user.dto.UserResponse;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jws;

import lombok.RequiredArgsConstructor;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Orchestrates the three auth use cases so {@code AuthController} stays thin.
 * The Spring {@link AuthenticationManager} does the actual password check
 * (against {@code UserDetailsServiceImpl} + {@code BCryptPasswordEncoder} wired
 * by Spring Boot's auto-configuration) — we just trigger it and mint the token.
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final RevokedTokenRepository revokedTokenRepository;

    /**
     * Verify credentials and return a freshly minted JWT. On bad password
     * (or unknown user, which {@code UserDetailsServiceImpl} surfaces as the
     * same exception), Spring throws {@code BadCredentialsException} which
     * {@code GlobalExceptionHandler} maps to 401.
     */
    public LoginResponse login(LoginRequest req) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(req.username(), req.password()));

        // Authentication succeeded — fetch the canonical User to embed its id and
        // role in the token (the Authentication object only carries the username
        // and authorities, not the numeric id).
        User user = userRepository.findByUsername(req.username())
                .orElseThrow(() -> new NotFoundException("user not found after auth"));

        String token = jwtService.issue(user);
        return new LoginResponse(token, "Bearer", jwtService.expiresInSeconds());
    }

    /**
     * Add the token's jti to the deny-list so the filter rejects subsequent
     * uses. Defensive: if the supplied token is missing/malformed/already
     * expired we still return 200 — logout must be idempotent for clients
     * that retry on timeout.
     */
    @Transactional
    public void logout(String bearerToken) {
        if (bearerToken == null || bearerToken.isBlank()) {
            return;
        }
        try {
            Jws<Claims> jws = jwtService.parse(bearerToken);
            String jti = jws.getPayload().getId();
            Instant exp = jws.getPayload().getExpiration().toInstant();
            if (jti != null && !revokedTokenRepository.existsById(jti)) {
                // Persist the jti only — the row itself is the "revoked" signal.
                revokedTokenRepository.save(new RevokedToken(jti, exp));
            }
        } catch (JwtException ignored) {
            // Token is already invalid (bad sig / expired). No need to deny-list
            // it; the filter would reject on parse anyway.
        }
    }

    /**
     * Look up the currently authenticated user. The filter put the username
     * into the SecurityContext as {@code Authentication.getName()}; the lookup
     * by username here is the cheapest path to a full {@link UserResponse}.
     */
    @Transactional(readOnly = true)
    public UserResponse me(String username) {
        User u = userRepository.findByUsername(username)
                .orElseThrow(() -> new NotFoundException("user not found: " + username));
        return new UserResponse(u.getId(), u.getUsername(), u.getEmail(), u.getFullName(), u.getRole());
    }
}
