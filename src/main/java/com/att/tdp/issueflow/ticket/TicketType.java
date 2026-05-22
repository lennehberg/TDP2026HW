package com.att.tdp.issueflow.ticket;

/**
 * Ticket type per PDF §2.4. Immutable after creation — not in the
 * {@code UpdateTicketRequest} mutable set.
 */
public enum TicketType {
    BUG,
    FEATURE,
    TECHNICAL
}
