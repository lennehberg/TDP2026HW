# Running IssueFlow

## Prerequisites

- **Java 21** (`java -version` should report 21.x).
  *Recommendation: Use [SDKMAN](https://sdkman.io/) for a clean install:*
  ```bash
  curl -s "https://get.sdkman.io" | bash
  source "$HOME/.sdkman/bin/sdkman-init.sh"
  sdk install java 21.0.5-tem
  sdk use java 21.0.5-tem
  ```
- **Docker** & **Docker Compose**. Needed for Postgres in dev/prod profile; tests use H2.
  *[Install Docker Desktop](https://www.docker.com/products/docker-desktop/) (macOS/Windows) or [Docker Engine](https://docs.docker.com/engine/install/) (Linux).*
- **jq** (Optional). Used in the API examples below to parse JSON.
  ```bash
  # macOS
  brew install jq
  # Ubuntu/Debian
  sudo apt-get install jq
  ```

## Setup and Installation

1. **Start Infrastructure**:
   ```bash
   docker compose up -d                # Postgres on :5432 (issueflow / issueflow / issueflow)
   ```

2. **Install Dependencies and Build**:
   ```bash
   ./mvnw clean package                # Downloads dependencies and builds the fat JAR
   ```

## Running the Application

```bash
./mvnw spring-boot:run              # App starts on :8080
```

## Testing

```bash
./mvnw test                         # Runs all tests using H2 in-memory DB (no Postgres required)
```

## API Documentation

- **Swagger UI**: <http://localhost:8080/swagger-ui.html>
- **OpenAPI JSON**: <http://localhost:8080/v3/api-docs>

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
`ISSUEFLOW_SEED_ADMIN_PASSWORD=...`).

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
