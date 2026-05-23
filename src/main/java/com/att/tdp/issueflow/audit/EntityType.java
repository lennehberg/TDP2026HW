package com.att.tdp.issueflow.audit;

/**
 * Pinned vocabulary for the {@code entityType} column of {@code AuditLog}.
 * Same rule as {@link AuditAction}: every audit write picks exactly one of
 * these — strings forbidden.
 */
public enum EntityType {
    USER,
    PROJECT,
    TICKET,
    COMMENT,
    ATTACHMENT,
    DEPENDENCY,
    MENTION
}
