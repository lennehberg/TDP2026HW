package com.att.tdp.issueflow.ticket;

import com.att.tdp.issueflow.ticket.dto.CreateTicketRequest;
import com.att.tdp.issueflow.ticket.dto.TicketResponse;
import com.att.tdp.issueflow.ticket.dto.UpdateTicketRequest;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

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

    @GetMapping("/{ticketId}")
    public TicketResponse getById(@PathVariable Long ticketId) {
        return ticketService.getById(ticketId);
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
