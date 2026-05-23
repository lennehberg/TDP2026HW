package com.att.tdp.issueflow.comment;

import com.att.tdp.issueflow.comment.dto.CommentResponse;
import com.att.tdp.issueflow.comment.dto.CreateCommentRequest;
import com.att.tdp.issueflow.comment.dto.UpdateCommentRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/tickets/{ticketId}/comments")
@RequiredArgsConstructor
public class CommentController {

    private final CommentService commentService;

    @GetMapping
    public List<CommentResponse> getComments(@PathVariable Long ticketId) {
        return commentService.listByTicket(ticketId);
    }

    @PostMapping
    public CommentResponse addComment(@PathVariable Long ticketId, @Valid @RequestBody CreateCommentRequest request) {
        return commentService.create(ticketId, request);
    }

    @PatchMapping("/{commentId}")
    public CommentResponse updateComment(@PathVariable Long ticketId, @PathVariable Long commentId, @Valid @RequestBody UpdateCommentRequest request) {
        return commentService.update(ticketId, commentId, request);
    }

    @DeleteMapping("/{commentId}")
    public void deleteComment(@PathVariable Long ticketId, @PathVariable Long commentId) {
        commentService.delete(ticketId, commentId);
    }
}
