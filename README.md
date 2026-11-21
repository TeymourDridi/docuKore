# Docukore

Multi-tenant SaaS platform for private medical practices.

## Overview

Docukore provides patient management, chronological medical records, consultations, appointments, prescriptions, simplified accounting and staff management for medical cabinets.

## Technology Stack

### Backend
- **Java**: 25 LTS (OpenJDK Zulu 25.0.4.1)
- **Spring Boot**: 4.1.2
- **Spring Modulith**: 2.1.2
- **Database**: PostgreSQL 18.7
- **Migrations**: Flyway
- **Build**: Maven 3.9.9 (via Maven Wrapper)

### Frontend
- **Framework**: Angular 22.2.7
- **Runtime**: Node.js 26.13.0
- **Package Manager**: npm 11.20.0
- **Language**: TypeScript (strict mode)
- **Styling**: SCSS

### Infrastructure
- **Local Development**: Docker Compose
- **Database**: PostgreSQL 18.7 in Docker

## Repository Structure

```
docukore/
├── backend/              # Spring Boot application
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/
│   │   │   └── resources/
│   │   └── test/
│   ├── pom.xml
│   └── mvnw.cmd
├── frontend/             # Angular application
│   ├── src/
│   ├── angular.json
│   └── package.json
├── infrastructure/
│   └── docker/
│       └── docker-compose.yml
├── docs/
│   └── adr/             # Architecture Decision Records
└── CLAUDE.md            # Project instructions
```

## Prerequisites

- Java 25 LTS
- Node.js 26.x
- Docker and Docker Compose (for local database)
- Git

## Getting Started

### 1. Start PostgreSQL

```bash
cd infrastructure/docker
docker-compose up -d
```

Verify PostgreSQL is healthy:
```bash
docker-compose ps
```

### 2. Run Backend

From the project root on Windows (PowerShell 5.1 or 7):

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\run-backend.ps1
```

The project-local launcher defaults to the `local` profile and does not read mail/OAuth configuration in that mode. Use `-Profile hosted` to load the approved mail settings and trusted signup app base from root `.env` into the Maven/backend child only. Paths resolve relative to the launcher; the caller's directory and Process/User/Machine environment stay unchanged. `-ExecutionPolicy Bypass` applies only to this invocation. See [Hosted verification email](#hosted-verification-email) for Gmail authorization, provider selection and limitations.

Direct Maven/IntelliJ launch remains available, but **does not load `.env`**. For offline IntelliJ development, set the Spring active profile to `local`; for HOSTED with root `.env`, use `run-backend.ps1 -Profile hosted`. An unconfigured HOSTED launch fails with an explicit provider-configuration error, not a missing transport bean.

```bash
cd backend
./mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=local
```

The backend will start on `http://localhost:8080`

Health check: `http://localhost:8080/actuator/health`

### 3. Run Frontend

```bash
cd frontend
npm start
```

Open `http://localhost:4200`. On a fresh local installation, Docukore displays first-run setup. Create your doctor account, then sign in to see your account and sign out.

The `local` backend profile binds to loopback and enables one-time setup without email. Without it, deployment defaults to hosted mode: local setup is disabled and cookies require HTTPS. The frontend development server proxies relative `/api` requests to the backend so session cookies and Angular CSRF remain same-origin.

Use a 3–64 character ASCII username locally and a password of at least 12 Unicode characters and at most 72 UTF-8 bytes. Setup creates one installation owner only, not a cabinet or platform administrator. If users already exist when upgrading, setup stays closed. Doctor registration is available at `/register` (also linked from login) and email verification at `/verify-email`. Password recovery remains unimplemented. See the [registration manual checklist](docs/doctor-registration-frontend.md).

See [Task 4 authentication](docs/milestone-1-task-4-authentication.md) for API, security, verification and limitations.

## Build Commands

### Backend

```bash
cd backend

# Compile
./mvnw.cmd compile

# Run tests
./mvnw.cmd test

# Package
./mvnw.cmd package

# Clean build
./mvnw.cmd clean install
```

### Frontend

