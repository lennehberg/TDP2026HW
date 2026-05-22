# AI Usage Log

Per requirements §4.5: AI tooling was used during implementation. This file
records which model, in what role, and for which phases.

## Tools

- **Claude Code (CLI)** running model **Claude Opus 4.7 (1M context)** — used
  interactively for code generation, refactoring, test scaffolding, and as a
  pair-programming partner on design decisions documented in `BUILD_PLAN.md`.

## Phase 2 — Authentication (this commit set)

Used for:

- Scaffolding the `auth/` package layout (`JwtProperties`, `JwtService`,
  `JwtAuthenticationFilter`, `AuthService`, `AuthController`, `RevokedToken`).
- Writing the `SecurityFilterChain` configuration with the JWT filter
  registered before `UsernamePasswordAuthenticationFilter`.
- Writing the test suite (`JwtServiceTest`, `AuthControllerTest`) and the
  `TestSecurityConfig` test bypass.
- Drafting `run.md` and this file.

Design decisions (logout strategy, package layout, audit enum vocabulary)
were author-driven; the model implemented the chosen approach and surfaced
trade-offs (e.g. stateless vs deny-list logout) when asked.

All generated code was reviewed and tested locally before commit.
