# IssueFlow – Project Context

## Overview
IssueFlow is a Ticket Management Backend Platform built with **Java 21** and **Spring Boot 3.4**. It follows a "package-by-feature" architectural style to ensure high cohesion and maintainability. The project is currently in its early development stages, with core foundations and authentication modules completed.

### Main Technologies
- **Java 21** (JDK 21)
- **Spring Boot 3.4.2**
- **Spring Data JPA** (PostgreSQL for production, H2 for testing)
- **Spring Security** with **Stateless JWT** authentication
- **Lombok** for boilerplate reduction
- **Apache Commons CSV** for ticket import/export (Phase 11)
- **Apache Tika** for MIME-type validation (Phase 10)
- **Springdoc OpenAPI** (Swagger UI available at `/swagger-ui.html`)

## Project Structure
The project uses a feature-based package layout under `com.att.tdp.issueflow`:
- `auth/`: JWT issuance, parsing, login/logout, and token revocation.
- `user/`: User entity, CRUD operations, and role management (ADMIN, DEVELOPER).
- `common/`: Shared utilities, base entity (ID + timestamps), and global exception handling.
- `config/`: Spring configuration for Security, JPA auditing, etc.
- `project/`, `ticket/`, `comment/`, `audit/`, `attachment/`, `csv/`, `scheduling/`: Upcoming feature modules (see `BUILD_PLAN.md`).

## Building and Running

### Prerequisites
- **JDK 21**
- **Docker** (for PostgreSQL)

### Commands
- **Build:** `./mvnw clean package`
- **Run:** `./mvnw spring-boot:run`
- **Test:** `./mvnw test`
- **Database:** `docker compose up -d` (starts PostgreSQL on port 5432)

## Development Conventions
- **Feature-Based Packaging:** Keep all components related to a domain (Entity, Controller, Service, Repository, DTOs) within its specific package.
- **Stateless Auth:** All endpoints (except login and Swagger) require a valid JWT `Authorization: Bearer <token>` header.
- **Soft Deletes:** Projects and Tickets use a `deletedAt` column and Hibernate `@SQLRestriction` to hide deleted records by default.
- **Optimistic Locking:** Tickets and Comments use `@Version` to handle concurrent updates.
- **Audit Logging:** Every state-changing action must be recorded via `AuditService`.
- **Validation:** Use Jakarta Bean Validation (`@Valid`) in Controllers; errors are mapped to 400 Bad Request via `GlobalExceptionHandler`.

## Current Status
- [x] **Phase 0 & 1:** Environment and Foundation (BaseEntity, JPA config, Exception handling).
- [x] **Phase 2:** Auth + Users (JWT, BCrypt, User CRUD, Admin Seeder).
- [ ] **Phase 3:** Projects (Next priority).
- [ ] **Phase 4+:** Tickets, Comments, Audit, etc. (Refer to `BUILD_PLAN.md` for the full roadmap).
