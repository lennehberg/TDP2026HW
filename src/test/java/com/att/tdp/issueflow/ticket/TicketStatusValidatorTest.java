package com.att.tdp.issueflow.ticket;

import com.att.tdp.issueflow.common.exception.BadRequestException;
import com.att.tdp.issueflow.common.exception.ConflictException;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TicketStatusValidatorTest {
    TicketStatusValidator validator = new TicketStatusValidator();

    @Test
    void validateTransition_allowsForwardOneStep() {
        assertDoesNotThrow(() -> validator.validateTransition(TicketStatus.TODO, TicketStatus.IN_PROGRESS, List.of()));
    }

    @Test
    void validateTransition_allowsForwardMultipleSteps() {
        assertDoesNotThrow(() -> validator.validateTransition(TicketStatus.TODO, TicketStatus.IN_REVIEW, List.of()));
    }

    @Test
    void validateTransition_rejectsBackward() {
        assertThrows(BadRequestException.class, () -> validator.validateTransition(TicketStatus.IN_PROGRESS, TicketStatus.TODO, List.of()));
    }

    @Test
    void validateTransition_allowsSameStatus() {
        assertDoesNotThrow(() -> validator.validateTransition(TicketStatus.IN_PROGRESS, TicketStatus.IN_PROGRESS, List.of()));
    }

    @Test
    void validateTransition_rejectsAnyTransitionFromDone() {
        assertThrows(ConflictException.class, () -> validator.validateTransition(TicketStatus.DONE, TicketStatus.TODO, List.of()));
    }

    @Test
    void validateTransition_rejectsDoneWhenBlockersOpen() {
        assertThrows(ConflictException.class, () -> validator.validateTransition(TicketStatus.IN_REVIEW, TicketStatus.DONE, List.of(TicketStatus.IN_PROGRESS)));
    }

    @Test
    void validateTransition_rejectsDoneToDone() {
        // Rule 1 must fire before Rule 2 — DONE-immutable trumps same-status no-op.
        // Phase 7's dependency wiring may call validateTransition(DONE, DONE, ...)
        // and must not get a green light.
        assertThrows(ConflictException.class,
                () -> validator.validateTransition(TicketStatus.DONE, TicketStatus.DONE, List.of()));
    }
}
