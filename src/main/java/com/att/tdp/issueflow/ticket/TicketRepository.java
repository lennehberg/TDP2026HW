package com.att.tdp.issueflow.ticket;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TicketRepository extends JpaRepository<Ticket, Long> {

    // Backs GET /tickets?projectId=:projectId. Phase 8 added
    // @SQLRestriction("deleted_at IS NULL") on the Ticket entity so this
    // finder (and every standard JpaRepository method) auto-filters
    // soft-deleted rows.
    List<Ticket> findAllByProjectId(Long projectId);

    /**
     * Bulk-loads just the {@code status} column for the given ticket ids.
     * Used by the DONE-transition guard so the blocker check doesn't pay
     * for full entity hydration. An empty {@code ids} list yields an empty
     * result (Hibernate handles the empty IN gracefully — no syntax error).
     * <p>
     * Phase 8 note: this is JPQL, so {@code @SQLRestriction} applies —
     * soft-deleted blocker tickets are silently dropped from the result,
     * which effectively unblocks their dependents. Documented in
     * {@code run.md} as deliberate.
     */
    @Query("SELECT t.status FROM Ticket t WHERE t.id IN :ids")
    List<TicketStatus> findStatusesByIdIn(@Param("ids") List<Long> ids);

    /**
     * Lists soft-deleted tickets for one project. Native query bypasses
     * {@code @SQLRestriction}. Powers {@code GET /tickets/deleted?projectId=...}.
     */
    @Query(value = "SELECT * FROM tickets WHERE deleted_at IS NOT NULL AND project_id = :projectId",
            nativeQuery = true)
    List<Ticket> findAllDeletedByProjectId(@Param("projectId") Long projectId);

    /**
     * Loads a ticket regardless of {@code deleted_at} state. Used by
     * {@code restore(...)} since {@code findById} can no longer see
     * soft-deleted rows.
     */
    @Query(value = "SELECT * FROM tickets WHERE id = :id",
            nativeQuery = true)
    Optional<Ticket> findByIdIncludingDeleted(@Param("id") Long id);

    @Query("""
    SELECT COUNT(t) FROM Ticket t
    WHERE t.projectId = :projectId
    AND t.assigneeId = :userId
    AND t.status != com.att.tdp.issueflow.ticket.TicketStatus.DONE
""")
    long countActiveTicketsForUserInProject(Long projectId, Long userId);

    @Query("""
    SELECT t FROM Ticket t
    WHERE t.status != com.att.tdp.issueflow.ticket.TicketStatus.DONE
    AND t.dueDate < :now
    AND (t.isOverdue = false OR t.priority < com.att.tdp.issueflow.ticket.Priority.CRITICAL)
""")
    List<Ticket> findEscalationCandidates(Instant now);
}
