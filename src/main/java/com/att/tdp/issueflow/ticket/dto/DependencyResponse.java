package com.att.tdp.issueflow.ticket.dto;

import com.att.tdp.issueflow.ticket.TicketStatus;

/**
 * README shape for {@code GET /tickets/{ticketId}/dependencies} list elements.
 * The three fields come from the <em>blocker</em> ticket — {@code id} is the
 * blocker's id, so a client can use it directly as the {@code :blockerId}
 * path variable in the DELETE endpoint.
 */
public record DependencyResponse(
        Long id,
        String title,
        TicketStatus status
) {
}
