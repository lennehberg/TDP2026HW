package com.att.tdp.issueflow.comment;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface MentionRepository extends JpaRepository<Mention, Long> {
    List<Mention> findAllByCommentId(Long commentId);
    void deleteAllByCommentId(Long commentId);

    @Query("""
    SELECT c FROM Comment c
    JOIN Mention m ON m.commentId = c.id
    WHERE m.mentionedUserId = :userId
    ORDER BY c.createdAt DESC
""")
    Page<Comment> findCommentsMentioningUser(Long userId, Pageable pageable);
}
