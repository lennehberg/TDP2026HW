package com.att.tdp.issueflow.auth;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The filter only calls {@code existsById(jti)} — a single PK probe per request.
 * No custom queries needed; the inherited {@code save} and {@code existsById}
 * cover both the logout write path and the auth read path.
 */
public interface RevokedTokenRepository extends JpaRepository<RevokedToken, String> {
}