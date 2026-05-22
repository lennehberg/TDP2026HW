package com.att.tdp.issueflow.user;

/**
 * User role per PDF §2.1. The auto-assignment rule (§3.8) targets
 * DEVELOPER users only; admin-only endpoints (§3.5) gate on ADMIN.
 */
public enum Role {
    ADMIN,
    DEVELOPER
}
