# IssueFlow — Build Plan

Stack: **Java 21 + Spring Boot 3.4** (skeleton at `Homework Assignment/TDP2026HW/issueflow-java`).

The phases below are ordered so each one builds on the previous, and so cross-cutting concerns (audit log, optimistic locking, soft-delete column) are in place before features that depend on them. Don't jump ahead — phase 6 (audit log) in particular has to exist before auto-assign/auto-escalate so those system actions can be logged correctly per spec §3.1.

---

## Phase 0 — Environment

Everything in this phase happens before a single line of feature code is written. Goal: be able to clone the repo on a fresh machine, run three commands, and have a working dev loop. If any of these fail later you'll lose hours to environment debugging instead of solving the assignment.

### System prerequisites
- **JDK 21** — required by `pom.xml`. On macOS the cleanest install is via SDKMAN:
  ```bash
  curl -s "https://get.sdkman.io" | bash
  sdk install java 21.0.5-tem
  ```
  Then in `~/.zshrc` (or `~/.bash_profile`): `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`. Verify with `java -version` → should print `21.x`.
- **Docker Desktop** or **OrbStack** (OrbStack is lighter on macOS and drop-in compatible). Either works with the provided `compose.yml`.
- **Git** — should already be installed; verify with `git --version`.

### Editor — IntelliJ IDEA Community
Install **IntelliJ IDEA Community Edition** (free). Spring Boot is annotation-heavy and the IDE pays for itself in:
- `application.yaml` schema completion (typos in property names get flagged).
- Maven import on open — JDK and dependencies resolve automatically.
- Endpoints tool window — live list of every REST mapping, useful for cross-checking the README contract.
- JPA entity navigation, Hibernate SQL logging, integrated test runner.

Lombok works out of the box (no plugin install needed in recent versions). The Spring-specific features locked behind Ultimate are nice but not required for this assignment.

### GitHub repo
Create the public repo **now**, not on submission day. The HackerRank window is 15 minutes from start.
```bash
cd "Homework Assignment/TDP2026HW/issueflow-java"
git init
git remote add origin git@github.com:<you>/issueflow.git
git add . && git commit -m "Initial skeleton import"
git push -u origin main
```
Keep that URL on your clipboard for submission day.

### Clean out the skeleton leftovers
The skeleton ships with a placeholder `task` table that will conflict with everything you build:
- Delete or empty `src/main/resources/schema.sql` and `src/main/resources/data.sql` before first run, otherwise Spring will execute them on every boot.

### pom.xml additions
The skeleton's `pom.xml` is missing dependencies you'll definitely need. Add now so you don't context-switch later:

```xml
<!-- Spring Security (auth scaffolding) — Phase 2 -->
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-security</artifactId>
</dependency>

<!-- JJWT (JWT issuing and parsing) — Phase 2 -->
<dependency>
  <groupId>io.jsonwebtoken</groupId>
  <artifactId>jjwt-api</artifactId>
  <version>0.12.6</version>
</dependency>
<dependency>
  <groupId>io.jsonwebtoken</groupId>
  <artifactId>jjwt-impl</artifactId>
  <version>0.12.6</version>
  <scope>runtime</scope>
</dependency>
<dependency>
  <groupId>io.jsonwebtoken</groupId>
  <artifactId>jjwt-jackson</artifactId>
  <version>0.12.6</version>
  <scope>runtime</scope>
</dependency>

<!-- Apache Tika (real MIME-type sniffing for attachments) — Phase 10 -->
<dependency>
  <groupId>org.apache.tika</groupId>
  <artifactId>tika-core</artifactId>
  <version>2.9.2</version>
</dependency>

<!-- Springdoc — auto-generated Swagger UI at /swagger-ui.html.
     Optional but high-value: gives you and the grader a live UI for the API. -->
<dependency>
  <groupId>org.springdoc</groupId>
  <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
  <version>2.6.0</version>
</dependency>
```

Run `./mvnw dependency:tree` after editing to catch version conflicts before they bite.

