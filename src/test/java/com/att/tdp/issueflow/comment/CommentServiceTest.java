package com.att.tdp.issueflow.comment;

import com.att.tdp.issueflow.comment.dto.CreateCommentRequest;
import com.att.tdp.issueflow.comment.dto.UpdateCommentRequest;
import com.att.tdp.issueflow.common.exception.NotFoundException;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openapitools.jackson.nullable.JsonNullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;

import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
class CommentServiceTest {

    @Autowired CommentService commentService;
    @Autowired CommentRepository commentRepository;
    @Autowired TicketRepository ticketRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired UserRepository userRepository;
    @Autowired EntityManager em;

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

    @Test
    @Transactional
    void update_returns409_onOptimisticLockConflict() {
        var c = commentService.create(testTicket.getId(), new CreateCommentRequest(testUser.getId(), "initial"));
        Comment comment = commentRepository.findById(c.id()).orElseThrow();
        em.flush();
        
        // Simulate concurrent modification by incrementing the version in the database directly
        em.createNativeQuery("UPDATE comments SET version = 99 WHERE id = :id")
          .setParameter("id", c.id())
          .executeUpdate();
        
        comment.setContent("concurrent update");
        
        assertThrows(ObjectOptimisticLockingFailureException.class, () -> {
            commentRepository.saveAndFlush(comment);
        });
    }

    @Test
    @Transactional
    void create_throws404_whenTicketMissing() {
        assertThrows(NotFoundException.class, () -> {
            commentService.create(9999L, new CreateCommentRequest(testUser.getId(), "hello"));
        });
    }

    @Test
    @Transactional
    void update_throws404_whenCommentDoesNotBelongToTicket() {
        var c = commentService.create(testTicket.getId(), new CreateCommentRequest(testUser.getId(), "initial"));
        assertThrows(NotFoundException.class, () -> {
            commentService.update(9999L, c.id(), new UpdateCommentRequest(JsonNullable.of("updated")));
        });
    }

    @Test
    @Transactional
    void delete_throws404_whenCommentDoesNotBelongToTicket() {
        var c = commentService.create(testTicket.getId(), new CreateCommentRequest(testUser.getId(), "hello"));
        assertThrows(NotFoundException.class, () -> {
            commentService.delete(9999L, c.id());
        });
    }
}
