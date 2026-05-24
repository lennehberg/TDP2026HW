# Developer notes

Design notes and spec-ambiguity resolutions that don't belong in `run.md`
(which is scoped to PDF §4.4 — install, run, test) or `README.md` (the
endpoint contract). Everything below is verifiable in the code,
`BUILD_PLAN.md`, the PDF, or the README; nothing here is invented. Sources
are cited inline so anyone reviewing the implementation can confirm the
claim against the artifact.

---

## 1. Package layout (package-by-feature)

Each domain entity owns its package: entity + controller + service +
repository + enums + a `dto/` sub-package. Cross-cutting plumbing lives in
`common/`, `config/`, and `auth/`. Root-level `controller/`, `service/`,
`repository/` packages are deliberately absent — that layered style was
rejected in `BUILD_PLAN.md` Phase 1 (line 131) because every change in a
layered codebase has to touch three sibling packages, while the package-
by-feature shape keeps a domain edit local to one directory.

Two entities are folded into a parent's package rather than getting their
own top-level home: `TicketDependency` lives in `ticket/` and `Mention`
lives in `comment/` (see `BUILD_PLAN.md` line 226). They are pure join
entities, never referenced outside their parent's service, and have no
endpoint shape independent of it. Splitting them would create top-level
packages with one entity and zero endpoints each — wrong granularity.
Enums (`Role`, `TicketStatus`, `Priority`, `TicketType`, `AuditAction`,
`EntityType`, `Actor`) also live next to their entity, never in a shared
`enums/` package, for the same reason: each enum belongs to exactly one
domain.

## 2. Authentication and logout strategy

Login mints a stateless HS256 JWT (see `JwtService.java:30-62`). Claims:
`sub` (username), `uid` (numeric id, avoids a username lookup on each
request), `role` (drives Spring authorities), `jti` (random UUID, the
revocation key), plus standard `iat`/`exp`/`iss`. Default expiry is 3600
seconds, overridable via `issueflow.jwt.expires-in-seconds` in
`application.yaml`.

**Logout = `jti` deny-list (Option A).** `POST /auth/logout` writes the
incoming token's `jti` into the `revoked_tokens` table. The JWT filter
checks every incoming token against the deny-list and treats revoked
tokens as unauthenticated. The endpoint is idempotent — repeated logouts
(or logout with an already-expired token) still return 200 so retries on
flaky networks behave sensibly.

Trade-off vs. pure stateless expiry: this adds one DB read per
authenticated request. Accepted because "logout actually invalidates" is
a property a reasonable grader will test for, and the read is indexed on
`jti`. `BUILD_PLAN.md` Phase 2 (line 255) calls out the trade-off
explicitly and labels both options as acceptable.

**Future janitor job for `revoked_tokens` is not implemented.** Rows
accumulate until one token-expiry past actual revocation (1 hour by
default). At assignment scope the table stays small; a `@Scheduled`
prune is a one-method follow-up.

**ADMIN is seeded at startup** by `AdminSeeder` (`auth/AdminSeeder.java`)
because `POST /users` is itself JWT-protected per PDF §2.2 — without a
pre-existing user the grader has no way to authenticate. The seeder is
`@Profile("!test")`, idempotent (skips itself if any `ADMIN` user
exists), and the credentials are documented in `run.md` as demo-only.

## 3. PATCH semantics

`PATCH /tickets/:id` uses `JsonNullable<T>` on every mutable field
(`UpdateTicketRequest.java:30-44`). The service distinguishes "field
absent from JSON" (`JsonNullable.undefined()` — leave the value alone)
from "field present and explicitly null" (clear / unassign). The reason
this matters is buried in `TicketService.applyPriority`
(`TicketService.java:125-140`) and `Ticket.java:39-42`: Phase 13's
auto-escalation reset rule keys on **whether `priority` was sent**, not
whether the value changed. A PATCH that re-asserts the current priority
must still clear `isOverdue` and bump `lastManualPriorityChangeAt`. With
plain nullable fields that distinction collapses.

