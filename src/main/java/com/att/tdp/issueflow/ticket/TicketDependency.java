package com.att.tdp.issueflow.ticket;

import com.att.tdp.issueflow.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A "blocked by" link between two tickets.
 * <p>
 * Surrogate id (inherited from {@link BaseEntity}) exists so audit-log rows
 * for {@code EntityType.DEPENDENCY} have a stable {@code entityId} to point
 * at, matching every other entity's audit shape. The id is never exposed in
 * the URL — the DELETE endpoint keys on the blocker ticket's id, not on the
 * join-row id.
 * <p>
 * The DB-level unique constraint on {@code (ticket_id, blocked_by_id)} is
 * the durable guarantee that a pair can't be inserted twice; the service
 * layer's pre-check just makes the error message friendlier under
 * non-concurrent calls.
 * <p>
 * Deliberate omissions:
 * <ul>
 *   <li>No {@code @Version} — dependencies aren't edited, only added/removed.</li>
 *   <li>No {@code deletedAt} — dependencies are hard-deleted; audit log
 *       carries the history.</li>
 *   <li>No {@code @ManyToOne} — raw {@code Long} FKs match the codebase
 *       convention. Same-project and ticket-exists checks live in
 *       {@link TicketDependencyService}.</li>
 * </ul>
 */
@Entity
@Table(name = "ticket_dependencies",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_ticket_dependency_ticket_blocked_by",
                columnNames = {"ticket_id", "blocked_by_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TicketDependency extends BaseEntity {

    @Column(name = "ticket_id", nullable = false)
    private Long ticketId;

    @Column(name = "blocked_by_id", nullable = false)
    private Long blockedById;
}
