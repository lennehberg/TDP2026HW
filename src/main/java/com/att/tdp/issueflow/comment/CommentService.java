package com.att.tdp.issueflow.comment;

import com.att.tdp.issueflow.audit.AuditAction;
import com.att.tdp.issueflow.audit.AuditService;
import com.att.tdp.issueflow.audit.EntityType;
import com.att.tdp.issueflow.comment.dto.CommentResponse;
import com.att.tdp.issueflow.comment.dto.CreateCommentRequest;
import com.att.tdp.issueflow.comment.dto.MentionedUserDto;
import com.att.tdp.issueflow.comment.dto.UpdateCommentRequest;
import com.att.tdp.issueflow.common.dto.PageResponse;
import com.att.tdp.issueflow.common.exception.BadRequestException;
import com.att.tdp.issueflow.common.exception.NotFoundException;
import com.att.tdp.issueflow.ticket.TicketRepository;
import com.att.tdp.issueflow.user.User;
import com.att.tdp.issueflow.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class CommentService {

    private static final Pattern MENTION_PATTERN = Pattern.compile("@([A-Za-z0-9_]+)");

    private final CommentRepository commentRepository;
    private final TicketRepository ticketRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final MentionRepository mentionRepository;

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
        
        syncMentions(saved);

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
            syncMentions(c);
        }
        auditService.recordUserAction(AuditAction.UPDATE, EntityType.COMMENT, commentId);

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

        // Cascade-delete Mention rows and audit each
        List<Mention> mentions = mentionRepository.findAllByCommentId(commentId);
        for (Mention m : mentions) {
            mentionRepository.delete(m);
            auditService.recordUserAction(AuditAction.DELETE, EntityType.MENTION, m.getId());
        }

        commentRepository.delete(c);
        auditService.recordUserAction(AuditAction.DELETE, EntityType.COMMENT, commentId);
    }

    @Transactional(readOnly = true)
    public PageResponse<CommentResponse> listUserMentions(Long userId, int page, int pageSize) {
        if (!userRepository.existsById(userId)) {
            throw new NotFoundException("user " + userId + " not found");
        }

        // Spring Data Pageable is 0-indexed; the API is 1-indexed.
        PageRequest pageRequest = PageRequest.of(page - 1, pageSize);
        Page<Comment> commentPage = mentionRepository.findCommentsMentioningUser(userId, pageRequest);

        return new PageResponse<>(
                commentPage.getContent().stream().map(this::toResponse).toList(),
                commentPage.getTotalElements(),
                page
        );
    }

    private void syncMentions(Comment comment) {
        // extract unique usernames (case-insensitive)
        Set<String> usernames = new HashSet<>();
        Matcher matcher = MENTION_PATTERN.matcher(comment.getContent());

        while (matcher.find()) {
            usernames.add(matcher.group(1).toLowerCase());
        }

        // filter usernames
        List<User> users = usernames.stream()
                        .map(userRepository::findByUsernameIgnoreCase)
                        .flatMap(Optional::stream)
                        .toList();

        // For simplicity, we clear and recreate, logging each deletion and creation.
        List<Mention> existing = mentionRepository.findAllByCommentId(comment.getId());
        for (Mention m : existing) {
            mentionRepository.delete(m);
            auditService.recordUserAction(AuditAction.DELETE, EntityType.MENTION, m.getId());
        }

        for (User user : users) {
            Mention m =
                    mentionRepository.save(Mention.builder()
                                    .commentId(comment.getId())
                                    .mentionedUserId(user.getId()).build());

            auditService.recordUserAction(AuditAction.CREATE, EntityType.MENTION, m.getId());
        }
    }

    private CommentResponse toResponse(Comment c) {
        List<MentionedUserDto> mentionedUsers =
                userRepository.findUserMentionedInComment(c.getId())
                        .stream()
                        .map(u -> new MentionedUserDto(
                                u.getId(),
                                u.getUsername(),
                                u.getFullName()))
                        .toList();

        return new CommentResponse(
                c.getId(),
                c.getTicketId(),
                c.getAuthorId(),
                c.getContent(),
                mentionedUsers
        );
    }
}
