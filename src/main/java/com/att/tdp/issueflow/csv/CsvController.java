package com.att.tdp.issueflow.csv;

import com.att.tdp.issueflow.csv.dto.ImportSummary;

import lombok.RequiredArgsConstructor;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.StringWriter;

/**
 * Bulk CSV ticket operations. URL prefix is {@code /tickets/...} to match
 * the README contract; the controller lives in {@code csv/} because the
 * work spans repositories and isn't really part of ticket CRUD.
 * <p>
 * Export buffers the full CSV into a {@link StringWriter} before responding.
 * StreamingResponseBody would be lower-memory for large exports but it
 * dispatches the body on a separate thread, which loses the caller's
 * {@code @Transactional} context — making the endpoint near-impossible to
 * cover with {@code MockMvc + @Transactional} tests. The buffered shape is
 * sufficient at the dataset sizes the spec targets.
 */
@RestController
@RequestMapping("/tickets")
@RequiredArgsConstructor
public class CsvController {

    private final CsvExportService csvExportService;
    private final CsvImportService csvImportService;

    @GetMapping(value = "/export", produces = "text/csv")
    public ResponseEntity<String> export(@RequestParam Long projectId) throws IOException {
        StringWriter writer = new StringWriter();
        csvExportService.exportProjectTickets(projectId, writer);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv"))
                .header("Content-Disposition",
                        "attachment; filename=\"tickets-project-" + projectId + ".csv\"")
                .body(writer.toString());
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportSummary importTickets(
            @RequestParam("file") MultipartFile file,
            @RequestParam("projectId") Long projectId
    ) throws IOException {
        return csvImportService.importTickets(file, projectId);
    }
}
