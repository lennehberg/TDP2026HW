package com.att.tdp.issueflow.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jws;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Reads {@code Authorization: Bearer <token>}, verifies the JWT, and populates
 * {@link SecurityContextHolder} with an {@link UsernamePasswordAuthenticationToken}
 * carrying the user's role as a {@code ROLE_*} authority.
 * <p>
 * Failure mode is intentionally silent: on a missing header, malformed token,
 * bad signature, expiry, or revoked jti, we leave the context empty and let
 * the request continue. Downstream, {@code SecurityConfig}'s entry point will
 * convert the "no auth" state into a 401. This keeps the filter free of
 * response-writing concerns.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final RevokedTokenRepository revokedTokenRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        // Skip cleanly when the header is absent or not a Bearer scheme — the
        // request stays unauthenticated and the entry point will 401 if needed.
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        String token = header.substring(BEARER_PREFIX.length()).trim();
        try {
            Jws<Claims> jws = jwtService.parse(token);
            Claims claims = jws.getPayload();

            String jti = claims.getId();
            // Deny-list check: a logged-out token still parses (good signature,
            // not yet expired), but must be rejected. Same outcome as a bad
            // signature — leave the context empty.
            if (jti != null && revokedTokenRepository.existsById(jti)) {
                chain.doFilter(request, response);
                return;
            }

            String username = claims.getSubject();
            String role = claims.get("role", String.class);
            // Spring's hasRole("ADMIN") prepends "ROLE_" — store the prefixed form.
            List<SimpleGrantedAuthority> authorities = role == null
                    ? List.of()
                    : List.of(new SimpleGrantedAuthority("ROLE_" + role));

            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(username, null, authorities);
            SecurityContextHolder.getContext().setAuthentication(auth);
        } catch (JwtException ex) {
            // Bad signature / expired / wrong issuer / malformed — fall through
            // unauthenticated. We deliberately do not log at WARN here: noisy
            // tokens from probes would spam logs in production.
            SecurityContextHolder.clearContext();
        }

        chain.doFilter(request, response);
    }
}