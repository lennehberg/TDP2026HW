package com.att.tdp.issueflow.comment.dto;

/**
 * Slot for an {@code @username} mention inside a comment response.
 * Populated by parsing {@code @username} tokens out of {@code Comment.content}
 * and matching against {@code UserRepository}; also reused by the
 * {@code GET /users/{id}/mentions} endpoint (§3.6).
 */
public record MentionedUserDto(
        Long id,
        String username,
        String fullName
) {}