```bash
cd frontend

# Install dependencies
npm install

# Development server
npm start

# Build for production
npm run build

# Run all tests once (Vitest)
npm test -- --watch=false

# No lint script is configured; production compilation checks strict TS/templates.
```

## Portable local test and executable configuration

Copy `.env.example` to the project-root `.env` and privately supply this workstation's **existing** test credentials. `.env` and its local variants are ignored by Git; the example contains placeholders only. Reuse the dedicated `docukore_test` infrastructure, never an application or production database.

The root runner supports Windows PowerShell 5.1 and PowerShell 7. It resolves paths relative to itself, regardless of the caller's directory. Unix wrapper handling is included but has not been verified on another workstation.

```powershell
# Check configuration without connecting or running Maven
.\run-backend-tests.ps1 -ValidateOnly

# Default: the two focused support classes, including their PostgreSQL cases, plus quality verification
.\run-backend-tests.ps1

# Select another focused class or method explicitly
.\run-backend-tests.ps1 -Tests '<fully-qualified-class>#<method>' -Goal test

# Dependency-free parser/isolation smoke tests (no database)
.\scripts\test-env-runner.ps1
```

The default selectors are `com.docukore.support.TestSchemaAllocatorConnectionTest` and `com.docukore.support.OwnedTestDatabaseProvenanceTest`; `-Goal` accepts `test` or `verify`. Avoid the provenance test's ambiguous short class name. Do not select the full suite for incremental micro-tasks.

The runner reads literal `KEY=VALUE` assignments, optional surrounding single/double quotes and whole-line `#` comments. There is **no interpolation, shell execution, escape expansion or inline-comment parsing**. Required keys are `DOCUKORE_TEST_JDBC_URL`, `DOCUKORE_TEST_USERNAME` and `DOCUKORE_TEST_PASSWORD`. Missing/blank/placeholder values and invalid targets fail before Maven starts; there is no fallback to application settings or Windows User variables. Conflicting `docukore.test.*` overrides in inherited JVM/Maven options are rejected.

Only these three `.env` values are injected into the Maven child's environment. The caller's Process environment and working directory remain unchanged, so no restoration is needed. The runner never persists User/Machine values. The three setup-created User test variables were removed only after successful `.env` verification; already-running terminals can retain an inherited snapshot until restarted, but the runner does not rely on it. Maven output is captured and the configured values redacted before writing `backend/target/env-focused-tests.log`; only counts/build status are printed. Avoid Maven debug/environment dumps and treat other generated artifacts as local. The unchanged Java helper also requires the literal database URL path `/docukore_test` and verifies `SELECT current_database()` before pool/schema work. Direct Maven execution does not load `.env`; absent settings safely skip gated support cases, while complete but invalid settings fail.

### Hosted verification email

Set `DOCUKORE_MAIL_PROVIDER=GMAIL_API` or `RESEND` explicitly; HOSTED startup rejects a missing/unknown provider or incomplete selected-provider configuration. LOCAL does not bind provider credentials or construct mail HTTP/OAuth clients, even if unrelated mail settings are invalid.

Gmail uses the central administrator-authorized sender, not Google login for doctors. Only `gmail.send` is requested. Keep the Web Application client ID/secret/sender in ignored root `.env`, then run **manually** from project root:

```powershell
node .\scripts\authorize-gmail.mjs
```

The one-time loopback callback is exactly `http://127.0.0.1:8765/oauth2/callback`; the command validates state, browser binding, PKCE and the granted scope before atomically storing the refresh token in `.env`. It sends no email. Start HOSTED afterward with `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\run-backend.ps1 -Profile hosted`. The trusted reachable HTTPS `DOCUKORE_SIGNUP_APPBASEURI` remains required and is not supplied by the application. Until configured, signup records delivery failure and cannot bypass verification. See [Gmail integration and manual checklist](docs/gmail-verification-mail.md).

#### Resend alternative

| Purpose | Spring property | Direct environment variable |
| --- | --- | --- |
| API key | `docukore.mail.resend.api-key` | `DOCUKORE_MAIL_RESEND_APIKEY` |
| Sender | `docukore.mail.resend.sender` | `DOCUKORE_MAIL_RESEND_SENDER` |

