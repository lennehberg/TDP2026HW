package com.att.tdp.issueflow.project;

import com.att.tdp.issueflow.TestSecurityConfig;
import com.att.tdp.issueflow.ticket.Priority;
import com.att.tdp.issueflow.ticket.Ticket;
import com.att.tdp.issueflow.ticket.TicketRepository;
import com.att.tdp.issueflow.ticket.TicketStatus;
import com.att.tdp.issueflow.ticket.TicketType;
import com.att.tdp.issueflow.user.Role;
import com.att.tdp.issueflow.user.User;
import com.att.tdp.issueflow.user.UserRepository;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 12 — {@code GET /projects/:projectId/workload}.
 * <p>
 * Pins the README contract field names ({@code userId}, {@code username},
 * {@code openTicketCount}) and the sort order ("ascending"). Soft-deleted
 * tickets and DONE tickets must not be counted; cross-project tickets must
 * not bleed in.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
@Transactional
class WorkloadControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired TicketRepository ticketRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired UserRepository userRepository;
    @Autowired EntityManager em;

    User devA;
    User devB;
    User devC;
    User adminUser;
    Project project;
    Project otherProject;

    @BeforeEach
    void setup() {
        ticketRepository.deleteAll();
        projectRepository.deleteAll();
        userRepository.deleteAll();
        em.flush();

        // Three developers; created in this order so devA is the oldest.
        devA = userRepository.save(User.builder()
                .username("wlDevA").email("a@e.com").fullName("A")
                .role(Role.DEVELOPER).passwordHash("h").build());
        devB = userRepository.save(User.builder()
                .username("wlDevB").email("b@e.com").fullName("B")
                .role(Role.DEVELOPER).passwordHash("h").build());
        devC = userRepository.save(User.builder()
                .username("wlDevC").email("c@e.com").fullName("C")
                .role(Role.DEVELOPER).passwordHash("h").build());
        adminUser = userRepository.save(User.builder()
                .username("wlAdmin").email("ad@e.com").fullName("Admin")
                .role(Role.ADMIN).passwordHash("h").build());

        project = projectRepository.save(Project.builder()
                .name("p").description("d").ownerId(adminUser.getId()).build());
        otherProject = projectRepository.save(Project.builder()
                .name("o").description("d").ownerId(adminUser.getId()).build());
    }

    private Ticket save(Project p, User assignee, TicketStatus status) {
        return ticketRepository.save(Ticket.builder()
                .title("t").description("d")
                .status(status).priority(Priority.MEDIUM).type(TicketType.BUG)
                .projectId(p.getId())
                .assigneeId(assignee == null ? null : assignee.getId())
                .isOverdue(false).build());
    }

    // ---------- Contract shape ----------

    @Test
    void workload_responseUsesReadmeFieldNames() throws Exception {
        // README key is `openTicketCount`, NOT `activeTickets` — Fix 1 renamed
        // the DTO field. A jsonPath check is the canonical regression guard.
        save(project, devA, TicketStatus.TODO);

        mockMvc.perform(get("/projects/{id}/workload", project.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userId").exists())
                .andExpect(jsonPath("$[0].username").exists())
                .andExpect(jsonPath("$[0].openTicketCount").exists())
                .andExpect(jsonPath("$[0].activeTickets").doesNotExist());
    }

    @Test
    void workload_includesAllDevelopers_evenWithZeroTickets() throws Exception {
        // No tickets anywhere — every DEVELOPER still appears with count 0.
        // ADMIN users are excluded.
        mockMvc.perform(get("/projects/{id}/workload", project.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)));
    }

    @Test
    void workload_excludesAdminUsers() throws Exception {
        // adminUser is a USER with role=ADMIN, not a DEVELOPER — must not
        // appear in the workload list.
        mockMvc.perform(get("/projects/{id}/workload", project.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.username == 'wlAdmin')]", hasSize(0)));
    }

    // ---------- Counting ----------

    @Test
    void workload_countsNonDoneTicketsInThisProject() throws Exception {
        save(project, devA, TicketStatus.TODO);
        save(project, devA, TicketStatus.IN_PROGRESS);
        save(project, devB, TicketStatus.TODO);

        mockMvc.perform(get("/projects/{id}/workload", project.getId()))
                .andExpect(status().isOk())
                // ascending sort: devC=0, devB=1, devA=2
                .andExpect(jsonPath("$[0].username", is("wlDevC")))
                .andExpect(jsonPath("$[0].openTicketCount", is(0)))
                .andExpect(jsonPath("$[1].username", is("wlDevB")))
                .andExpect(jsonPath("$[1].openTicketCount", is(1)))
                .andExpect(jsonPath("$[2].username", is("wlDevA")))
                .andExpect(jsonPath("$[2].openTicketCount", is(2)));
    }

    @Test
    void workload_doneTicketsAreNotCounted() throws Exception {
        // DONE tickets don't burden a developer per §3.8.
        save(project, devA, TicketStatus.TODO);
        save(project, devA, TicketStatus.DONE);
        save(project, devA, TicketStatus.DONE);

        mockMvc.perform(get("/projects/{id}/workload", project.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$[?(@.username == 'wlDevA' && @.openTicketCount == 1)]",
                        hasSize(1)));
    }

    @Test
    void workload_softDeletedTicketsAreNotCounted() throws Exception {
        // @SQLRestriction on Ticket excludes soft-deleted rows from any JPQL
        // count too — the workload must not include them.
        save(project, devA, TicketStatus.TODO);
        Ticket dead = save(project, devA, TicketStatus.TODO);
        dead.setDeletedAt(Instant.now());
        ticketRepository.save(dead);
        em.flush();
        em.clear();

        mockMvc.perform(get("/projects/{id}/workload", project.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$[?(@.username == 'wlDevA' && @.openTicketCount == 1)]",
                        hasSize(1)));
    }

    @Test
    void workload_otherProjectTicketsDoNotLeakIn() throws Exception {
        // devA has tickets in the other project — they must not affect the
        // count when querying THIS project.
        save(otherProject, devA, TicketStatus.TODO);
        save(otherProject, devA, TicketStatus.TODO);
        save(project, devA, TicketStatus.TODO);

        mockMvc.perform(get("/projects/{id}/workload", project.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$[?(@.username == 'wlDevA' && @.openTicketCount == 1)]",
                        hasSize(1)));
    }

    // ---------- Sort + error paths ----------

    @Test
    void workload_resultIsSortedAscendingByCount() throws Exception {
        // devB heavily loaded; devC moderate; devA empty. Ascending sort:
        // devA(0), devC(1), devB(3).
        save(project, devB, TicketStatus.TODO);
        save(project, devB, TicketStatus.TODO);
        save(project, devB, TicketStatus.IN_PROGRESS);
        save(project, devC, TicketStatus.TODO);

        mockMvc.perform(get("/projects/{id}/workload", project.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].openTicketCount", is(0)))
                .andExpect(jsonPath("$[1].openTicketCount", is(1)))
                .andExpect(jsonPath("$[2].openTicketCount", is(3)));
    }

    @Test
    void workload_unknownProject_returns404() throws Exception {
        mockMvc.perform(get("/projects/{id}/workload", 99999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", is("project 99999 not found")));
    }

    @Test
    void workload_softDeletedProject_returns404() throws Exception {
        // @SQLRestriction filters soft-deleted projects from existsById too,
        // so a soft-deleted project's workload is invisible.
        project.setDeletedAt(Instant.now());
        projectRepository.save(project);
        em.flush();
        em.clear();

        mockMvc.perform(get("/projects/{id}/workload", project.getId()))
                .andExpect(status().isNotFound());
    }
}
