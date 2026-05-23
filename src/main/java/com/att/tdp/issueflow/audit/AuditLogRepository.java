package com.att.tdp.issueflow.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Extends {@link JpaSpecificationExecutor} so the audit-log listing endpoint
 * can compose its optional filters (entityType, entityId, action, actor) as
 * a {@code Specification<AuditLog>} chain rather than maintaining 16
 * permutations of finder methods.
 */
public interface AuditLogRepository
        extends JpaRepository<AuditLog, Long>, JpaSpecificationExecutor<AuditLog> {
}
