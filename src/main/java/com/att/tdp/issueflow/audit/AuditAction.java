package com.att.tdp.issueflow.audit;

/**
 * Pinned vocabulary for the {@code action} column of {@code AuditLog}.
 * Every state-changing call site uses one of these — no ad-hoc strings
 * anywhere in the codebase.
 */
public enum AuditAction {
    CREATE,
    UPDATE,
    DELETE,
    RESTORE,
    AUTO_ASSIGN,
    AUTO_ESCALATE
}
