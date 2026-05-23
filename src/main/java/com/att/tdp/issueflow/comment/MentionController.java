package com.att.tdp.issueflow.comment;

import com.att.tdp.issueflow.comment.dto.CommentResponse;
import com.att.tdp.issueflow.common.dto.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class MentionController {

    private final CommentService commentService;

    @GetMapping("/{userId}/mentions")
    public PageResponse<CommentResponse> getUserMentions(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {

        // implementation in CommentService maps Page<Comment> to PageResponse<CommentResponse>
        return commentService.listUserMentions(userId, page, pageSize);
    }

}
