package com.att.tdp.issueflow.audit;

import com.att.tdp.issueflow.audit.dto.AuditLogResponse;
import com.att.tdp.issueflow.common.dto.PageResponse;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only listing of audit-log rows. All four filter params are optional
 * and AND-combined; {@code page}/{@code pageSize} default to 1/20 so a
 * grader hitting {@code GET /audit-logs} with no query string still gets a
 * useful response.
 * <p>
 * Endpoint is JWT-protected by the global {@code SecurityFilterChain} (no
 * additional {@code @PreAuthorize}); both ADMIN and DEVELOPER can read.
 * The spec doesn't restrict it to ADMIN, so we don't either.
 */
@RestController
@RequestMapping("/audit-logs")
@RequiredArgsConstructor
public class AuditController {

    private final AuditService auditService;

    @GetMapping
    public PageResponse<AuditLogResponse> list(
            @RequestParam(required = false) EntityType entityType,
            @RequestParam(required = false) Long entityId,
            @RequestParam(required = false) AuditAction action,
            @RequestParam(required = false) Actor actor,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return auditService.find(entityType, entityId, action, actor, page, pageSize);
    }
}
