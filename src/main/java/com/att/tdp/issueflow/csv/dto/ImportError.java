package com.att.tdp.issueflow.csv.dto;

/**
 * One per-row failure in a CSV import. {@code row} is 1-indexed against data
 * rows only (the header is not counted), so {@code row=1} is the first ticket
 * row in the file — matches what a user sees when they look at their CSV.
 */
public record ImportError(
        int row,
        String message
) {
}
