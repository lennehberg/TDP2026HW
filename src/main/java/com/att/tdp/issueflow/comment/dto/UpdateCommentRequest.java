package com.att.tdp.issueflow.comment.dto;

import jakarta.validation.constraints.Size;
import org.openapitools.jackson.nullable.JsonNullable;

/**
 * PATCH /tickets/{id}/comments/{commentId} body. {@code content} is wrapped
 * in {@link JsonNullable} so the service can distinguish "field absent from
 * JSON" (leave value untouched, §2b) from "field present and explicitly
 * null/blank" (rejected — comment content is NOT NULL).
 * <p>
 * Mirrors {@link com.att.tdp.issueflow.ticket.dto.UpdateTicketRequest}'s
 * convention so PATCH semantics are uniform across the codebase.
 */
public record UpdateCommentRequest(
        @Size(max = 4096, message = "content must be less than 4096 characters")
        JsonNullable<String> content
) {
}
