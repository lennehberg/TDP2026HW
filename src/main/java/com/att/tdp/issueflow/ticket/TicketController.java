package com.att.tdp.issueflow.ticket;

import com.att.tdp.issueflow.ticket.dto.CreateTicketRequest;
import com.att.tdp.issueflow.ticket.dto.TicketResponse;
import com.att.tdp.issueflow.ticket.dto.UpdateTicketRequest;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/tickets")
@RequiredArgsConstructor
public class TicketController {

    private final TicketService ticketService;

    @PostMapping
    public TicketResponse create(@Valid @RequestBody CreateTicketRequest req) {
        return ticketService.create(req);
    }

    @GetMapping
    public List<TicketResponse> listByProject(@RequestParam Long projectId) {
        return ticketService.listByProject(projectId);
    }

    // Declared before /{ticketId} for readability (handler-mapping precedence
    // would prefer the more specific path either way). projectId is required
    // — mirrors GET /tickets; the Spring binder yields 400 if absent.
    @GetMapping("/deleted")
    @PreAuthorize("hasRole('ADMIN')")
    public List<TicketResponse> listDeleted(@RequestParam Long projectId) {
        return ticketService.listDeletedByProject(projectId);
    }

    @GetMapping("/{ticketId}")
    public TicketResponse getById(@PathVariable Long ticketId) {
        return ticketService.getById(ticketId);
    }

    @PostMapping("/{ticketId}/restore")
    @PreAuthorize("hasRole('ADMIN')")
    public TicketResponse restore(@PathVariable Long ticketId) {
        return ticketService.restore(ticketId);
    }

    @PatchMapping("/{ticketId}")
    public TicketResponse update(@PathVariable Long ticketId, @Valid @RequestBody UpdateTicketRequest req) {
        return ticketService.update(ticketId, req);
    }

    @DeleteMapping("/{ticketId}")
    public void delete(@PathVariable Long ticketId) {
        ticketService.delete(ticketId);
    }
}
