package com.att.tdp.issueflow.project;

import com.att.tdp.issueflow.project.dto.CreateProjectRequest;
import com.att.tdp.issueflow.project.dto.ProjectResponse;
import com.att.tdp.issueflow.project.dto.UpdateProjectRequest;

import com.att.tdp.issueflow.project.dto.WorkloadResponse;
import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

    @PostMapping
    public ProjectResponse create(@Valid @RequestBody CreateProjectRequest req) {
        return projectService.create(req);
    }

    @GetMapping
    public List<ProjectResponse> list() {
        return projectService.list();
    }

    // Phase 8 — declared before /{projectId} for readability; Spring's
    // RequestMappingHandlerMapping prefers more specific paths but visual
    // ordering makes the intent obvious.
    @GetMapping("/deleted")
    @PreAuthorize("hasRole('ADMIN')")
    public List<ProjectResponse> listDeleted() {
        return projectService.listDeleted();
    }

    @GetMapping("/{projectId}")
    public ProjectResponse getById(@PathVariable Long projectId) {
        return projectService.getById(projectId);
    }

    @PostMapping("/{projectId}/restore")
    @PreAuthorize("hasRole('ADMIN')")
    public ProjectResponse restore(@PathVariable Long projectId) {
        return projectService.restore(projectId);
    }

    @PatchMapping("/{projectId}")
    public ProjectResponse update(@PathVariable Long projectId,
                                  @Valid @RequestBody UpdateProjectRequest req) {
        return projectService.update(projectId, req);
    }

    @GetMapping("/{id}/workload")
    public List<WorkloadResponse> getWorkload(@PathVariable Long id) {
        return projectService.getWorkload(id);
    }

    @DeleteMapping("/{projectId}")
    public void delete(@PathVariable Long projectId) {
        projectService.delete(projectId);
    }
}
