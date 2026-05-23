package com.att.tdp.issueflow.audit;

import com.att.tdp.issueflow.TestSecurityConfig;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end tests for {@code GET /audit-logs}. Exercises the full controller
 * → service → Specification query → JSON serialization pipeline so that
 * the {@code PageResponse<T>} envelope and the README field-name contract
 * (especially {@code timestamp} instead of {@code createdAt}) are locked in.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
@Transactional
class AuditControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired AuditService auditService;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired EntityManager em;

    @BeforeEach
    void setup() {
        auditLogRepository.deleteAll();
        em.flush();

        // Seed a small mix so filter combinations have something to discriminate.
        auditService.record(AuditAction.CREATE, EntityType.TICKET, 1L, 5L, Actor.USER);
        auditService.record(AuditAction.UPDATE, EntityType.TICKET, 1L, 5L, Actor.USER);
        auditService.record(AuditAction.CREATE, EntityType.PROJECT, 2L, 5L, Actor.USER);
        auditService.recordSystemAction(AuditAction.AUTO_ASSIGN, EntityType.TICKET, 1L);
        em.flush();
    }

    @Test
    void list_returnsPagedEnvelope() throws Exception {
        mockMvc.perform(get("/audit-logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(4)))
                .andExpect(jsonPath("$.total", is(4)))
                .andExpect(jsonPath("$.page", is(1)));
    }

    @Test
    void list_filtersByAction() throws Exception {
        mockMvc.perform(get("/audit-logs").param("action", "UPDATE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].action", is("UPDATE")));
    }

    @Test
    void list_filtersByEntityType() throws Exception {
        mockMvc.perform(get("/audit-logs").param("entityType", "PROJECT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].entityType", is("PROJECT")));
    }

    @Test
    void list_filtersByEntityId() throws Exception {
        // entityId=1 hits 3 of the 4 seeded rows.
        mockMvc.perform(get("/audit-logs").param("entityId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(3)));
    }

    @Test
    void list_filtersBySystemActor_andPerformedByIsNull() throws Exception {
        mockMvc.perform(get("/audit-logs").param("actor", "SYSTEM"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].actor", is("SYSTEM")))
                .andExpect(jsonPath("$.data[0].performedBy", nullValue()));
    }

    @Test
    void list_combinesFiltersWithAnd() throws Exception {
        mockMvc.perform(get("/audit-logs")
                        .param("entityType", "TICKET")
                        .param("action", "CREATE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].entityType", is("TICKET")))
                .andExpect(jsonPath("$.data[0].action", is("CREATE")));
    }

    @Test
    void list_paginatesAcrossPages() throws Exception {
        mockMvc.perform(get("/audit-logs")
                        .param("page", "2")
                        .param("pageSize", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.total", is(4)))
                .andExpect(jsonPath("$.page", is(2)));
    }

    @Test
    void list_invalidPage_returns400() throws Exception {
        mockMvc.perform(get("/audit-logs").param("page", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void list_invalidPageSize_returns400() throws Exception {
        mockMvc.perform(get("/audit-logs").param("pageSize", "0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/audit-logs").param("pageSize", "200"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void list_unknownEnumValue_returns400() throws Exception {
        // HttpMessageNotReadable / type-conversion failure path. Spring maps
        // an unparseable enum on a query param to 400 — locked in here so a
        // future handler change doesn't quietly demote it to 500.
        mockMvc.perform(get("/audit-logs").param("action", "NOT_A_REAL_ACTION"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void list_responseFieldIsTimestamp_notCreatedAt() throws Exception {
        // README contract: the audit-log response field is named `timestamp`,
        // not `createdAt`. Locks in the entity → DTO field rename.
        mockMvc.perform(get("/audit-logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].timestamp", notNullValue()))
                .andExpect(jsonPath("$.data[0].createdAt").doesNotExist());
    }
}
