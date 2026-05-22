package com.att.tdp.issueflow.project.dto;

import jakarta.validation.constraints.Size;

public record UpdateProjectRequest(
        @Size(max = 64)
        String name,

        @Size(max = 1024)
        String description
) {
}
