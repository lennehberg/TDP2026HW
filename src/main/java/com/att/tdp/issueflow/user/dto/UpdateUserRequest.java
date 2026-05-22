package com.att.tdp.issueflow.user.dto;

import com.att.tdp.issueflow.user.Role;

import jakarta.validation.constraints.Size;

/**
 * Both fields are optional; a null value means "leave the existing value alone."
 * Username, email, and password are deliberately not exposed here — changing
 * them would re-run uniqueness checks and is outside the README contract for
 * this endpoint.
 */
public record UpdateUserRequest(
        @Size(max = 128) String fullName,
        Role role
) {
}
