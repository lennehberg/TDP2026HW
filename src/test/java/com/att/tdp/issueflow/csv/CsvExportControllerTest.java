package com.att.tdp.issueflow.csv;

import com.att.tdp.issueflow.TestSecurityConfig;
import com.att.tdp.issueflow.project.Project;
import com.att.tdp.issueflow.project.ProjectRepository;
import com.att.tdp.issueflow.ticket.Priority;
import com.att.tdp.issueflow.ticket.Ticket;
import com.att.tdp.issueflow.ticket.TicketRepository;
import com.att.tdp.issueflow.ticket.TicketStatus;
import com.att.tdp.issueflow.ticket.TicketType;
import com.att.tdp.issueflow.user.Role;
import com.att.tdp.issueflow.user.User;
import com.att.tdp.issueflow.user.UserRepository;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.io.StringReader;
import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 11 — CSV export.
 * <p>
 * Header order is locked to {@code id, title, description, status, priority,
 * type, assigneeId} per the README contract. Soft-deleted tickets must not
 * appear ({@code @SQLRestriction} handles that for free). Commas and quotes
 * inside fields must be properly RFC-4180-escaped — commons-csv handles this
 * on the write side but we round-trip parse to confirm.
 * <p>
 * The controller streams via {@code StreamingResponseBody}, so MockMvc has to
 * dispatch the async result before reading the body.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
