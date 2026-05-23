package com.att.tdp.issueflow.comment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateCommentRequest(
        @NotNull(message = "authorId is required")
        Long authorId,

        @NotBlank(message = "content is required")
        @Size(max = 4096, message = "content must be less than 4096 characters")
        String content
) {
}
