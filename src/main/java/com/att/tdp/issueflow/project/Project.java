package com.att.tdp.issueflow.project;

import com.att.tdp.issueflow.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A project groups tickets and is owned by a single user. Optimistic locking
 * is deliberately omitted ({@code BaseEntity} carries no {@code @Version});
 * only Ticket and Comment opt in per the build plan.
 * <p>
 * {@code deletedAt} lands ahead of Phase 8, which will add
 * {@code @SQLRestriction("deleted_at IS NULL")} so every standard
 * {@code JpaRepository} query auto-filters soft-deleted rows. Until then,
 * soft-deleted projects remain visible via the standard finders.
 * <p>
 * {@code ownerId} is kept as a raw {@code Long} (no {@code @ManyToOne}) to
 * match {@code User}'s convention and keep DTOs flat; owner-exists validation
 * happens in the service layer.
 */
@Entity
@Table(name = "projects")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Project extends BaseEntity {

    // 64 chars is intentional — fits the README sample and discourages bloat.
    @Column(nullable = false, length = 64)
    private String name;

    @Column(nullable = false, length = 1024)
    private String description;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(name = "deleted_at")
    private Instant deletedAt;
}
