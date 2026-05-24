package com.att.tdp.issueflow.audit;

import com.att.tdp.issueflow.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Append-only record of every state-changing action in the system.
 * <p>
 * The {@code createdAt} timestamp inherited from {@link BaseEntity} is what
 * the README exposes as {@code timestamp} — the field rename happens in the
 * response mapper, not on the entity. {@code updatedAt} is unused (audit rows
 * are never modified) but inherited harmlessly.
 * <p>
 * Invariants enforced at the service layer (not at the column level):
 * <ul>
 *   <li>{@code actor == SYSTEM} ⇒ {@code performedBy == null}. No sentinel
 *       "system user" id. Auto-assign and auto-escalate both respect this
 *       via {@link AuditService#recordSystemAction}.</li>
 *   <li>{@code action}/{@code entityType} are the pinned enum vocabularies
 *       ({@link AuditAction}, {@link EntityType}). No ad-hoc strings.</li>
 * </ul>
 * <p>
 * {@code payload} is an optional JSON blob serialized to a {@code String}
 * column — keeps H2 (tests) and Postgres (dev/prod) on the same shape. The
 * spec doesn't require querying inside it.
 */
@Entity
@Table(name = "audit_logs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AuditAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 32)
    private EntityType entityType;

    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    // Nullable: SYSTEM actor (auto-assign / auto-escalate) leaves this null.
    @Column(name = "performed_by")
    private Long performedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Actor actor;

    // Serialized JSON. TEXT in Postgres, CLOB-compatible in H2.
    @Column(columnDefinition = "TEXT")
    private String payload;
}
