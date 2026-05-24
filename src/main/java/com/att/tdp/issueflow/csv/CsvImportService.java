package com.att.tdp.issueflow.csv;

import com.att.tdp.issueflow.common.exception.BadRequestException;
import com.att.tdp.issueflow.csv.dto.ImportError;
import com.att.tdp.issueflow.csv.dto.ImportSummary;
import com.att.tdp.issueflow.project.ProjectRepository;
import com.att.tdp.issueflow.ticket.Priority;
import com.att.tdp.issueflow.ticket.TicketService;
import com.att.tdp.issueflow.ticket.TicketStatus;
import com.att.tdp.issueflow.ticket.TicketType;
import com.att.tdp.issueflow.ticket.dto.CreateTicketRequest;

import lombok.RequiredArgsConstructor;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * CSV import with partial-success semantics.
 * <p>
 * Each row is converted to a {@link CreateTicketRequest} and handed to
 * {@link TicketService#create(CreateTicketRequest)}, which already encapsulates
 * the full create path (validation, audit, and auto-assign).
 * <p>
 * <b>Auto-assign on import is intentional.</b> Rows with a blank
 * {@code assigneeId} column ride the same code path as
 * {@code POST /tickets} with {@code assigneeId == null} — they get
 * auto-assigned to the DEVELOPER in the project with the fewest non-DONE
 * tickets (tie-break: oldest {@code createdAt}) and produce an
 * {@code AUTO_ASSIGN} audit row. The spec doesn't carve out CSV from §3.8;
 * if you want to import unassigned tickets, that's the bulk equivalent of
 * "create N tickets without an assignee," which is exactly the trigger
 * condition for auto-assignment. The audit log distinguishes the two
 * provenance modes clearly (USER/CREATE then SYSTEM/AUTO_ASSIGN).
 * <p>
 * <b>Transaction strategy.</b> This service is intentionally NOT
 * {@code @Transactional}: each call into {@code TicketService.create} starts
 * its own transaction (REQUIRED), so a failure on row N rolls back only row
 * N's insert and audit row — rows 1..N-1 stay committed and rows N+1.. keep
 * going. A wrapping transaction here would mark the whole import rollback-only
 * on the first failure, which violates the "partial success" rule from the
 * BUILD_PLAN cross-cutting reminders.
 * <p>
 * <b>Header.</b> The CSV must contain the same header row produced by
 * {@link CsvExportService} (minus {@code id}, which is ignored if present).
 * Missing columns are flagged per-row, not per-batch — same partial-success
 * philosophy.
 */
@Service
@RequiredArgsConstructor
public class CsvImportService {

    private final TicketService ticketService;
    private final ProjectRepository projectRepository;

    // UTF-8 BOM is U+FEFF. Excel prepends this when saving as "CSV UTF-8";
    // commons-csv treats it as part of the first header name, so without
    // stripping, isMapped("title") returns false and every row reports
    // "missing column: title". We strip it once after charset decoding.
    private static final char BOM = '﻿';

    public ImportSummary importTickets(MultipartFile file, Long projectId) throws IOException {
        // Fail fast on bad projectId — otherwise every row in a large CSV
        // would report the same "project not found" error.
        if (!projectRepository.existsById(projectId)) {
            throw new BadRequestException("project " + projectId + " not found");
        }

        List<ImportError> errors = new ArrayList<>();
        int created = 0;
        int failed = 0;

        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setTrim(true)
                .build();

        try (Reader reader = bomStrippingReader(file);
             CSVParser parser = new CSVParser(reader, format)) {

            int dataRow = 0;
            for (CSVRecord record : parser) {
                dataRow++;
                try {
                    CreateTicketRequest req = toRequest(record, projectId);
                    ticketService.create(req);
                    created++;
                } catch (RuntimeException ex) {
                    failed++;
                    errors.add(new ImportError(dataRow, ex.getMessage()));
                }
            }
        }

        return new ImportSummary(created, failed, errors);
    }

    /**
     * Wraps the upload as a UTF-8 reader and consumes the leading BOM if
     * present. We use a {@link java.io.PushbackReader} so a missing BOM
     * leaves the stream untouched — peek one char, push back if it's not BOM.
     */
    private static Reader bomStrippingReader(MultipartFile file) throws IOException {
        java.io.PushbackReader reader = new java.io.PushbackReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8));
        int first = reader.read();
        if (first != -1 && first != BOM) {
            reader.unread(first);
        }
        return reader;
    }

    /**
     * Parse a single CSV record into a {@link CreateTicketRequest}. Throws
     * {@link IllegalArgumentException} for anything the per-row catch should
     * report as a partial-success error — missing required columns, blank
     * values, unparseable enum literals. The service-layer checks in
     * {@code TicketService.create} (assignee/project lookup) round out the
     * remaining failure modes.
     */
    private CreateTicketRequest toRequest(CSVRecord record, Long projectId) {
        String title       = requireNonBlank(record, "title");
        String description = requireNonBlank(record, "description");
        TicketStatus status = parseEnum(record, "status", TicketStatus.class);
        Priority priority   = parseEnum(record, "priority", Priority.class);
        TicketType type     = parseEnum(record, "type", TicketType.class);
        Long assigneeId     = parseOptionalLong(record, "assigneeId");

        // dueDate is intentionally absent from the export contract and so
        // absent from import too — pass null to TicketService.create.
        return new CreateTicketRequest(
                title, description, status, priority, type, projectId, assigneeId, null);
    }

    private static String requireNonBlank(CSVRecord record, String column) {
        if (!record.isMapped(column)) {
            throw new IllegalArgumentException("missing column: " + column);
        }
        String value = record.get(column);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(column + " is required");
        }
        return value;
    }

    private static <E extends Enum<E>> E parseEnum(CSVRecord record, String column, Class<E> type) {
        String raw = requireNonBlank(record, column);
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "invalid " + column + ": " + raw);
        }
    }

    private static Long parseOptionalLong(CSVRecord record, String column) {
        if (!record.isMapped(column)) {
            return null;
        }
        String raw = record.get(column);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(
                    "invalid " + column + ": " + raw);
        }
    }
}
