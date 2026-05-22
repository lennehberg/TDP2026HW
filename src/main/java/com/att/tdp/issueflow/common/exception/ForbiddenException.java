package com.att.tdp.issueflow.common.exception;

/**
 * Thrown when the authenticated principal lacks permission for an action.
 * Reserved for service-layer authorization checks; controller-level
 * {@code @PreAuthorize} failures are translated separately by Spring Security.
 */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}