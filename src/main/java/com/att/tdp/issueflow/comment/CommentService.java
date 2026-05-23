package com.att.tdp.issueflow.comment;

import com.att.tdp.issueflow.audit.AuditAction;
import com.att.tdp.issueflow.audit.AuditService;
import com.att.tdp.issueflow.audit.EntityType;
import com.att.tdp.issueflow.comment.dto.CommentResponse;
import com.att.tdp.issueflow.comment.dto.CreateCommentRequest;
import com.att.tdp.issueflow.comment.dto.UpdateCommentRequest;
import com.att.tdp.issueflow.common.exception.BadRequestException;
import com.att.tdp.issueflow.common.exception.NotFoundException;
import com.att.tdp.issueflow.ticket.TicketRepository;
import com.att.tdp.issueflow.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CommentService {

    private final CommentRepository commentRepository;
    private final TicketRepository ticketRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<CommentResponse> listByTicket(Long ticketId) {
        // Mirrors ProjectService.listByProject: a missing parent yields an empty
        // list rather than 404 (§4b).
        return commentRepository.findAllByTicketIdOrderByCreatedAtAsc(ticketId)
                                .stream()
                                .map(this::toResponse)
                                .toList();
    }

    @Transactional
    public CommentResponse create(Long ticketId, CreateCommentRequest req) {
        if (!ticketRepository.existsById(ticketId)) {
            throw new NotFoundException("ticket " + ticketId + " not found");
        }
        // Body reference → 400 (URL parent → 404). Convention shared with TicketService.
        if (!userRepository.existsById(req.authorId())) {
            throw new BadRequestException("author " + req.authorId() + " not found");
        }
        Comment saved = commentRepository.save(Comment.builder()
                .ticketId(ticketId)
                .authorId(req.authorId())
                .content(req.content())
                .build());
        auditService.recordUserAction(AuditAction.CREATE, EntityType.COMMENT, saved.getId());
        // TODO Phase 9: parse @username mentions from saved.getContent(), persist
        //               Mention rows, audit each as (CREATE, MENTION, mention.getId()).
        return toResponse(saved);
    }

    @Transactional
    public CommentResponse update(Long ticketId, Long commentId, UpdateCommentRequest req) {
        Comment c = commentRepository.findById(commentId)
                .orElseThrow(() -> new NotFoundException("comment " + commentId + " not found"));

        // From the client's perspective the URL doesn't address a real resource (§4c).
        if (!c.getTicketId().equals(ticketId)) {
            throw new NotFoundException(
                    "comment " + commentId + " not found on ticket " + ticketId);
        }

        // PATCH semantics: undefined → leave untouched; present + null/blank → 400.
        // Same shape as TicketService's ApplyTitle/ApplyDescription.
        if (req.content() != null && req.content().isPresent()) {
            String content = req.content().get();
            if (content == null || content.isBlank()) {
                throw new BadRequestException("content cannot be blank");
            }
            c.setContent(content);
        }
        auditService.recordUserAction(AuditAction.UPDATE, EntityType.COMMENT, commentId);
        // TODO Phase 9: re-evaluate mentions from saved.getContent();
        //               insert new mentions, delete removed mentions, audit each.
        return toResponse(c);
    }

    @Transactional
    public void delete(Long ticketId, Long commentId) {
        Comment c = commentRepository.findById(commentId)
                .orElseThrow(() -> new NotFoundException("comment " + commentId + " not found"));

        if (!c.getTicketId().equals(ticketId)) {
            throw new NotFoundException(
                    "comment " + commentId + " not found on ticket " + ticketId);
        }

        commentRepository.delete(c);
        auditService.recordUserAction(AuditAction.DELETE, EntityType.COMMENT, commentId);
        // TODO Phase 9: cascade-delete Mention rows and audit each
        //               as (DELETE, MENTION, mention.getId()).
    }

    private CommentResponse toResponse(Comment c) {
        return new CommentResponse(
                c.getId(),
                c.getTicketId(),
                c.getAuthorId(),
                c.getContent(),
                List.of() // Mentions empty until Phase 9
        );
    }
}
