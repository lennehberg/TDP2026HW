package com.att.tdp.issueflow.ticket;

import com.att.tdp.issueflow.ticket.dto.AddDependencyRequest;
import com.att.tdp.issueflow.ticket.dto.DependencyResponse;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Nested under tickets. README contract:
 * <ul>
 *   <li>{@code POST /tickets/{ticketId}/dependencies} body {@code {"blockedBy": 42}} → 200 (no body)</li>
 *   <li>{@code GET  /tickets/{ticketId}/dependencies} → list of {@code {id, title, status}} per blocker</li>
 *   <li>{@code DELETE /tickets/{ticketId}/dependencies/{blockerId}} → 200 (no body)</li>
 * </ul>
 * No single-dependency GET — not in the README.
 */
@RestController
@RequestMapping("/tickets/{ticketId}/dependencies")
@RequiredArgsConstructor
public class TicketDependencyController {

    private final TicketDependencyService dependencyService;

    @PostMapping
    public void add(@PathVariable Long ticketId,
                    @Valid @RequestBody AddDependencyRequest req) {
        dependencyService.add(ticketId, req);
    }

    @GetMapping
    public List<DependencyResponse> list(@PathVariable Long ticketId) {
        return dependencyService.listBlockers(ticketId);
    }

    @DeleteMapping("/{blockerId}")
    public void remove(@PathVariable Long ticketId,
                       @PathVariable Long blockerId) {
        dependencyService.remove(ticketId, blockerId);
    }
}