@Transactional
class CsvExportControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired TicketRepository ticketRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired UserRepository userRepository;

    User owner;
    Project project;

    @BeforeEach
    void setup() {
        ticketRepository.deleteAll();
        projectRepository.deleteAll();
        userRepository.deleteAll();

        owner = userRepository.save(User.builder()
                .username("csvOwner").email("csv@e.com").fullName("o")
                .role(Role.DEVELOPER).passwordHash("h").build());
        project = projectRepository.save(Project.builder()
                .name("p").description("d").ownerId(owner.getId()).build());
    }

    private Ticket save(String title, TicketStatus status, Priority priority,
                        TicketType type, Long assigneeId) {
        return ticketRepository.save(Ticket.builder()
                .title(title).description("d")
                .status(status).priority(priority).type(type)
                .projectId(project.getId()).assigneeId(assigneeId)
                .isOverdue(false).build());
    }

    private String performExport() throws Exception {
        MvcResult res = mockMvc.perform(get("/tickets/export")
                        .param("projectId", project.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(header().string("Content-Disposition",
                        containsString("tickets-project-" + project.getId() + ".csv")))
                .andReturn();
        return res.getResponse().getContentAsString();
    }

    @Test
    void export_emitsHeaderAndOneRow() throws Exception {
        Ticket t = save("Fix login", TicketStatus.TODO, Priority.HIGH, TicketType.BUG, owner.getId());

        String csv = performExport();
        try (CSVParser parser = CSVParser.parse(new StringReader(csv),
                CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build())) {
            assertEquals(List.of("id", "title", "description", "status", "priority", "type", "assigneeId"),
                    parser.getHeaderNames());
            List<CSVRecord> rows = parser.getRecords();
            assertEquals(1, rows.size());
            CSVRecord r = rows.get(0);
            assertEquals(t.getId().toString(), r.get("id"));
            assertEquals("Fix login", r.get("title"));
            assertEquals("d", r.get("description"));
            assertEquals("TODO", r.get("status"));
            assertEquals("HIGH", r.get("priority"));
            assertEquals("BUG", r.get("type"));
            assertEquals(owner.getId().toString(), r.get("assigneeId"));
        }
    }

    @Test
    void export_quotesEmbeddedCommasAndQuotes() throws Exception {
        // The interesting parsing case from BUILD_PLAN Phase 14: commas and
        // quotes inside fields must round-trip cleanly. commons-csv quotes
        // the field, doubling embedded quotes per RFC 4180.
        save("title, with comma", TicketStatus.TODO, Priority.LOW, TicketType.FEATURE, null);
        save("title with \"quotes\"", TicketStatus.TODO, Priority.LOW, TicketType.FEATURE, null);

        String csv = performExport();
        try (CSVParser parser = CSVParser.parse(new StringReader(csv),
                CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build())) {
            List<String> titles = parser.getRecords().stream()
                    .map(r -> r.get("title")).toList();
            assertTrue(titles.contains("title, with comma"),
                    "comma-bearing title must round-trip: " + titles);
            assertTrue(titles.contains("title with \"quotes\""),
                    "quote-bearing title must round-trip: " + titles);
        }
        // Raw CSV should contain the literal RFC-4180 doubled-quote sequence.
        assertTrue(csv.contains("\"title with \"\"quotes\"\"\""),
                "raw CSV must contain doubled-quote escapes: " + csv);
    }

    @Test
    void export_emptyAssigneeIsEmptyField() throws Exception {
        // assigneeId null should render as the empty field, not "null".
        save("unassigned", TicketStatus.TODO, Priority.LOW, TicketType.FEATURE, null);

        String csv = performExport();
        // First data line: id,unassigned,d,TODO,LOW,TASK,
        assertTrue(csv.split("\\R")[1].endsWith(","),
                "trailing assigneeId column should be empty, not literal 'null': " + csv);
    }

    @Test
    void export_emptyProjectReturnsHeaderOnly() throws Exception {
        String csv = performExport();
        // Header row only, no data rows.
        try (CSVParser parser = CSVParser.parse(new StringReader(csv),
                CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build())) {
            assertEquals(0, parser.getRecords().size());
        }
        // Output is just the header line plus the trailing newline.
        assertTrue(csv.startsWith("id,title,description,status,priority,type,assigneeId"),
                "expected header-only output: " + csv);
    }

    @Test
    void export_omitsSoftDeletedTickets() throws Exception {
        // @SQLRestriction on Ticket should already filter — this pins the
        // assumption so a future change to TicketRepository.findAllByProjectId
        // can't silently leak deleted rows into exports.
        save("alive", TicketStatus.TODO, Priority.LOW, TicketType.FEATURE, null);
        Ticket dead = save("dead", TicketStatus.TODO, Priority.LOW, TicketType.FEATURE, null);
        dead.setDeletedAt(Instant.now());
        ticketRepository.save(dead);

        String csv = performExport();
        assertTrue(csv.contains("alive"));
        assertTrue(!csv.contains("dead"),
                "soft-deleted ticket must not appear in export: " + csv);
    }

    @Test
    void export_filtersByProject() throws Exception {
        // A ticket in another project must not bleed into the export.
        Project other = projectRepository.save(Project.builder()
                .name("other").description("d").ownerId(owner.getId()).build());
        ticketRepository.save(Ticket.builder()
                .title("foreign").description("d")
                .status(TicketStatus.TODO).priority(Priority.LOW).type(TicketType.FEATURE)
                .projectId(other.getId()).isOverdue(false).build());
        save("local", TicketStatus.TODO, Priority.LOW, TicketType.FEATURE, null);

        String csv = performExport();
        assertTrue(csv.contains("local"));
        assertTrue(!csv.contains("foreign"),
                "tickets from other projects must not appear: " + csv);
    }

    @Test
    void export_responseShapeStartsWithExpectedHeaderLine() throws Exception {
        save("t", TicketStatus.TODO, Priority.LOW, TicketType.FEATURE, null);

        String csv = performExport();
        // Locks the exact header ordering at the byte level — a contract check
        // a CSV-parser test wouldn't catch (parsers are tolerant of column order).
        assertTrue(csv.lines().findFirst().orElseThrow()
                        .equals("id,title,description,status,priority,type,assigneeId"),
                "first line must be the canonical header in order: " + csv);
    }

    @Test
    void export_missingProjectIdReturns400() throws Exception {
        mockMvc.perform(get("/tickets/export"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void export_contentDispositionUsesAttachment() throws Exception {
        save("t", TicketStatus.TODO, Priority.LOW, TicketType.FEATURE, null);

        mockMvc.perform(get("/tickets/export")
                        .param("projectId", project.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        startsWith("attachment;")));
    }
}
