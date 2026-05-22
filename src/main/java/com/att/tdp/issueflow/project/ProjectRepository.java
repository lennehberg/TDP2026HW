package com.att.tdp.issueflow.project;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectRepository extends JpaRepository<Project, Long> {
    // Custom finders intentionally omitted — Phase 8 adds @SQLRestriction on
    // the entity so standard JpaRepository methods auto-filter soft-deleted
    // rows. Phase 8 also adds the ADMIN-only "list deleted" / "restore"
    // queries that bypass the restriction.
}
