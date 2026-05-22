package com.att.tdp.issueflow.ticket;

import com.att.tdp.issueflow.common.exception.BadRequestException;
import com.att.tdp.issueflow.common.exception.ConflictException;
import com.att.tdp.issueflow.project.Project;
import com.att.tdp.issueflow.project.ProjectRepository;
import com.att.tdp.issueflow.ticket.dto.CreateTicketRequest;
import com.att.tdp.issueflow.ticket.dto.UpdateTicketRequest;
import com.att.tdp.issueflow.user.Role;
import com.att.tdp.issueflow.user.User;
import com.att.tdp.issueflow.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openapitools.jackson.nullable.JsonNullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
class TicketServiceTest {

    @Autowired TicketService ticketService;
    @Autowired TicketRepository ticketRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired UserRepository userRepository;
    @Autowired EntityManager em;

    Project testProject;

    @BeforeEach
    void setup() {
        ticketRepository.deleteAll();
        projectRepository.deleteAll();
        userRepository.deleteAll();

        User u = userRepository.save(User.builder().username("u").email("u@e.com").fullName("u").role(Role.DEVELOPER).passwordHash("h").build());
        testProject = projectRepository.save(Project.builder().name("p").description("d").ownerId(u.getId()).build());
    }

    @Test
    @Transactional
    void update_throws409_whenTicketIsDone() {
        var t = ticketService.create(new CreateTicketRequest("t", "d", TicketStatus.TODO, Priority.MEDIUM, TicketType.BUG, testProject.getId(), null, null));
        var ticket = ticketRepository.findById(t.id()).orElseThrow();
        ticket.setStatus(TicketStatus.DONE); // force to DONE directly
        ticketRepository.save(ticket);
        em.flush();
        em.clear();

        assertThrows(ConflictException.class, () -> ticketService.update(t.id(), new UpdateTicketRequest(JsonNullable.of("new"), JsonNullable.undefined(), JsonNullable.undefined(), JsonNullable.undefined(), JsonNullable.undefined(), JsonNullable.undefined())));
    }

    @Test
    @Transactional
    void update_throws409_whenPATCH_isEmpty_andTicketIsDone() {
        var t = ticketService.create(new CreateTicketRequest("t", "d", TicketStatus.TODO, Priority.MEDIUM, TicketType.BUG, testProject.getId(), null, null));
        var ticket = ticketRepository.findById(t.id()).orElseThrow();
        ticket.setStatus(TicketStatus.DONE); // force to DONE directly
        ticketRepository.save(ticket);
        em.flush();
        em.clear();

        assertThrows(ConflictException.class, () -> ticketService.update(t.id(), new UpdateTicketRequest(JsonNullable.undefined(), JsonNullable.undefined(), JsonNullable.undefined(), JsonNullable.undefined(), JsonNullable.undefined(), JsonNullable.undefined())));
    }

    @Test
    @Transactional
    void create_rejects_DONE_initialStatus() {
        assertThrows(BadRequestException.class, () -> ticketService.create(new CreateTicketRequest("t", "d", TicketStatus.DONE, Priority.MEDIUM, TicketType.BUG, testProject.getId(), null, null)));
    }

    @Test
    @Transactional
    void update_returns409_onOptimisticLockConflict() {
        var t = ticketService.create(new CreateTicketRequest("t", "d", TicketStatus.TODO, Priority.MEDIUM, TicketType.BUG, testProject.getId(), null, null));
        
        Ticket ticket = ticketRepository.findById(t.id()).orElseThrow();
        em.flush();
        
        // Simulate concurrent modification by incrementing the version in the database directly
        em.createNativeQuery("UPDATE tickets SET version = 99 WHERE id = :id")
          .setParameter("id", t.id())
          .executeUpdate();
        
        ticket.setStatus(TicketStatus.IN_PROGRESS);
        
        assertThrows(ObjectOptimisticLockingFailureException.class, () -> {
            ticketRepository.saveAndFlush(ticket);
        });
    }
}
