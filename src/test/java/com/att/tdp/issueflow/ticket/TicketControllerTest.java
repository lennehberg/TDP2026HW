package com.att.tdp.issueflow.ticket;

import com.att.tdp.issueflow.TestSecurityConfig;
import com.att.tdp.issueflow.project.Project;
import com.att.tdp.issueflow.project.ProjectRepository;
import com.att.tdp.issueflow.ticket.dto.CreateTicketRequest;
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

import jakarta.persistence.EntityManager;
import java.time.Instant;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
@Transactional
class TicketControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TicketRepository ticketRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired UserRepository userRepository;
    @Autowired TicketService ticketService;
    @Autowired EntityManager em;

    Project testProject;
    User testUser;

    @BeforeEach
    void setup() {
        ticketRepository.deleteAll();
        projectRepository.deleteAll();
        userRepository.deleteAll();

        testUser = userRepository.save(User.builder().username("u").email("u@e.com").fullName("u").role(Role.DEVELOPER).passwordHash("h").build());
        testProject = projectRepository.save(Project.builder().name("p").description("d").ownerId(testUser.getId()).build());
    }

    private Long createTicket() throws Exception {
        var req = new CreateTicketRequest("title", "desc", TicketStatus.TODO, Priority.MEDIUM, TicketType.BUG, testProject.getId(), testUser.getId(), null);
        return ticketService.create(req).id();
    }

    // 9d. PATCH partial semantics
    @Test
    void update_fieldAbsent_leavesValueUntouched() throws Exception {
        Long id = createTicket();
        mockMvc.perform(patch("/tickets/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        { "title": "new title" }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title", is("new title")))
                .andExpect(jsonPath("$.assigneeId", is(testUser.getId().intValue())));
    }

    @Test
    void update_fieldPresentNull_unassignsAssignee() throws Exception {
        Long id = createTicket();
        mockMvc.perform(patch("/tickets/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        { "assigneeId": null }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assigneeId", nullValue()));
    }

    @Test
    void update_fieldPresentNull_clearsDueDate() throws Exception {
        Long id = ticketService.create(new CreateTicketRequest("t", "d", TicketStatus.TODO, Priority.LOW, TicketType.BUG, testProject.getId(), null, Instant.now())).id();
        mockMvc.perform(patch("/tickets/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        { "dueDate": null }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dueDate", nullValue()));
    }

    @Test
    void update_title_null_returns400() throws Exception {
        Long id = createTicket();
        mockMvc.perform(patch("/tickets/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        { "title": null }
                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void update_title_blank_returns400() throws Exception {
        Long id = createTicket();
        mockMvc.perform(patch("/tickets/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        { "title": "" }
                        """))
                .andExpect(status().isBadRequest());
    }

    // 9e. CRUD smoke
    @Test
    void create_returns200_andEntityShape() throws Exception {
        String body = """
                {
                  "title": "Fix login bug",
                  "description": "...",
                  "status": "TODO",
                  "priority": "HIGH",
                  "type": "BUG",
                  "projectId": %d,
                  "assigneeId": %d,
                  "dueDate": "2026-04-01T00:00:00Z"
                }
                """.formatted(testProject.getId(), testUser.getId());

        mockMvc.perform(post("/tickets")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.isOverdue", is(false)))
                .andExpect(jsonPath("$.title", is("Fix login bug")));
    }

    @Test
    void get_returnsNotFound_whenMissing() throws Exception {
        mockMvc.perform(get("/tickets/9999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void list_returnsTicketsForProject() throws Exception {
        Project testProject2 = projectRepository.save(Project.builder().name("p2").description("d").ownerId(testUser.getId()).build());
        ticketService.create(new CreateTicketRequest("t1", "d", TicketStatus.TODO, Priority.MEDIUM, TicketType.BUG, testProject.getId(), null, null));
        ticketService.create(new CreateTicketRequest("t2", "d", TicketStatus.TODO, Priority.MEDIUM, TicketType.BUG, testProject.getId(), null, null));
        ticketService.create(new CreateTicketRequest("t3", "d", TicketStatus.TODO, Priority.MEDIUM, TicketType.BUG, testProject2.getId(), null, null));

        mockMvc.perform(get("/tickets?projectId=" + testProject.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", is(2)));
    }

    @Test
    void delete_softDeletesOnly() throws Exception {
        Long id = createTicket();
        mockMvc.perform(delete("/tickets/{id}", id))
                .andExpect(status().isOk());
                
        // Ensure it's soft deleted
        Ticket t = em.find(Ticket.class, id);
        assertNotNull(t);
        assertNotNull(t.getDeletedAt());
    }

    // 9f. Validation surface
    @Test
    void create_missingTitle_returns400() throws Exception {
        String body = """
                {
                  "description": "...",
                  "status": "TODO",
                  "priority": "HIGH",
                  "type": "BUG",
                  "projectId": %d
                }
                """.formatted(testProject.getId());

        mockMvc.perform(post("/tickets")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_invalidStatusEnum_returns400() throws Exception {
        String body = """
                {
                  "title": "t",
                  "description": "...",
                  "status": "WAITING",
                  "priority": "HIGH",
                  "type": "BUG",
                  "projectId": %d
                }
                """.formatted(testProject.getId());

        mockMvc.perform(post("/tickets")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_assigneeNotFound_returns400() throws Exception {
        String body = """
                {
                  "title": "t",
                  "description": "...",
                  "status": "TODO",
                  "priority": "HIGH",
                  "type": "BUG",
                  "projectId": %d,
                  "assigneeId": 9999
                }
                """.formatted(testProject.getId());

        mockMvc.perform(post("/tickets")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_projectNotFound_returns400() throws Exception {
        String body = """
                {
                  "title": "t",
                  "description": "...",
                  "status": "TODO",
                  "priority": "HIGH",
                  "type": "BUG",
                  "projectId": 9999
                }
                """;

        mockMvc.perform(post("/tickets")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isBadRequest());
    }
}
