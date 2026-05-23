package com.att.tdp.issueflow.audit;

import com.att.tdp.issueflow.audit.dto.AuditLogResponse;
import com.att.tdp.issueflow.auth.CurrentUserService;
import com.att.tdp.issueflow.common.dto.PageResponse;
import com.att.tdp.issueflow.common.exception.BadRequestException;

import jakarta.persistence.criteria.Predicate;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Central writer of {@link AuditLog} rows and reader of the filtered listing.
 * <p>
 * Every state-changing service in the codebase calls one of the
 * {@link #recordUserAction recordUserAction} /
 * {@link #recordSystemAction recordSystemAction} wrappers from inside its
 * own transaction. The audit row is written in the same transaction as the
 * business change so a rollback discards both — there is no partial state
 * where the business mutation lands but the audit row doesn't (or vice
 * versa).
 * <p>
 * Invariant: {@code actor == SYSTEM} ⇒ {@code performedBy == null}.
 * Enforced in {@link #record} so call sites can't bypass it. The reverse
 * direction ({@code USER} with {@code null} {@code performedBy}) is
 * deliberately permitted to keep unauthenticated test paths working —
 * the production {@code SecurityFilterChain} guarantees a real principal
 * for every {@code USER}-actor call site.
 */
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    private final CurrentUserService currentUserService;

    /**
     * Persist a single audit row. {@code payload} may be {@code null}.
     * Already-serialized JSON only — serialization is the caller's concern.
     */
    @Transactional
    public void record(AuditAction action,
                       EntityType entityType,
                       Long entityId,
                       Long performedBy,
                       Actor actor,
                       String payload) {
        if (actor == Actor.SYSTEM && performedBy != null) {
            throw new IllegalArgumentException(
                    "SYSTEM actor must not carry a performedBy id (no sentinel system user)");
        }
        auditLogRepository.save(AuditLog.builder()
                .action(action)
                .entityType(entityType)
                .entityId(entityId)
                .performedBy(performedBy)
                .actor(actor)
                .payload(payload)
                .build());
    }

    /** Convenience overload for callers that don't attach a payload. */
    public void record(AuditAction action,
                       EntityType entityType,
                       Long entityId,
                       Long performedBy,
                       Actor actor) {
        record(action, entityType, entityId, performedBy, actor, null);
    }

    /**
     * Wrapper for the common case: an end-user action. Looks up the current
     * principal via {@link CurrentUserService} so call sites don't repeat the
     * boilerplate.
     */
    public void recordUserAction(AuditAction action, EntityType entityType, Long entityId) {
        recordUserAction(action, entityType, entityId, null);
    }

    public void recordUserAction(AuditAction action, EntityType entityType, Long entityId, String payload) {
        Long performedBy = currentUserService.userId().orElse(null);
        record(action, entityType, entityId, performedBy, Actor.USER, payload);
    }

    /**
     * Wrapper for SYSTEM-actor flows (auto-assign, auto-escalate). Forces
     * {@code performedBy = null} so callers can't accidentally pass one.
     */
    public void recordSystemAction(AuditAction action, EntityType entityType, Long entityId) {
        record(action, entityType, entityId, null, Actor.SYSTEM, null);
    }

    /**
     * Filtered, paginated listing for {@code GET /audit-logs}. Any filter
     * may be {@code null} (no constraint on that column). Filters compose
     * with AND. Sorted newest-first (the conventional audit-log read order).
     */
    @Transactional(readOnly = true)
    public PageResponse<AuditLogResponse> find(EntityType entityType,
                                               Long entityId,
                                               AuditAction action,
                                               Actor actor,
                                               int page,
                                               int pageSize) {
        if (page < 1) {
            throw new BadRequestException("page must be >= 1");
        }
        if (pageSize < 1 || pageSize > 100) {
            throw new BadRequestException("pageSize must be between 1 and 100");
        }

        Specification<AuditLog> spec = (root, query, cb) -> {
            List<Predicate> preds = new ArrayList<>(4);
            if (entityType != null) preds.add(cb.equal(root.get("entityType"), entityType));
            if (entityId != null)   preds.add(cb.equal(root.get("entityId"), entityId));
            if (action != null)     preds.add(cb.equal(root.get("action"), action));
            if (actor != null)      preds.add(cb.equal(root.get("actor"), actor));
            return cb.and(preds.toArray(new Predicate[0]));
        };

        Pageable pageable = PageRequest.of(
                page - 1, pageSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<AuditLog> result = auditLogRepository.findAll(spec, pageable);

        List<AuditLogResponse> data = result.getContent().stream()
                .map(this::toResponse)
                .toList();
        return new PageResponse<>(data, result.getTotalElements(), page);
    }

    private AuditLogResponse toResponse(AuditLog a) {
        return new AuditLogResponse(
                a.getId(),
                a.getAction(),
                a.getEntityType(),
                a.getEntityId(),
                a.getPerformedBy(),
                a.getActor(),
                a.getCreatedAt()
        );
    }
}
