package com.att.tdp.issueflow.auth;

import com.att.tdp.issueflow.auth.dto.LoginRequest;
import com.att.tdp.issueflow.auth.dto.LoginResponse;
import com.att.tdp.issueflow.user.dto.UserResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Auth endpoints. {@code /auth/login} is the only path open to anonymous
 * requests — {@code /auth/logout} and {@code /auth/me} require a valid token,
 * gated by the {@code SecurityFilterChain}.
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest req) {
        return authService.login(req);
    }

    /**
     * Logout always returns 200. Idempotent by design — clients that retry on
     * a flaky network shouldn't see different statuses for "first logout" vs
     * "already logged out". The body is empty; the deny-list write is the
     * side effect that matters.
     */
    @PostMapping("/logout")
    public void logout(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        // Filter has already accepted this request, so the header is well-formed
        // — but extract defensively in case a future filter change loosens that.
        if (header != null && header.startsWith("Bearer ")) {
            authService.logout(header.substring("Bearer ".length()).trim());
        }
    }

    @GetMapping("/me")
    public UserResponse me(Authentication authentication) {
        // The JWT filter populates Authentication.getName() with the token's
        // subject claim (the username). AuthService re-loads the canonical user.
        return authService.me(authentication.getName());
    }
}
