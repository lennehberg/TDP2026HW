package com.att.tdp.issueflow.common.exception;

/**
 * Thrown by services when the request body is well-formed (deserializes,
 * passes Bean Validation) but is semantically invalid — typically a foreign
 * key id that doesn't reference an existing row. Mapped to HTTP 400 by
 * {@link GlobalExceptionHandler}.
 */
public class BadRequestException extends RuntimeException {
    public BadRequestException(String message) {
        super(message);
    }
}
