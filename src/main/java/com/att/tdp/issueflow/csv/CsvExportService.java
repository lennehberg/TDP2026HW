package com.att.tdp.issueflow.csv;

import com.att.tdp.issueflow.ticket.Ticket;
import com.att.tdp.issueflow.ticket.TicketRepository;

import lombok.RequiredArgsConstructor;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.Writer;
import java.util.List;

/**
 * Streams a CSV of tickets for one project to the response writer.
 * <p>
 * Field order is pinned to the README contract:
 * {@code id, title, description, status, priority, type, assigneeId}.
 * {@code dueDate}, {@code projectId}, {@code createdAt}, and {@code isOverdue}
 * are deliberately excluded — round-tripping export-then-import is therefore
 * lossy for those fields, which is a contract decision (not a bug).
 * <p>
 * commons-csv's {@code CSVPrinter} handles quoting embedded commas, quotes,
 * and newlines per RFC 4180 automatically — the implementation just hands it
 * raw strings.
 */
@Service
@RequiredArgsConstructor
public class CsvExportService {

    private final TicketRepository ticketRepository;

    static final String[] HEADERS = {
            "id", "title", "description", "status", "priority", "type", "assigneeId"
    };

    @Transactional(readOnly = true)
    public void exportProjectTickets(Long projectId, Writer writer) throws IOException {
        // @SQLRestriction on Ticket already filters soft-deleted rows, so the
        // export naturally excludes them — matches the listing endpoint.
        List<Ticket> tickets = ticketRepository.findAllByProjectId(projectId);

        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader(HEADERS)
                .build();

        try (CSVPrinter printer = new CSVPrinter(writer, format)) {
            for (Ticket t : tickets) {
                printer.printRecord(
                        t.getId(),
                        t.getTitle(),
                        t.getDescription(),
                        t.getStatus(),
                        t.getPriority(),
                        t.getType(),
                        t.getAssigneeId()
                );
            }
        }
    }
}
