# Running IssueFlow

## Prerequisites

- Java 21 (`java -version` should report 21.x)
- Docker (only needed for Postgres in dev/prod profile; tests use H2)

## Quick start

```bash
docker compose up -d                # Postgres on :5432 (issueflow / issueflow / issueflow)
./mvnw spring-boot:run              # app on :8080
./mvnw test                         # run all tests (H2 in-memory; no Postgres needed)
```

Swagger UI is at <http://localhost:8080/swagger-ui.html>; OpenAPI JSON at <http://localhost:8080/v3/api-docs>.

## Demo ADMIN credentials

On first boot against an empty database, `AdminSeeder` creates a single ADMIN
user so the API can be used immediately (POST `/users` is itself
JWT-protected per spec §2.2). **These are demo-only — change before any
non-local deployment.**

| Username | Password | Email                  |
|----------|----------|------------------------|
| `admin`  | `admin`  | `admin@issueflow.local`|

Override via `issueflow.seed.admin.username` / `.password` / `.email` / `.full-name`
in `application.yaml` or as environment variables (e.g.
`ISSUEFLOW_SEED_ADMIN_PASSWORD=...`). The seeder skips itself if any user
with role=ADMIN already exists, so renaming the demo admin disables the
re-create on restart.

The seeder is `@Profile("!test")`, so test runs never see it.

## Example: login and authenticated call

```bash
# 1. Login → token
TOKEN=$(curl -s -X POST localhost:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin"}' | jq -r .accessToken)

# 2. Authenticated request
curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/auth/me

# 3. Logout — same token now returns 401 on subsequent use
curl -s -X POST -H "Authorization: Bearer $TOKEN" localhost:8080/auth/logout
curl -i -H "Authorization: Bearer $TOKEN" localhost:8080/auth/me   # 401
```

---
