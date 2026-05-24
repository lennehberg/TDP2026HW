package com.att.tdp.issueflow.ticket;

import com.att.tdp.issueflow.audit.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class EscalationService {

    private final TicketRepository ticketRepository;
    private final AuditService auditService;

    @Scheduled(fixedRate = 60000)
    @Transactional
    public void runEscalation() {
        Instant now = Instant.now();

        // Candidates: not DONE, past dueDate, and not yet at the terminal
        // state (CRITICAL + overdue). The query filters out the terminal
        // case so we don't re-audit no-ops every tick.
        List<Ticket> candidates = ticketRepository.findEscalationCandidates(now);

        for (Ticket t: candidates) {
            escalate(t, now);
        }
    }

    /**
     * Apply one escalation step per §3.7:
     * <ul>
     *   <li>{@code priority < CRITICAL} → bump one level. {@code isOverdue}
     *       stays as-is (typically still false).</li>
     *   <li>{@code priority == CRITICAL} &amp;&amp; not yet overdue → flip
     *       {@code isOverdue=true}. Terminal state.</li>
     *   <li>{@code priority == CRITICAL} &amp;&amp; already overdue → no-op.
     *       The escalation-candidate query also filters this case out, so
     *       this branch is defensive against a races between the query and
     *       the update.</li>
     * </ul>
     * Never touches {@code status} — that invariant is owned by the user-driven
     * PATCH path. The {@code ordinal()+1} bump is safe because the bump branch
     * only fires when priority is strictly below CRITICAL (the top of the enum).
     */
    private void escalate(Ticket t, Instant now) {
        if (t.getPriority().ordinal() < Priority.CRITICAL.ordinal()) {
            Priority next = Priority.values()[t.getPriority().ordinal() + 1];
            t.setPriority(next);
            recordEscalation(t, now);
        } else if (!t.isOverdue()) {
            t.setOverdue(true);
            recordEscalation(t, now);
        }
        // Else: CRITICAL + already overdue → terminal state, idempotent no-op.
    }

    private void recordEscalation(Ticket t, Instant now) {
        t.setLastEscalatedAt(now);
        auditService.recordSystemAction(
                AuditAction.AUTO_ESCALATE,
                EntityType.TICKET,
                t.getId()
        );
    }

}
