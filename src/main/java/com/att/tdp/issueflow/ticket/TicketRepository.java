package com.att.tdp.issueflow.ticket;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TicketRepository extends JpaRepository<Ticket, Long> {

    // Backs GET /tickets?projectId=:projectId. Phase 8 will add
    // @SQLRestriction("deleted_at IS NULL") on the Ticket entity so this
    // finder (and every standard JpaRepository method) auto-filters
    // soft-deleted rows. Don't add a non-deleted variant here — it would
    // become dead code in Phase 8.
    List<Ticket> findAllByProjectId(Long projectId);

    /**
     * Bulk-loads just the {@code status} column for the given ticket ids.
     * Used by the DONE-transition guard so the blocker check doesn't pay
     * for full entity hydration. An empty {@code ids} list yields an empty
     * result (Hibernate handles the empty IN gracefully — no syntax error).
     */
    @Query("SELECT t.status FROM Ticket t WHERE t.id IN :ids")
    List<TicketStatus> findStatusesByIdIn(@Param("ids") List<Long> ids);
}
