package com.att.tdp.issueflow.ticket;

import com.att.tdp.issueflow.audit.Actor;
import com.att.tdp.issueflow.audit.AuditAction;
import com.att.tdp.issueflow.audit.AuditLog;
import com.att.tdp.issueflow.audit.AuditLogRepository;
import com.att.tdp.issueflow.audit.EntityType;
import com.att.tdp.issueflow.project.Project;
import com.att.tdp.issueflow.project.ProjectRepository;
import com.att.tdp.issueflow.ticket.dto.AddDependencyRequest;
import com.att.tdp.issueflow.user.Role;
import com.att.tdp.issueflow.user.User;
import com.att.tdp.issueflow.user.UserRepository;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Audit-log wiring tests for the dependency CRUD path. The HTTP-shape
 * concerns live in {@link TicketDependencyControllerTest}; this class is
 * narrowly about confirming that {@code add} / {@code remove} write the
 * right (action, entityType, entityId, actor) audit rows and that reads
 * never audit.
 */
@SpringBootTest
@Transactional
class TicketDependencyServiceTest {

    @Autowired TicketDependencyService dependencyService;
    @Autowired TicketDependencyRepository dependencyRepository;
    @Autowired TicketRepository ticketRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired UserRepository userRepository;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired EntityManager em;

    User testUser;
    Project testProject;

    @BeforeEach
    void setup() {
        auditLogRepository.deleteAll();
        dependencyRepository.deleteAll();
        ticketRepository.deleteAll();
        projectRepository.deleteAll();
        userRepository.deleteAll();
        em.flush();

        testUser = userRepository.save(User.builder()
                .username("u").email("u@e.com").fullName("u")
                .role(Role.DEVELOPER).passwordHash("h").build());
        testProject = projectRepository.save(Project.builder()
                .name("p").description("d").ownerId(testUser.getId()).build());
    }

    private Ticket newTicket(String title) {
        return ticketRepository.save(Ticket.builder()
                .title(title).description("d")
                .status(TicketStatus.TODO).priority(Priority.MEDIUM).type(TicketType.BUG)
                .projectId(testProject.getId()).isOverdue(false).build());
    }

    @Test
    void add_recordsCreateDependencyAudit() {
        Ticket dep = newTicket("dep");
        Ticket blocker = newTicket("blocker");
        long before = auditLogRepository.count();

        dependencyService.add(dep.getId(), new AddDependencyRequest(blocker.getId()));
        em.flush();

        List<AuditLog> created = auditLogRepository.findAll().stream()
                .filter(r -> r.getEntityType() == EntityType.DEPENDENCY)
                .filter(r -> r.getAction() == AuditAction.CREATE)
                .toList();
        assertEquals(1, created.size());
        assertEquals(before + 1, auditLogRepository.count());

        AuditLog row = created.get(0);
        assertEquals(Actor.USER, row.getActor());
        // No SecurityContext in this test — performedBy is null (documented
        // leniency for the test path; production filter would set it).
        assertNull(row.getPerformedBy());

        // entityId is the join-row id, NOT the dependent or blocker ticket id.
        Long joinRowId = dependencyRepository
                .findByTicketIdAndBlockedById(dep.getId(), blocker.getId())
                .orElseThrow().getId();
        assertEquals(joinRowId, row.getEntityId());
    }

    @Test
    void remove_recordsDeleteDependencyAudit() {
        Ticket dep = newTicket("dep");
        Ticket blocker = newTicket("blocker");
        dependencyService.add(dep.getId(), new AddDependencyRequest(blocker.getId()));
        em.flush();
        Long joinRowId = dependencyRepository
                .findByTicketIdAndBlockedById(dep.getId(), blocker.getId())
                .orElseThrow().getId();

        dependencyService.remove(dep.getId(), blocker.getId());
        em.flush();

        List<AuditLog> deleted = auditLogRepository.findAll().stream()
                .filter(r -> r.getEntityType() == EntityType.DEPENDENCY)
                .filter(r -> r.getAction() == AuditAction.DELETE)
                .toList();
        assertEquals(1, deleted.size());

        AuditLog row = deleted.get(0);
        assertEquals(Actor.USER, row.getActor());
        // entityId is the join-row id captured BEFORE the delete fired.
        assertEquals(joinRowId, row.getEntityId());
    }

    @Test
    void list_doesNotRecordAudit() {
        Ticket dep = newTicket("dep");
        Ticket blocker = newTicket("blocker");
        dependencyService.add(dep.getId(), new AddDependencyRequest(blocker.getId()));
        em.flush();
        long auditCountAfterAdd = auditLogRepository.count();

        dependencyService.listBlockers(dep.getId());
        em.flush();

        // No new audit rows from the read.
        assertEquals(auditCountAfterAdd, auditLogRepository.count());
    }
}
