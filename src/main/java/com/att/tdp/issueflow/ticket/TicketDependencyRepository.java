package com.att.tdp.issueflow.ticket;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Persistence for {@link TicketDependency}.
 * <p>
 * Three finders only — anything more elaborate (cycle traversal, reverse
 * lookups) is composed in {@link TicketDependencyService} from these
 * primitives rather than added here.
 */
public interface TicketDependencyRepository extends JpaRepository<TicketDependency, Long> {

    /** All dependencies for a single dependent ticket. */
    List<TicketDependency> findAllByTicketId(Long ticketId);

    /** Used by DELETE to load the join row before removal + audit. */
    Optional<TicketDependency> findByTicketIdAndBlockedById(Long ticketId, Long blockedById);

    /** Used by POST for the friendly-message duplicate pre-check. */
    boolean existsByTicketIdAndBlockedById(Long ticketId, Long blockedById);
}
