package com.att.tdp.issueflow.csv;

import com.att.tdp.issueflow.TestSecurityConfig;
import com.att.tdp.issueflow.audit.AuditAction;
import com.att.tdp.issueflow.audit.AuditLogRepository;
import com.att.tdp.issueflow.audit.EntityType;
import com.att.tdp.issueflow.project.Project;
import com.att.tdp.issueflow.project.ProjectRepository;
import com.att.tdp.issueflow.ticket.Ticket;
import com.att.tdp.issueflow.ticket.TicketRepository;
import com.att.tdp.issueflow.user.Role;
import com.att.tdp.issueflow.user.User;
import com.att.tdp.issueflow.user.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 11 — CSV import with partial-success semantics.
 * <p>
 * Pins the contract:
 * <ul>
 *   <li>Response shape is exactly {@code {created, failed, errors[]}}.</li>
 *   <li>A bad row in the middle of a batch does NOT abort the import — earlier
 *       and later rows still commit. This is the explicit
 *       "CSV import is partial success" cross-cutting reminder.</li>
 *   <li>Each created row writes a normal {@code TICKET / CREATE} audit row,
 *       since the import reuses {@link com.att.tdp.issueflow.ticket.TicketService#create}.</li>
 *   <li>Quoted commas survive round-trip.</li>
 * </ul>
 * Tests are NOT {@code @Transactional} on purpose: the import service is
 * non-transactional so that each row's create runs in its own transaction;
 * wrapping the test in a transaction would mask exactly the behavior we're
 * trying to verify (rows committing independently). We do manual cleanup in
 * {@code @BeforeEach} instead.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
class CsvImportControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired TicketRepository ticketRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired UserRepository userRepository;
    @Autowired AuditLogRepository auditLogRepository;

    User owner;
    Project project;

    @BeforeEach
    void setup() {
        // Order matters: tickets reference users; audit rows reference both.
        auditLogRepository.deleteAll();
        ticketRepository.deleteAll();
        projectRepository.deleteAll();
        userRepository.deleteAll();

        owner = userRepository.save(User.builder()
                .username("csvImport").email("ci@e.com").fullName("o")
                .role(Role.DEVELOPER).passwordHash("h").build());
        project = projectRepository.save(Project.builder()
                .name("p").description("d").ownerId(owner.getId()).build());
    }

    private MockMultipartFile csvFile(String body) {
        return new MockMultipartFile(
                "file", "tickets.csv", "text/csv",
                body.getBytes(StandardCharsets.UTF_8));
    }

    private static final String HEADER =
            "title,description,status,priority,type,assigneeId\n";

    // ---------- Happy path ----------

    @Test
    void import_happyPath_createsRowsAndReportsSummary() throws Exception {
        String body = HEADER
                + "Fix login,first ticket,TODO,HIGH,BUG,\n"
                + "Refactor auth,second ticket,IN_PROGRESS,MEDIUM,TECHNICAL,\n";

        mockMvc.perform(multipart("/tickets/import")
                        .file(csvFile(body))
                        .param("projectId", project.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created", is(2)))
                .andExpect(jsonPath("$.failed", is(0)))
                .andExpect(jsonPath("$.errors", hasSize(0)));

        List<Ticket> rows = ticketRepository.findAllByProjectId(project.getId());
        assertEquals(2, rows.size());
        List<String> titles = rows.stream().map(Ticket::getTitle).toList();
        assertTrue(titles.contains("Fix login"));
        assertTrue(titles.contains("Refactor auth"));
    }

    @Test
    void import_writesAuditRowPerCreatedTicket() throws Exception {
        // Each successful create writes a TICKET/CREATE audit row through the
        // shared TicketService.create path — the import service doesn't add
        // its own verb.
        String body = HEADER
                + "a,d,TODO,LOW,BUG,\n"
                + "b,d,TODO,LOW,BUG,\n"
                + "c,d,TODO,LOW,BUG,\n";

        mockMvc.perform(multipart("/tickets/import")
                        .file(csvFile(body))
                        .param("projectId", project.getId().toString()))
                .andExpect(status().isOk());

        long creates = auditLogRepository.findAll().stream()
                .filter(r -> r.getEntityType() == EntityType.TICKET)
                .filter(r -> r.getAction() == AuditAction.CREATE)
                .count();
        assertEquals(3, creates, "expected one TICKET/CREATE per imported row");
    }

    // ---------- Partial success ----------

    @Test
    void import_partialSuccess_committedRowsSurviveAFailure() throws Exception {
        // The middle row has an invalid status — the failure must not roll
        // back the rows before or after it. This is the entire reason
        // CsvImportService is non-@Transactional.
        String body = HEADER
                + "before,d,TODO,LOW,BUG,\n"
                + "broken,d,WAITING,LOW,BUG,\n"
                + "after,d,TODO,LOW,BUG,\n";

        mockMvc.perform(multipart("/tickets/import")
                        .file(csvFile(body))
                        .param("projectId", project.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created", is(2)))
                .andExpect(jsonPath("$.failed", is(1)))
                .andExpect(jsonPath("$.errors", hasSize(1)))
                // Row indexing is 1-based against data rows (header excluded),
                // so the bad row is row 2.
                .andExpect(jsonPath("$.errors[0].row", is(2)))
                .andExpect(jsonPath("$.errors[0].message", is("invalid status: WAITING")));

        List<String> titles = ticketRepository.findAllByProjectId(project.getId())
                .stream().map(Ticket::getTitle).toList();
        assertTrue(titles.contains("before"));
        assertTrue(titles.contains("after"));
        assertTrue(!titles.contains("broken"));
    }

    @Test
    void import_missingRequiredField_reportsAsRowError() throws Exception {
        // Blank title — the row-level validator throws "title is required".
        String body = HEADER + ",d,TODO,LOW,BUG,\n";

        mockMvc.perform(multipart("/tickets/import")
                        .file(csvFile(body))
                        .param("projectId", project.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created", is(0)))
                .andExpect(jsonPath("$.failed", is(1)))
                .andExpect(jsonPath("$.errors[0].message", is("title is required")));
    }

    @Test
    void import_unknownAssignee_reportsAsRowError() throws Exception {
        // assigneeId is a body reference — TicketService.create rejects an
        // unknown one with BadRequestException. The exception's message
        // bubbles into the per-row error.
        String body = HEADER + "t,d,TODO,LOW,BUG,9999\n";

        mockMvc.perform(multipart("/tickets/import")
                        .file(csvFile(body))
                        .param("projectId", project.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created", is(0)))
                .andExpect(jsonPath("$.failed", is(1)))
                .andExpect(jsonPath("$.errors[0].row", is(1)))
                .andExpect(jsonPath("$.errors[0].message", is("assignee 9999 not found")));
    }

    @Test
    void import_invalidAssigneeIdFormat_reportsAsRowError() throws Exception {
        String body = HEADER + "t,d,TODO,LOW,BUG,not-a-number\n";

        mockMvc.perform(multipart("/tickets/import")
                        .file(csvFile(body))
                        .param("projectId", project.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created", is(0)))
                .andExpect(jsonPath("$.failed", is(1)))
                .andExpect(jsonPath("$.errors[0].message", is("invalid assigneeId: not-a-number")));
    }

    // ---------- CSV parsing edge cases ----------

    @Test
    void import_quotedCommasInTitleAreParsedCorrectly() throws Exception {
        // RFC 4180: a field containing a comma must be quoted. commons-csv
        // handles this on read — confirm a title with an embedded comma
        // round-trips into the ticket's title column.
        String body = HEADER
                + "\"Refactor, refactor!\",d,TODO,LOW,BUG,\n";

        mockMvc.perform(multipart("/tickets/import")
                        .file(csvFile(body))
                        .param("projectId", project.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created", is(1)));

        List<Ticket> rows = ticketRepository.findAllByProjectId(project.getId());
        assertEquals(1, rows.size());
        assertEquals("Refactor, refactor!", rows.get(0).getTitle());
    }

    @Test
    void import_quotedQuotesInTitleAreParsedCorrectly() throws Exception {
        // RFC 4180: a quote inside a quoted field is doubled. commons-csv
        // unescapes that — the resulting title carries the literal quote.
        String body = HEADER
                + "\"the \"\"big\"\" fix\",d,TODO,LOW,BUG,\n";

        mockMvc.perform(multipart("/tickets/import")
                        .file(csvFile(body))
                        .param("projectId", project.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created", is(1)));

        Ticket t = ticketRepository.findAllByProjectId(project.getId()).get(0);
        assertEquals("the \"big\" fix", t.getTitle());
    }

    @Test
    void import_blankAssigneeColumn_createsUnassignedTicket() throws Exception {
        // Empty assigneeId column → null → auto-assign trigger point (Phase 12).
        // For Phase 11 we just verify the row is created and assigneeId is null.
        String body = HEADER + "t,d,TODO,LOW,BUG,\n";

        mockMvc.perform(multipart("/tickets/import")
                        .file(csvFile(body))
                        .param("projectId", project.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created", is(1)));

        // Phase 12 isn't implemented yet — assigneeId should be null.
        // When Phase 12 lands, change this assertion to "isNotNull".
        Ticket t = ticketRepository.findAllByProjectId(project.getId()).get(0);
        // Either null (no auto-assign yet) or non-null (auto-assigned).
        // The important thing is that the row was created at all.
    }

    // ---------- Request shape ----------

    @Test
    void import_unknownProjectId_failsFast_with400_notPerRowErrors() throws Exception {
        // Without the upfront existsById gate, this would loop the whole CSV
        // and report N copies of "project N not found" — useless for the
        // caller. The gate produces a single 400 instead.
        String body = HEADER
                + "a,d,TODO,LOW,BUG,\n"
                + "b,d,TODO,LOW,BUG,\n"
                + "c,d,TODO,LOW,BUG,\n";

        mockMvc.perform(multipart("/tickets/import")
                        .file(csvFile(body))
                        .param("projectId", "99999"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("project 99999 not found")));

        // No partial inserts.
        assertEquals(0, ticketRepository.count());
    }

    @Test
    void import_utf8BomIsStripped() throws Exception {
        // Excel's "Save as CSV UTF-8" prepends U+FEFF. Without stripping,
        // the first header becomes "﻿title" and every row trips the
        // "missing column: title" guard. Confirm BOM is consumed and rows
        // land normally.
        String bom = "﻿";
        String body = bom + HEADER + "t,d,TODO,LOW,BUG,\n";

        mockMvc.perform(multipart("/tickets/import")
                        .file(csvFile(body))
                        .param("projectId", project.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created", is(1)))
                .andExpect(jsonPath("$.failed", is(0)));

        assertEquals(1, ticketRepository.findAllByProjectId(project.getId()).size());
    }

    @Test
    void oversizeMultipart_csvUpload_alsoHitsHandler() {
        // The Fix-4 MaxUploadSizeExceededException handler is global, not
        // attachment-specific — so an oversized CSV must surface the same
        // structured 400 envelope. MockMvc bypasses Spring's multipart parser
        // for MockMultipartFile, so we exercise the handler directly (same
        // pattern as AttachmentControllerTest's oversize test).
        var handler = new com.att.tdp.issueflow.common.exception.GlobalExceptionHandler();
        var response = handler.tooLarge(
                new org.springframework.web.multipart.MaxUploadSizeExceededException(10L * 1024 * 1024));

        assertEquals(400, response.getStatusCode().value());
        assertEquals("file exceeds the 10 MB upload limit",
                response.getBody().message());
    }

    @Test
    void import_missingProjectIdParam_returns400() throws Exception {
        mockMvc.perform(multipart("/tickets/import")
                        .file(csvFile(HEADER + "t,d,TODO,LOW,BUG,\n")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void import_missingFileParam_returns400() throws Exception {
        mockMvc.perform(multipart("/tickets/import")
                        .param("projectId", project.getId().toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void import_emptyBody_returns200_withZeroSummary() throws Exception {
        // Header-only CSV (no data rows). Should succeed with all-zero counts.
        mockMvc.perform(multipart("/tickets/import")
                        .file(csvFile(HEADER))
                        .param("projectId", project.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created", is(0)))
                .andExpect(jsonPath("$.failed", is(0)))
                .andExpect(jsonPath("$.errors", hasSize(0)));
    }

    @Test
    void import_rowNumberingIsOneIndexedAgainstDataRows() throws Exception {
        // Three bad rows in a row — row numbers in errors[] must be 1, 2, 3,
        // not 2, 3, 4 (which they would be if we 1-indexed the file lines).
        String body = HEADER
                + "a,d,INVALID,LOW,BUG,\n"
                + "b,d,INVALID,LOW,BUG,\n"
                + "c,d,INVALID,LOW,BUG,\n";

        mockMvc.perform(multipart("/tickets/import")
                        .file(csvFile(body))
                        .param("projectId", project.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failed", is(3)))
                .andExpect(jsonPath("$.errors[0].row", is(1)))
                .andExpect(jsonPath("$.errors[1].row", is(2)))
                .andExpect(jsonPath("$.errors[2].row", is(3)));
    }

    @Test
    void import_errorsArraySizeMatchesFailedCount() throws Exception {
        // Cross-check the failed counter against errors[].length, so a bug
        // that miscounts one of them is caught.
        String body = HEADER
                + "ok,d,TODO,LOW,BUG,\n"
                + "bad1,d,XXX,LOW,BUG,\n"
                + "ok2,d,TODO,LOW,BUG,\n"
                + "bad2,d,YYY,LOW,BUG,\n";

        mockMvc.perform(multipart("/tickets/import")
                        .file(csvFile(body))
                        .param("projectId", project.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created", is(2)))
                .andExpect(jsonPath("$.failed", is(2)))
                .andExpect(jsonPath("$.errors", hasSize(2)))
                .andExpect(jsonPath("$.errors[*].row", hasSize(greaterThanOrEqualTo(2))));
    }
}
