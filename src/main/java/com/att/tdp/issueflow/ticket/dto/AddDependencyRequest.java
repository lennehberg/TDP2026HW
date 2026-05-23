package com.att.tdp.issueflow.ticket.dto;

import jakarta.validation.constraints.NotNull;

/**
 * POST body for {@code POST /tickets/{ticketId}/dependencies}.
 * The dependent ticket id comes from the URL, not the body — including it
 * here would invite mismatch bugs.
 */
public record AddDependencyRequest(
        @NotNull Long blockedBy
) {
}
