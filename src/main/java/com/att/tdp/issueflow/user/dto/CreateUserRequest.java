package com.att.tdp.issueflow.user.dto;

import com.att.tdp.issueflow.user.Role;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The username pattern intentionally matches the mention regex from PDF §3.6
 * ({@code @([A-Za-z0-9_]+)}). Permitting characters here that the mention
 * parser cannot match would create unreachable mentions later.
 */
public record CreateUserRequest(
        @NotBlank
        @Size(min = 3, max = 64)
        @Pattern(regexp = "^[A-Za-z0-9_]+$", message = "must contain only letters, digits, or underscore")
        String username,

        @NotBlank
        @Email
        @Size(max = 254)
        String email,

        @NotBlank
        @Size(max = 128)
        String fullName,

        @NotNull
        Role role,

        @NotBlank
        @Size(min = 8, max = 128)
        String password
) {
}
