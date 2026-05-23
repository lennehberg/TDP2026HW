package com.att.tdp.issueflow.common.dto;

import java.util.List;

/**
 * Generic { data, total, page } envelope for paginated endpoints.
 * <p>
 * The mentions endpoint (§3.6) specifies this exact shape, and the audit-log
 * listing reuses it. {@code page} is 1-indexed to match the README example
 * ({@code "page": 1}). {@code total} is the unpaginated row count, not the
 * page count — clients derive page count themselves.
 */
public record PageResponse<T>(
        List<T> data,
        long total,
        int page
) {
}
