package com.att.tdp.issueflow.project;

import com.att.tdp.issueflow.TestSecurityConfig;
import com.att.tdp.issueflow.audit.Actor;
import com.att.tdp.issueflow.audit.AuditAction;
import com.att.tdp.issueflow.audit.AuditLog;
import com.att.tdp.issueflow.audit.AuditLogRepository;
import com.att.tdp.issueflow.audit.EntityType;
import com.att.tdp.issueflow.user.Role;
import com.att.tdp.issueflow.user.User;
import com.att.tdp.issueflow.user.UserRepository;

import jakarta.persistence.EntityManager;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 8 soft-delete behavior for projects: {@code @SQLRestriction} filters
 * deleted rows from standard finders, the ADMIN-only listing/restore
 * endpoints bypass it via native queries, and the audit chain records the
 * RESTORE verb. Auth context is set manually via {@link SecurityContextHolder}
 * (no {@code spring-security-test} dep needed — the production
 * {@link AuthenticatedUser} principal works here too).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
@Transactional
class ProjectSoftDeleteTest {

    @Autowired MockMvc mockMvc;
    @Autowired ProjectRepository projectRepository;
    @Autowired UserRepository userRepository;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired EntityManager em;

    User owner;

    @BeforeEach
    void setup() {
        auditLogRepository.deleteAll();
        projectRepository.deleteAll();
        userRepository.deleteAll();
        em.flush();

        owner = userRepository.save(User.builder()
                .username("o").email("o@e.com").fullName("o")
                .role(Role.ADMIN).passwordHash("h").build());
    }

    // Spring Security test post-processors: attaches an Authentication
    // with the given role to the request itself, so SecurityContextHolderFilter
    // doesn't clobber it. Use as `.with(asAdmin)` on the MockMvc request.
    private static final RequestPostProcessor asAdmin = user("admin").roles("ADMIN");
    private static final RequestPostProcessor asDeveloper = user("dev").roles("DEVELOPER");

    private Project saveProject(String name) {
        return projectRepository.save(Project.builder()
                .name(name).description("d").ownerId(owner.getId()).build());
    }

    private Project softDelete(Project p) {
        p.setDeletedAt(Instant.now());
        projectRepository.save(p);
        em.flush();
        em.clear();    // force re-query; bypass Hibernate L1 cache for downstream finders
        return p;
    }

    // ---------- §6a Global filtering ----------

    @Test
    void softDeletedProject_invisibleToFindAll() throws Exception {
        Project keep = saveProject("keep");
        Project drop = saveProject("drop");
        softDelete(drop);

        mockMvc.perform(get("/projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id", is(keep.getId().intValue())));
    }

    @Test
    void softDeletedProject_invisibleToGetById() throws Exception {
        Project p = saveProject("p");
        softDelete(p);

        mockMvc.perform(get("/projects/{id}", p.getId()))
                .andExpect(status().isNotFound());
    }

    @Test
    void softDeletedProject_rejectsTicketCreation() throws Exception {
        // existsById on the project goes through @SQLRestriction now, so a
        // soft-deleted project FK is rejected with 400 from TicketService.create.
        Project p = saveProject("p");
        softDelete(p);

        String body = """
                {
                  "title": "t", "description": "d",
                  "status": "TODO", "priority": "MEDIUM", "type": "BUG",
                  "projectId": %d
                }
                """.formatted(p.getId());

        mockMvc.perform(post("/tickets")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    // ---------- §6b Deleted-listing endpoint ----------

    @Test
    void getProjectsDeleted_asAdmin_returnsSoftDeletedOnly() throws Exception {
        saveProject("keep1");
        saveProject("keep2");
        Project drop = saveProject("drop");
        softDelete(drop);

        mockMvc.perform(get("/projects/deleted").with(asAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id", is(drop.getId().intValue())));
    }

    @Test
    void getProjectsDeleted_asDeveloper_returns403() throws Exception {
        mockMvc.perform(get("/projects/deleted").with(asDeveloper))
                .andExpect(status().isForbidden());
    }

    @Test
    void getProjectsDeleted_unauthenticated_returns403() throws Exception {
        // TestSecurityConfig permits the URL; @PreAuthorize still rejects
        // anonymous principals → AccessDeniedException → 403.
        mockMvc.perform(get("/projects/deleted"))
                .andExpect(status().isForbidden());
    }

    // ---------- §6c Restore endpoint ----------

    @Test
    void restoreProject_asAdmin_clearsDeletedAt_andAudits() throws Exception {
        Project p = saveProject("p");
        softDelete(p);

        mockMvc.perform(post("/projects/{id}/restore", p.getId()).with(asAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(p.getId().intValue())));

        // Standard finder sees it again.
        Project reloaded = projectRepository.findById(p.getId()).orElseThrow();
        assertNull(reloaded.getDeletedAt());

        // Audit row recorded.
        List<AuditLog> restoreRows = auditLogRepository.findAll().stream()
                .filter(r -> r.getAction() == AuditAction.RESTORE)
                .filter(r -> r.getEntityType() == EntityType.PROJECT)
                .filter(r -> r.getEntityId().equals(p.getId()))
                .toList();
        assertTrue(restoreRows.size() >= 1, "expected one RESTORE audit row");
        // actor is USER even though performedBy may be null in this test path
        // (production filter would set it from the JWT uid claim).
        assertNotNull(restoreRows.get(0).getActor());
    }

    @Test
    void restoreProject_alreadyLive_returns409() throws Exception {
        Project p = saveProject("p");

        mockMvc.perform(post("/projects/{id}/restore", p.getId()).with(asAdmin))
                .andExpect(status().isConflict());
    }

    @Test
    void restoreProject_unknownId_returns404() throws Exception {
        mockMvc.perform(post("/projects/{id}/restore", 9999L).with(asAdmin))
                .andExpect(status().isNotFound());
    }

    @Test
    void restoreProject_asDeveloper_returns403() throws Exception {
        Project p = saveProject("p");
        softDelete(p);

        mockMvc.perform(post("/projects/{id}/restore", p.getId()).with(asDeveloper))
                .andExpect(status().isForbidden());
    }

    // ---------- §6e Audit vocabulary chain ----------

    @Test
    void auditLogContainsBothDeleteAndRestore() throws Exception {
        Project p = saveProject("p");
        em.flush();

        // DELETE through the controller so the audit row uses the normal verb.
        mockMvc.perform(delete("/projects/{id}", p.getId()))
                .andExpect(status().isOk());

        mockMvc.perform(post("/projects/{id}/restore", p.getId()).with(asAdmin))
                .andExpect(status().isOk());
        em.flush();

        List<AuditLog> rows = auditLogRepository.findAll().stream()
                .filter(r -> r.getEntityType() == EntityType.PROJECT)
                .filter(r -> r.getEntityId().equals(p.getId()))
                .toList();
        // CREATE (from saveProject's create path? no — saveProject went
        // through the raw repository, not the service. So the audit chain
        // starts at DELETE.) Then RESTORE.
        long deletes = rows.stream().filter(r -> r.getAction() == AuditAction.DELETE).count();
        long restores = rows.stream().filter(r -> r.getAction() == AuditAction.RESTORE).count();
        assertTrue(deletes >= 1, "expected at least one DELETE audit row");
        assertTrue(restores >= 1, "expected at least one RESTORE audit row");
        // SYSTEM actor never appears here — only USER.
        assertTrue(rows.stream().allMatch(r -> r.getActor() == Actor.USER));
    }
}
