package com.att.tdp.issueflow.ticket;

import com.att.tdp.issueflow.audit.AuditAction;
import com.att.tdp.issueflow.audit.AuditService;
import com.att.tdp.issueflow.audit.EntityType;
import com.att.tdp.issueflow.common.exception.BadRequestException;
import com.att.tdp.issueflow.common.exception.ConflictException;
import com.att.tdp.issueflow.common.exception.NotFoundException;
import com.att.tdp.issueflow.project.ProjectRepository;
import com.att.tdp.issueflow.ticket.dto.CreateTicketRequest;
import com.att.tdp.issueflow.ticket.dto.TicketResponse;
import com.att.tdp.issueflow.ticket.dto.UpdateTicketRequest;
import com.att.tdp.issueflow.user.Role;
import com.att.tdp.issueflow.user.User;
import com.att.tdp.issueflow.user.UserRepository;

import lombok.RequiredArgsConstructor;

import org.openapitools.jackson.nullable.JsonNullable;
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
    private final AuditService auditService;
    private final TicketDependencyRepository dependencyRepository;

    /**
     * Phase 12 — auto-assign per §3.8.
     * <p>
     * Scope: <i>any</i> DEVELOPER in the system, scored by their non-DONE
     * workload <i>in this project</i>. The data model has no concept of
     * "project membership" beyond the owner, so "DEVELOPER in the project"
     * effectively reduces to "DEVELOPER whose workload we measure inside this
     * project." A developer who has never touched the project gets score 0
     * and is therefore a valid (preferred) candidate — the only sensible read
     * given the model.
     * <p>
     * Tie-break: oldest registrant wins. The candidate list is pre-sorted by
     * {@code createdAt ASC}, and the strict {@code <} comparison below means
     * an equal-workload later registrant cannot displace the running winner.
     */
    private void autoAssign(Ticket ticket) {
        List<User> developers = userRepository.findAllByRoleOrderByCreatedAtAsc(Role.DEVELOPER);
        if (developers.isEmpty()) return;

        User bestCandidate = null;
        long lowestWorkload = Long.MAX_VALUE;

        for (User dev : developers) {
            long workload = ticketRepository.countActiveTicketsForUserInProject(
                    ticket.getProjectId(), dev.getId());
            if (workload < lowestWorkload) {
                lowestWorkload = workload;
                bestCandidate = dev;
            }
        }

        // Unreachable when developers is non-empty (the first iteration always
        // beats Long.MAX_VALUE), but kept defensive against future refactors.
        if (bestCandidate != null) {
            ticket.setAssigneeId(bestCandidate.getId());
            auditService.recordSystemAction(AuditAction.AUTO_ASSIGN, EntityType.TICKET, ticket.getId());
        }
    }

    private List<TicketStatus> blockerStatuses(Ticket t, TicketStatus nextStatus) {
        List<TicketStatus> blockerStatuses;
        if (nextStatus == TicketStatus.DONE) {
            List<Long> blockerIds = dependencyRepository.findAllByTicketId(t.getId())
                                    .stream()
                                    .map(TicketDependency::getBlockedById)
                                    .toList();
            blockerStatuses = blockerIds.isEmpty() ? List.of()
                                                    :
                                ticketRepository.findStatusesByIdIn(blockerIds);

        } else {
            blockerStatuses = List.of();
        }
        return blockerStatuses;
    }

    private void applyStatus(Ticket t, JsonNullable<TicketStatus> status) {
        if (status.isPresent()) {
            TicketStatus nextStatus = status.get();
            if (nextStatus == null) {
                throw new BadRequestException("Ticket status cannot be null");
            }
            statusValidator.validateTransition(t.getStatus(), nextStatus,
                    blockerStatuses(t,  nextStatus));
            t.setStatus(nextStatus);
        }
    }

    private void applyTitle(Ticket t, JsonNullable<String> title) {
        if (title.isPresent()) {
            String value = title.get();
            if (value == null || value.isBlank()) {
                throw new BadRequestException("Title cannot be empty");
            }
            t.setTitle(value);
        }
    }

    private void applyDescription(Ticket t, JsonNullable<String> description) {
        if (description.isPresent()) {
            String value = description.get();
            if (value == null || value.isBlank()) {
                throw new BadRequestException("Description cannot be empty");
            }
            t.setDescription(value);
        }
    }

    private void applyPriority(Ticket t, JsonNullable<Priority> priority) {
        // Per CLAUDE.md: the Phase 13 reset rule keys on whether `priority` was
        // SENT, not whether its value changed. A PATCH that re-asserts the
        // current priority still clears isOverdue and bumps the manual-change
        // timestamp — that's the user's signal that they're re-owning the
        // priority, and the next escalation cycle should re-evaluate from
        // there.
        if (priority.isPresent()) {
            if (priority.get() == null) {
                throw new BadRequestException("Priority cannot be null");
            }
            t.setPriority(priority.get());
            t.setOverdue(false);
            t.setLastManualPriorityChangeAt(Instant.now());
        }
    }

    private void applyAssigneeId(Ticket t, JsonNullable<Long> assigneeId) {
        if (assigneeId.isPresent()) {
            Long value = assigneeId.get();
            if (value != null && !userRepository.existsById(value)) {
                throw new BadRequestException("assignee " + value + " not found");
            }
            t.setAssigneeId(value);
        }
    }

    private void applyDueDate(Ticket t, JsonNullable<Instant> dueDate) {
        if (dueDate.isPresent()) {
            t.setDueDate(dueDate.get());
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

        auditService.recordUserAction(AuditAction.CREATE, EntityType.TICKET, saved.getId());
        if (req.assigneeId() == null) {
            autoAssign(saved);
        }
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public TicketResponse getById(Long id) {
        return ticketRepository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new NotFoundException(
                        "ticket " + id + " not found"
                ));
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
            throw new ConflictException("ticket " + id + " is DONE and cannot be modified");
        }

        applyStatus(t,      orUndefined(req.status()));
        applyTitle(t,       orUndefined(req.title()));
        applyDescription(t, orUndefined(req.description()));
        applyPriority(t,    orUndefined(req.priority()));
        applyAssigneeId(t,  orUndefined(req.assigneeId()));
        applyDueDate(t,     orUndefined(req.dueDate()));

        auditService.recordUserAction(AuditAction.UPDATE, EntityType.TICKET, id);
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
        auditService.recordUserAction(AuditAction.DELETE, EntityType.TICKET, id);
    }

    /**
     * Phase 8: ADMIN-only listing of soft-deleted tickets within a project.
     * Mirrors {@link #listByProject} in returning {@code []} for an unknown
     * project (no pre-check).
     */
    @Transactional(readOnly = true)
    public List<TicketResponse> listDeletedByProject(Long projectId) {
        return ticketRepository.findAllDeletedByProjectId(projectId).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Phase 8: clears {@code deletedAt} and audits a {@code RESTORE} row.
     * Does NOT validate that the parent project is still live — a ticket
     * can be restored even if its project is soft-deleted (the restored
     * ticket will reference a project hidden from standard finders, but
     * the audit chain stays consistent). Trade-off documented in
     * {@code run.md} as the chosen §4d-(a) variant.
     */
    @Transactional
    public TicketResponse restore(Long id) {
        Ticket t = ticketRepository.findByIdIncludingDeleted(id)
                .orElseThrow(() -> new NotFoundException("ticket " + id + " not found"));
        if (t.getDeletedAt() == null) {
            throw new ConflictException("ticket " + id + " is not deleted");
        }
        t.setDeletedAt(null);
        // Status is unchanged on restore. A DONE ticket comes back DONE and
        // remains DONE-immutable; nothing else here needs to handle that.
        auditService.recordUserAction(AuditAction.RESTORE, EntityType.TICKET, id);
        return toResponse(t);
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

    /**
     * Defensive guard: if Jackson somehow hands us a null reference for a
     * {@code JsonNullable<T>} field (rare, but possible across different
     * deserialization paths), normalize to {@code undefined()} so the
     * apply* helpers can use plain {@code .isPresent()}.
     */
    private static <T> JsonNullable<T> orUndefined(JsonNullable<T> v) {
        return v == null ? JsonNullable.undefined() : v;
    }
}
