package com.att.tdp.issueflow.project;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    /**
     * Lists soft-deleted projects. {@code @SQLRestriction} on the entity
     * is appended to every JPQL query, so the only way to bypass it is a
     * native query. Powers {@code GET /projects/deleted}.
     */
    @Query(value = "SELECT * FROM projects WHERE deleted_at IS NOT NULL",
            nativeQuery = true)
    List<Project> findAllDeleted();

    /**
     * Loads a project regardless of {@code deleted_at} state. Used by
     * {@code restore(...)} since the standard {@code findById} can no
     * longer see soft-deleted rows. Also serves as an "is this id known
     * at all?" check that ignores the soft-delete filter.
     */
    @Query(value = "SELECT * FROM projects WHERE id = :id",
            nativeQuery = true)
    Optional<Project> findByIdIncludingDeleted(@Param("id") Long id);
}
