package com.att.tdp.issueflow.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Deny-list entry for an explicitly revoked JWT. Keyed by the token's
 * {@code jti} claim so the filter's lookup is a single primary-key probe.
 * <p>
 * Deliberately does NOT extend {@link com.att.tdp.issueflow.common.BaseEntity}:
 * we don't want auto-generated ids (the jti <i>is</i> the id) and the auditing
 * timestamps would be redundant noise. {@code expiresAt} mirrors the token's
 * {@code exp} so a future janitor can prune entries that no longer matter.
 */
@Entity
@Table(name = "revoked_tokens")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RevokedToken {

    // UUID string — 36 chars including hyphens, leave a little slack.
    @Id
    @Column(length = 64)
    private String jti;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
}