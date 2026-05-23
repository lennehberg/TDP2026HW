package com.att.tdp.issueflow.audit;

/**
 * Who performed the action recorded in an {@code AuditLog} row.
 * <p>
 * Invariant (BUILD_PLAN Phase 6): when {@code actor == SYSTEM} (auto-assign,
 * auto-escalate), {@code performedBy} must be {@code null} — there is no
 * sentinel "system user" id.
 */
public enum Actor {
    USER,
    SYSTEM
}
