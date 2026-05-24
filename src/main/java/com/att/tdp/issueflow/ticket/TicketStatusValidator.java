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
 * The {@code blockerStatuses} parameter exists so dependency-aware callers
 * (see {@code TicketService.blockerStatuses}) can wire blocker info in
 * without changing this signature. Callers that don't care pass {@code []}.
 */
@Component
public class TicketStatusValidator {

    /**
     * @param current          the ticket's current status (loaded from DB)
     * @param next             the requested next status
     * @param blockerStatuses  statuses of every "blocked-by" ticket; pass {@code []}
     *                         when the caller doesn't track dependencies
     */
    public void validateTransition(
            TicketStatus current,
            TicketStatus next,
            List<TicketStatus> blockerStatuses
    ) {
        // Rule 1: never green-light any transition from DONE — even DONE → DONE.
        // DONE-immutable is also enforced upstream in TicketService.update,
        // but the validator stays airtight so every caller (incl. dependency
        // wiring) can rely on it as a single source of truth.
        if (current == TicketStatus.DONE) {
            throw new ConflictException("ticket is DONE and cannot be modified");
        }
        // Rule 2: same-status PATCH is a legal no-op.
        if (next == current) {
            return;
        }
        // Rule 3: forward-only.
        if (next.ordinal() < current.ordinal()) {
            throw new BadRequestException(
                    "status cannot move backward: " + current + " → " + next);
        }
        // Rule 4: DONE requires every blocker to be DONE. If the caller didn't
        // pass blocker info, the list is empty and the check vacuously passes.
        if (next == TicketStatus.DONE
                && !blockerStatuses.stream().allMatch(s -> s == TicketStatus.DONE)) {
            throw new ConflictException(
                    "cannot transition to DONE while blockers are unresolved");
        }
    }
}
