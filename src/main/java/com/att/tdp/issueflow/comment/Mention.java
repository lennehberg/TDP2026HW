package com.att.tdp.issueflow.comment;

import com.att.tdp.issueflow.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.*;

/**
 * Persists a link between a Comment and a mentioned user.
 * Mentioned are hard-deleted when the parent Comment is deleted.
 */
@Entity
@Table(name="mentions")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class Mention extends BaseEntity {
    @Column(name = "comment_id", nullable = false)
    private Long commentId;

    @Column(name = "mentioned_user_id", nullable = false)
    private Long mentionedUserId;
}
