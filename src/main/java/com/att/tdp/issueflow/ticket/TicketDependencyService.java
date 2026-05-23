package com.att.tdp.issueflow.ticket;

import com.att.tdp.issueflow.audit.AuditAction;
import com.att.tdp.issueflow.audit.AuditService;
import com.att.tdp.issueflow.audit.EntityType;
import com.att.tdp.issueflow.common.exception.BadRequestException;
import com.att.tdp.issueflow.common.exception.ConflictException;
import com.att.tdp.issueflow.ticket.dto.AddDependencyRequest;
import com.att.tdp.issueflow.ticket.dto.DependencyResponse;
import com.att.tdp.issueflow.common.exception.NotFoundException;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Manages "blocked by" relationships between tickets.
 * <p>
 * Phase 7 invariants the bodies below must honor (see PHASE_7_CHECKLIST §4):
 * <ul>
 *   <li>A ticket cannot depend on itself ({@code ticketId == blockedBy} → 400).</li>
 *   <li>Both tickets must exist; unknown dependent in the URL → 404, unknown
 *       blocker in the body → 400.</li>
 *   <li>Both tickets must be in the <em>same project</em> (PDF §3.2) → 400.</li>
 *   <li>Duplicate {@code (ticketId, blockedById)} pair → 409. DB unique
 *       constraint is the safety net for the race window.</li>
 *   <li>Cycle detection: BFS from {@code blockedBy} following "blocked by"
 *       edges; reject if {@code ticketId} is reachable → 409. Bounded walk
 *       to defend against pathological graphs. Document the choice (or its
 *       omission) in {@code run.md}.</li>
 *   <li>Audit: {@code AuditAction.CREATE}/{@code DELETE},
 *       {@code EntityType.DEPENDENCY}, {@code entityId = join row id}.</li>
 *   <li>Reads never audit.</li>
 * </ul>
 * The corresponding DONE-transition guard wiring lives in
 * {@link TicketService#applyStatus} — not here.
 */
@Service
@RequiredArgsConstructor
public class TicketDependencyService {

    private final TicketDependencyRepository dependencyRepository;
    private final TicketRepository ticketRepository;
    private final AuditService auditService;

    @Transactional
    public void add(Long ticketId, AddDependencyRequest req) {
        // check if blocking ticket and blocked ticket exists
        Ticket dependent = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new NotFoundException("ticket " + ticketId + " not found"));
        Ticket blocker = ticketRepository.findById(req.blockedBy())
                .orElseThrow(() -> new BadRequestException("ticket " + req.blockedBy() + " not found"));
        if (ticketId.equals(req.blockedBy())) {
            throw new BadRequestException("ticket cannot block itself");
        }
        if (dependencyRepository.existsByTicketIdAndBlockedById(ticketId, req.blockedBy())) {
            throw new ConflictException("dependency already exists");
        }
        if (!dependent.getProjectId().equals(blocker.getProjectId())) {
            throw new BadRequestException("Dependent and blocker must be in the same project");
        }

        TicketDependency saved =  dependencyRepository.save(
                TicketDependency
                        .builder()
                        .ticketId(ticketId)
                        .blockedById(req.blockedBy())
                        .build()
        );

        auditService.recordUserAction(AuditAction.CREATE, EntityType.DEPENDENCY, saved.getId());
    }

    @Transactional(readOnly = true)
    public List<DependencyResponse> listBlockers(Long ticketId) {
        return dependencyRepository.findAllByTicketId
                (ticketId).stream().map(this::toResponse).toList();
    }

    @Transactional
    public void remove(Long ticketId, Long blockerId) {
        TicketDependency tD = dependencyRepository.findByTicketIdAndBlockedById(ticketId, blockerId)
                .orElseThrow(() ->
                        new NotFoundException("dependency (ticket " + ticketId + ", blocker " + blockerId +") not found"));
        Long tDId = tD.getId();
        dependencyRepository.delete(tD);

        auditService.recordUserAction(AuditAction.DELETE, EntityType.DEPENDENCY, tDId);
    }

    private DependencyResponse toResponse(TicketDependency tD) {
        Ticket blocker =
                ticketRepository.findById(tD.getBlockedById())
                        .orElseThrow(() -> new
                                NotFoundException(
                                "blocker ticket " +
                                        tD.getBlockedById() + " not found"));
        return new
                DependencyResponse(blocker.getId(),
                blocker.getTitle(), blocker.getStatus());
    }
}
