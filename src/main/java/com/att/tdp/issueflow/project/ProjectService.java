package com.att.tdp.issueflow.project;

import com.att.tdp.issueflow.audit.AuditAction;
import com.att.tdp.issueflow.audit.AuditService;
import com.att.tdp.issueflow.audit.EntityType;
import com.att.tdp.issueflow.common.exception.BadRequestException;
import com.att.tdp.issueflow.common.exception.NotFoundException;
import com.att.tdp.issueflow.project.dto.CreateProjectRequest;
import com.att.tdp.issueflow.project.dto.ProjectResponse;
import com.att.tdp.issueflow.project.dto.UpdateProjectRequest;
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
        // Soft delete only — still recorded as DELETE per BUILD_PLAN Phase 6
        // ("delete is a soft delete, still DELETE"). Phase 8's restore endpoint
        // will use AuditAction.RESTORE.
        p.setDeletedAt(Instant.now());
        auditService.recordUserAction(AuditAction.DELETE, EntityType.PROJECT, id);
    }

    private ProjectResponse toResponse(Project p) {
        return new ProjectResponse(p.getId(), p.getName(), p.getDescription(), p.getOwnerId());
    }
}