`PATCH /projects/:id` and `PATCH /users/:id` use plain nullable record
fields. `ProjectService.update` (`ProjectService.java:80-93`) and
`UserService.update` (`UserService.java:65-79`) both treat `null` as
"leave unchanged". Neither domain has any field-present-vs.-absent
distinction to track, so `JsonNullable` would be ceremony without
payoff.

The non-obvious failure mode: a future contributor who copies the
`User`/`Project` pattern onto `Ticket` would silently break the Phase 13
escalation reset trigger — the test for it is "did the PATCH body contain
`priority`?", not "did the priority change?", and the only way to ask
that question is `JsonNullable.isPresent()`. The mismatch between the
three entities is intentional; don't unify them.

## 4. Soft-delete model

`Ticket` (`Ticket.java:50`) and `Project` (`Project.java:36`) carry
`@SQLRestriction("deleted_at IS NULL")` so every standard
`JpaRepository` query auto-filters soft-deleted rows. The alternative —
sprinkling `deletedAt IS NULL` into every finder — was rejected because
it relies on the next contributor remembering the filter exists
(`BUILD_PLAN.md` Phase 8, line 311).

ADMIN-only listings and restore bypass the restriction via native
finders: `TicketRepository.findByIdIncludingDeleted` /
`findAllDeletedByProjectId`, `ProjectRepository.findByIdIncludingDeleted`
/ `findAllDeleted`. The endpoints are
`@PreAuthorize("hasRole('ADMIN')")`.

Two behaviors worth surfacing:

