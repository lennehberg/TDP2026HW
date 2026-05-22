package com.att.tdp.issueflow.user.dto;

import com.att.tdp.issueflow.user.Role;

/**
 * Mirrors the exact response shape in the README §APIs/Users table.
 * Password and audit timestamps are deliberately omitted.
 */
public record UserResponse(
        Long id,
        String username,
        String email,
        String fullName,
        Role role
) {
}