Spring's direct environment binding removes hyphens: use **APIKEY**, not `API_KEY` ([Spring Boot external configuration](https://docs.spring.io/spring-boot/reference/features/external-config.html)). The sender is passed as Resend's `from` address. Both values must be configured for transport use; the current application defines no `RESEND_API_KEY` or `RESEND_FROM` aliases. Store the workstation's Resend key privately in the ignored root `.env` under `DOCUKORE_MAIL_RESEND_APIKEY`; never copy it into documentation, templates, logs or source. Configure `DOCUKORE_MAIL_RESEND_SENDER` separately with a verified sender before enabling mail. `.env` is plaintext local configuration: limit access and use a managed secret store for deployed environments. `.env.example` has placeholders only.

In HOSTED mode, `run-backend.ps1` allowlists the provider, four Gmail credentials/sender settings, optional exact Gmail redirect URI, the two Resend settings and `DOCUKORE_SIGNUP_APPBASEURI`. It uses the literal assignment/quote/comment syntax above, with no interpolation or shell execution. Malformed assignments, duplicate selected keys, unclosed quotes, placeholders and control characters fail without printing values. Missing keys retain inherited child values; explicit blanks override them. LOCAL skips the file completely. The child deployment mode is pinned to the selected launch profile. Datasource/provisioning configuration is unchanged: no `DOCUKORE_TEST_*`, database credentials or arbitrary keys are imported. The launcher writes no files, persistent environment or secrets in arguments. The separate manually invoked Gmail authorization command is the only new writer of the refresh token. Avoid Maven debug/environment dumps; restart after private configuration changes. Direct Maven/IDE launch does not load `.env`.

### Future packaged executable contract

Configuration must remain **outside** the packaged Spring Boot executable. Each workstation owns its private `.env` or external `application.properties`/`application.yml`; no persistent Windows User environment variables are required. A Boot JAR does not automatically load `.env`: a future launcher must read it and inject application settings into its child process, or use Boot's existing external configuration support, for example `java -jar docukore.jar --spring.config.additional-location=file:./config/` (with private configuration in that directory, never secrets in arguments). The application must use its own datasource settings, not the `DOCUKORE_TEST_*` credentials. `run-backend.ps1` covers the local Maven launch only; a packaged-executable launcher and production configuration redesign remain future work.

## Database

### Connection Details (Local Development)

- **Host**: localhost
- **Port**: 5432
- **Database**: docukore
- **Username**: docukore
- **Password**: docukore

**⚠️ These credentials are for local development only. Never commit production credentials.**

### Migrations

Database migrations are managed by Flyway and located in:
- `backend/src/main/resources/db/migration/platform/`

Migrations run automatically on application startup. Platform V1–V11 includes setup/session tables plus permanent allocation/receipt and registration/provisioning/trial metadata; tenant V1–V7 remains separate. Stale-attempt recovery changed no migrations. Back up any existing database before starting a version with new migrations. Never use the application development or production database for tests; backend integration tests use the existing dedicated `docukore_test` PostgreSQL infrastructure through the root `run-backend-tests.ps1` runner.

## Architecture

Docukore is built as a **modular monolith** with clear business boundaries:

- **Platform domain**: SaaS-level metadata, subscriptions, tenant provisioning
- **Medical cabinet domain**: Clinical data, patients, consultations, appointments

### Multi-Tenancy

Uses **PostgreSQL schema-per-tenant** isolation:
- One shared platform schema
- One isolated schema per medical cabinet
- Tenant resolution and schema selection handled by backend

Synchronous Hibernate routing, pooled-connection cleanup and authenticated active-membership cabinet access are implemented and tested. Cabinet provisioning now has focused transaction/recovery verification; clinical permission/entitlement enforcement remains deferred, and cabinet membership alone does not authorize medical-record operations.

## Current Status

