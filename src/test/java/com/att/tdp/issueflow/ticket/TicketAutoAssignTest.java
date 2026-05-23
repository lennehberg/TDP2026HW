package com.att.tdp.issueflow.ticket;

import com.att.tdp.issueflow.audit.Actor;
import com.att.tdp.issueflow.audit.AuditAction;
import com.att.tdp.issueflow.audit.AuditLog;
import com.att.tdp.issueflow.audit.AuditLogRepository;
import com.att.tdp.issueflow.audit.EntityType;
import com.att.tdp.issueflow.project.Project;
import com.att.tdp.issueflow.project.ProjectRepository;
import com.att.tdp.issueflow.ticket.dto.CreateTicketRequest;
import com.att.tdp.issueflow.ticket.dto.TicketResponse;
import com.att.tdp.issueflow.ticket.dto.UpdateTicketRequest;
import com.att.tdp.issueflow.user.Role;
import com.att.tdp.issueflow.user.User;
import com.att.tdp.issueflow.user.UserRepository;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openapitools.jackson.nullable.JsonNullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 12 — auto-assignment behavior on ticket create.
 * <p>
 * Rules being pinned:
 * <ul>
 *   <li>Fires only when {@code assigneeId == null} on create. Never on update.
 *       (BUILD_PLAN cross-cutting reminder: "Auto-assign fires on create only,
 *       never on update.")</li>
 *   <li>Picks the DEVELOPER with the fewest non-DONE tickets in the project.</li>
 *   <li>Tie-break: oldest registrant ({@code createdAt} ASC).</li>
 *   <li>Audit row: {@code action=AUTO_ASSIGN, entityType=TICKET, actor=SYSTEM,
 *       performedBy=null}. No sentinel "system user" id (CLAUDE.md invariant).</li>
 *   <li>No developers → ticket saved with {@code assigneeId=null}, no error,
 *       no AUTO_ASSIGN audit row.</li>
 * </ul>
 */
@SpringBootTest
@Transactional
class TicketAutoAssignTest {

    @Autowired TicketService ticketService;
    @Autowired TicketRepository ticketRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired UserRepository userRepository;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired EntityManager em;

    User devA;
    User devB;
    User devC;
    User admin;
    Project project;

    @BeforeEach
    void setup() {
        auditLogRepository.deleteAll();
        ticketRepository.deleteAll();
        projectRepository.deleteAll();
        userRepository.deleteAll();
        em.flush();

        // devA created first → tie-break winner when counts are equal.
        devA = userRepository.save(User.builder()
                .username("aaDevA").email("a@e.com").fullName("A")
                .role(Role.DEVELOPER).passwordHash("h").build());
        devB = userRepository.save(User.builder()
                .username("aaDevB").email("b@e.com").fullName("B")
                .role(Role.DEVELOPER).passwordHash("h").build());
        devC = userRepository.save(User.builder()
                .username("aaDevC").email("c@e.com").fullName("C")
                .role(Role.DEVELOPER).passwordHash("h").build());
        admin = userRepository.save(User.builder()
                .username("aaAdmin").email("ad@e.com").fullName("Admin")
                .role(Role.ADMIN).passwordHash("h").build());

        project = projectRepository.save(Project.builder()
                .name("p").description("d").ownerId(admin.getId()).build());
    }

    private CreateTicketRequest req(Long assigneeId) {
        return new CreateTicketRequest(
                "t", "d", TicketStatus.TODO, Priority.MEDIUM, TicketType.BUG,
                project.getId(), assigneeId, null);
    }

    private Ticket preload(User assignee, TicketStatus status) {
        return ticketRepository.save(Ticket.builder()
                .title("pre").description("d")
                .status(status).priority(Priority.MEDIUM).type(TicketType.BUG)
                .projectId(project.getId())
                .assigneeId(assignee == null ? null : assignee.getId())
                .isOverdue(false).build());
    }

    // ---------- Tie-break ----------

    @Test
    void autoAssign_noPriorTickets_picksOldestDeveloper() throws Exception {
        // Empty project, all developers tied at 0 — oldest registrant (devA)
        // wins per §3.8.
        TicketResponse res = ticketService.create(req(null));

        assertEquals(devA.getId(), res.assigneeId(),
                "oldest DEVELOPER should win the tie-break");
    }

    @Test
    void autoAssign_picksDeveloperWithLowestWorkload() throws Exception {
        // devA loaded; devB empty. devB wins regardless of registration order.
        preload(devA, TicketStatus.TODO);
        preload(devA, TicketStatus.IN_PROGRESS);

        TicketResponse res = ticketService.create(req(null));

        assertEquals(devB.getId(), res.assigneeId());
    }

    @Test
    void autoAssign_tieBreak_oldestAmongTied() throws Exception {
        // devA loaded once; devB and devC tied at 0. devB (older than devC)
        // wins the tie.
        preload(devA, TicketStatus.TODO);

        TicketResponse res = ticketService.create(req(null));

        assertEquals(devB.getId(), res.assigneeId(),
                "between equal-workload devs, oldest registrant wins");
    }

    @Test
    void autoAssign_doneTicketsDoNotBurden() throws Exception {
        // DONE tickets don't count toward workload — devA stays the winner
        // even with five DONE tickets piled up.
        for (int i = 0; i < 5; i++) {
            preload(devA, TicketStatus.DONE);
        }

        TicketResponse res = ticketService.create(req(null));

        assertEquals(devA.getId(), res.assigneeId());
    }

    // ---------- Audit ----------

    @Test
    void autoAssign_emitsAuditRow_actorSystem_performedByNull() throws Exception {
        TicketResponse res = ticketService.create(req(null));

        List<AuditLog> autoRows = auditLogRepository.findAll().stream()
                .filter(r -> r.getAction() == AuditAction.AUTO_ASSIGN)
                .filter(r -> r.getEntityType() == EntityType.TICKET)
                .filter(r -> r.getEntityId().equals(res.id()))
                .toList();
        assertEquals(1, autoRows.size(), "expected one AUTO_ASSIGN row");

        AuditLog row = autoRows.get(0);
        assertEquals(Actor.SYSTEM, row.getActor(),
                "auto-assign is a SYSTEM action per CLAUDE.md");
        assertNull(row.getPerformedBy(),
                "SYSTEM actor must not carry a performedBy id (no sentinel user)");
    }

    @Test
    void autoAssign_emitsBothCreateAndAutoAssignRows() throws Exception {
        // The original CREATE row stays — auto-assign adds a second row.
        // Both share entityId. Order: CREATE first, AUTO_ASSIGN after.
        TicketResponse res = ticketService.create(req(null));

        List<AuditLog> rows = auditLogRepository.findAll().stream()
                .filter(r -> r.getEntityType() == EntityType.TICKET)
                .filter(r -> r.getEntityId().equals(res.id()))
                .toList();

        long creates = rows.stream().filter(r -> r.getAction() == AuditAction.CREATE).count();
        long autos   = rows.stream().filter(r -> r.getAction() == AuditAction.AUTO_ASSIGN).count();
        assertEquals(1, creates);
        assertEquals(1, autos);
    }

    // ---------- "Don't trigger" paths ----------

    @Test
    void explicitAssignee_doesNotTriggerAutoAssign() throws Exception {
        // Caller specified devB — auto-assign must NOT fire.
        TicketResponse res = ticketService.create(req(devB.getId()));

        assertEquals(devB.getId(), res.assigneeId());

        long autos = auditLogRepository.findAll().stream()
                .filter(r -> r.getAction() == AuditAction.AUTO_ASSIGN)
                .count();
        assertEquals(0, autos, "explicit assigneeId must not produce AUTO_ASSIGN");
    }

    @Test
    void update_setAssigneeNull_doesNotTriggerAutoAssign() throws Exception {
        // BUILD_PLAN: "Auto-assign fires on create only, never on update."
        // Even if a PATCH clears the assignee, no AUTO_ASSIGN row appears.
        TicketResponse res = ticketService.create(req(devB.getId()));
        auditLogRepository.deleteAll();   // isolate the PATCH path

        ticketService.update(res.id(), new UpdateTicketRequest(
                JsonNullable.undefined(),                                       // title
                JsonNullable.undefined(),                                       // description
                JsonNullable.undefined(),                                       // status
                JsonNullable.undefined(),                                       // priority
                JsonNullable.<Long>of(null),                                    // assigneeId → null
                JsonNullable.undefined()                                        // dueDate
        ));

        long autos = auditLogRepository.findAll().stream()
                .filter(r -> r.getAction() == AuditAction.AUTO_ASSIGN)
                .count();
        assertEquals(0, autos, "PATCH must never trigger auto-assign");

        // Confirm the assignee really did get cleared (else the test isn't proving anything).
        Ticket reloaded = ticketRepository.findById(res.id()).orElseThrow();
        assertNull(reloaded.getAssigneeId());
    }

    @Test
    void noDevelopers_savesTicketWithNullAssignee_andNoAuditRow() throws Exception {
        // Yank every DEVELOPER so the candidate list is empty.
        userRepository.delete(devA);
        userRepository.delete(devB);
        userRepository.delete(devC);
        em.flush();

        TicketResponse res = ticketService.create(req(null));

        assertNull(res.assigneeId(),
                "no developers → ticket saved with null assignee (no error)");

        long autos = auditLogRepository.findAll().stream()
                .filter(r -> r.getAction() == AuditAction.AUTO_ASSIGN)
                .count();
        assertEquals(0, autos, "no candidates → no AUTO_ASSIGN row");
    }

    @Test
    void adminUsers_areNeverPickedByAutoAssign() throws Exception {
        // The single admin would otherwise win on workload (0) and createdAt
        // (oldest, since they were created right after devC). Confirm role
        // gating excludes them.
        userRepository.delete(devA);
        userRepository.delete(devB);
        userRepository.delete(devC);
        em.flush();

        TicketResponse res = ticketService.create(req(null));

        assertNull(res.assigneeId(), "ADMIN users must not be picked");
    }

    // ---------- Provenance check ----------

    @Test
    void autoAssign_assigneeIsActuallyPersisted() throws Exception {
        // Cheap belt-and-braces: the response value matches the DB.
        TicketResponse res = ticketService.create(req(null));

        Ticket reloaded = ticketRepository.findById(res.id()).orElseThrow();
        assertNotNull(reloaded.getAssigneeId());
        assertEquals(res.assigneeId(), reloaded.getAssigneeId());
        assertTrue(List.of(devA.getId(), devB.getId(), devC.getId())
                        .contains(reloaded.getAssigneeId()),
                "assignee must be one of the developers");
    }
}
