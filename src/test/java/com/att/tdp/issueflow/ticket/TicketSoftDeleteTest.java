package com.att.tdp.issueflow.ticket;

import com.att.tdp.issueflow.TestSecurityConfig;
import com.att.tdp.issueflow.audit.AuditAction;
import com.att.tdp.issueflow.audit.AuditLog;
import com.att.tdp.issueflow.audit.AuditLogRepository;
import com.att.tdp.issueflow.audit.EntityType;
import com.att.tdp.issueflow.project.Project;
import com.att.tdp.issueflow.project.ProjectRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 8 soft-delete behavior for tickets. Mirrors {@code ProjectSoftDeleteTest}
 * with ticket-specific concerns (per-project listing, restore-of-DONE-stays-DONE,
 * restore-orphan-ticket-when-project-is-deleted permitted per §4d-a).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
@Transactional
class TicketSoftDeleteTest {

    @Autowired MockMvc mockMvc;
    @Autowired TicketRepository ticketRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired UserRepository userRepository;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired EntityManager em;

    User owner;
    Project projectA;
    Project projectB;

    @BeforeEach
    void setup() {
        auditLogRepository.deleteAll();
        ticketRepository.deleteAll();
        projectRepository.deleteAll();
        userRepository.deleteAll();
        em.flush();

        owner = userRepository.save(User.builder()
                .username("o").email("o@e.com").fullName("o")
                .role(Role.ADMIN).passwordHash("h").build());
        projectA = projectRepository.save(Project.builder()
                .name("A").description("d").ownerId(owner.getId()).build());
        projectB = projectRepository.save(Project.builder()
                .name("B").description("d").ownerId(owner.getId()).build());
    }

    private static final RequestPostProcessor asAdmin = user("admin").roles("ADMIN");
    private static final RequestPostProcessor asDeveloper = user("dev").roles("DEVELOPER");

    private Ticket saveTicket(Project project, String title, TicketStatus status) {
        return ticketRepository.save(Ticket.builder()
                .title(title).description("d")
                .status(status).priority(Priority.MEDIUM).type(TicketType.BUG)
                .projectId(project.getId()).isOverdue(false).build());
    }

    private void softDelete(Ticket t) {
        t.setDeletedAt(Instant.now());
        ticketRepository.save(t);
        em.flush();
        em.clear();
    }

    // ---------- §6a Global filtering ----------

    @Test
    void softDeletedTicket_invisibleToListByProject() throws Exception {
        saveTicket(projectA, "a", TicketStatus.TODO);
        saveTicket(projectA, "b", TicketStatus.TODO);
        Ticket dead = saveTicket(projectA, "dead", TicketStatus.TODO);
        softDelete(dead);

        mockMvc.perform(get("/tickets").param("projectId", projectA.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void softDeletedTicket_invisibleToGetById() throws Exception {
        Ticket t = saveTicket(projectA, "t", TicketStatus.TODO);
        softDelete(t);

        mockMvc.perform(get("/tickets/{id}", t.getId()))
                .andExpect(status().isNotFound());
    }

    // ---------- §6b Deleted-listing ----------

    @Test
    void getTicketsDeleted_requiresProjectId() throws Exception {
        // Missing projectId → 400 from Spring's @RequestParam binder.
        mockMvc.perform(get("/tickets/deleted").with(asAdmin))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getTicketsDeleted_asAdmin_filtersByProject() throws Exception {
        Ticket deadA = saveTicket(projectA, "deadA", TicketStatus.TODO);
        Ticket deadB = saveTicket(projectB, "deadB", TicketStatus.TODO);
        softDelete(deadA);
        softDelete(deadB);

        mockMvc.perform(get("/tickets/deleted")
                        .param("projectId", projectA.getId().toString())
                        .with(asAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id", is(deadA.getId().intValue())));
    }

    @Test
    void getTicketsDeleted_asDeveloper_returns403() throws Exception {
        mockMvc.perform(get("/tickets/deleted")
                        .param("projectId", projectA.getId().toString())
                        .with(asDeveloper))
                .andExpect(status().isForbidden());
    }

    // ---------- §6c Restore endpoint ----------

    @Test
    void restoreTicket_asAdmin_clearsDeletedAt_andAudits() throws Exception {
        Ticket t = saveTicket(projectA, "t", TicketStatus.IN_PROGRESS);
        softDelete(t);

        mockMvc.perform(post("/tickets/{id}/restore", t.getId()).with(asAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(t.getId().intValue())));

        Ticket reloaded = ticketRepository.findById(t.getId()).orElseThrow();
        assertNull(reloaded.getDeletedAt());

        List<AuditLog> restores = auditLogRepository.findAll().stream()
                .filter(r -> r.getAction() == AuditAction.RESTORE)
                .filter(r -> r.getEntityType() == EntityType.TICKET)
                .filter(r -> r.getEntityId().equals(t.getId()))
                .toList();
        assertTrue(restores.size() >= 1);
    }

    @Test
    void restoreTicket_doneRemainsDone_andStillImmutable() throws Exception {
        // A DONE ticket that was soft-deleted comes back as DONE. The
        // DONE-immutable rule still applies; a subsequent PATCH → 409.
        Ticket t = saveTicket(projectA, "done", TicketStatus.DONE);
        softDelete(t);

        mockMvc.perform(post("/tickets/{id}/restore", t.getId()).with(asAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("DONE")));

        // Verify DONE-immutable still fires.
        mockMvc.perform(patch("/tickets/{id}", t.getId())
                        .contentType("application/json")
                        .content("{\"title\":\"new\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void restoreTicket_alreadyLive_returns409() throws Exception {
        Ticket t = saveTicket(projectA, "t", TicketStatus.TODO);

        mockMvc.perform(post("/tickets/{id}/restore", t.getId()).with(asAdmin))
                .andExpect(status().isConflict());
    }

    @Test
    void restoreTicket_unknownId_returns404() throws Exception {
        mockMvc.perform(post("/tickets/{id}/restore", 9999L).with(asAdmin))
                .andExpect(status().isNotFound());
    }

    @Test
    void restoreTicket_asDeveloper_returns403() throws Exception {
        Ticket t = saveTicket(projectA, "t", TicketStatus.TODO);
        softDelete(t);

        mockMvc.perform(post("/tickets/{id}/restore", t.getId()).with(asDeveloper))
                .andExpect(status().isForbidden());
    }

    @Test
    void restoreTicket_whenProjectIsSoftDeleted_stillSucceeds() throws Exception {
        // §4d-(a) decision: ticket restore is independent of project state.
        // Restoring an "orphan" ticket whose project is soft-deleted succeeds;
        // the ticket reappears with a dangling projectId. Locks in the choice.
        Ticket t = saveTicket(projectA, "t", TicketStatus.TODO);
        softDelete(t);

        projectA.setDeletedAt(Instant.now());
        projectRepository.save(projectA);
        em.flush();
        em.clear();

        mockMvc.perform(post("/tickets/{id}/restore", t.getId()).with(asAdmin))
                .andExpect(status().isOk());
    }
}
