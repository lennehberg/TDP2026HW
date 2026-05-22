package com.att.tdp.issueflow.ticket.dto;

import com.att.tdp.issueflow.ticket.Priority;
import com.att.tdp.issueflow.ticket.TicketStatus;
import com.att.tdp.issueflow.ticket.TicketType;

import java.time.Instant;

/**
 * Field order matches the README sample exactly for easy grep verification.
 * {@code createdAt}, {@code updatedAt}, and {@code version} are intentionally
 * omitted — they're on the entity for business logic, but the README
 * contract has none of them.
 */
public record TicketResponse(
        Long id,
        String title,
        String description,
        TicketStatus status,
        Priority priority,
        TicketType type,
        Long projectId,
        Long assigneeId,
        Instant dueDate,
        boolean isOverdue
) {
}
