package com.att.tdp.issueflow.ticket;

import com.att.tdp.issueflow.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;

/**
 * A ticket belongs to a project and may be assigned to a single user.
 * <p>
 * Concurrency: {@code @Version} opts this entity into optimistic locking
 * (PDF §2.4/§2.5 require it). Conflicting writes raise
 * {@code ObjectOptimisticLockingFailureException}, which the global handler
 * maps to 409 Conflict.
 * <p>
 * Phase 8 added {@code @SQLRestriction("deleted_at IS NULL")} so every
 * standard {@code JpaRepository} query auto-filters soft-deleted rows.
 * Listings and restore of soft-deleted tickets go through the native
 * {@code findAllDeletedByProjectId} / {@code findByIdIncludingDeleted}
 * finders on {@link TicketRepository}, which bypass the restriction.
 * Notable downstream effect: the Phase 7 blocker-status fetch silently
 * drops soft-deleted blockers, so a soft-deleted blocker effectively
 * unblocks its dependent (correct per spec; documented in {@code run.md}).
 * <p>
 * {@code isOverdue} is owned by the Phase 13 scheduler (sets it,
 * actor=SYSTEM) and the manual {@code priority} PATCH path (clears it,
 * actor=USER). Status changes never touch it. Phase 4 writes it exactly
 * once: {@code false} on create.
 * <p>
 * {@code projectId} and {@code assigneeId} are raw {@code Long}s (no
 * {@code @ManyToOne}) to match the codebase convention; existence checks
 * happen in the service layer.
 */
@Entity
@Table(name = "tickets")
@SQLRestriction("deleted_at IS NULL")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Ticket extends BaseEntity {

    @Column(nullable = false, length = 256)
    private String title;

    @Column(nullable = false, length = 4096)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TicketStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Priority priority;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TicketType type;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "assignee_id")
    private Long assigneeId;

    @Column(name = "due_date")
    private Instant dueDate;

    // Primitive — can't be null, which removes a class of NPE in the DTO mapper.
    @Column(name = "is_overdue", nullable = false)
    @Builder.Default
    private boolean isOverdue = false;

    @Version
    private Long version;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "last_manual_priority_change_at")
    private Instant lastManualPriorityChangeAt;

    @Column(name = "last_escalated_at")
    private Instant lastEscalatedAt;
}
