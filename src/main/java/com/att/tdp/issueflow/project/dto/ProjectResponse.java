package com.att.tdp.issueflow.project.dto;

public record ProjectResponse(
        Long id,
        String name,
        String description,
        Long ownerId
) {
}
