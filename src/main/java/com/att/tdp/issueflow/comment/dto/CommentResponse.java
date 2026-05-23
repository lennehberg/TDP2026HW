package com.att.tdp.issueflow.comment.dto;

import java.util.List;

public record CommentResponse(
        Long id,
        Long ticketId,
        Long authorId,
        String content,
        // Phase 5: deliberately always empty.
        // Phase 9: populated by parsing @username tokens from content and
        //          joining against UserRepository (case-insensitive).
        List<MentionedUserDto> mentionedUsers
) {
}
