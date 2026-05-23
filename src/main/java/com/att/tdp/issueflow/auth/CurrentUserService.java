package com.att.tdp.issueflow.auth;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Reads the current user's id from the {@link SecurityContextHolder}.
 * Empty when no authenticated request is in progress (tests, anonymous
 * paths, scheduled jobs).
 * <p>
 * Used by {@link com.att.tdp.issueflow.audit.AuditService} to stamp the
 * {@code performedBy} column on user-actor rows. The reverse — looking up
 * by username — already exists on the {@link AuthService#me(String)} path.
 */
@Component
public class CurrentUserService {

    public Optional<Long> userId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return Optional.empty();
        }
        Object principal = auth.getPrincipal();
        if (principal instanceof AuthenticatedUser u) {
            return Optional.ofNullable(u.id());
        }
        return Optional.empty();
    }
}