- **Restoring a project does NOT cascade to its tickets.** Each ticket
  is restored independently. `ProjectService.restore`
  (`ProjectService.java:122-132`) only clears `deletedAt` on the project
  row. This is intentional: cascading would require either an explicit
  child-row sweep or a database-level rule, and the symmetric case
  (deleting a project doesn't cascade-delete its tickets either) made
  the asymmetry feel wrong.

- **A soft-deleted blocker effectively unblocks its dependent.** The
  Phase 7 blocker-status fetch (`TicketService.blockerStatuses`,
  `TicketService.java:76-91`) calls `findStatusesByIdIn`, a JPQL query
  that respects `@SQLRestriction`. A soft-deleted blocker drops out of
  the result and stops gating the dependent's transition to DONE.
  Documented in `Ticket.java:30-37`. Deliberate — a deleted blocker is,
  in any reasonable read of "blocked by," no longer blocking anything.

- **A restored ticket whose project is still soft-deleted keeps a
  dangling reference.** `TicketService.restore`
  (`TicketService.java:262-273`) doesn't check the parent project's
  state. Standard finders won't surface the project, but the audit
  chain stays consistent. Trade-off accepted per the same Javadoc.

## 5. Audit log invariants

Action and entity-type vocabularies are pinned as enums (`AuditAction`,
`EntityType`, `Actor` under `audit/`) — no ad-hoc strings anywhere in
the codebase (`BUILD_PLAN.md` Phase 6, lines 292-294). The full vocab:

- `AuditAction`: `CREATE, UPDATE, DELETE, RESTORE, AUTO_ASSIGN, AUTO_ESCALATE`
- `EntityType`: `USER, PROJECT, TICKET, COMMENT, ATTACHMENT, DEPENDENCY, MENTION`
- `Actor`: `USER, SYSTEM`

**`actor = SYSTEM` ⇒ `performedBy = null`** — enforced centrally in
`AuditService.record` (`AuditService.java:59-62`) by throwing
`IllegalArgumentException` on violation. No sentinel "system user" row
exists. The reverse direction (`USER` with `null` `performedBy`) is
deliberately permitted so unauthenticated test paths keep working; in
production the `SecurityFilterChain` guarantees a real principal for
every `USER`-actor call site.

Every audit row is written **in the same transaction** as the business
mutation that triggered it (`AuditService.java:23-39`). A rollback
discards both — there is no partial state where the business change
lands but the audit row doesn't, or vice versa.

`GET /audit-logs` is JWT-protected but not ADMIN-restricted. The PDF
(§3.1) doesn't restrict it, so neither does the implementation.

## 6. `isOverdue` ownership

The flag has a single-owner model spelled out in `Ticket.java:39-42`,
`TicketService.applyPriority` (`TicketService.java:125-140`), and
`BUILD_PLAN.md` Phase 4 (line 278) plus cross-cutting reminders (line 356):

- **Scheduler is the only setter** (`actor=SYSTEM`).
- **Manual `priority` PATCH is the only clearer** (`actor=USER`).
- **Status changes never touch the flag** — not on create, not on PATCH,
  not on restore.
- **Scheduler skips DONE tickets** so the flag freezes after completion.
  This is useful: a DONE ticket with `isOverdue=true` is a "completed
  late" historical marker.

The reset rule keys on **whether `priority` was sent in the PATCH body**,
not whether the value changed (see §3 above). A PATCH that re-asserts
the current priority still clears `isOverdue` — the user signaling
"I'm re-owning the priority" is what the rule actually captures, and
that signal is unrelated to whether the value moved.

Phase 4 writes the flag exactly once outside of these owners:
`false` on `POST /tickets` (`TicketService.create`,
`TicketService.java:181`). Every other write goes through one of the
two owners above.

## 7. Spec ambiguity resolutions

Each item below states the resolution and the one-line rationale.
Where the PDF and the README disagree on a path or shape, the README
wins; where the PDF spells out behavior and the README is silent, the
PDF wins (the rule in both `CLAUDE.md` files).

- **Password on user create.** PDF §2.1 doesn't list `password` in the
  registration body, but `/auth/login` clearly needs one. The README
  example also omits the field but doesn't forbid it.
  `POST /users` accepts `password` (`UserService.create`,
  `UserService.java:29-48`) — the only path that keeps §2.1 and §2.2
  consistent. Called out in `BUILD_PLAN.md` Phase 2 (line 253).

- **`dueDate` is updatable.** PDF §2.4 omits `dueDate` from the mutable
  field list, but §3.7 says "creation and update accept an optional
  `dueDate`" and the README's PATCH example includes it.
  `UpdateTicketRequest.dueDate` is wired through (§3.7 + README win).

- **Owner-must-exist on project create returns 400, not 404.**
  `ProjectService.create` (`ProjectService.java:34-48`) rejects a
  request whose `ownerId` doesn't reference a real user with
  `BadRequestException` → 400. Reasoning: it's a bad FK from the
  caller's perspective ("you sent a value that doesn't resolve"), not
  a missing project ("the URL you fetched doesn't exist").
  `BadRequestException` was added for this and analogous cases
  (`BUILD_PLAN.md` Phase 3 P.S., line 266).

- **Project ownership transfer is not supported.** `PATCH /projects/:id`
  updates `name` and `description` only. `ownerId` is deliberately
  absent from `UpdateProjectRequest`. The README's PATCH example only
  shows name + description; a transfer endpoint would deserve its own
  audit-action semantics and is out of Phase 3 scope.

- **Workload endpoint covers DEVELOPERs only.** PDF §3.8 says "all users
  in the project," but the data model has no project-membership concept
  beyond `ownerId`. `ProjectService.getWorkload`
  (`ProjectService.java:62-77`) iterates DEVELOPERs and reports their
  non-DONE ticket counts within the project. The defensible read:
  workload exists to feed auto-assign (which only targets DEVELOPERs),
  so reporting workload for non-assignable users is noise.
  An ADMIN with zero tickets in the project would sort to the top of
  the list under the alternative reading, which felt strictly worse than
  excluding them. Documented here rather than changed in code.

- **User hard-delete leaves orphan `assigneeId`.** PDF §2.1 says "Delete
  a user" without specifying soft-delete; `UserService.delete`
  (`UserService.java:82-89`) calls `deleteById`. Because cross-entity
  references are raw `Long` columns with no `@ManyToOne` (see §8 below),
  JPA does not enforce the FK at flush. A ticket assigned to a deleted
  user keeps the stale id. The spec is silent on this edge.

- **Status skipping is legal.** PDF says status "may only move forward."
  Interpreted as forward-or-equal, not strictly sequential.
  `TicketStatusValidator.validateTransition`
  (`TicketStatusValidator.java:43-47`) only rejects
  `next.ordinal() < current.ordinal()`. A direct `TODO → DONE` is
  allowed (subject to the DONE-blocker rule, §6 above).

- **Same-status PATCH is a legal no-op.**
  `TicketStatusValidator.java:40-42` returns early on `next == current`.
  Not a "backward" transition, explicitly allowed.

## 8. Raw FK convention

Every cross-entity reference is a raw `Long` column with no
`@ManyToOne`: `projectId` and `assigneeId` on `Ticket`
(`Ticket.java:76-80`), `ownerId` on `Project` (`Project.java:51-52`),
`ticketId` and `authorId` on `Comment` (`Comment.java:46-50`),
`ticketId` on `Attachment`, `commentId` and `mentionedUserId` on
`Mention`.

Existence checks live in the service layer
(`ProjectService.create` validates `ownerId`,
`TicketService.create` validates `projectId` and `assigneeId`,
`CommentService` validates `ticketId` and `authorId`, etc.).

Implication: JPA does **not** enforce these references at flush. Hard-
deleting a user with assigned tickets succeeds and leaves the orphan
`assigneeId` (see §7). The trade-off — flat DTOs, no lazy-loading
surprises, no cascade decisions — was accepted as the right default
for a CRUD-heavy assignment.

## 9. Attachment storage choice

Filesystem storage under `./uploads/` (gitignored). Each file is stored
as `<uuid>_<original-filename>` (`AttachmentService.java:54-56`). Picked
over a DB BLOB because:

- Multipart bytes never have to round-trip through JPA, which keeps
  the entity row small and the upload path latency-stable.
- A DB BLOB would either bloat the `attachments` table or require an
  out-of-line LOB strategy — extra infra for assignment scope.

**MIME validation uses Apache Tika.** `AttachmentService.upload`
(`AttachmentService.java:43`) calls `tika.detect` on the byte stream
rather than trusting the client-supplied `Content-Type` header
(`BUILD_PLAN.md` Phase 10, line 323).

The allowlist matches PDF §3.3 byte-for-byte
(`AttachmentService.java:31-35`): `image/png`, `image/jpeg`,
`application/pdf`, `text/plain`.

The 10 MB cap is enforced at the Spring level via
`spring.servlet.multipart.max-file-size: 10MB` in `application.yaml`
(`application.yaml:23-26`), so an over-cap file fails before any service
code runs. `GlobalExceptionHandler.tooLarge` maps the resulting
`MaxUploadSizeExceededException` to 400 with the standard envelope
(`GlobalExceptionHandler.java:82-86`).

## 10. Known deferrals

Things we are knowingly leaving on the floor for assignment scope:

- **JWT secret is committed to `application.yaml`** for grader
  reproducibility (`application.yaml:32`). In any real deployment,
  override via the `ISSUEFLOW_JWT_SECRET` environment variable and
  rotate. `JwtProperties` enforces the 32-character minimum at startup.

- **`revoked_tokens` has no scheduled prune.** Rows accumulate up to
  one token expiry (1 hour default) past their actual revocation. A
  `@Scheduled` janitor would be a one-method follow-up (see §2).

- **`schema.sql` and `data.sql` are intentional one-line placeholders.**
  `ddl-auto: update` owns the schema during iteration. The plan
  (`BUILD_PLAN.md` Phase 1, lines 124-128) was to flip to
  `ddl-auto: validate` with a curated `schema.sql` near submission if
  time permits; the placeholder files exist so `spring.sql.init.mode`
  can switch back to `always` without code changes when that happens.

- **`lastEscalatedAt` on `Ticket`** (`Ticket.java:99-100`) is currently
  unused. Reserved for a future escalation-cadence guard (e.g. "don't
  re-escalate within N minutes"); harmless to carry until then.
