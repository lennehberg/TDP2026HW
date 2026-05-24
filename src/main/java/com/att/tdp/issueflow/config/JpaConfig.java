package com.att.tdp.issueflow.config;

import org.openapitools.jackson.nullable.JsonNullableModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Activates Spring Data JPA auditing so {@code @CreatedDate} and
 * {@code @LastModifiedDate} fields on {@link com.att.tdp.issueflow.common.BaseEntity}
 * are populated automatically.
 * <p>
 * Also registers {@link JsonNullableModule} on the application
 * {@code ObjectMapper} via Spring Boot's {@code Jackson2ObjectMapperBuilder},
 * which auto-discovers Jackson {@code Module} beans. Without this bean,
 * {@code JsonNullable<T>} deserialization is silently broken and the
 * "field absent vs. field present null" distinction collapses — which
 * the auto-escalation reset trigger on {@code PATCH /tickets/:id} depends on.
 */
@Configuration
@EnableJpaAuditing
public class JpaConfig {

    @Bean
    public JsonNullableModule jsonNullableModule() {
        return new JsonNullableModule();
    }
}
