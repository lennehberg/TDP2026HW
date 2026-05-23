package com.att.tdp.issueflow.comment;

import com.att.tdp.issueflow.TestSecurityConfig;
import com.att.tdp.issueflow.audit.Actor;
import com.att.tdp.issueflow.audit.AuditAction;
import com.att.tdp.issueflow.audit.AuditLog;
import com.att.tdp.issueflow.audit.AuditLogRepository;
import com.att.tdp.issueflow.audit.EntityType;
import com.att.tdp.issueflow.comment.dto.CreateCommentRequest;
import com.att.tdp.issueflow.comment.dto.UpdateCommentRequest;
import com.att.tdp.issueflow.project.Project;
import com.att.tdp.issueflow.project.ProjectRepository;
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
import org.openapitools.jackson.nullable.JsonNullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 5/9 — mention extraction + audit + feed.
 * <p>
 * Two surfaces:
 * <ul>
 *   <li>Mention audit rows must carry {@link Actor#USER}, not {@link Actor#SYSTEM}.
 *       The {@code @mentions} sync is triggered by a USER editing/posting a comment;
 *       the principal is known and attributable. SYSTEM is reserved for
 *       AUTO_ASSIGN and AUTO_ESCALATE only.</li>
 *   <li>The {@code GET /users/{id}/mentions} feed returns paginated comments
 *       mentioning the user, newest-first.</li>
 * </ul>
 * The audit-actor assertions defend Fix 3 (CommentService previously called
 * {@code recordSystemAction} for mention rows).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
@Transactional
class MentionFeedTest {

    @Autowired MockMvc mockMvc;
    @Autowired CommentService commentService;
    @Autowired CommentRepository commentRepository;
    @Autowired MentionRepository mentionRepository;
    @Autowired TicketRepository ticketRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired UserRepository userRepository;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired EntityManager em;

    User author;
    User alice;
    User bob;
    Project project;
    Ticket ticket;

    @BeforeEach
    void setup() {
        auditLogRepository.deleteAll();
        mentionRepository.deleteAll();
        commentRepository.deleteAll();
        ticketRepository.deleteAll();
        projectRepository.deleteAll();
        userRepository.deleteAll();
        // Force delete flush before the username INSERTs below, otherwise
        // Hibernate may batch INSERTs ahead of DELETEs and hit the unique
        // index from non-@Transactional tests (e.g. AuthControllerTest seeds
        // a persistent "alice"). em.flush() pins the ordering.
        em.flush();

        author = userRepository.save(User.builder()
                .username("mentionAuthor").email("ma@e.com").fullName("author")
                .role(Role.DEVELOPER).passwordHash("h").build());
        alice = userRepository.save(User.builder()
                .username("mentionAlice").email("alice@e.com").fullName("Alice")
                .role(Role.DEVELOPER).passwordHash("h").build());
        bob = userRepository.save(User.builder()
                .username("mentionBob").email("bob@e.com").fullName("Bob")
                .role(Role.DEVELOPER).passwordHash("h").build());
        project = projectRepository.save(Project.builder()
                .name("p").description("d").ownerId(author.getId()).build());
        ticket = ticketRepository.save(Ticket.builder()
                .title("t").description("d")
                .status(TicketStatus.TODO).priority(Priority.MEDIUM).type(TicketType.BUG)
                .projectId(project.getId()).isOverdue(false).build());
    }

    // ---------- Mention extraction + persistence ----------

    @Test
    void create_extractsMentionsFromContent_andPersistsRows() throws Exception {
        commentService.create(ticket.getId(),
                new CreateCommentRequest(author.getId(), "Hi @mentionAlice and @mentionBob!"));

        List<Mention> rows = mentionRepository.findAll();
        assertEquals(2, rows.size());
        List<Long> mentioned = rows.stream().map(Mention::getMentionedUserId).toList();
        assertTrue(mentioned.contains(alice.getId()));
        assertTrue(mentioned.contains(bob.getId()));
    }

    @Test
    void create_ignoresUnknownUsernames() throws Exception {
        // @nobody doesn't resolve to a user; no Mention row is created for it.
        commentService.create(ticket.getId(),
                new CreateCommentRequest(author.getId(), "Hi @mentionAlice and @nobody"));

        List<Mention> rows = mentionRepository.findAll();
        assertEquals(1, rows.size());
        assertEquals(alice.getId(), rows.get(0).getMentionedUserId());
    }

    @Test
    void update_replacesMentions_clearingOldOnes() throws Exception {
        var c = commentService.create(ticket.getId(),
                new CreateCommentRequest(author.getId(), "@mentionAlice"));
        commentService.update(ticket.getId(), c.id(),
                new UpdateCommentRequest(JsonNullable.of("@mentionBob")));

        List<Mention> rows = mentionRepository.findAll();
        assertEquals(1, rows.size());
        assertEquals(bob.getId(), rows.get(0).getMentionedUserId());
    }

    // ---------- Audit-actor (Fix 3 regression guard) ----------

    @Test
    void create_mentionAuditRowsCarryActorUser_notSystem() throws Exception {
        // Fix 3: mention sync is triggered by a USER action, so audit rows
        // must record actor=USER. Previously CommentService called
        // recordSystemAction, which writes actor=SYSTEM, performedBy=null —
        // that corrupts the audit trail (SYSTEM is reserved for auto-assign
        // and auto-escalate per CLAUDE.md).
        commentService.create(ticket.getId(),
                new CreateCommentRequest(author.getId(), "Hello @mentionAlice"));

        List<AuditLog> mentionRows = auditLogRepository.findAll().stream()
                .filter(r -> r.getEntityType() == EntityType.MENTION)
                .toList();
        assertTrue(mentionRows.size() >= 1, "expected at least one MENTION audit row");
        assertTrue(mentionRows.stream().allMatch(r -> r.getActor() == Actor.USER),
                "every MENTION audit row must be actor=USER, not SYSTEM");
        assertTrue(mentionRows.stream().anyMatch(r -> r.getAction() == AuditAction.CREATE),
                "expected at least one MENTION/CREATE row");
    }

    @Test
    void update_mentionDeleteAuditCarriesActorUser() throws Exception {
        // Mention sync clears old rows and re-inserts. The DELETE side of the
        // sync must also be USER-attributed — Fix 3 covered both branches.
        var c = commentService.create(ticket.getId(),
                new CreateCommentRequest(author.getId(), "@mentionAlice"));
        // Drop any rows from the create path so we can isolate the update's audit.
        auditLogRepository.deleteAll();

        commentService.update(ticket.getId(), c.id(),
                new UpdateCommentRequest(JsonNullable.of("@mentionBob")));

        List<AuditLog> mentionRows = auditLogRepository.findAll().stream()
                .filter(r -> r.getEntityType() == EntityType.MENTION)
                .toList();
        // We expect at least: DELETE of the @mentionAlice mention + CREATE of the @mentionBob one.
        long userDeletes = mentionRows.stream()
                .filter(r -> r.getAction() == AuditAction.DELETE)
                .filter(r -> r.getActor() == Actor.USER)
                .count();
        long userCreates = mentionRows.stream()
                .filter(r -> r.getAction() == AuditAction.CREATE)
                .filter(r -> r.getActor() == Actor.USER)
                .count();
        assertTrue(userDeletes >= 1, "expected MENTION/DELETE actor=USER on update");
        assertTrue(userCreates >= 1, "expected MENTION/CREATE actor=USER on update");
        assertTrue(mentionRows.stream().noneMatch(r -> r.getActor() == Actor.SYSTEM),
                "no MENTION audit row may be actor=SYSTEM");
    }

    @Test
    void delete_cascadeMentionAuditCarriesActorUser() throws Exception {
        // Fix 3 also covered the cascade in CommentService.delete — when a
        // comment is deleted, the MENTION rows it owns get DELETE audit rows.
        // Those must also be USER (the user deleted the comment).
        var c = commentService.create(ticket.getId(),
                new CreateCommentRequest(author.getId(), "@mentionAlice"));
        auditLogRepository.deleteAll();

        commentService.delete(ticket.getId(), c.id());

        List<AuditLog> mentionDeletes = auditLogRepository.findAll().stream()
                .filter(r -> r.getEntityType() == EntityType.MENTION)
                .filter(r -> r.getAction() == AuditAction.DELETE)
                .toList();
        assertEquals(1, mentionDeletes.size());
        assertEquals(Actor.USER, mentionDeletes.get(0).getActor());
    }

    // ---------- Mention feed (/users/{id}/mentions) ----------

    @Test
    void mentionFeed_returnsCommentsMentioningUser_newestFirst() throws Exception {
        // Comments are ordered by Comment.createdAt DESC in the repository
        // query. We insert with explicit delays so the H2 millisecond clock
        // produces distinct timestamps.
        commentService.create(ticket.getId(),
                new CreateCommentRequest(author.getId(), "first @mentionAlice"));
        Thread.sleep(5);
        commentService.create(ticket.getId(),
                new CreateCommentRequest(author.getId(), "@mentionBob only"));
        Thread.sleep(5);
        commentService.create(ticket.getId(),
                new CreateCommentRequest(author.getId(), "second @mentionAlice"));

        mockMvc.perform(get("/users/{id}/mentions", alice.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.total", is(2)))
                .andExpect(jsonPath("$.page", is(1)))
                // Newest first.
                .andExpect(jsonPath("$.data[0].content", is("second @mentionAlice")))
                .andExpect(jsonPath("$.data[1].content", is("first @mentionAlice")));
    }

    @Test
    void mentionFeed_paginationHonoured() throws Exception {
        // Three comments mentioning alice; pageSize=2 should return the
        // newest two on page 1, the remaining one on page 2.
        commentService.create(ticket.getId(),
                new CreateCommentRequest(author.getId(), "one @mentionAlice"));
        Thread.sleep(5);
        commentService.create(ticket.getId(),
                new CreateCommentRequest(author.getId(), "two @mentionAlice"));
        Thread.sleep(5);
        commentService.create(ticket.getId(),
                new CreateCommentRequest(author.getId(), "three @mentionAlice"));

        mockMvc.perform(get("/users/{id}/mentions", alice.getId())
                        .param("page", "1").param("pageSize", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.total", is(3)));

        mockMvc.perform(get("/users/{id}/mentions", alice.getId())
                        .param("page", "2").param("pageSize", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.total", is(3)))
                .andExpect(jsonPath("$.page", is(2)));
    }

    @Test
    void mentionFeed_unknownUser_returns404() throws Exception {
        mockMvc.perform(get("/users/{id}/mentions", 9999L))
                .andExpect(status().isNotFound());
    }

    @Test
    void mentionFeed_emptyResult_returnsEmptyPage() throws Exception {
        // alice exists but isn't mentioned anywhere — the feed must still be
        // a valid PageResponse, just with an empty data list.
        commentService.create(ticket.getId(),
                new CreateCommentRequest(author.getId(), "no mentions here"));

        mockMvc.perform(get("/users/{id}/mentions", alice.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)))
                .andExpect(jsonPath("$.total", is(0)))
                .andExpect(jsonPath("$.page", is(1)));
    }
}
