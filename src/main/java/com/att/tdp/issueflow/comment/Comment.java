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
 * Comments are hard-deleted; child {@link Mention} rows are removed
 * explicitly by {@link CommentService#delete} (no JPA cascade since the
 * association is by raw {@code commentId}, not {@code @OneToMany}).
 * <p>
 * Mentions: the {@code mentionedUsers} slot on the response DTO is
 * populated by parsing {@code @username} tokens out of {@code content}
 * and matching them (case-insensitive) against {@code UserRepository}.
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
