package com.att.tdp.issueflow.project;

import com.att.tdp.issueflow.audit.AuditAction;
import com.att.tdp.issueflow.audit.AuditService;
import com.att.tdp.issueflow.audit.EntityType;
import com.att.tdp.issueflow.common.exception.BadRequestException;
import com.att.tdp.issueflow.common.exception.ConflictException;
import com.att.tdp.issueflow.common.exception.NotFoundException;
import com.att.tdp.issueflow.project.dto.CreateProjectRequest;
import com.att.tdp.issueflow.project.dto.ProjectResponse;
import com.att.tdp.issueflow.project.dto.UpdateProjectRequest;
import com.att.tdp.issueflow.project.dto.WorkloadResponse;
import com.att.tdp.issueflow.ticket.TicketRepository;
import com.att.tdp.issueflow.user.Role;
import com.att.tdp.issueflow.user.UserRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final TicketRepository ticketRepository;

    @Transactional
    public ProjectResponse create(CreateProjectRequest req) {
        // FK is a raw Long, not a @ManyToOne, so JPA won't enforce it at flush.
        // Validate here to avoid silently creating orphan projects.
        if (!userRepository.existsById(req.ownerId())) {
            throw new BadRequestException("owner " + req.ownerId() + " not found");
        }
        Project saved = projectRepository.save(Project.builder()
                .name(req.name())
                .description(req.description())
                .ownerId(req.ownerId())
                .build());
        auditService.recordUserAction(AuditAction.CREATE, EntityType.PROJECT, saved.getId());
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<ProjectResponse> list() {
        return projectRepository.findAll().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public ProjectResponse getById(Long id) {
        return projectRepository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new NotFoundException("project " + id + " not found"));
    }

    @Transactional(readOnly = true)
    public List<WorkloadResponse> getWorkload(Long projectId) {
        if (!projectRepository.existsById(projectId)) {
            throw new NotFoundException("project " + projectId + " not found");
        }

        return userRepository.findAllByRoleOrderByCreatedAtAsc(Role.DEVELOPER).stream()
                .map(dev -> {
                    long count = ticketRepository.countActiveTicketsForUserInProject(
                            projectId, dev.getId()
                    );
                   return new WorkloadResponse(dev.getId(), dev.getUsername(), count);
                })
                .sorted(java.util.Comparator.comparingLong(WorkloadResponse::openTicketCount))
                .toList();
    }

    @Transactional
    public ProjectResponse update(Long id, UpdateProjectRequest req) {
        Project p = projectRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("project " + id + " not found"));
        // Null means "field absent" — leave the existing value. Projects have
        // no field-present-and-null semantics, so JsonNullable is not needed.
        if (req.name() != null) {
            p.setName(req.name());
        }
        if (req.description() != null) {
            p.setDescription(req.description());
        }
        auditService.recordUserAction(AuditAction.UPDATE, EntityType.PROJECT, id);
        return toResponse(p);
    }

    @Transactional
    public void delete(Long id) {
        Project p = projectRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("project " + id + " not found"));
        // Soft delete: visibility is cleared via @SQLRestriction on the entity,
        // but the audit row is still AuditAction.DELETE (restore() is the only
        // path that records RESTORE).
        p.setDeletedAt(Instant.now());
        auditService.recordUserAction(AuditAction.DELETE, EntityType.PROJECT, id);
    }

    /**
     * ADMIN-only listing of soft-deleted projects. The repository uses a
     * native query so {@code @SQLRestriction} on the entity is bypassed.
     * Reads are never audited.
     */
    @Transactional(readOnly = true)
    public List<ProjectResponse> listDeleted() {
        return projectRepository.findAllDeleted().stream().map(this::toResponse).toList();
    }

    /**
     * Clears {@code deletedAt} and audits a {@code RESTORE} row. Loads via
     * the bypass finder since {@code findById} can no longer see soft-deleted
     * rows. Restoring a project does NOT cascade to its tickets (each ticket
     * is restored independently) — documented in {@code run.md}.
     */
    @Transactional
    public ProjectResponse restore(Long id) {
        Project p = projectRepository.findByIdIncludingDeleted(id)
                .orElseThrow(() -> new NotFoundException("project " + id + " not found"));
        if (p.getDeletedAt() == null) {
            throw new ConflictException("project " + id + " is not deleted");
        }
        p.setDeletedAt(null);
        auditService.recordUserAction(AuditAction.RESTORE, EntityType.PROJECT, id);
        return toResponse(p);
    }

    private ProjectResponse toResponse(Project p) {
        return new ProjectResponse(p.getId(), p.getName(), p.getDescription(), p.getOwnerId());
    }
}
