package com.att.tdp.issueflow.ticket;

/**
 * Ticket lifecycle status per PDF §2.4. Declaration order IS the lifecycle
 * order: {@code TicketStatusValidator} uses {@link Enum#ordinal()} to enforce
 * forward-only transitions. Reordering this enum will silently break the
 * validator — don't.
 */
public enum TicketStatus {
    TODO,
    IN_PROGRESS,
    IN_REVIEW,
    DONE
}
