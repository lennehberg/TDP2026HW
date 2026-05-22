package com.att.tdp.issueflow.ticket.dto;

import com.att.tdp.issueflow.ticket.Priority;
import com.att.tdp.issueflow.ticket.TicketStatus;

import jakarta.validation.constraints.Size;

import org.openapitools.jackson.nullable.JsonNullable;

import java.time.Instant;

/**
 * PATCH /tickets/:id body. Every mutable field is wrapped in
 * {@link JsonNullable} so the service can distinguish "field absent from
 * JSON" (leave value untouched) from "field present and explicitly null"
 * (clear / unassign). Phase 13's auto-escalation reset rule keys on
 * {@code priority().isPresent()}, not on the value — uniform wrapping
 * makes that trigger trivial.
 * <p>
 * Mutable set = title, description, status, priority, assigneeId, dueDate.
 * {@code type} and {@code projectId} are NOT mutable: PDF §2.4 lists no
 * type change, and moving a ticket between projects is out of scope.
 * <p>
 * Value-level null rules (enforced in service):
 * <ul>
 *   <li>title, description, status, priority — cannot be set to null.</li>
 *   <li>assigneeId, dueDate — null is legal (unassign / clear).</li>
 * </ul>
 */
public record UpdateTicketRequest(
        @Size(max = 256)
        JsonNullable<String> title,

        @Size(max = 4096)
        JsonNullable<String> description,

        JsonNullable<TicketStatus> status,

        JsonNullable<Priority> priority,

        JsonNullable<Long> assigneeId,

        JsonNullable<Instant> dueDate
) {
}
