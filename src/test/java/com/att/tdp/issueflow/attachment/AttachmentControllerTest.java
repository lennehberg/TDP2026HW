package com.att.tdp.issueflow.attachment;

import com.att.tdp.issueflow.TestSecurityConfig;
import com.att.tdp.issueflow.audit.Actor;
import com.att.tdp.issueflow.audit.AuditAction;
import com.att.tdp.issueflow.audit.AuditLog;
import com.att.tdp.issueflow.audit.AuditLogRepository;
import com.att.tdp.issueflow.audit.EntityType;
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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 6 — attachment upload/delete via the REST surface.
 * <p>
 * Pins the shape and status codes the README declares (upload/delete both
 * 200 OK; response is {@code {id, ticketId, filename, contentType}} only —
 * no {@code size}) as well as Tika-based content-type sniffing and the
 * 10 MB upload limit. The {@link MaxUploadSizeExceededException} handler
 * (Phase 10) is exercised here because attachments are the only multipart
 * endpoint that can trigger it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
@Transactional
class AttachmentControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired AttachmentRepository attachmentRepository;
    @Autowired TicketRepository ticketRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired UserRepository userRepository;
    @Autowired AuditLogRepository auditLogRepository;

    User owner;
    Project project;
    Ticket ticket;

    // Smallest valid PNG: 8-byte signature is enough for Tika to detect image/png.
    private static final byte[] PNG_BYTES = new byte[] {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52
    };

    @BeforeEach
    void setup() {
        auditLogRepository.deleteAll();
        attachmentRepository.deleteAll();
        ticketRepository.deleteAll();
        projectRepository.deleteAll();
        userRepository.deleteAll();

        owner = userRepository.save(User.builder()
                .username("o").email("o@e.com").fullName("o")
                .role(Role.ADMIN).passwordHash("h").build());
        project = projectRepository.save(Project.builder()
                .name("p").description("d").ownerId(owner.getId()).build());
        ticket = ticketRepository.save(Ticket.builder()
                .title("t").description("d")
                .status(TicketStatus.TODO).priority(Priority.MEDIUM).type(TicketType.BUG)
                .projectId(project.getId()).isOverdue(false).build());
    }

    private MockMultipartFile png(String name) {
        return new MockMultipartFile("file", name, "image/png", PNG_BYTES);
    }

    // ---------- Upload happy path ----------

    @Test
    void upload_returns200_andResponseShape() throws Exception {
        // Fix 1 — controller returns 200, not 201.
        // Fix 2 — response carries exactly {id, ticketId, filename, contentType};
        // no `size` field, no timestamps.
        mockMvc.perform(multipart("/tickets/{id}/attachments", ticket.getId())
                        .file(png("screenshot.png")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.ticketId", is(ticket.getId().intValue())))
                .andExpect(jsonPath("$.filename", is("screenshot.png")))
                .andExpect(jsonPath("$.contentType", is("image/png")))
                .andExpect(jsonPath("$.size").doesNotExist())
                .andExpect(jsonPath("$.createdAt").doesNotExist())
                .andExpect(jsonPath("$.updatedAt").doesNotExist())
                .andExpect(jsonPath("$.storagePath").doesNotExist());
    }

    @Test
    void upload_persistsAttachmentRow_andPopulatesSizeOnEntity() throws Exception {
        // The DTO drops `size` (Fix 2) but the column is still load-bearing —
        // confirm the row is persisted with a non-zero size.
        mockMvc.perform(multipart("/tickets/{id}/attachments", ticket.getId())
                        .file(png("a.png")))
                .andExpect(status().isOk());

        List<Attachment> rows = attachmentRepository.findAll();
        assertEquals(1, rows.size());
        Attachment a = rows.get(0);
        assertEquals(ticket.getId(), a.getTicketId());
        assertEquals("a.png", a.getFilename());
        assertEquals("image/png", a.getContentType());
        assertTrue(a.getSize() > 0, "expected entity size to be populated");
    }

    @Test
    void upload_writesAuditRowAsUserActor() throws Exception {
        // Phase 9 — every state-changing service writes a CREATE audit row.
        // Actor must be USER (attachment upload is a user-initiated action).
        mockMvc.perform(multipart("/tickets/{id}/attachments", ticket.getId())
                        .file(png("a.png")))
                .andExpect(status().isOk());

        List<AuditLog> rows = auditLogRepository.findAll().stream()
                .filter(r -> r.getEntityType() == EntityType.ATTACHMENT)
                .filter(r -> r.getAction() == AuditAction.CREATE)
                .toList();
        assertEquals(1, rows.size(), "expected one ATTACHMENT/CREATE audit row");
        assertEquals(Actor.USER, rows.get(0).getActor());
    }

    @Test
    void upload_detectsContentTypeFromBytes_ignoringClientHeader() throws Exception {
        // README §3.6: content type is sniffed via Tika, not trusted from the
        // multipart header. Client lies about content type → response still
        // reports the sniffed type (image/png).
        MockMultipartFile lying = new MockMultipartFile(
                "file", "screenshot.png", "text/plain", PNG_BYTES);

        mockMvc.perform(multipart("/tickets/{id}/attachments", ticket.getId())
                        .file(lying))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contentType", is("image/png")));
    }

    // ---------- Validation ----------

    @Test
    void upload_disallowedContentType_returns400() throws Exception {
        // GIF is intentionally not in the whitelist (jpeg/png/pdf/plain only).
        byte[] gif = new byte[] {
                0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00, 0x01, 0x00,
                (byte) 0x80, 0x00, 0x00, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
                0x00, 0x00, 0x00, 0x21, (byte) 0xF9, 0x04, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x2C, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01,
                0x00, 0x00, 0x02, 0x02, 0x44, 0x01, 0x00, 0x3B
        };
        MockMultipartFile file = new MockMultipartFile("file", "a.gif", "image/gif", gif);

        mockMvc.perform(multipart("/tickets/{id}/attachments", ticket.getId())
                        .file(file))
                .andExpect(status().isBadRequest());
    }

    @Test
    void upload_unknownTicket_returns404() throws Exception {
        mockMvc.perform(multipart("/tickets/{id}/attachments", 9999L)
                        .file(png("a.png")))
                .andExpect(status().isNotFound());
    }

    @Test
    void oversizeMultipart_handlerEmitsStructuredEnvelope() {
        // Phase 10 / Fix 4: MaxUploadSizeExceededException must map to 400 with
        // the standard {message} envelope, not a raw HTML 500. Spring's real
        // multipart parser raises this before the controller is invoked, but
        // MockMvc's MockMultipartFile bypasses that path entirely, so we
        // exercise the handler directly (same pattern as CommentControllerTest's
        // optimisticLockHandler_emitsGenericMessage).
        var handler = new com.att.tdp.issueflow.common.exception.GlobalExceptionHandler();
        var response = handler.tooLarge(
                new org.springframework.web.multipart.MaxUploadSizeExceededException(10L * 1024 * 1024));

        assertEquals(400, response.getStatusCode().value());
        assertEquals("file exceeds the 10 MB upload limit",
                response.getBody().message());
    }

    // ---------- Delete ----------

    @Test
    void delete_returns200_andRemovesRow() throws Exception {
        // Upload first to get a real id.
        String body = mockMvc.perform(multipart("/tickets/{id}/attachments", ticket.getId())
                        .file(png("a.png")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Long attachmentId = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(body).get("id").asLong();

        // Fix 1 — controller returns 200, not 204.
        mockMvc.perform(delete("/tickets/{ticketId}/attachments/{id}",
                        ticket.getId(), attachmentId))
                .andExpect(status().isOk());

        assertFalse(attachmentRepository.existsById(attachmentId));
    }

    @Test
    void delete_writesAuditRowAsUserActor() throws Exception {
        String body = mockMvc.perform(multipart("/tickets/{id}/attachments", ticket.getId())
                        .file(png("a.png")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Long attachmentId = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(body).get("id").asLong();

        mockMvc.perform(delete("/tickets/{ticketId}/attachments/{id}",
                        ticket.getId(), attachmentId))
                .andExpect(status().isOk());

        long deletes = auditLogRepository.findAll().stream()
                .filter(r -> r.getEntityType() == EntityType.ATTACHMENT)
                .filter(r -> r.getAction() == AuditAction.DELETE)
                .filter(r -> r.getActor() == Actor.USER)
                .count();
        assertEquals(1, deletes, "expected one ATTACHMENT/DELETE USER audit row");
    }

    @Test
    void delete_unknownAttachment_returns404() throws Exception {
        mockMvc.perform(delete("/tickets/{ticketId}/attachments/{id}",
                        ticket.getId(), 9999L))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_attachmentBelongsToDifferentTicket_returns404() throws Exception {
        // Path consistency: the URL pair {ticketId, attachmentId} must match a
        // real row. Otherwise the attachment is invisible from this URL → 404.
        Ticket other = ticketRepository.save(Ticket.builder()
                .title("t2").description("d")
                .status(TicketStatus.TODO).priority(Priority.MEDIUM).type(TicketType.BUG)
                .projectId(project.getId()).isOverdue(false).build());

        String body = mockMvc.perform(multipart("/tickets/{id}/attachments", ticket.getId())
                        .file(png("a.png")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Long attachmentId = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(body).get("id").asLong();

        mockMvc.perform(delete("/tickets/{ticketId}/attachments/{id}",
                        other.getId(), attachmentId))
                .andExpect(status().isNotFound());
    }
}
