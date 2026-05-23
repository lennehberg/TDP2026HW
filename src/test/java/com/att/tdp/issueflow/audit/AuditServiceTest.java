package com.att.tdp.issueflow.audit;

import com.att.tdp.issueflow.auth.AuthenticatedUser;
import com.att.tdp.issueflow.common.exception.BadRequestException;
import com.att.tdp.issueflow.project.Project;
import com.att.tdp.issueflow.project.ProjectRepository;
import com.att.tdp.issueflow.project.ProjectService;
import com.att.tdp.issueflow.project.dto.UpdateProjectRequest;
import com.att.tdp.issueflow.user.Role;
import com.att.tdp.issueflow.user.User;
import com.att.tdp.issueflow.user.UserRepository;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Service-level tests for {@link AuditService}. Uses the real H2 instance so
 * the Specification query and pagination round-trip through Hibernate exactly
 * as they will in production. Phase 6 invariants verified here:
 * <ul>
 *   <li>{@code record(...)} persists every field as supplied.</li>
 *   <li>{@code SYSTEM} actor with non-null {@code performedBy} is rejected.</li>
 *   <li>{@code recordUserAction} stamps {@code performedBy} from the current
 *       {@link AuthenticatedUser} principal; falls back to {@code null} when
 *       no SecurityContext is set (tests, scheduler).</li>
 *   <li>{@code find(...)} composes filters with AND, paginates, and sorts
 *       newest-first.</li>
 * </ul>
 * Plus the Phase 3 P.P.S. carry-over smoke test: a {@code PATCH /projects}
 * writes both an audit row AND populates the entity's {@code updated_at}.
 */
@SpringBootTest
@Transactional
class AuditServiceTest {

    @Autowired AuditService auditService;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired ProjectService projectService;
    @Autowired ProjectRepository projectRepository;
    @Autowired UserRepository userRepository;
    @Autowired EntityManager em;

