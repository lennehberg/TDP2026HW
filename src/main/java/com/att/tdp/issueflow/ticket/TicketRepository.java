package com.att.tdp.issueflow.ticket;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TicketRepository extends JpaRepository<Ticket, Long> {

    // Backs GET /tickets?projectId=:projectId. Phase 8 will add
    // @SQLRestriction("deleted_at IS NULL") on the Ticket entity so this
    // finder (and every standard JpaRepository method) auto-filters
    // soft-deleted rows. Don't add a non-deleted variant here — it would
    // become dead code in Phase 8.
    List<Ticket> findAllByProjectId(Long projectId);
}
