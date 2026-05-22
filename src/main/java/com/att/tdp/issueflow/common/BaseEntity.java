package com.att.tdp.issueflow.common;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import lombok.Getter;

import java.time.Instant;

/**
 * Shared base for all JPA entities: surrogate primary key plus audited
 * created/updated timestamps. Subclasses opt into optimistic locking
 * individually with their own {@code @Version} field — it is deliberately
 * not on the base because most entities (User, Project, AuditLog, etc.)
 * don't need it.
 * <p>
 * Auditing only fires when {@link com.att.tdp.issueflow.config.JpaConfig}
 * is on the classpath (it carries {@code @EnableJpaAuditing}).
 */
@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @CreatedDate
    @Column(updatable = false, nullable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;
}