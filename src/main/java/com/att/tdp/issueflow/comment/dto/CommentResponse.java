package com.att.tdp.issueflow.comment.dto;

import java.util.List;

public record CommentResponse(
        Long id,
        Long ticketId,
        Long authorId,
        String content,
        // Populated by parsing @username tokens from content and joining
        // against UserRepository (case-insensitive); never null, may be empty.
        List<MentionedUserDto> mentionedUsers
) {
}
