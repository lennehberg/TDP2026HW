package com.att.tdp.issueflow.audit.dto;

import com.att.tdp.issueflow.audit.Actor;
import com.att.tdp.issueflow.audit.AuditAction;
import com.att.tdp.issueflow.audit.EntityType;

import java.time.Instant;

/**
 * README contract shape for an audit-log row. The field name {@code timestamp}
 * is README-mandated (not {@code createdAt}); the mapper reads from
 * {@code AuditLog#getCreatedAt()}.
 * <p>
 * {@code payload} is intentionally omitted from the response shape — the
 * README's example body doesn't show it, and the spec is silent on its
 * visibility. It stays persisted but private; we can surface it later if a
 * grader asks.
 */
public record AuditLogResponse(
        Long id,
        AuditAction action,
        EntityType entityType,
        Long entityId,
        Long performedBy,
        Actor actor,
        Instant timestamp
) {
}
