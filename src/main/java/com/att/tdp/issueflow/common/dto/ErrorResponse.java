package com.att.tdp.issueflow.common.dto;

/**
 * Uniform error body for non-validation failures (404, 409, etc.).
 * Validation errors use {@link ValidationErrorResponse} instead so the
 * per-field map has a typed home.
 */
public record ErrorResponse(String message) {
}