package com.att.tdp.issueflow.common.exception;

/**
 * Thrown when a state change would violate a uniqueness or domain
 * invariant (duplicate username/email, optimistic-locking failure, etc.).
 * Mapped to HTTP 409 by {@link GlobalExceptionHandler}.
 */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}