### Workflow tooling
- **Bruno** (free, file-based API client) — save the collection inside the repo as `api/`, and the grader can replay every request. Postman works too but its collections don't live in git as cleanly.
- **DBeaver Community** or `psql` CLI for spot-checking the DB — useful when verifying the audit log table is filling correctly.

### `.gitignore` review
The skeleton ships a `.gitignore`. Open it and add (if missing):
- `target/`, `.idea/`, `.vscode/`, `.zed/`
- `*.env`, `application-local.yaml`
- `uploads/` (you'll create this in Phase 10 for attachment storage if you go with filesystem)

### Smoke test
At the end of Phase 0 you should be able to run all of these in order from `issueflow-java/`:
```bash
docker compose up -d                # Postgres on :5432
./mvnw clean package                # builds the jar
./mvnw spring-boot:run              # boots the app on :8080
./mvnw test                         # the placeholder test passes
```
If any step fails, fix it before moving to Phase 1.

---

## Phase 1 — Foundation

### Confirm Postgres is up
`docker compose up -d` from the `issueflow-java` folder. Already smoke-tested in Phase 0; this step is just making sure the container is running before any code change.

### Skeleton SQL files
Empty `src/main/resources/schema.sql` and `src/main/resources/data.sql` (don't delete the files, just clear them). Rely on JPA `ddl-auto: update` to generate tables from entities while iterating. Optionally near submission, dump the generated DDL into `schema.sql` and flip `ddl-auto: validate` for a more "production-shaped" repo.

> **Correction (from a Claude Code test run):** the original plan above claimed Spring tolerates literally-empty `.sql` files when `spring.sql.init.mode: always` is set. It does not — the script initializer throws `'script' must not be null or empty` on boot. Two workable options:
> 1. **Keep `mode: always` and put a single SQL comment line in each file** so the script is non-empty. This is what the current repo does (`-- Intentionally empty during iteration.` lives in both `schema.sql` and `data.sql`). Matches the original BUILD_PLAN intent of having `mode: always` ready to pick up curated SQL near submission.
> 2. **Set `spring.sql.init.mode: never` in both `src/main/resources/application.yaml` and `src/test/resources/application.yaml`** so the script initializer is bypassed entirely while JPA `ddl-auto: update` owns the schema. Flip back to `always` once curated `schema.sql`/`data.sql` content actually exists near submission.
>
> The current repo uses option 1 in the files and option 2 in the configs (defense in depth — even if a future edit accidentally empties the comments, `mode: never` keeps the app booting). Either approach is fine; just don't leave the files empty *and* `mode: always` at the same time.

### Package layout — package by feature
Root package stays `com.att.tdp.issueflow`. Each domain entity gets its own package; cross-cutting plumbing lives in `common/`, `config/`, `auth/`. Each feature package holds its entity, controller, service, repository, enums, and a `dto/` sub-package. **Avoid `controller/` / `service/` / `repository/` packages at the root** — that's the layered style we explicitly rejected.

```
src/main/java/com.att.tdp.issueflow/
├── IssueFlowApplication.java        (already exists)
│
├── common/                          ← cross-cutting plumbing
│   ├── BaseEntity.java              (@MappedSuperclass — id + timestamps)
│   ├── exception/
│   │   ├── NotFoundException.java
│   │   ├── ConflictException.java
│   │   ├── ForbiddenException.java
│   │   └── GlobalExceptionHandler.java   (@RestControllerAdvice)
│   └── dto/
│       └── PageResponse.java        (generic { data, total, page } envelope)
│
├── config/                          ← Spring configuration classes
│   ├── JpaConfig.java               (@EnableJpaAuditing lives here)
│   ├── SecurityConfig.java          (filter chain, password encoder — Phase 2)
│   ├── OpenApiConfig.java           (Swagger metadata — optional)
│   └── SchedulingConfig.java        (@EnableScheduling — Phase 13)
│
├── auth/                            ← Phase 2 — JWT + login endpoints
│   ├── AuthController.java          (POST /auth/login, /auth/logout, GET /auth/me)
│   ├── AuthService.java
│   ├── JwtService.java              (sign + parse tokens)
│   ├── JwtAuthenticationFilter.java (reads Authorization header)
│   └── dto/
│       ├── LoginRequest.java
│       └── LoginResponse.java
│
├── user/                            ← Phase 2
│   ├── User.java                    (extends BaseEntity, no @Version)
│   ├── UserController.java
│   ├── UserService.java
│   ├── UserRepository.java
│   ├── Role.java                    (enum: ADMIN, DEVELOPER)
│   └── dto/
│       ├── CreateUserRequest.java
│       ├── UpdateUserRequest.java
│       └── UserResponse.java
│
├── project/                         ← Phase 3 (same shape as user/)
│   ├── Project.java                 (extends BaseEntity, has deletedAt, no @Version)
│   ├── ProjectController.java
│   ├── ProjectService.java
│   ├── ProjectRepository.java
│   └── dto/
│
├── ticket/                          ← Phase 4
│   ├── Ticket.java                  (extends BaseEntity, @Version, has deletedAt, isOverdue)
│   ├── TicketController.java
│   ├── TicketService.java
│   ├── TicketRepository.java
│   ├── TicketStatus.java            (enum + transition rules)
│   ├── Priority.java                (enum)
│   ├── TicketType.java              (enum)
│   ├── TicketDependency.java        (join entity — folded into ticket/ rather than its own package)
│   └── dto/
│
├── comment/                         ← Phase 5
│   ├── Comment.java                 (extends BaseEntity, @Version)
│   ├── CommentController.java
│   ├── CommentService.java
│   ├── CommentRepository.java
│   ├── Mention.java                 (join entity — folded into comment/ rather than its own package)
│   ├── MentionController.java       (GET /users/:userId/mentions — Phase 9)
│   └── dto/
│
├── audit/                           ← Phase 6
│   ├── AuditLog.java                (extends BaseEntity; response uses its own `timestamp` field)
│   ├── AuditController.java
│   ├── AuditService.java            (called by every state-changing service)
│   ├── AuditAction.java             (enum: CREATE, UPDATE, DELETE, RESTORE, AUTO_ASSIGN, AUTO_ESCALATE)
│   ├── EntityType.java              (enum: USER, PROJECT, TICKET, COMMENT, ATTACHMENT, DEPENDENCY, MENTION)
│   ├── Actor.java                   (enum: USER, SYSTEM)
│   └── dto/
│
├── attachment/                      ← Phase 10
│   ├── Attachment.java
│   ├── AttachmentController.java
│   ├── AttachmentService.java
│   ├── AttachmentRepository.java
│   └── dto/
│
├── csv/                             ← Phase 11
│   ├── CsvExportService.java        (GET /tickets/export)
│   └── CsvImportService.java        (POST /tickets/import — partial-success summary)
│
└── scheduling/                      ← Phase 13
    └── EscalationJob.java           (@Scheduled — promotes priority, sets isOverdue)
```

Decisions baked into the tree above (worth re-stating so they don't drift):

- **Dependencies and mentions are folded into their parent entity's package** (`ticket/TicketDependency.java`, `comment/Mention.java`). They're tightly coupled to their parent and don't have separate top-level concepts. If they grow legs you can split later.
- **Enums live next to their entity**, not in a shared `enums/` package. Each enum belongs to exactly one domain.
- **`audit/` is its own top-level package** — it has its own entity and endpoint, and *every other feature* calls into it via `AuditService`. Cross-cutting *and* a feature.
- **`config/` holds only Spring `@Configuration` classes**, not domain code. `JpaConfig` (with `@EnableJpaAuditing`) is created in this phase; the other config classes appear in their respective phases.
- **`common/dto/PageResponse.java`** is a generic `{ data, total, page }` envelope reused by any paginated endpoint — the mentions endpoint (§3.6) requires this exact shape, so build the type once and reuse.

### `BaseEntity` and JPA auditing
`@MappedSuperclass` in `common/` with three fields: `id` (Long, `@GeneratedValue(IDENTITY)`), `createdAt` (`Instant`, `@CreatedDate`, `updatable=false`), `updatedAt` (`Instant`, `@LastModifiedDate`). All feature entities extend it. `@Version` is **not** on `BaseEntity` — it stays per-entity on `Ticket` and `Comment` only.

Reasoning for timestamps in the base (even though most response DTOs hide them):
- `User.createdAt` is *load-bearing* for the auto-assignment tie-break (PDF §3.8: "oldest registrant first").
- `Comment.createdAt` is *load-bearing* for the mentions endpoint's "newest first" ordering (PDF §3.6).
- `AuditLog`'s response requires a `timestamp` field directly (README).

Since two entities need `createdAt` for spec compliance anyway, hoisting it into the base is the path of least resistance and `updatedAt` is one free annotation away. DTOs hide both fields for User/Project/Ticket/Comment to keep the response shape exactly as specified in the README.

**Don't forget `@EnableJpaAuditing`** on a `@Configuration` class (`config/JpaConfig.java`). Without it, the `@CreatedDate` / `@LastModifiedDate` callbacks never fire and you'll get null timestamps with no error message.

> **P.S. (review notes — 2026-05-22):** Phase 1 implementation walks through cleanly with three small gaps worth closing before they get harder to spot later.
> 1. `ForbiddenException` is declared in `common/exception/` but `GlobalExceptionHandler` has no `@ExceptionHandler` for it — a throw currently becomes a 500 instead of a 403. Two-line fix; add it now so the contract is consistent before service-layer auth checks start firing in later phases.
> 2. `common/dto/PageResponse.java` from the layout above is not yet created. Only the mentions endpoint (§3.6) actually consumes it in Phase 9, so this is genuinely deferrable — but the plan's intent is "build once, reuse," so stubbing the record now keeps Phase 9 cheap.
> 3. `BaseEntity` carries leftover dev-note comments at the bottom (`// getters only for id/createdAt; ...`). Cosmetic — delete before the submission diff.
>
> None of these block Phase 2; they're cleanup, not correctness.

## Phase 2 — Auth + Users (§2.1, §2.2)
- `User` entity: id, username (unique), email (unique), passwordHash, fullName, role enum {ADMIN, DEVELOPER}, createdAt.
- Note: the spec doesn't list `password` in §2.1's registration body, but `/auth/login` clearly needs one — add it to the create endpoint and document the decision in `run.md`.
- BCrypt for password hashing.
- JWT auth: `/auth/login` issues, `/auth/logout` invalidates (deny-list table is the simplest correct option; pure stateless expiry is acceptable if documented), `/auth/me` returns the principal.
- Global `SecurityFilterChain` protecting everything except `/auth/login`.
- **Seed an initial ADMIN user at startup** via `CommandLineRunner` (or `data.sql`) so `POST /users` can stay JWT-protected per §2.2's "all endpoints protected by JWT" wording. Default credentials documented in `run.md` (e.g., `admin / admin`) and flagged as a demo seed only — the grader needs *some* way to log in before any user exists. Without this seed there's a chicken-and-egg problem: §2.2 says everything is JWT-protected, but §2.1 needs a way to create the first user.
- `@ControllerAdvice` for `MethodArgumentNotValidException` → 400 with field-level messages.

## Phase 3 — Projects (§2.3)
- `Project` entity: id, name, description, ownerId (FK → User), createdAt, **deletedAt (nullable)** — add the soft-delete column now so we don't migrate later.
- CRUD endpoints per the README contract.
- `DELETE /projects/:id` sets `deletedAt` instead of removing.

> **P.S. (review notes — 2026-05-22):** Phase 3 implementation walks the original plan with three deliberate refinements documented for the record.
> 1. **`BadRequestException` added to `common/exception/`** (+ handler in `GlobalExceptionHandler` mapping to 400). The plan was silent on how to report "well-formed body, but `ownerId` doesn't reference a real user"; the only pre-existing service-throwable exceptions were `NotFound` (404), `Conflict` (409), and `Forbidden` (403). Reporting an invalid FK as 404 would have conflated "the project is missing" with "the caller's FK is bad," so a dedicated 400 type was the cleanest fit. Reusable by future phases (Ticket's `projectId` / `assigneeId` FK validation, Comment's `ticketId`, etc.).
> 2. **Soft-delete guards deferred wholesale to Phase 8 ("Option B").** Phase 3 lands the `deleted_at` column and the `DELETE → setDeletedAt(now)` behavior but adds no per-method `deletedAt IS NULL` guards. Between Phase 3 and Phase 8, `GET /projects`, `GET /projects/:id`, PATCH, and DELETE all still see soft-deleted rows. Trade-off accepted: avoids three guards now that Phase 8's `@SQLRestriction` will make redundant, in exchange for a known intermediate-state window where the API leaks soft-deleted rows. Documented in `run.md` under "Project endpoints (Phase 3)" so the grader sees the choice was deliberate.
> 3. **`description` column widened from the original 128 to 1024.** 128 chars is the kind of cap that bites during a live demo with a long description string; 1024 is comfortable without being so wide it changes the storage profile. Validation on `CreateProjectRequest.description` mirrors at `@Size(max = 1024)`.
>
> None of these block Phase 4. The seven Phase-3 unit tests from the checklist's §6 are intentionally deferred to Phase 14's test sweep.

> **P.P.S. (completeness audit — 2026-05-22):** Phase 3 walks cleanly against both the BUILD_PLAN and the README contract. Spec requirements all landed (entity shape, 5 CRUD endpoints, `ProjectResponse` matches README verbatim, DELETE soft-deletes via `Instant.now()`). All three P.S. refinements applied (`BadRequestException` + handler, soft-delete guards deferred to Phase 8, `description` widened to 1024 in both column and `@Size`). Ambiguity resolutions documented in `run.md` "Project endpoints (Phase 3)". Two carry-overs flagged for future phases — neither blocks Phase 4:
> 1. **Audit retro-wire owed to Phase 6.** `ProjectService.create/update/delete` do not yet call `AuditService.record(...)` because Phase 6 hasn't built `AuditService` yet. Phase 6's checklist below has been updated to call this out explicitly.
> 2. **`updatedAt` smoke test owed to Phase 6.** `ProjectService.update` relies on JPA dirty-checking inside `@Transactional` to flush; `@LastModifiedDate` should fire because `@EnableJpaAuditing` is on `JpaConfig`. Eyeball the `updated_at` column once Phase 6's audit log makes timestamps visible — if it's null after a PATCH, the auditing wiring is broken even though the response shape hides it.

## Phase 4 — Tickets, core (§2.4)
- `Ticket` entity: id, title, description, status enum, priority enum, type enum, projectId (FK), assigneeId (nullable FK), dueDate (nullable), `isOverdue` (default `false`), `@Version` long for optimistic locking, deletedAt. (createdAt/updatedAt come from `BaseEntity`.)
- **`isOverdue` is added day 1 and surfaced in every Ticket DTO** even before Phase 13 wires the scheduler — §3.7 says the flag is "visible in all GET responses," and we don't want the response shape to change mid-build. Ownership rule (agreed reading of §3.7, which is silent on status changes): the **scheduler is the only writer** when actor=SYSTEM, the **manual priority PATCH is the only clearer** when actor=USER, **status changes never touch the flag**, and the scheduler **only processes non-DONE tickets** — so once a ticket reaches DONE the flag freezes at its final value (useful as a "completed late" historical marker). Document this interpretation in `run.md`.
- Status-transition guard: a service-layer validator that rejects backward transitions and any change to a DONE ticket. **Design the validator's API now to also accept a "list of blocker statuses" parameter** (unused in Phase 4, populated in Phase 7), so Phase 7 doesn't have to refactor the signature when it adds the DONE-blocked-by-unresolved-blockers rule.
- Optimistic locking via `@Version` — catch `ObjectOptimisticLockingFailureException` and return 409 Conflict.
- **True partial PATCH semantics on `PATCH /tickets/:id`**: distinguish "field absent in JSON" from "field present and explicitly null." Use `JsonNullable<T>` (`openapi-jackson-databind-nullable`) on PATCH DTO fields. Required because Phase 13's "manual priority change resets `is_overdue`" rule keys on *whether `priority` was sent*, not on whether the value changed.
- Don't yet implement auto-assignment here; just persist with whatever `assigneeId` is provided (or null).

> **P.S. (review note — 2026-05-22, queued from Phase 1 review):** `pom.xml` does not yet include `org.openapitools:jackson-databind-nullable`, which is required for the `JsonNullable<T>` PATCH-DTO fields described above. Add it as a first step of Phase 4 before writing the PATCH DTOs, otherwise the "field absent vs. field present and null" distinction collapses and the Phase 13 escalation-reset rule loses its trigger.

## Phase 5 — Comments (§2.5)
- `Comment` entity: id, ticketId, authorId, content, `@Version`, createdAt, updatedAt.
- Same concurrency rule: optimistic lock → 409 on conflict.

## Phase 6 — Audit log (§3.1)
- `AuditLog` entity: id, action, entityType, entityId, performedBy (nullable), actor enum {USER, SYSTEM}, timestamp, optional JSON payload.
- **Action vocabulary** pinned as an enum `AuditAction`: `CREATE, UPDATE, DELETE, RESTORE, AUTO_ASSIGN, AUTO_ESCALATE`. Every later phase that records an audit entry uses these exact values — no ad-hoc strings.
- **Entity-type vocabulary** pinned as an enum `EntityType`: `USER, PROJECT, TICKET, COMMENT, ATTACHMENT, DEPENDENCY, MENTION`. Same rule.
- **`performedBy = null` whenever `actor = SYSTEM`** (auto-assign, auto-escalate). No sentinel "system user" id. Phases 12/13 must follow this.
- `AuditService.record(...)` called from every create/update/delete service method.
- `GET /audit-logs` with optional `entityType`, `entityId`, `action`, `actor` query params.
- **Wire this in before later phases** so auto-assign and auto-escalate can log naturally.
- **Retro-wire checklist for services built before Phase 6** — these were intentionally left without `AuditService` calls because the service didn't exist yet. When Phase 6 lands, add `AuditService.record(...)` to each:
  - `UserService.create / update / delete` → `EntityType.USER`, actor=USER, `performedBy` = current principal id.
  - `ProjectService.create / update / delete` → `EntityType.PROJECT`, actor=USER, `performedBy` = current principal id. Note: `delete` is a soft delete (sets `deletedAt`), so the action is still `AuditAction.DELETE`, not a separate "soft delete" verb.
  - Any `AuthService` events that create state (e.g., revocation rows in `revoked_tokens`) — decide whether token revocation deserves an audit row or is purely operational. Recommended: skip — auth events are noisy and not in the spec's audit examples.
- **`updatedAt` smoke test (carried from Phase 3 P.P.S.).** Once `AuditService` is logging, PATCH a project and confirm both the audit row and the entity's `updated_at` column updated. If `updated_at` is null, `@EnableJpaAuditing` isn't taking effect even though the entity dirty-flushed — fix `JpaConfig` before continuing.

## Phase 7 — Ticket dependencies (§3.2)
- Join table `ticket_dependency(ticket_id, blocked_by_id)`.
- POST/GET/DELETE endpoints per the README.
- Same-project validation on add.
- Add a second guard to the lifecycle validator: cannot transition to DONE if any blocker is not DONE.

## Phase 8 — Soft delete finalization (§3.5)
- Add Hibernate `@SQLRestriction("deleted_at IS NULL")` (or `@Where`) on Ticket and Project so every standard query auto-filters.
- For deleted listings, use a separate native or JPQL query that ignores the restriction.
- ADMIN-only endpoints: `GET /tickets/deleted`, `GET /projects/deleted`, `POST /tickets/:id/restore`, `POST /projects/:id/restore`. Use `@PreAuthorize("hasRole('ADMIN')")`.

## Phase 9 — Mentions (§3.6)
- `Mention` entity: id, commentId, mentionedUserId, createdAt.
- On comment create/update: regex `@([A-Za-z0-9_]+)` (case-insensitive lookup against `username`), upsert mention links, drop removed ones.
- `GET /users/:userId/mentions` with `page`, `pageSize` query params, newest first.
- Add `mentionedUsers` array to every comment response DTO.

## Phase 10 — Attachments (§3.3)
- `Attachment` entity: id, ticketId, filename, contentType, size, storagePath (or BLOB).
- Multipart upload endpoint; reject >10 MB (already enforced by `spring.servlet.multipart.max-file-size`) and any MIME outside the allowlist (validate via `Tika` or by sniffing the bytes — don't trust the client header alone).
- Pick filesystem storage under `./uploads/` (gitignored) or DB BLOB — document the choice.

## Phase 11 — CSV export/import (§3.4)
- Export: `GET /tickets/export?projectId=...` streams a CSV using `commons-csv` with the spec's exact field list.
- Import: `POST /tickets/import` accepts multipart `file` + `projectId` form field. Parse with `commons-csv` (handles commas and quotes inside fields out of the box). For each row, attempt to create a ticket; collect failures into the `errors` array; return `{ created, failed, errors }`.

## Phase 12 — Workload + auto-assignment (§3.8)
- `GET /projects/:projectId/workload` — count non-DONE tickets per user in the project, sort ascending.
- On ticket create when `assigneeId == null`: query DEVELOPER users for the project, compute workload, pick lowest, tie-break by oldest `createdAt` (or smallest id). If empty, save with `assigneeId = null` and don't error.
- Emit an audit log entry with `actor=SYSTEM`, `action=AUTO_ASSIGN`.
- **Important:** do NOT trigger on update; only on create.

## Phase 13 — Auto-escalation scheduler (§3.7)
- `@Scheduled(fixedRate = 60_000)` (or longer) job in a `EscalationService`.
- For each non-DONE ticket with `dueDate` past now: if priority < CRITICAL, bump one level; if priority == CRITICAL, set `is_overdue = true`. Idempotent — running twice is safe.
- Add a `lastManualPriorityChangeAt` (or boolean flag) to Ticket. When PATCH changes `priority`, clear `is_overdue` and reset the flag so the next cycle re-evaluates from the new priority.
- Never change `status`.
- Emit audit log entries with `actor=SYSTEM`, `action=AUTO_ESCALATE`.

## Phase 14 — Tests + docs
- Unit tests for: status lifecycle guard, DONE-immutable rule, concurrency 409, dependency block, soft-delete filter, mention parsing, escalation idempotency at CRITICAL, escalation reset on manual change, auto-assign tie-break, CSV with quoted commas.
- Integration tests using `@SpringBootTest` + `TestRestTemplate` or `MockMvc`; H2 is already in `pom.xml` for the test scope.
- Fill in `run.md` — install JDK, `docker compose up -d`, `./mvnw clean package`, `./mvnw spring-boot:run`, `./mvnw test`.
- Fill in `prompts.md` — list the main prompts used with the agent and **explicitly name the model** (per §4.5 and the requirements PDF).

---

## Cross-cutting reminders

- **DONE tickets are immutable** — guard once, centrally, applied to every PATCH.
- **Auto-assign fires on create only**, never on update.
- **Auto-escalation never touches status**, only priority + is_overdue.
- **`isOverdue` ownership**: scheduler is the only setter (SYSTEM), manual priority PATCH is the only clearer (USER), status changes never touch it, scheduler skips DONE tickets so the flag freezes for historical record.
- **Soft-deleted rows must be invisible to every standard endpoint** — use the global `@SQLRestriction` so you can't forget per-query.
- **CSV import is partial success** — don't fail the whole batch on one bad row.
- **Audit enums are the only legal values** — `AuditAction` and `EntityType`. No ad-hoc strings anywhere in the codebase.
- **README contract vs PDF rules**: README endpoint paths win; PDF business rules win.

## Submission reminder

The HackerRank submission window is **15 minutes from the moment you start it** — the repo must be public, complete, and pushed *before* you open the invite. Have the URL on the clipboard.
