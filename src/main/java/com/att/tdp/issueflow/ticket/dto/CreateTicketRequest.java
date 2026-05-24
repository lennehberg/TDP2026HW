package com.att.tdp.issueflow.ticket.dto;

import com.att.tdp.issueflow.ticket.Priority;
import com.att.tdp.issueflow.ticket.TicketStatus;
import com.att.tdp.issueflow.ticket.TicketType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * POST /tickets body. The seven core fields from PDF §2.4 are required;
 * {@code assigneeId} and {@code dueDate} are optional (§3.7). Status is
 * required on create — the README's create example always sends it, and
 * defaulting in the DTO would hide a contract violation. Creating a ticket
 * in {@code DONE} is rejected at the service layer (it would bypass the
 * DONE-immutable rule).
 */
public record CreateTicketRequest(
        @NotBlank
        @Size(max = 256)
        String title,

        @NotBlank
        @Size(max = 4096)
        String description,

        @NotNull
        TicketStatus status,

        @NotNull
        Priority priority,

        @NotNull
        TicketType type,

        @NotNull
        Long projectId,

        // Optional — auto-assigned (in TicketService.create) to the DEVELOPER
        // in the project with the fewest non-DONE tickets if left null.
        Long assigneeId,

        // Optional per §3.7.
        Instant dueDate
) {
}
