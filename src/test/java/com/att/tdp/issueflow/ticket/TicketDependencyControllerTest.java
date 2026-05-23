package com.att.tdp.issueflow.ticket;

import com.att.tdp.issueflow.TestSecurityConfig;
import com.att.tdp.issueflow.project.Project;
import com.att.tdp.issueflow.project.ProjectRepository;
import com.att.tdp.issueflow.user.Role;
import com.att.tdp.issueflow.user.User;
import com.att.tdp.issueflow.user.UserRepository;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManager;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for {@code /tickets/{ticketId}/dependencies}. Exercises
 * the full HTTP path so spec-shape concerns (URL routing, status codes,
 * JSON field names) are locked in alongside the business rules.
 * <p>
 * Cycle-detection tests are intentionally omitted — Phase 7 skipped cycle
 * detection (documented in run.md). If a future change adds it, the
 * direct/transitive cycle tests live in the checklist §7b.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
@Transactional
class TicketDependencyControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TicketDependencyRepository dependencyRepository;
    @Autowired TicketRepository ticketRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired UserRepository userRepository;
    @Autowired EntityManager em;

    User testUser;
    Project projectA;
    Project projectB;

    @BeforeEach
    void setup() {
        dependencyRepository.deleteAll();
        ticketRepository.deleteAll();
        projectRepository.deleteAll();
        userRepository.deleteAll();
        em.flush();

        testUser = userRepository.save(User.builder()
                .username("u").email("u@e.com").fullName("u")
                .role(Role.DEVELOPER).passwordHash("h").build());
        projectA = projectRepository.save(Project.builder()
                .name("A").description("d").ownerId(testUser.getId()).build());
        projectB = projectRepository.save(Project.builder()
                .name("B").description("d").ownerId(testUser.getId()).build());
    }

    private Ticket newTicket(Project project, String title, TicketStatus status) {
        return ticketRepository.save(Ticket.builder()
                .title(title).description("d")
                .status(status).priority(Priority.MEDIUM).type(TicketType.BUG)
                .projectId(project.getId()).isOverdue(false).build());
    }

    private String addBody(Long blockerId) {
        return "{\"blockedBy\":" + blockerId + "}";
    }

    // ---------- §7a CRUD smoke ----------

    @Test
    void add_returns200_andPersists() throws Exception {
        Ticket dep = newTicket(projectA, "dep", TicketStatus.TODO);
        Ticket blocker = newTicket(projectA, "blocker", TicketStatus.TODO);

        mockMvc.perform(post("/tickets/{id}/dependencies", dep.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addBody(blocker.getId())))
                .andExpect(status().isOk());

        assertTrue(dependencyRepository
                .existsByTicketIdAndBlockedById(dep.getId(), blocker.getId()));
    }

    @Test
    void list_returnsBlockerShape() throws Exception {
        Ticket dep = newTicket(projectA, "dep", TicketStatus.TODO);
        Ticket blocker = newTicket(projectA, "blocker title", TicketStatus.IN_PROGRESS);
        dependencyRepository.save(TicketDependency.builder()
                .ticketId(dep.getId()).blockedById(blocker.getId()).build());

        mockMvc.perform(get("/tickets/{id}/dependencies", dep.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                // Response carries blocker's id/title/status — NOT the join-row id
                // and NOT the dependent ticket's id.
                .andExpect(jsonPath("$[0].id", is(blocker.getId().intValue())))
                .andExpect(jsonPath("$[0].title", is("blocker title")))
                .andExpect(jsonPath("$[0].status", is("IN_PROGRESS")))
                .andExpect(jsonPath("$[0].ticketId").doesNotExist())
                .andExpect(jsonPath("$[0].blockedById").doesNotExist());
    }

    @Test
    void list_emptyForUnknownTicket() throws Exception {
        mockMvc.perform(get("/tickets/{id}/dependencies", 9999L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", empty()));
    }

    @Test
    void list_emptyWhenNoDependencies() throws Exception {
        Ticket dep = newTicket(projectA, "dep", TicketStatus.TODO);

        mockMvc.perform(get("/tickets/{id}/dependencies", dep.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", empty()));
    }

    @Test
    void remove_returns200_andDeletes() throws Exception {
        Ticket dep = newTicket(projectA, "dep", TicketStatus.TODO);
        Ticket blocker = newTicket(projectA, "blocker", TicketStatus.TODO);
        dependencyRepository.save(TicketDependency.builder()
                .ticketId(dep.getId()).blockedById(blocker.getId()).build());

        mockMvc.perform(delete("/tickets/{id}/dependencies/{blockerId}",
                        dep.getId(), blocker.getId()))
                .andExpect(status().isOk());

        assertEquals(0, dependencyRepository.findAllByTicketId(dep.getId()).size());
    }

    // ---------- §7b Validation surface ----------

    @Test
    void add_selfDependency_returns400() throws Exception {
        Ticket t = newTicket(projectA, "t", TicketStatus.TODO);

        mockMvc.perform(post("/tickets/{id}/dependencies", t.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addBody(t.getId())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void add_missingBlockedBy_returns400() throws Exception {
        Ticket dep = newTicket(projectA, "dep", TicketStatus.TODO);

        mockMvc.perform(post("/tickets/{id}/dependencies", dep.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void add_unknownDependentTicket_returns404() throws Exception {
        Ticket blocker = newTicket(projectA, "blocker", TicketStatus.TODO);

        // Dependent (URL) is missing → 404 (URL identifies the parent resource)
        mockMvc.perform(post("/tickets/{id}/dependencies", 9999L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addBody(blocker.getId())))
                .andExpect(status().isNotFound());
    }

    @Test
    void add_unknownBlocker_returns400() throws Exception {
        Ticket dep = newTicket(projectA, "dep", TicketStatus.TODO);

        // Blocker (body) is missing → 400 (body-side FK; mirrors assigneeId convention)
        mockMvc.perform(post("/tickets/{id}/dependencies", dep.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addBody(9999L)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void add_crossProject_returns400() throws Exception {
        Ticket dep = newTicket(projectA, "dep", TicketStatus.TODO);
        Ticket blockerInB = newTicket(projectB, "blocker", TicketStatus.TODO);

        mockMvc.perform(post("/tickets/{id}/dependencies", dep.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addBody(blockerInB.getId())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void add_duplicate_returns409() throws Exception {
        Ticket dep = newTicket(projectA, "dep", TicketStatus.TODO);
        Ticket blocker = newTicket(projectA, "blocker", TicketStatus.TODO);

        // First add succeeds…
        mockMvc.perform(post("/tickets/{id}/dependencies", dep.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addBody(blocker.getId())))
                .andExpect(status().isOk());

        // …second add of the same pair is a conflict.
        mockMvc.perform(post("/tickets/{id}/dependencies", dep.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addBody(blocker.getId())))
                .andExpect(status().isConflict());
    }

    @Test
    void remove_unknownDependency_returns404() throws Exception {
        Ticket dep = newTicket(projectA, "dep", TicketStatus.TODO);

        // Never linked → 404 (not silent 200; we don't promise idempotent DELETE).
        mockMvc.perform(delete("/tickets/{id}/dependencies/{blockerId}",
                        dep.getId(), 9999L))
                .andExpect(status().isNotFound());
    }

    // ---------- §7c DONE-transition guard (the whole point of Phase 7) ----------

    @Test
    void transitionToDone_blocked_returns409() throws Exception {
        Ticket blocker = newTicket(projectA, "A", TicketStatus.IN_PROGRESS);
        Ticket dep = newTicket(projectA, "B", TicketStatus.IN_REVIEW);
        dependencyRepository.save(TicketDependency.builder()
                .ticketId(dep.getId()).blockedById(blocker.getId()).build());

        mockMvc.perform(patch("/tickets/{id}", dep.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DONE\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void transitionToDone_allBlockersDone_succeeds() throws Exception {
        Ticket blocker = newTicket(projectA, "A", TicketStatus.DONE);
        Ticket dep = newTicket(projectA, "B", TicketStatus.IN_REVIEW);
        dependencyRepository.save(TicketDependency.builder()
                .ticketId(dep.getId()).blockedById(blocker.getId()).build());

        mockMvc.perform(patch("/tickets/{id}", dep.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DONE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("DONE")));
    }

    @Test
    void transitionToInReview_withUnresolvedBlocker_succeeds() throws Exception {
        // The DONE-block fires only when next == DONE. Intermediate transitions
        // ignore blocker state — verifies the blockerStatuses() helper's branch.
        Ticket blocker = newTicket(projectA, "A", TicketStatus.IN_PROGRESS);
        Ticket dep = newTicket(projectA, "B", TicketStatus.IN_PROGRESS);
        dependencyRepository.save(TicketDependency.builder()
                .ticketId(dep.getId()).blockedById(blocker.getId()).build());

        mockMvc.perform(patch("/tickets/{id}", dep.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"IN_REVIEW\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("IN_REVIEW")));
    }

    @Test
    void transitionToDone_noBlockers_succeeds() throws Exception {
        // Sanity: empty blocker list must be treated as "no constraint",
        // not as "forbidden". The validator's Rule 4 uses allMatch which
        // returns true for an empty stream — locked in here.
        Ticket t = newTicket(projectA, "t", TicketStatus.IN_REVIEW);

        mockMvc.perform(patch("/tickets/{id}", t.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DONE\"}"))
                .andExpect(status().isOk());
    }

    // ---------- §7e Cross-resource integrity (Phase 8 tripwire) ----------

    @Test
    void list_softDeletedDependent_stillReturnsBlocker_inPhase7() throws Exception {
        // Phase 8 will add @SQLRestriction("deleted_at IS NULL") so the
        // standard finder hides soft-deleted blockers. Until then, deleted
        // rows remain visible. When Phase 8 lands, flip this assertion:
        // the blocker should disappear from the list.
        // TODO Phase 8: flip — expect empty list once @SQLRestriction is on Ticket.
        Ticket dep = newTicket(projectA, "dep", TicketStatus.TODO);
        Ticket blocker = newTicket(projectA, "blocker", TicketStatus.TODO);
        dependencyRepository.save(TicketDependency.builder()
                .ticketId(dep.getId()).blockedById(blocker.getId()).build());

        // Soft-delete the blocker.
        blocker.setDeletedAt(Instant.now());
        ticketRepository.save(blocker);
        em.flush();

        mockMvc.perform(get("/tickets/{id}/dependencies", dep.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }
}
