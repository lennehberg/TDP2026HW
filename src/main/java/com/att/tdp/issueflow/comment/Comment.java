package com.att.tdp.issueflow.comment;

import com.att.tdp.issueflow.common.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.persistence.Column;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A comment posted on a ticket.
 * <p>
 * Concurrency: {@code @Version} opts this entity into optimistic locking
 * per §2.5 ("two users can't edit a comment in the same time"). Conflicting
 * writes raise {@code ObjectOptimisticLockingFailureException}, which the
 * global handler maps to 409 Conflict with a generic message shared with
 * {@link com.att.tdp.issueflow.ticket.Ticket}.
 * <p>
 * No soft-delete: §3.5 scopes soft-delete to Project and Ticket only.
 * Comments are hard-deleted. Phase 9's {@code Mention} child rows must
 * be cleaned up alongside (note for the Phase 9 author).
 * <p>
 * Mentions: the response DTO carries a {@code mentionedUsers} slot from
 * day 1 to keep the wire shape stable, but Phase 5 always emits {@code []}.
 * Phase 9 parses {@code @username} tokens out of {@code content} and
 * populates the field.
 * <p>
 * {@code ticketId} and {@code authorId} are raw {@code Long}s (no
 * {@code @ManyToOne}) to match the codebase convention; existence checks
 * live in {@link CommentService}.
 */
@Entity
@Table(name = "comments")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class Comment extends BaseEntity {

    @Column(name = "ticket_id", nullable = false)
    private Long ticketId;

    @Column(name = "author_id", nullable = false)
    private Long authorId;

    @Column(nullable = false, length = 4096)
    private String content;

    @Version
    private Long version;
}