**Gmail HOSTED verification integration (2026-11-20):** Explicit Gmail/Resend provider selection, offline LOCAL isolation, refresh-aware Gmail transport and a one-time local administrator OAuth command are implemented. Backend compiled and 85 focused tests passed, followed by 9 passing configuration/YAML tests; 23 mocked OAuth-command tests and both intercepted launcher modes passed. Actual private configuration syntax was validated without network calls. Real OAuth consent, receipt, HTTPS app-base configuration and live cabinet provisioning remain manual; see [Gmail setup and checklist](docs/gmail-verification-mail.md).

**Doctor registration frontend (2026-11-20):** Angular `/register` and `/verify-email` connect to the existing signup, verification and resend APIs with backend-derived deployment mode and next states. Production Angular build completed without warnings; the initial 69-test frontend run passed, followed by 30 passing targeted tests after validation corrections (unchanged passing tests were not rerun). Backend compiled and one targeted public-metadata test passed. The project-local Resend child-environment check passed without starting a server or sending email. Live hosted delivery and provisioned-cabinet acceptance still require private sender, HTTPS app base and provisioning settings; see [manual checklist and configuration](docs/doctor-registration-frontend.md).

**Milestone 1, Task 7 — incremental recovery verified (2026-11-20)**: Permanent allocation/provenance, independently committed provisioning claims, owned tenant scopes and lock-based interrupted-attempt recovery have focused verification. Latest recovery run: **52 passed, zero failures/errors/skips**, Maven `test` BUILD SUCCESS (31 real PostgreSQL cases + 21 JDBC-boundary cases). This is not full Task 7 or signup/HTTP acceptance; no full suite or Checkstyle verification ran for this micro-task.

- Server-authenticated identity and fresh active, unrevoked membership checks before tenant persistence.
- Synchronous authorized application gateway reusing existing schema-per-cabinet routing and persistence guards.
- Disposable PostgreSQL authorization/lifecycle/isolation tests and real session HTTP tests through a test-only probe.
- Task 5 patient persistence and Task 4 authentication/FR/EN/AR shell retained unchanged.

See the [Task 6 completion report](docs/milestone-1-task-6-cabinet-access-authorization.md) for the authorization contract and verification; the [Task 5 report](docs/milestone-1-task-5-patient-persistence.md) records patient mapping and schema discrepancies. Broader provisioning/signup acceptance, membership-management workflows, clinical permission/entitlement enforcement and patient services/endpoints remain separately authorized work. No patient selection API, Angular screen, consultation or other clinical workflow was added in Task 6.

Task 7's approved stages A–E cover product/lifecycle consistency, forward allocation/registration metadata, permanent-mapping routing, recoverable provisioning/owner/trial and signup/hosted confirmation. Focused micro-task results do not certify every stage. A held cabinet lock returns `IN_PROGRESS`; an interrupted attempt is taken over only after acquiring that same lock, with a fresh fenced token and reused schema/receipt/history. Valid ACTIVE replay preserves suspension and performs no data writes. Independently running Flyway cannot be cancelled on lock-session loss; contention may require another retry, and inconsistent receipt/catalog evidence requires operator recovery. Unverified hosted signup reserves no tenant number. Only initial signup creates a cabinet; authenticated retry can recover only that original cabinet. Angular doctor signup and email verification now connect to the existing backend; runtime configuration and a manual journey remain necessary. No payments/expiry enforcement, patient CRUD, future clinical modules or OCR provider. See [Task 7 assessment and actual stage results](docs/milestone-1-task-7-registration-and-provisioning.md).

## Documentation

- [Authoritative product specification](docs/product-requirements.md) — implemented, current-task and future capabilities distinguished
- [Architecture Decision Records](docs/adr/)
- [Project Instructions](CLAUDE.md)

## Development Guidelines

See project rules in `.claude/rules/`:
- [Java & Spring Boot Standards](.claude/rules/java-spring.md)
- [Angular Standards](.claude/rules/angular.md)
- [PostgreSQL & Flyway](.claude/rules/postgresql.md)
- [Testing & Quality](.claude/rules/testing-quality.md)
- [API Contracts](.claude/rules/api-contracts.md)
- [Security & Multi-tenancy](.claude/rules/security-multitenancy.md)

## License

Proprietary - All Rights Reserved
