package com.att.tdp.issueflow.csv.dto;

import java.util.List;

public record ImportSummary(
        int created,
        int failed,
        List<ImportError> errors
) {
}
