package com.att.tdp.issueflow.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Activates Spring Data JPA auditing so {@code @CreatedDate} and
 * {@code @LastModifiedDate} fields on {@link com.att.tdp.issueflow.common.BaseEntity}
 * are populated automatically. The class is intentionally empty — its only job
 * is to host {@code @EnableJpaAuditing} on a {@code @Configuration} bean.
 */
@Configuration
@EnableJpaAuditing
public class JpaConfig {
}