    @BeforeEach
    void setup() {
        auditLogRepository.deleteAll();
        projectRepository.deleteAll();
        userRepository.deleteAll();
        em.flush();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void record_persistsAllFields() {
        auditService.record(
                AuditAction.CREATE, EntityType.TICKET, 42L, 7L,
                Actor.USER, "{\"foo\":1}");
        em.flush();
        em.clear();

        List<AuditLog> all = auditLogRepository.findAll();
        assertEquals(1, all.size());
        AuditLog r = all.get(0);
        assertEquals(AuditAction.CREATE, r.getAction());
        assertEquals(EntityType.TICKET, r.getEntityType());
        assertEquals(42L, r.getEntityId());
        assertEquals(7L, r.getPerformedBy());
        assertEquals(Actor.USER, r.getActor());
        assertEquals("{\"foo\":1}", r.getPayload());
        assertNotNull(r.getCreatedAt(), "createdAt must be populated by JPA auditing");
    }

    @Test
    void record_throws_whenSystemActorCarriesPerformedBy() {
        // Phase 6 invariant: no sentinel system user.
        assertThrows(IllegalArgumentException.class, () ->
                auditService.record(AuditAction.AUTO_ASSIGN, EntityType.TICKET, 1L, 7L, Actor.SYSTEM));
    }

    @Test
    void record_allowsSystemActorWithNullPerformedBy() {
        auditService.record(AuditAction.AUTO_ASSIGN, EntityType.TICKET, 1L, null, Actor.SYSTEM);
        em.flush();
        assertEquals(1, auditLogRepository.count());
    }

    @Test
    void recordUserAction_usesAuthenticatedPrincipalId() {
        setPrincipal(99L, "alice");
        auditService.recordUserAction(AuditAction.CREATE, EntityType.PROJECT, 1L);
        em.flush();

        AuditLog r = auditLogRepository.findAll().get(0);
        assertEquals(99L, r.getPerformedBy());
        assertEquals(Actor.USER, r.getActor());
    }

    @Test
    void recordUserAction_storesNullWhenNoPrincipal() {
        // Tests run without a SecurityContext; this is the documented
        // production-vs-test leniency around USER actor + null performedBy.
        SecurityContextHolder.clearContext();
        auditService.recordUserAction(AuditAction.CREATE, EntityType.PROJECT, 1L);
        em.flush();

        AuditLog r = auditLogRepository.findAll().get(0);
        assertNull(r.getPerformedBy());
        assertEquals(Actor.USER, r.getActor());
    }

    @Test
    void recordSystemAction_storesNullPerformedBy_evenWhenPrincipalIsSet() {
        // Locks in the invariant: SYSTEM-actor flows ignore any current
        // principal, so a future Phase 12/13 author can't accidentally
        // leak a user id into auto-assign/auto-escalate audit rows.
        setPrincipal(99L, "alice");
        auditService.recordSystemAction(AuditAction.AUTO_ESCALATE, EntityType.TICKET, 1L);
        em.flush();

        AuditLog r = auditLogRepository.findAll().get(0);
        assertNull(r.getPerformedBy());
        assertEquals(Actor.SYSTEM, r.getActor());
    }

    @Test
    void find_returnsAll_whenNoFilters() {
        seed(AuditAction.CREATE, EntityType.TICKET, 1L);
        seed(AuditAction.UPDATE, EntityType.TICKET, 1L);
        seed(AuditAction.CREATE, EntityType.PROJECT, 2L);
        em.flush();

        var page = auditService.find(null, null, null, null, 1, 20);
        assertEquals(3, page.total());
        assertEquals(3, page.data().size());
        assertEquals(1, page.page());
    }

    @Test
    void find_filtersByAction() {
        seed(AuditAction.CREATE, EntityType.TICKET, 1L);
        seed(AuditAction.UPDATE, EntityType.TICKET, 1L);
        em.flush();

        var page = auditService.find(null, null, AuditAction.UPDATE, null, 1, 20);
        assertEquals(1, page.total());
        assertEquals(AuditAction.UPDATE, page.data().get(0).action());
    }

    @Test
    void find_filtersByEntityTypeAndEntityId() {
        seed(AuditAction.CREATE, EntityType.TICKET, 1L);
        seed(AuditAction.CREATE, EntityType.TICKET, 2L);
        seed(AuditAction.CREATE, EntityType.PROJECT, 1L);
        em.flush();

        var page = auditService.find(EntityType.TICKET, 1L, null, null, 1, 20);
        assertEquals(1, page.total());
        var row = page.data().get(0);
        assertEquals(EntityType.TICKET, row.entityType());
        assertEquals(1L, row.entityId());
    }

    @Test
    void find_combinesFiltersWithAnd() {
        seed(AuditAction.CREATE, EntityType.TICKET, 1L);
        seed(AuditAction.CREATE, EntityType.PROJECT, 1L);
        seed(AuditAction.UPDATE, EntityType.TICKET, 1L);
        em.flush();

        var page = auditService.find(EntityType.TICKET, null, AuditAction.CREATE, null, 1, 20);
        assertEquals(1, page.total());
    }

    @Test
    void find_filtersBySystemActor() {
        seed(AuditAction.CREATE, EntityType.TICKET, 1L);
        auditService.recordSystemAction(AuditAction.AUTO_ASSIGN, EntityType.TICKET, 1L);
        em.flush();

        var page = auditService.find(null, null, null, Actor.SYSTEM, 1, 20);
        assertEquals(1, page.total());
        assertNull(page.data().get(0).performedBy());
        assertEquals(Actor.SYSTEM, page.data().get(0).actor());
    }

    @Test
    void find_validatesPageBounds() {
        assertThrows(BadRequestException.class,
                () -> auditService.find(null, null, null, null, 0, 20));
        assertThrows(BadRequestException.class,
                () -> auditService.find(null, null, null, null, -1, 20));
    }

    @Test
    void find_validatesPageSizeBounds() {
        assertThrows(BadRequestException.class,
                () -> auditService.find(null, null, null, null, 1, 0));
        assertThrows(BadRequestException.class,
                () -> auditService.find(null, null, null, null, 1, 101));
    }

    @Test
    void find_paginates() {
        for (int i = 0; i < 5; i++) {
            seed(AuditAction.CREATE, EntityType.TICKET, (long) i);
        }
        em.flush();

        var page1 = auditService.find(null, null, null, null, 1, 2);
        assertEquals(5, page1.total());
        assertEquals(2, page1.data().size());

        var page3 = auditService.find(null, null, null, null, 3, 2);
        assertEquals(5, page3.total());
        // Last page holds the remainder: 5 rows / 2 per page = pages of 2,2,1.
        assertEquals(1, page3.data().size());
    }

    @Test
    void find_sortsNewestFirst() {
        // @CreatedDate fires on persist but H2 timestamp resolution may
        // collapse near-simultaneous saves. Bump created_at via native SQL
        // afterward so the sort assertion is deterministic.
        long id1 = seed(AuditAction.CREATE, EntityType.TICKET, 1L);
        long id2 = seed(AuditAction.UPDATE, EntityType.TICKET, 1L);
        long id3 = seed(AuditAction.DELETE, EntityType.TICKET, 1L);
        em.flush();

        Instant now = Instant.now();
        bumpCreatedAt(id1, now.minusSeconds(300));
        bumpCreatedAt(id2, now.minusSeconds(200));
        bumpCreatedAt(id3, now.minusSeconds(100));
        em.flush();
        em.clear();

        var page = auditService.find(null, null, null, null, 1, 20);
        assertEquals(AuditAction.DELETE, page.data().get(0).action());
        assertEquals(AuditAction.UPDATE, page.data().get(1).action());
        assertEquals(AuditAction.CREATE, page.data().get(2).action());
    }

    /**
     * Phase 3 P.P.S. carry-over: PATCH a project, confirm both that an audit
     * row landed AND that the entity's {@code updated_at} was populated by
     * {@code @LastModifiedDate}. A null {@code updated_at} would mean
     * {@code @EnableJpaAuditing} isn't taking effect.
     */
    @Test
    void patchProject_writesAuditRow_andPopulatesUpdatedAt() {
        User owner = userRepository.save(User.builder()
                .username("o").email("o@e.com").fullName("o")
                .role(Role.DEVELOPER).passwordHash("h").build());
        Project p = projectRepository.save(Project.builder()
                .name("orig").description("orig").ownerId(owner.getId()).build());
        em.flush();
        em.clear();

        long auditCountBefore = auditLogRepository.count();

        projectService.update(p.getId(), new UpdateProjectRequest("updated", null));
        em.flush();
        em.clear();

        // Audit row landed.
        assertEquals(auditCountBefore + 1, auditLogRepository.count());
        var rows = auditLogRepository.findAll();
        var lastRow = rows.get(rows.size() - 1);
        assertEquals(AuditAction.UPDATE, lastRow.getAction());
        assertEquals(EntityType.PROJECT, lastRow.getEntityType());
        assertEquals(p.getId(), lastRow.getEntityId());

        // updated_at populated — the original Phase 3 P.P.S. concern.
        Project reloaded = projectRepository.findById(p.getId()).orElseThrow();
        assertNotNull(reloaded.getUpdatedAt(),
                "updated_at must be non-null after PATCH (Phase 3 P.P.S. carry-over)");
    }

    // ---------- helpers ----------

    private Long seed(AuditAction action, EntityType type, Long entityId) {
        AuditLog row = AuditLog.builder()
                .action(action)
                .entityType(type)
                .entityId(entityId)
                .performedBy(1L)
                .actor(Actor.USER)
                .build();
        return auditLogRepository.save(row).getId();
    }

    private void bumpCreatedAt(Long id, Instant t) {
        em.createNativeQuery("UPDATE audit_logs SET created_at = ? WHERE id = ?")
                .setParameter(1, Timestamp.from(t))
                .setParameter(2, id)
                .executeUpdate();
    }

    private void setPrincipal(Long uid, String username) {
        var auth = new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser(uid, username), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
