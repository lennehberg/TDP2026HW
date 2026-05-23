package com.att.tdp.issueflow.project.dto;

public record WorkloadResponse(
        Long userId,
        String username,
        long openTicketCount
) {
}
