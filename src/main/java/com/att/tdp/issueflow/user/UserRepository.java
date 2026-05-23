package com.att.tdp.issueflow.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.List;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    Optional<User> findByUsernameIgnoreCase(String username);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    // Used by AdminSeeder to short-circuit when an ADMIN already exists.
    boolean existsByRole(Role role);

    @Query("""
    SELECT u FROM User u
    JOIN Mention m ON m.mentionedUserId = u.id
    WHERE m.commentId = :commentId
""")
    List<User> findUserMentionedInComment(Long commentId);

    List<User> findAllByRoleOrderByCreatedAtAsc(Role role);
}
