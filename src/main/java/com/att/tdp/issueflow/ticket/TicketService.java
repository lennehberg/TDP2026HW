package com.att.tdp.issueflow.ticket;

import com.att.tdp.issueflow.common.exception.BadRequestException;
import com.att.tdp.issueflow.common.exception.NotFoundException;
import com.att.tdp.issueflow.project.ProjectRepository;
import com.att.tdp.issueflow.ticket.dto.CreateTicketRequest;
import com.att.tdp.issueflow.ticket.dto.TicketResponse;
import com.att.tdp.issueflow.ticket.dto.UpdateTicketRequest;
import com.att.tdp.issueflow.user.UserRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TicketService {

    private final TicketRepository ticketRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final TicketStatusValidator statusValidator;

    private void ApplyStatus(Ticket t, UpdateTicketRequest req) {
        if (req.status() != null && req.status().isPresent()) {
            TicketStatus nextStatus = req.status().get();

            if (nextStatus == null) {
                throw new BadRequestException("Ticket status cannot be null");
            }

            statusValidator.validateTransition(t.getStatus(), nextStatus, List.of());
            t.setStatus(nextStatus);
        }
    }

    private void ApplyTitle(Ticket t, UpdateTicketRequest req) {
        if (req.title() != null && req.title().isPresent()) {
            String title = req.title().get();
            if (title == null || title.isBlank()) {
                throw new BadRequestException("Title cannot be empty");
            }
            t.setTitle(title);
        }
    }

    private void ApplyDescription(Ticket t, UpdateTicketRequest req) {
        if (req.description() != null && req.description().isPresent()) {
            String description = req.description().get();
            if (description == null || description.isBlank()) {
                throw new BadRequestException("Description cannot be empty");
            }
            t.setDescription(description);
        }
    }

    private void ApplyPriority(Ticket t, UpdateTicketRequest req) {
        if (req.priority() != null && req.priority().isPresent()) {
            if (req.priority().get() == null) {
                throw new BadRequestException("Priority cannot be null");
            }
            t.setPriority(req.priority().get());
            // TODO Phase 13: clear isOverdue + reset escalation state
        }
    }

    private void ApplyAsigneeId(Ticket t, UpdateTicketRequest req) {
        if (req.assigneeId() != null && req.assigneeId().isPresent()) {
            Long assigneeId = req.assigneeId().get();
            if (assigneeId != null && !userRepository.existsById(assigneeId)) {
                throw new BadRequestException("assignee " + assigneeId + " not found");
            }
            t.setAssigneeId(assigneeId);
        }
    }

    private void ApplyDueDate(Ticket t, UpdateTicketRequest req) {
        if (req.dueDate() != null && req.dueDate().isPresent()) {
            t.setDueDate(req.dueDate().get());
        }
    }

    @Transactional
    public TicketResponse create(CreateTicketRequest req) {
        if (!projectRepository.existsById(req.projectId())) {
            throw new BadRequestException("project " + req.projectId() + " not found");
        }
        if (req.assigneeId() != null && !userRepository.existsById(req.assigneeId())) {
            throw new BadRequestException("assignee " + req.assigneeId() + " not found");
        }
        // A ticket born DONE can never be PATCHed (DONE-immutable rule), and the
        // spec describes DONE as the terminal state reached by transition.
        if (req.status() == TicketStatus.DONE) {
            throw new BadRequestException("cannot create a ticket in DONE status");
        }

        Ticket saved = ticketRepository.save(Ticket.builder()
                .title(req.title())
                .description(req.description())
                .status(req.status())
                .priority(req.priority())
                .type(req.type())
                .projectId(req.projectId())
                .assigneeId(req.assigneeId())      // may be null
                .dueDate(req.dueDate())
                .isOverdue(false)
                .build());

        // TODO Phase 6: auditService.record(CREATE, TICKET, saved.getId(), currentUser, USER)
        // TODO Phase 12: if (req.assigneeId() == null) auto-assign DEVELOPER with lowest workload
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public TicketResponse getById(Long id) {
        return ticketRepository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new NotFoundException("ticket " + id + " not found"));
    }

    @Transactional(readOnly = true)
    public List<TicketResponse> listByProject(Long projectId) {
        // No project-exists pre-check: per the README contract a missing
        // project just yields an empty list, which findAllByProjectId
        // already does.
        return ticketRepository.findAllByProjectId(projectId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public TicketResponse update(Long id, UpdateTicketRequest req) {
        Ticket t = ticketRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("ticket " + id + " not found"));

        if (t.getStatus() == TicketStatus.DONE) {
            throw new com.att.tdp.issueflow.common.exception.ConflictException("ticket " + id + " is DONE and cannot be modified");
        }

        ApplyStatus(t, req);
        ApplyTitle(t, req);
        ApplyDescription(t, req);
        ApplyPriority(t, req);
        ApplyAsigneeId(t, req);
        ApplyDueDate(t, req);

        // TODO Phase 6: auditService.record(UPDATE, TICKET, id, currentUser, USER, payload)
        return toResponse(t);
    }

    @Transactional
    public void delete(Long id) {
        Ticket t = ticketRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("ticket " + id + " not found"));
        // Soft delete only — Phase 8 wires @SQLRestriction so standard
        // queries auto-filter. DONE tickets ARE deletable (spec doesn't
        // forbid archiving completed work; DONE-immutable is about PATCH).
        t.setDeletedAt(Instant.now());
        // TODO Phase 6: auditService.record(DELETE, TICKET, id, currentUser, USER)
    }

    private TicketResponse toResponse(Ticket t) {
        return new TicketResponse(
                t.getId(),
                t.getTitle(),
                t.getDescription(),
                t.getStatus(),
                t.getPriority(),
                t.getType(),
                t.getProjectId(),
                t.getAssigneeId(),
                t.getDueDate(),
                t.isOverdue()
        );
    }
}
