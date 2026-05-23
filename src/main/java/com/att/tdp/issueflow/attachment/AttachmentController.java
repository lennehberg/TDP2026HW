package com.att.tdp.issueflow.attachment;

import com.att.tdp.issueflow.attachment.dto.AttachmentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/tickets/{ticketId}/attachments")
@RequiredArgsConstructor
public class AttachmentController {

    private final AttachmentService attachmentService;

    @PostMapping
    public AttachmentResponse upload(
            @PathVariable Long ticketId,
            @RequestParam("file") MultipartFile file
    ) throws IOException {
        return attachmentService.upload(ticketId, file);
    }

    @DeleteMapping("/{attachmentId}")
    public void delete(@PathVariable Long ticketId,
                       @PathVariable Long attachmentId) throws IOException {
        attachmentService.delete(ticketId, attachmentId);
    }

}
