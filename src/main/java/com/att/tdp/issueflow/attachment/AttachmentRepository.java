package com.att.tdp.issueflow.attachment;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AttachmentRepository extends JpaRepository<Attachment, Long> {
    List<Attachment> findAllByTicketId(Long ticketId);
}
