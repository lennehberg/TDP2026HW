# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

IssueFlow — a Spring Boot 3.4 / Java 21 backend for a lightweight ticket-tracking platform. It is a HackerRank-style take-home assignment with a 15-minute final submission window, so the repo must be kept push-ready.

Two documents are authoritative and should be consulted before non-trivial changes:

- `BUILD_PLAN.md` — phased implementation plan and **decision log**. Explains *why* the codebase is shaped the way it is (package-by-feature, audit enum vocabulary, isOverdue ownership, etc.). Read the relevant phase before starting work on that feature.
- `README.md` — endpoint contract (paths, request/response shapes). Repeated as tables in §APIs.
- `TDP_issueflow_requirements.pdf` — original spec; business rules.

Conflict resolution: **README endpoint paths win; PDF business rules win.**

## Commands

```bash
docker compose up -d                         # start Postgres (port 5432; user/pass/db all "issueflow")
./mvnw clean package                         # build
./mvnw spring-boot:run                       # run on :8080
./mvnw test                                  # all tests (H2 in-memory; no Postgres needed)
./mvnw test -Dtest=ClassName#methodName      # single test
./mvnw dependency:tree                       # debug version conflicts
```

## Architecture

### Package-by-feature, not layered

Root package `com.att.tdp.issueflow`. Each domain entity gets its own package containing entity + controller + service + repository + enums + a `dto/` sub-package. **Do not introduce root-level `controller/`, `service/`, or `repository/` packages** — that layered layout was deliberately rejected (BUILD_PLAN Phase 1).

Cross-cutting code lives in three top-level packages:

- `common/` — `BaseEntity`, exception types, `GlobalExceptionHandler`, shared DTOs like `PageResponse<T>`.
- `config/` — only Spring `@Configuration` classes (`JpaConfig`, `SecurityConfig`, `OpenApiConfig`, `SchedulingConfig`). No domain code.
- `auth/` — JWT issuing/parsing and `/auth/*` endpoints. The `User` entity itself lives in `user/`.

Two folded-in entities to be aware of: `TicketDependency` lives inside `ticket/`, `Mention` lives inside `comment/`. They're tightly coupled to their parents and intentionally don't get their own top-level packages. Enums live next to their entity, not in a shared `enums/` package.

### BaseEntity and timestamps

`common/BaseEntity` is a `@MappedSuperclass` providing `id`, `createdAt`, `updatedAt` via Spring Data JPA auditing.

- **`@Version` is NOT on `BaseEntity`** — optimistic locking is opt-in per entity, currently only `Ticket` and `Comment`.
- JPA auditing requires `@EnableJpaAuditing` on a `@Configuration` class (`config/JpaConfig`). Without it, `@CreatedDate`/`@LastModifiedDate` silently produce nulls — no error.
- Response DTOs for User/Project/Ticket/Comment hide `createdAt`/`updatedAt` to match the README contract. The fields are still load-bearing in business logic — keep them on the entities:
  - `User.createdAt` → auto-assign tie-break ("oldest registrant first", §3.8).
  - `Comment.createdAt` → mentions feed "newest first" ordering (§3.6).
  - `AuditLog`'s response field is literally named `timestamp` (not `createdAt`) — map accordingly.

### Audit log is centralized

Every state-changing service method calls `AuditService.record(...)`. The legal vocabularies are pinned as enums and **nothing else is permitted** anywhere in the codebase:

- `AuditAction`: `CREATE, UPDATE, DELETE, RESTORE, AUTO_ASSIGN, AUTO_ESCALATE`
- `EntityType`: `USER, PROJECT, TICKET, COMMENT, ATTACHMENT, DEPENDENCY, MENTION`
- `Actor`: `USER, SYSTEM`. When `actor=SYSTEM` (auto-assign, auto-escalate), `performedBy` is `null` — **do not invent a "system user" id.**

The audit log exists and gets wired into create/update/delete paths *before* auto-assign and auto-escalate, so those system actions log naturally.

### Soft delete is global

`Project` and `Ticket` have a nullable `deletedAt` column. Use Hibernate `@SQLRestriction("deleted_at IS NULL")` on the entity so every standard query auto-filters — **don't repeat the filter per repository call**. The "list deleted" / "restore" endpoints use a separate native or JPQL query that bypasses the restriction, and are ADMIN-only (`@PreAuthorize("hasRole('ADMIN')")`).

### Auth

JWT-only. `SecurityFilterChain` protects everything except `/auth/login`. An ADMIN user is seeded at startup (CommandLineRunner or `data.sql`) so the grader can log in before any user exists — `POST /users` is itself JWT-protected per §2.2. Default seed credentials are documented in `run.md` and marked demo-only.

### Concurrency

`Ticket` and `Comment` carry `@Version`. The global exception handler translates `ObjectOptimisticLockingFailureException` to **409 Conflict**.

### PATCH semantics

`PATCH /tickets/:id` uses true partial-update semantics — must distinguish "field absent from JSON" from "field present and explicitly null." Implementation uses `JsonNullable<T>` from `openapi-jackson-databind-nullable` on PATCH DTO fields. This matters because the auto-escalation reset rule keys on **whether `priority` was sent**, not whether its value changed.

### Database

PostgreSQL via `compose.yml` for dev/prod. `ddl-auto: update` during iteration; goal is to flip to `validate` near submission with a hand-curated `schema.sql`. The skeleton ships placeholder `schema.sql` and `data.sql` referencing a `task` table that conflicts with everything — **keep these files present but empty** (Spring requires them when `spring.sql.init.mode: always`).

## Cross-cutting invariants

These are the rules most likely to be violated when a change is made in isolation. Re-read before touching tickets, comments, or the scheduler.

- **DONE tickets are immutable.** One central guard, applied to every PATCH.
- **Auto-assign fires on create only**, never on update. Triggered when `assigneeId == null` on `POST /tickets`. Picks the DEVELOPER in the project with the fewest non-DONE tickets, tie-break by oldest `createdAt`.
- **Auto-escalation never touches `status`**, only `priority` and `isOverdue`. The scheduler is the only writer of `isOverdue` (actor=SYSTEM); a manual `priority` PATCH is the only clearer (actor=USER); status changes never touch the flag. The scheduler skips DONE tickets so the flag freezes after completion (acts as a "completed late" marker).
- **Status transitions:** no backward transitions; no changes to a DONE ticket; cannot transition to DONE if any blocker is not DONE. Centralize in a single service-layer validator that takes a "list of blocker statuses" parameter so dependency rules can be added without changing the signature.
- **CSV import is partial success** — collect per-row errors and return `{ created, failed, errors }`. Never fail the whole batch on one bad row.

## Submission

The HackerRank submission window is **15 minutes** from the moment it's started. The repo must already be public and pushed before opening the invite. The single git repo (`issueflow-java/`) is what gets submitted — keep `main` push-ready.
