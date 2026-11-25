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

### 1. Reuse existing PostgreSQL

Development and tests reuse the existing PostgreSQL **18.7** container `docukore-postgres`, named volume `docukore-postgres-data`, and mount `/var/lib/postgresql`. Never recreate/delete the container or volume, start Testcontainers, or run `docker compose up/down` for this workflow. If PostgreSQL is unavailable, report the blocker.

Use one ignored root `.env`; `.env.example` lists the supported keys. Preserve existing passwords. Normal Java startup loads the file, verifies/creates missing application roles and `docukore`, then starts Spring and existing Flyway migrations. It never resets existing data or initializes `docukore_test`. Bootstrap uses the existing administrator on maintenance database `postgres`, never the application datasource.

### 2. Run Backend

From the project root:

```powershell
.\run-backend.ps1 -Profile local
.\run-backend.ps1 -Profile hosted
```

No `-Bootstrap` is required. Direct Maven from `backend` discovers the root file automatically:

```powershell
.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=local
```

Shared IntelliJ configurations **Docukore Local** and **Docukore Hosted** in `.run/` select the profile and file, not credentials. They use the same application main. LOCAL binds to loopback by default. Health: `http://localhost:8080/actuator/health`.

HOSTED configuration uses optional nonblank `DOCUKORE_HOSTED_<base key>` overrides in the same file. Host-side HOSTED launches use host-reachable URLs. The backend container selects `--docukore.env.context=compose` and four URL-only `DOCUKORE_COMPOSE_<base key>` settings using service hostname `postgres`, while inheriting the same credentials. For an actual hosted deployment (not this project's no-new-containers verification workflow):

```powershell
docker compose --env-file .env -f infrastructure/docker/docker-compose.yml up -d --build
```

Compose mounts the same `.env` read-only in both services. The PostgreSQL entrypoint adapter reads bootstrap credentials literally (including quotes, `$`, `#` and backslashes), applies nonblank HOSTED overrides, and delegates to the official image entrypoint with maintenance database `postgres`. Only a fresh volume receives that initial administrator; existing volume `docukore-postgres-data` remains authoritative and passwords are never rotated. Backend waits for PostgreSQL health before its Java initialization. Do not render configuration containing secrets.

Installed Windows configuration: `%ProgramData%\Docukore\.env`. A future launcher may set `DOCUKORE_ENV_FILE` or `-Ddocukore.env.file=<absolute-path>`; binaries and working directory need not be in the project. No EXE/installer/VM is built here. See [environment configuration](docs/environment-configuration.md).

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

Test settings live in the same root `.env`. Ordinary startup never creates or resets the dedicated test database. When genuinely absent, initialize it explicitly through the packaged Java main (or the Maven equivalent), without Spring/Flyway startup:

```powershell
# From backend; explicitly requested test maintenance only
.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=local "-Dspring-boot.run.arguments=--docukore.initialize-test"
# From root
.\run-backend-tests.ps1 -ValidateOnly
.\run-backend-tests.ps1 -Tests '*' -Goal verify
.\run-backend-tests.ps1 -Tests '<fully-qualified-class>#<method>' -Goal test
.\scripts\test-env-runner.ps1
.\scripts\test-backend-launcher.ps1
```

The test launcher sends only the five `DOCUKORE_TEST_*` aliases to Maven; administrator/mail/application credentials are excluded. Missing/blank/placeholder keys, malformed syntax, duplicate keys and non-test targets fail before the child starts. The runner uses `docukore_test_runner`; runtime uses `docukore_app`. No Testcontainers or new containers. Defaults select the full suite; use focused selectors for incremental changes. Output is redacted and saved under `backend/target/`. `-Bootstrap` is deprecated; it performs no test database initialization.

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

Java binds selected provider settings from the single file; LOCAL ignores provider values. The launcher only selects a file and profile, never copies secrets into arguments or persistent environment. Gmail authorization remains the refresh-token writer.

### Future packaged executable contract

The installer establishes PostgreSQL and its initial bootstrap account, writes the private `%ProgramData%\Docukore\.env`, and launches the packaged application. Automatic Java initialization is included in the normal JAR main. Apply restrictive file ACLs and arrange PostgreSQL service readiness; EXE, service packaging and live VM deployment remain future work.

## Database

### Connection Details (Local Development)

- **Host**: localhost
- **Port**: 5432
- **Database**: docukore
- **Runtime login**: `docukore_app`
- **Migration/provisioning login**: `docukore_migrator` (separate, not database owner)
- **Database owner/bootstrap administrator**: existing `docukore` (OID 10; never an ordinary datasource)
- **Passwords**: private only, no defaults; all credentials supplied in .env; existing role passwords are verified, never rotated

Bootstrap performs explicit bootstrap-owned database initialization and scoped object ownership/grants. It does not demote OID 10 or rotate existing passwords. Only `reset-development` explicitly recreates confirmed disposable development/test databases; it never recreates the PostgreSQL container or volume and is not exposed as a launcher switch.

### Migrations

Database migrations are managed by Flyway and located in:
- `backend/src/main/resources/db/migration/platform/`
- `backend/src/main/resources/db/migration/tenant/`

Platform migrations run automatically on application startup. Platform V1–V12 includes setup/session tables plus permanent allocation/receipt and registration/provisioning/trial metadata; tenant V1–V9 remains separate and runs through tenant provisioning/migration. Restarting or replaying an already ACTIVE cabinet does not apply pending tenant migrations. Stale-attempt recovery changed no migrations. Back up any existing database before starting a version with new migrations. Never use the application development or production database for tests; backend integration tests use the existing dedicated `docukore_test` PostgreSQL infrastructure through the root `run-backend-tests.ps1` runner.

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

**Patient directory — Task 9.5.2 (2026-11-25):** Backend-only `GET /api/patients` provides READ-authorized, tenant-local pagination and literal case-insensitive name/number/current-phone search. Defaults are page 0 and size 20, maximum size 100; seven-field rows preserve unknown phone/birth values and omit clinical notes. Focused Maven verification passed 110 tests (65 listing + 45 creation regression), packaging and Checkstyle with zero violations. No Angular/detail/edit/archive integration. See the [contract, changed files and actual verification](docs/TASK-9-5-2-COMPLETION-REPORT.md).

**Patient creation — Task 9.5.1 (2026-11-24):** Backend-only `POST /api/patients` creates identity, supplied phone and CREATE audit atomically in the trusted cabinet schema, using tenant-local backend numbering. The request mirrors the manual prototype form; no synthetic clinical/financial records or Angular API integration. See the [contract, changed files and actual verification](docs/TASK-9-5-1-COMPLETION-REPORT.md). Existing cabinets need tenant V9 applied; multiple available memberships fail closed until a cabinet-selection workflow exists.

**Unified installation configuration (2026-11-24):** One literal `.env`, pre-Spring automatic PostgreSQL initialization, thin LOCAL/HOSTED launchers, shared IntelliJ configurations and Compose healthy ordering are implemented. Final packaged LOCAL/HOSTED launches returned health UP with retained database identity and no pending migration. The full run had one failure (618 tests, one intended skip); its test synchronization was corrected and the six-case class passed, but no clean full rerun exists. That checkpoint reported 77 Checkstyle errors; the later Task 9.5.1 audit passed with zero violations. IDE UI, backend-container/fresh-volume execution and installer deployment remain unexecuted; see the [implementation and verification record](docs/environment-configuration.md#7-implementation-and-verification-record--2026-11-24).

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
