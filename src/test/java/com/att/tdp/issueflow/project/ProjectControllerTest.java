package com.att.tdp.issueflow.project;

import com.att.tdp.issueflow.TestSecurityConfig;
import com.att.tdp.issueflow.audit.AuditAction;
import com.att.tdp.issueflow.audit.AuditLogRepository;
import com.att.tdp.issueflow.audit.EntityType;
import com.att.tdp.issueflow.user.Role;
import com.att.tdp.issueflow.user.User;
import com.att.tdp.issueflow.user.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 3 — project CRUD via the REST surface.
 * <p>
 * Pins the response shape (no createdAt/updatedAt timestamps per the README
 * contract), the validation surface (NotBlank + Size limits on
 * {@link com.att.tdp.issueflow.project.dto.CreateProjectRequest}), and the
 * "body reference → 400" convention from §4a (unknown ownerId is a body
 * reference, so 400 — not 404, which is reserved for URL parents).
 * Soft-delete and ADMIN-only listing/restore are covered separately in
 * {@link ProjectSoftDeleteTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
@Transactional
class ProjectControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
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

        owner = userRepository.save(User.builder()
                .username("o").email("o@e.com").fullName("o")
                .role(Role.DEVELOPER).passwordHash("h").build());
    }

    private String createBody() {
        return """
                {
                  "name": "Apollo",
                  "description": "Reentry vehicle",
                  "ownerId": %d
                }
                """.formatted(owner.getId());
    }

    // ---------- Create ----------

    @Test
    void create_returns200_andResponseShape() throws Exception {
        // README: response is {id, name, description, ownerId} — no timestamps.
        mockMvc.perform(post("/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.name", is("Apollo")))
                .andExpect(jsonPath("$.description", is("Reentry vehicle")))
                .andExpect(jsonPath("$.ownerId", is(owner.getId().intValue())))
                .andExpect(jsonPath("$.createdAt").doesNotExist())
                .andExpect(jsonPath("$.updatedAt").doesNotExist())
                .andExpect(jsonPath("$.deletedAt").doesNotExist());
    }

    @Test
    void create_writesAuditRow() throws Exception {
        mockMvc.perform(post("/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isOk());

        long creates = auditLogRepository.findAll().stream()
                .filter(r -> r.getEntityType() == EntityType.PROJECT)
                .filter(r -> r.getAction() == AuditAction.CREATE)
                .count();
        assertEquals(1, creates, "expected one PROJECT/CREATE audit row");
    }

    @Test
    void create_unknownOwner_returns400() throws Exception {
        // §4a — body references resolve to 400, not 404 (URL parent → 404).
        String body = """
                {
                  "name": "Apollo",
                  "description": "...",
                  "ownerId": 9999
                }
                """;
        mockMvc.perform(post("/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_missingName_returns400() throws Exception {
        String body = """
                {
                  "description": "...",
                  "ownerId": %d
                }
                """.formatted(owner.getId());
        mockMvc.perform(post("/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name", notNullValue()));
    }

    @Test
    void create_blankName_returns400() throws Exception {
        String body = """
                {
                  "name": "",
                  "description": "d",
                  "ownerId": %d
                }
                """.formatted(owner.getId());
        mockMvc.perform(post("/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name", notNullValue()));
    }

    @Test
    void create_missingDescription_returns400() throws Exception {
        String body = """
                {
                  "name": "Apollo",
                  "ownerId": %d
                }
                """.formatted(owner.getId());
        mockMvc.perform(post("/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.description", notNullValue()));
    }

    @Test
    void create_missingOwnerId_returns400() throws Exception {
        String body = """
                {
                  "name": "Apollo",
                  "description": "d"
                }
                """;
        mockMvc.perform(post("/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.ownerId", notNullValue()));
    }

    @Test
    void create_nameTooLong_returns400() throws Exception {
        // Size(max=64) on name
        String oversize = "n".repeat(65);
        String body = """
                {
                  "name": "%s",
                  "description": "d",
                  "ownerId": %d
                }
                """.formatted(oversize, owner.getId());
        mockMvc.perform(post("/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name", notNullValue()));
    }

    // ---------- List / get ----------

    @Test
    void list_returnsAllNonDeletedProjects() throws Exception {
        projectRepository.save(Project.builder()
                .name("A").description("d").ownerId(owner.getId()).build());
        projectRepository.save(Project.builder()
                .name("B").description("d").ownerId(owner.getId()).build());

        mockMvc.perform(get("/projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void getById_returnsProject() throws Exception {
        Project p = projectRepository.save(Project.builder()
                .name("Apollo").description("d").ownerId(owner.getId()).build());

        mockMvc.perform(get("/projects/{id}", p.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(p.getId().intValue())))
                .andExpect(jsonPath("$.name", is("Apollo")));
    }

    @Test
    void getById_unknownId_returns404() throws Exception {
        mockMvc.perform(get("/projects/{id}", 9999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", is("project 9999 not found")));
    }

    // ---------- Patch ----------

    @Test
    void update_partialName_preservesDescription() throws Exception {
        // PATCH on projects uses simple "null = absent" semantics (UpdateProjectRequest
        // has no JsonNullable fields per §3 design — only Ticket needs that distinction).
        MvcResult res = mockMvc.perform(post("/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isOk())
                .andReturn();
        Long id = objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(patch("/projects/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "Apollo II" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("Apollo II")))
                .andExpect(jsonPath("$.description", is("Reentry vehicle")));
    }

    @Test
    void update_emptyBody_leavesProjectUnchanged() throws Exception {
        MvcResult res = mockMvc.perform(post("/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isOk())
                .andReturn();
        Long id = objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(patch("/projects/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("Apollo")))
                .andExpect(jsonPath("$.description", is("Reentry vehicle")));
    }

    @Test
    void update_unknownId_returns404() throws Exception {
        mockMvc.perform(patch("/projects/{id}", 9999L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "Phoenix" }
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void update_writesAuditRow() throws Exception {
        Project p = projectRepository.save(Project.builder()
                .name("A").description("d").ownerId(owner.getId()).build());

        mockMvc.perform(patch("/projects/{id}", p.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "B" }
                                """))
                .andExpect(status().isOk());

        long updates = auditLogRepository.findAll().stream()
                .filter(r -> r.getEntityType() == EntityType.PROJECT)
                .filter(r -> r.getAction() == AuditAction.UPDATE)
                .filter(r -> r.getEntityId().equals(p.getId()))
                .count();
        assertEquals(1, updates, "expected one PROJECT/UPDATE audit row");
    }

    // ---------- Delete ----------

    @Test
    void delete_returns200_andSoftDeletesProject() throws Exception {
        // Soft delete behavior in depth is covered by ProjectSoftDeleteTest;
        // here we only assert the controller path returns 200 and the row
        // disappears from the standard finder. We clear the L1 cache between
        // the two MockMvc calls so @SQLRestriction is actually consulted — in
        // a real HTTP server each request is its own JPA session.
        Project p = projectRepository.save(Project.builder()
                .name("A").description("d").ownerId(owner.getId()).build());

        mockMvc.perform(delete("/projects/{id}", p.getId()))
                .andExpect(status().isOk());
        em.flush();
        em.clear();

        mockMvc.perform(get("/projects/{id}", p.getId()))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_unknownId_returns404() throws Exception {
        mockMvc.perform(delete("/projects/{id}", 9999L))
                .andExpect(status().isNotFound());
    }
}
