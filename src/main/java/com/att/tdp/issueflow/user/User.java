package com.att.tdp.issueflow.user;

import com.att.tdp.issueflow.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Application user. Username and email are independently unique. Optimistic
 * locking is deliberately omitted ({@code BaseEntity} carries no {@code @Version});
 * only Ticket and Comment opt in per the build plan.
 * <p>
 * Lombok {@code @Getter}/{@code @Setter} cover this class's own fields; the
 * inherited {@code id}/{@code createdAt}/{@code updatedAt} remain read-only via
 * {@code BaseEntity}'s {@code @Getter}.
 */
@Entity
@Table(name = "users",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_users_username", columnNames = "username"),
                @UniqueConstraint(name = "uk_users_email", columnNames = "email")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User extends BaseEntity {

    @Column(nullable = false, length = 64)
    private String username;

    @Column(nullable = false, length = 254)
    private String email;

    // bcrypt outputs ~60 chars; 72 leaves slack for future cost-factor bumps.
    @Column(name = "password_hash", nullable = false, length = 72)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 128)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Role role;
}
