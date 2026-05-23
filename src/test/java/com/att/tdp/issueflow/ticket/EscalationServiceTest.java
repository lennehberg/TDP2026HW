package com.att.tdp.issueflow.ticket;

import com.att.tdp.issueflow.audit.Actor;
import com.att.tdp.issueflow.audit.AuditAction;
import com.att.tdp.issueflow.audit.AuditLog;
import com.att.tdp.issueflow.audit.AuditLogRepository;
import com.att.tdp.issueflow.audit.EntityType;
import com.att.tdp.issueflow.project.Project;
import com.att.tdp.issueflow.project.ProjectRepository;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 13 — auto-escalation scheduler behavior.
 * <p>
 * The scheduled trigger is irrelevant for testing — we drive
 * {@link EscalationService#runEscalation()} directly so the test owns timing.
 * Each test wires a ticket whose {@code dueDate} is in the past, runs one
 * tick, and asserts the resulting state.
 * <p>
 * Pinned invariants:
 * <ul>
 *   <li>{@code priority < CRITICAL} bumps one level; {@code priority == CRITICAL}
 *       flips {@code isOverdue=true} (terminal).</li>
 *   <li>Scheduler never touches {@code status} (BUILD_PLAN cross-cutting rule).</li>
 *   <li>Audit row is {@code action=AUTO_ESCALATE, actor=SYSTEM, performedBy=null}.</li>
 *   <li>DONE tickets and soft-deleted tickets are excluded by the
 *       candidate query.</li>
 *   <li>Idempotency at terminal state: running the scheduler again on a
 *       CRITICAL+overdue ticket is a no-op (no new audit row).</li>
 * </ul>
 */
@SpringBootTest
@Transactional
class EscalationServiceTest {

    @Autowired EscalationService escalationService;
    @Autowired TicketRepository ticketRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired UserRepository userRepository;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired EntityManager em;

    User owner;
    Project project;

    @BeforeEach
    void setup() {
        auditLogRepository.deleteAll();
        ticketRepository.deleteAll();
        projectRepository.deleteAll();
        userRepository.deleteAll();
        em.flush();

        owner = userRepository.save(User.builder()
                .username("escOwner").email("e@e.com").fullName("o")
                .role(Role.ADMIN).passwordHash("h").build());
        project = projectRepository.save(Project.builder()
                .name("p").description("d").ownerId(owner.getId()).build());
    }

    private Ticket overdueTicket(Priority priority, boolean isOverdue, TicketStatus status) {
        return ticketRepository.save(Ticket.builder()
                .title("t").description("d")
                .status(status).priority(priority).type(TicketType.BUG)
                .projectId(project.getId())
                .dueDate(Instant.now().minus(1, ChronoUnit.HOURS))
                .isOverdue(isOverdue).build());
    }

    private long escalationAuditRows(Long ticketId) {
        return auditLogRepository.findAll().stream()
                .filter(r -> r.getEntityType() == EntityType.TICKET)
                .filter(r -> r.getAction() == AuditAction.AUTO_ESCALATE)
                .filter(r -> r.getEntityId().equals(ticketId))
                .count();
    }

    // ---------- Priority bumping ----------

    @Test
    void escalate_lowOverdue_bumpsToMedium() throws Exception {
        Ticket t = overdueTicket(Priority.LOW, false, TicketStatus.TODO);

        escalationService.runEscalation();
        em.flush();
        em.clear();

        Ticket reloaded = ticketRepository.findById(t.getId()).orElseThrow();
        assertEquals(Priority.MEDIUM, reloaded.getPriority());
        // isOverdue stays false — flipping it is reserved for the CRITICAL step.
        assertFalse(reloaded.isOverdue());
        assertEquals(1, escalationAuditRows(t.getId()));
    }

    @Test
    void escalate_mediumOverdue_bumpsToHigh() throws Exception {
        Ticket t = overdueTicket(Priority.MEDIUM, false, TicketStatus.TODO);

        escalationService.runEscalation();
        em.flush();
        em.clear();

        assertEquals(Priority.HIGH, ticketRepository.findById(t.getId()).orElseThrow().getPriority());
    }

    @Test
    void escalate_highOverdue_bumpsToCritical() throws Exception {
        Ticket t = overdueTicket(Priority.HIGH, false, TicketStatus.TODO);

        escalationService.runEscalation();
        em.flush();
        em.clear();

        Ticket reloaded = ticketRepository.findById(t.getId()).orElseThrow();
        assertEquals(Priority.CRITICAL, reloaded.getPriority());
        // Reached CRITICAL on this tick — isOverdue still false; next tick will set it.
        assertFalse(reloaded.isOverdue());
    }

    @Test
    void escalate_criticalOverdue_setsIsOverdueWithoutChangingPriority() throws Exception {
        // CRITICAL + not yet overdue → flip isOverdue. No more priority changes.
        Ticket t = overdueTicket(Priority.CRITICAL, false, TicketStatus.TODO);

        escalationService.runEscalation();
        em.flush();
        em.clear();

        Ticket reloaded = ticketRepository.findById(t.getId()).orElseThrow();
        assertEquals(Priority.CRITICAL, reloaded.getPriority());
        assertTrue(reloaded.isOverdue());
        assertEquals(1, escalationAuditRows(t.getId()));
    }

    // ---------- Idempotency ----------

    @Test
    void escalate_criticalAndOverdue_isIdempotentNoOp() throws Exception {
        // BUILD_PLAN Phase 14: "escalation idempotency at CRITICAL." Once a
        // ticket is at the terminal state, further runs must not produce
        // additional audit rows.
        Ticket t = overdueTicket(Priority.CRITICAL, true, TicketStatus.TODO);

        escalationService.runEscalation();
        em.flush();
        em.clear();

        Ticket reloaded = ticketRepository.findById(t.getId()).orElseThrow();
        assertEquals(Priority.CRITICAL, reloaded.getPriority());
        assertTrue(reloaded.isOverdue());
        assertEquals(0, escalationAuditRows(t.getId()),
                "terminal state must not produce additional AUTO_ESCALATE rows");
    }

    @Test
    void escalate_convergesAcrossMultipleTicks_lowToCriticalOverdue() throws Exception {
        // Run the scheduler four times; ticket should climb LOW → MEDIUM →
        // HIGH → CRITICAL → CRITICAL+overdue, then stabilize.
        Ticket t = overdueTicket(Priority.LOW, false, TicketStatus.TODO);

        for (int i = 0; i < 4; i++) {
            escalationService.runEscalation();
            em.flush();
            em.clear();
        }

        Ticket reloaded = ticketRepository.findById(t.getId()).orElseThrow();
        assertEquals(Priority.CRITICAL, reloaded.getPriority());
        assertTrue(reloaded.isOverdue());

        // One audit row per actual transition: LOW→MEDIUM, MEDIUM→HIGH,
        // HIGH→CRITICAL, CRITICAL→overdue. Fifth tick is a no-op.
        escalationService.runEscalation();
        em.flush();
        em.clear();
        assertEquals(4, escalationAuditRows(t.getId()));
    }

    // ---------- Skip conditions ----------

    @Test
    void escalate_doneTicket_isSkipped_evenIfOverdue() throws Exception {
        // BUILD_PLAN: "scheduler skips DONE tickets so the flag freezes after
        // completion." Once DONE, isOverdue is whatever it was — never updated.
        Ticket t = overdueTicket(Priority.LOW, false, TicketStatus.DONE);

        escalationService.runEscalation();
        em.flush();
        em.clear();

        Ticket reloaded = ticketRepository.findById(t.getId()).orElseThrow();
        assertEquals(Priority.LOW, reloaded.getPriority(), "DONE must not be re-prioritized");
        assertFalse(reloaded.isOverdue());
        assertEquals(0, escalationAuditRows(t.getId()));
    }

    @Test
    void escalate_ticketWithFutureDueDate_isSkipped() throws Exception {
        // Only past-due tickets are candidates.
        Ticket t = ticketRepository.save(Ticket.builder()
                .title("t").description("d")
                .status(TicketStatus.TODO).priority(Priority.LOW).type(TicketType.BUG)
                .projectId(project.getId())
                .dueDate(Instant.now().plus(1, ChronoUnit.HOURS))
                .isOverdue(false).build());

        escalationService.runEscalation();
        em.flush();
        em.clear();

        assertEquals(Priority.LOW,
                ticketRepository.findById(t.getId()).orElseThrow().getPriority());
    }

    @Test
    void escalate_ticketWithNullDueDate_isSkipped() throws Exception {
        // No due date → never overdue. The candidate query's
        // `dueDate < :now` filter naturally excludes null.
        Ticket t = ticketRepository.save(Ticket.builder()
                .title("t").description("d")
                .status(TicketStatus.TODO).priority(Priority.LOW).type(TicketType.BUG)
                .projectId(project.getId())
                .dueDate(null)
                .isOverdue(false).build());

        escalationService.runEscalation();
        em.flush();
        em.clear();

        assertEquals(Priority.LOW,
                ticketRepository.findById(t.getId()).orElseThrow().getPriority());
    }

    @Test
    void escalate_softDeletedTicket_isSkipped() throws Exception {
        // @SQLRestriction filters soft-deleted rows from the candidate query.
        Ticket t = overdueTicket(Priority.LOW, false, TicketStatus.TODO);
        t.setDeletedAt(Instant.now());
        ticketRepository.save(t);
        em.flush();

        escalationService.runEscalation();
        em.flush();
        em.clear();

        // Reload via the soft-delete-bypassing finder (since standard finders
        // can't see soft-deleted rows).
        Ticket reloaded = ticketRepository.findByIdIncludingDeleted(t.getId()).orElseThrow();
        assertEquals(Priority.LOW, reloaded.getPriority());
    }

    // ---------- Status invariant ----------

    @Test
    void escalate_neverTouchesStatus() throws Exception {
        // BUILD_PLAN: "Auto-escalation never touches status, only priority
        // and isOverdue."
        Ticket t = overdueTicket(Priority.LOW, false, TicketStatus.IN_PROGRESS);

        escalationService.runEscalation();
        em.flush();
        em.clear();

        Ticket reloaded = ticketRepository.findById(t.getId()).orElseThrow();
        assertEquals(TicketStatus.IN_PROGRESS, reloaded.getStatus());
        // priority did move
        assertEquals(Priority.MEDIUM, reloaded.getPriority());
    }

    // ---------- Audit shape ----------

    @Test
    void escalate_auditRow_actorSystem_performedByNull() throws Exception {
        Ticket t = overdueTicket(Priority.HIGH, false, TicketStatus.TODO);

        escalationService.runEscalation();
        em.flush();

        List<AuditLog> rows = auditLogRepository.findAll().stream()
                .filter(r -> r.getAction() == AuditAction.AUTO_ESCALATE)
                .filter(r -> r.getEntityId().equals(t.getId()))
                .toList();
        assertEquals(1, rows.size());
        assertEquals(Actor.SYSTEM, rows.get(0).getActor(),
                "AUTO_ESCALATE must be a SYSTEM action");
        assertNull(rows.get(0).getPerformedBy(),
                "SYSTEM actor must not carry a performedBy id");
    }

    // ---------- Manual PATCH interactions ----------

    @Autowired TicketService ticketService;

    @Test
    void manualPriorityPatch_clearsIsOverdue_evenWhenValueDoesNotChange() throws Exception {
        // CLAUDE.md: the reset rule keys on whether `priority` was sent, not
        // whether its value changed. A user PATCH-ing CRITICAL onto an
        // already-CRITICAL ticket still clears isOverdue.
        Ticket t = overdueTicket(Priority.CRITICAL, true, TicketStatus.TODO);

        ticketService.update(t.getId(), new UpdateTicketRequest(
                JsonNullable.undefined(),
                JsonNullable.undefined(),
                JsonNullable.undefined(),
                JsonNullable.of(Priority.CRITICAL),   // same value
                JsonNullable.undefined(),
                JsonNullable.undefined()
        ));

        Ticket reloaded = ticketRepository.findById(t.getId()).orElseThrow();
        assertFalse(reloaded.isOverdue(),
                "manual priority PATCH must clear isOverdue even if value unchanged");
        assertNotNull(reloaded.getLastManualPriorityChangeAt(),
                "PATCH must stamp lastManualPriorityChangeAt");
    }

    @Test
    void manualPriorityPatch_clearsIsOverdue_whenValueChanges() throws Exception {
        Ticket t = overdueTicket(Priority.CRITICAL, true, TicketStatus.TODO);

        ticketService.update(t.getId(), new UpdateTicketRequest(
                JsonNullable.undefined(),
                JsonNullable.undefined(),
                JsonNullable.undefined(),
                JsonNullable.of(Priority.HIGH),
                JsonNullable.undefined(),
                JsonNullable.undefined()
        ));

        Ticket reloaded = ticketRepository.findById(t.getId()).orElseThrow();
        assertEquals(Priority.HIGH, reloaded.getPriority());
        assertFalse(reloaded.isOverdue());
    }

    @Test
    void manualPriorityPatch_thenSchedulerCanReEscalate() throws Exception {
        // After a manual clear, the ticket is back in the candidate set
        // (still past dueDate). Next scheduler tick bumps again from the
        // user-asserted priority.
        Ticket t = overdueTicket(Priority.CRITICAL, true, TicketStatus.TODO);

        // User downgrades to LOW (re-owns the priority).
        ticketService.update(t.getId(), new UpdateTicketRequest(
                JsonNullable.undefined(),
                JsonNullable.undefined(),
                JsonNullable.undefined(),
                JsonNullable.of(Priority.LOW),
                JsonNullable.undefined(),
                JsonNullable.undefined()
        ));
        em.flush();
        em.clear();

        // One scheduler tick — should bump LOW → MEDIUM.
        escalationService.runEscalation();
        em.flush();
        em.clear();

        Ticket reloaded = ticketRepository.findById(t.getId()).orElseThrow();
        assertEquals(Priority.MEDIUM, reloaded.getPriority());
        assertFalse(reloaded.isOverdue());
    }

    @Test
    void statusPatch_doesNotTouchIsOverdue() throws Exception {
        // BUILD_PLAN: "status changes never touch the flag."
        Ticket t = overdueTicket(Priority.CRITICAL, true, TicketStatus.TODO);

        ticketService.update(t.getId(), new UpdateTicketRequest(
                JsonNullable.undefined(),
                JsonNullable.undefined(),
                JsonNullable.of(TicketStatus.IN_PROGRESS),
                JsonNullable.undefined(),
                JsonNullable.undefined(),
                JsonNullable.undefined()
        ));

        Ticket reloaded = ticketRepository.findById(t.getId()).orElseThrow();
        assertEquals(TicketStatus.IN_PROGRESS, reloaded.getStatus());
        assertTrue(reloaded.isOverdue(), "status PATCH must not clear isOverdue");
    }
}
