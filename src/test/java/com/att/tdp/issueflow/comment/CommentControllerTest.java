package com.att.tdp.issueflow.comment;

import com.att.tdp.issueflow.TestSecurityConfig;
import com.att.tdp.issueflow.comment.dto.CreateCommentRequest;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertFalse;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
@Transactional
class CommentControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired CommentRepository commentRepository;
    @Autowired TicketRepository ticketRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired UserRepository userRepository;
    @Autowired CommentService commentService;

    User testUser;
    Project testProject;
    Ticket testTicket;

    @BeforeEach
    void setup() {
        commentRepository.deleteAll();
        ticketRepository.deleteAll();
        projectRepository.deleteAll();
        userRepository.deleteAll();

        testUser = userRepository.save(User.builder().username("u").email("u@e.com").fullName("u").role(Role.DEVELOPER).passwordHash("h").build());
        testProject = projectRepository.save(Project.builder().name("p").description("d").ownerId(testUser.getId()).build());
        testTicket = ticketRepository.save(Ticket.builder().title("t").description("d").status(TicketStatus.TODO).priority(Priority.MEDIUM).type(TicketType.BUG).projectId(testProject.getId()).build());
    }

    private Long createComment() {
        var req = new CreateCommentRequest(testUser.getId(), "initial comment");
        return commentService.create(testTicket.getId(), req).id();
    }

    @Test
    void listComments_returnsCommentsForTicket() throws Exception {
        commentService.create(testTicket.getId(), new CreateCommentRequest(testUser.getId(), "1"));
        commentService.create(testTicket.getId(), new CreateCommentRequest(testUser.getId(), "2"));

        mockMvc.perform(get("/tickets/{id}/comments", testTicket.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", is(2)));
    }

    @Test
    void addComment_persistsAndReturnsComment() throws Exception {
        String body = """
                {
                  "authorId": %d,
                  "content": "Hello world!"
                }
                """.formatted(testUser.getId());

        mockMvc.perform(post("/tickets/{id}/comments", testTicket.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.content", is("Hello world!")))
                .andExpect(jsonPath("$.authorId", is(testUser.getId().intValue())));
    }

    @Test
    void updateComment_updatesContent() throws Exception {
        Long id = createComment();
        String body = """
                {
                  "content": "Updated content."
                }
                """;

        mockMvc.perform(patch("/tickets/{ticketId}/comments/{id}", testTicket.getId(), id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", is("Updated content.")));
    }

    @Test
    void deleteComment_removesComment() throws Exception {
        Long id = createComment();
        mockMvc.perform(delete("/tickets/{ticketId}/comments/{id}", testTicket.getId(), id))
                .andExpect(status().isOk());
                
        assertFalse(commentRepository.existsById(id));
    }

    @Test
    void addComment_missingContent_returns400() throws Exception {
        String body = """
                {
                  "authorId": %d
                }
                """.formatted(testUser.getId());

        mockMvc.perform(post("/tickets/{id}/comments", testTicket.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void addComment_missingAuthorId_returns400() throws Exception {
        String body = """
                {
                  "content": "Hello world!"
                }
                """;

        mockMvc.perform(post("/tickets/{id}/comments", testTicket.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listComments_unknownTicket_returnsEmptyList() throws Exception {
        // §4b: missing parent yields [], not 404. Mirrors ProjectService.listByProject.
        mockMvc.perform(get("/tickets/{id}/comments", 9999L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", empty()));
    }

    @Test
    void addComment_blankContent_returns400() throws Exception {
        String body = """
                {
                  "authorId": %d,
                  "content": "   "
                }
                """.formatted(testUser.getId());

        mockMvc.perform(post("/tickets/{id}/comments", testTicket.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void addComment_authorNotFound_returns400() throws Exception {
        // §4a / Phase 4 convention: body reference → 400, not 404.
        String body = """
                {
                  "authorId": 9999,
                  "content": "Hello"
                }
                """;

        mockMvc.perform(post("/tickets/{id}/comments", testTicket.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void addComment_ticketNotFound_returns404() throws Exception {
        String body = """
                {
                  "authorId": %d,
                  "content": "Hello"
                }
                """.formatted(testUser.getId());

        mockMvc.perform(post("/tickets/{id}/comments", 9999L)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isNotFound());
    }

    @Test
    void addComment_contentTooLong_returns400() throws Exception {
        String tooLong = "a".repeat(4097);
        String body = """
                {
                  "authorId": %d,
                  "content": "%s"
                }
                """.formatted(testUser.getId(), tooLong);

        mockMvc.perform(post("/tickets/{id}/comments", testTicket.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void addComment_responseMentionedUsersIsAlwaysEmpty() throws Exception {
        // Phase 5: deliberately empty. Phase 9 will populate from @mentions.
        // Flip this assertion when Phase 9 lands.
        String body = """
                {
                  "authorId": %d,
                  "content": "Hello @nobody!"
                }
                """.formatted(testUser.getId());

        mockMvc.perform(post("/tickets/{id}/comments", testTicket.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mentionedUsers", empty()));
    }

    @Test
    void addComment_onDoneTicket_returns200() throws Exception {
        // §4a: comments are independent of ticket lifecycle. DONE tickets are
        // immutable as tickets, but still accept new comments.
        testTicket.setStatus(TicketStatus.DONE);
        ticketRepository.save(testTicket);

        String body = """
                {
                  "authorId": %d,
                  "content": "post-mortem note"
                }
                """.formatted(testUser.getId());

        mockMvc.perform(post("/tickets/{id}/comments", testTicket.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isOk());
    }

    @Test
    void updateComment_fieldAbsent_leavesContentUntouched() throws Exception {
        // §2b: PATCH {} = no-op. JsonNullable.undefined() guard in CommentService.
        Long id = createComment();

        mockMvc.perform(patch("/tickets/{ticketId}/comments/{id}", testTicket.getId(), id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", is("initial comment")));
    }

    @Test
    void updateComment_blankContent_returns400() throws Exception {
        Long id = createComment();
        String body = """
                {
                  "content": "   "
                }
                """;

        mockMvc.perform(patch("/tickets/{ticketId}/comments/{id}", testTicket.getId(), id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateComment_unknownComment_returns404() throws Exception {
        String body = """
                {
                  "content": "updated"
                }
                """;

        mockMvc.perform(patch("/tickets/{ticketId}/comments/{id}", testTicket.getId(), 9999L)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteComment_unknownComment_returns404() throws Exception {
        mockMvc.perform(delete("/tickets/{ticketId}/comments/{id}", testTicket.getId(), 9999L))
                .andExpect(status().isNotFound());
    }

    @Test
    void optimisticLockHandler_emitsGenericMessage() {
        // Defends Fix 6: handler covers Ticket AND Comment, message must not name
        // a specific entity. Direct handler invocation — avoids needing two
        // concurrent MockMvc requests to race a real version conflict.
        var handler = new com.att.tdp.issueflow.common.exception.GlobalExceptionHandler();
        var response = handler.optimisticLock(
                new org.springframework.orm.ObjectOptimisticLockingFailureException("comments", 1L));

        org.junit.jupiter.api.Assertions.assertEquals(409, response.getStatusCode().value());
        org.hamcrest.MatcherAssert.assertThat(response.getBody().message(),
                containsString("modified concurrently"));
        org.hamcrest.MatcherAssert.assertThat(response.getBody().message(),
                not(containsString("ticket")));
    }
}
