package com.att.tdp.issueflow.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateProjectRequest(
        @NotBlank
        @Size(max = 64)
        String name,

        @NotBlank
        @Size(max = 1024)
        String description,

        @NotNull
        Long ownerId
) {
}
