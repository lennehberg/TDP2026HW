package com.att.tdp.issueflow.attachment;

import com.att.tdp.issueflow.audit.*;
import com.att.tdp.issueflow.attachment.dto.AttachmentResponse;
import com.att.tdp.issueflow.common.exception.*;
import com.att.tdp.issueflow.ticket.TicketRepository;

import lombok.RequiredArgsConstructor;
import org.apache.tika.Tika;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

@Service
@RequiredArgsConstructor
public class AttachmentService {

    private final AttachmentRepository attachmentRepository;
    private final TicketRepository ticketRepository;
    private final AuditService auditService;
    private final Tika tika = new Tika();

    @Value("${app.upload.dir:uploads}")
    private String uploadDir;

    private static final Set<String> ALLOWED_TYPES =
            Set.of(
                    "image/jpeg", "image/png",
                    "application/pdf", "text/plain"
            );
    @Transactional
    public AttachmentResponse upload(Long ticketId, MultipartFile file) throws IOException {
        if (!ticketRepository.existsById(ticketId)) {
            throw new NotFoundException("Ticket id " + ticketId + " not found");
        }

        // Validate content type (tika sniffing)
        String detectedType = tika.detect(file.getInputStream());
        if (!ALLOWED_TYPES.contains(detectedType)) {
            throw new BadRequestException("file type " + detectedType + " not allowed");
        }

        // Save to physical storage
        Path root = Paths.get(uploadDir);
        if (!Files.exists(root)) {
            Files.createDirectories(root);
        }

        String storageName = UUID.randomUUID() + "_" + file.getOriginalFilename();
        Path target = root.resolve(storageName);
        Files.copy(file.getInputStream(), target);

        // Persistence
        Attachment saved = attachmentRepository.save(
                Attachment.builder()
                        .ticketId(ticketId)
                        .filename(file.getOriginalFilename())
                        .contentType(detectedType)
                        .size(file.getSize())
                        .storagePath(target.toString())
                        .build()
        );

        // audit and return
        auditService.recordUserAction(AuditAction.CREATE, EntityType.ATTACHMENT, saved.getId());
        return toResponse(saved);
    }

    @Transactional
    public void delete(Long ticketId, Long attachmentId) throws IOException {
        Attachment a =
                attachmentRepository.findById(attachmentId).orElseThrow(()
                        -> new NotFoundException(
                        "Attachment id " + attachmentId
                ));

        if (!a.getTicketId().equals(ticketId)) {
            throw new NotFoundException("Attachment " + attachmentId + " is not found on this ticket " + ticketId);
        }

        // delete and audit
        Files.deleteIfExists(Paths.get(a.getStoragePath()));
        attachmentRepository.delete(a);
        auditService.recordUserAction(AuditAction.DELETE, EntityType.ATTACHMENT, attachmentId);
    }

    private AttachmentResponse toResponse(Attachment a) {
        return new AttachmentResponse(
                a.getId(),
                a.getTicketId(),
                a.getFilename(),
                a.getContentType()
        );
    }

}
