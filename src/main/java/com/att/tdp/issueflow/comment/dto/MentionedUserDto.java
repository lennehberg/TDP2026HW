package com.att.tdp.issueflow.comment.dto;

/**
 * Slot for an @username mention inside a comment response.
 * <p>
 * Phase 5 never populates this — the {@code mentionedUsers} array is
 * always emitted as {@code []}. Phase 9 will parse {@code @username}
 * tokens out of {@code Comment.content} and populate the list, and
 * will reuse this record for the {@code GET /users/{id}/mentions}
 * endpoint (§3.6 of the spec).
 */
public record MentionedUserDto(
        Long id,
        String username,
        String fullName
) {}
