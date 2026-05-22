package com.att.tdp.issueflow.ticket;

import com.att.tdp.issueflow.common.exception.BadRequestException;
import com.att.tdp.issueflow.common.exception.ConflictException;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Single source of truth for "is this status transition legal?".
 * <p>
 * Lifecycle order is the {@link TicketStatus} declaration order; comparison
 * uses {@link Enum#ordinal()}. Reordering the enum will silently break this
 * validator.
 * <p>
 * The third parameter ({@code blockerStatuses}) is empty in Phase 4 and gets
 * populated by Phase 7's dependency wiring without changing the signature.
 */
@Component
public class TicketStatusValidator {

    /**
     * @param current          the ticket's current status (loaded from DB)
     * @param next             the requested next status
     * @param blockerStatuses  statuses of every "blocked-by" ticket; empty in Phase 4
     */
    public void validateTransition(
            TicketStatus current,
            TicketStatus next,
            List<TicketStatus> blockerStatuses
    ) {
        // TODO Phase 4 — interesting logic; paused for user.
        // Rules (checklist order):
        //   1. current == DONE                    → ConflictException
        //   2. next == current                    → no-op, return
        //   3. ordinal(next) < ordinal(current)   → BadRequestException
        //   4. next == DONE && any blocker not DONE → ConflictException
        if (next == current) {
            return;
        }
        if (current == TicketStatus.DONE) {
            throw new ConflictException("Ticket can't be updated if status is already DONE");
        }
        if (next.ordinal() < current.ordinal()) {
            throw new BadRequestException("Ticket status cannot be moved to lower category");
        }
        if (next == TicketStatus.DONE && !blockerStatuses.stream().allMatch(s -> s == TicketStatus.DONE)) {
            throw new ConflictException("Ticket cannot be marked DONE because blocked by other ticket");
        }
    }
}
