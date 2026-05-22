package com.att.tdp.issueflow.common.dto;

import java.util.Map;

/**
 * Error body for Bean Validation failures (HTTP 400). {@code errors} maps
 * the offending field name to its violation message; multiple violations
 * on the same field collapse to the first one observed.
 */
public record ValidationErrorResponse(Map<String, String> errors) {
}