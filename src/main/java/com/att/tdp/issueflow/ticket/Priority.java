package com.att.tdp.issueflow.ticket;

/**
 * Ticket priority per PDF §2.4. Declaration order is ascending severity;
 * Phase 13's auto-escalation bumps via {@link Enum#ordinal()}, so don't
 * reorder.
 */
public enum Priority {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}
