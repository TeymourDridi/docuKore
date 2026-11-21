# Docukore — Project Instructions

## 1. Project Overview

Docukore is a multi-tenant SaaS platform for private medical practices.

The application will provide patient management, chronological medical records, consultations, appointments, prescriptions, simplified accounting and staff management.

The platform must prioritize security, medical-data confidentiality, reliability, maintainability and a consistent user experience.

## 2. Technology Stack

- Java 25 LTS
- Spring Boot: latest stable production release compatible with Java 25.
- Angular: latest supported LTS version.
- PostgreSQL: latest stable supported major version selected for the project.
- Maven with Maven Wrapper.
- Flyway for database migrations.
- Docker for local infrastructure.

Verify framework and dependency compatibility through official documentation before establishing project versions.

Never silently introduce release candidates, snapshots or incompatible dependencies.

Pin selected versions consistently and document important version decisions.

## 3. Architecture Principles

- Start with a modular monolith.
- Keep domain boundaries explicit.
- Organize code primarily by business feature.
- Avoid unnecessary abstractions and premature microservices.
- Maintain clear separation between application, domain and infrastructure responsibilities.
- Make architectural decisions consistently across the entire project.

### Multi-tenancy

Use PostgreSQL schema-per-tenant isolation.

- One shared platform schema.
- One isolated schema per medical cabinet.
- Platform metadata belongs in the platform schema.
- Clinical and business data belongs in the corresponding tenant schema.
- Tenant identification and schema resolution must be handled by the backend.
- Never trust tenant identifiers or schema names supplied directly by the frontend.
- Cross-tenant data access must be prevented and tested.

## 4. Mandatory Coding Standards

### Java and Spring Boot

- Always use explicit constructor injection.
- Never use field injection.
- Do not use @Autowired.
- Do not use Lombok-generated constructors.
- Prefer final fields for injected dependencies.
- Use Java records for suitable DTOs.
- Never expose JPA entities directly through REST APIs.
- Keep controllers thin.
- Keep business logic out of controllers.
- Use explicit transaction boundaries.
- Use type-safe configuration properties.
- Avoid giant services and unnecessary interfaces.
- Use clear, consistent naming.

A single constructor does not require @Autowired.

### Angular

- Use standalone components.
- Enable strict TypeScript.
- Use typed Reactive Forms.
- Prefer Signals for appropriate local state.
- Use RxJS for appropriate asynchronous operations.
- Use lazy-loaded feature routes.
- Use functional interceptors and guards.
- Keep presentation components separate from business logic.
- Avoid unnecessary state-management libraries.

### PostgreSQL

- Use snake_case naming.
- Use UUID identifiers where appropriate.
- Maintain explicit foreign keys, indexes and constraints.
- Use Flyway as the source of truth for database migrations.
- Never modify an already-applied migration.
- Maintain separate platform and tenant migration histories.
- Never allow tenant connection state to leak between pooled connections.

## 5. Security and Data Privacy

Security must be enforced by the backend.

Keep the following concepts separate:

- Feature flags.
- Subscription entitlements.
- Individual user permissions.

The SaaS administrator controls platform-level configuration and subscriptions.

The doctor controls access and permissions for members of their cabinet.

Secretary accounts must never automatically inherit doctor permissions.

Never rely exclusively on frontend restrictions for authorization.

Never expose medical information in application logs.

Use fictional data for development and automated testing.

All access to sensitive medical information must respect tenant isolation and authorization rules.

## 6. Code Quality

- Maintain consistent formatting and naming conventions.
- Follow established project patterns rather than introducing alternatives.
- Prefer readable, testable code.
- Test important business rules and failure scenarios.
- Use real PostgreSQL integration tests when verifying database behavior.
- Test security and tenant isolation explicitly.
- Do not claim tests or builds passed unless they were actually executed.
- Do not introduce dependencies without a clear justification.

## 7. Development Workflow

Work incrementally.

Only implement the task currently requested.

Do not jump ahead to future modules or generate the entire application in one operation.

Before substantial changes:

1. Inspect the existing project structure and conventions.
2. Understand the affected components.
3. Identify relevant dependencies and constraints.
4. Explain important architectural decisions when necessary.
5. Implement only the agreed scope.
6. Run the relevant verification commands.
7. Report what changed and what remains.

Use the existing Context7 skill and rules for current framework and library documentation.

Do not duplicate existing configuration or modify working OmniRoute settings without explicit authorization.

Keep documentation synchronized with significant architectural changes.

Consistency across the entire repository is more important than introducing a new pattern for an isolated piece of code.

## User Workflow Preferences

- Maximum development speed and efficient token usage.
- Prefer implementation over prolonged planning.
- At every new session, provide one concise project status report before working.
- Use existing project reports instead of repeating investigations.
- Never run expensive tests just to understand project status.
- Avoid repetitive explanations and unnecessary documentation.
- One concise completion report per major task.
- Distinguish actual results from assumptions.

## Aggressive Subagent Usage

Use subagents proactively whenever available.

- Parallelize independent backend, database, frontend and research work.
- Use lightweight agents for inspections and simple tasks.
- Use stronger agents for complex implementation and debugging.
- Give each agent a clearly separated scope.
- Avoid multiple agents editing the same files.
- Main agent coordinates and integrates changes.
- Do not let every subagent run its own builds or tests.
- One agent should own verification.
- Never create additional Docker containers through subagents.

# User Working Preferences — Permanent

## 1. Speed and efficiency
- Prioritize implementation over lengthy planning.
- Avoid repeated architecture investigations and redundant documentation.
- Keep explanations, reports and tool output concise.
- Save verbose logs to existing project artifact locations; show only relevant excerpts.
- Avoid unnecessary waiting, polling and repeated commands.

## 2. Subagents
- Use subagents proactively and aggressively for independent tasks.
- Parallelize backend, database, frontend and research work where useful.
- Give each agent a clearly defined scope.
- Avoid agents editing the same files.
- Assign one agent to coordinate integration.
- Avoid duplicate builds and tests from different agents.
- Never allow subagents to create Docker containers.

## 3. Docker and PostgreSQL
- NEVER create new Docker containers.
- NEVER start Testcontainers.
- Reuse the existing PostgreSQL infrastructure.
- Never delete Docker containers or volumes.
- Development/test database contents are disposable.
- Destructive database operations are permitted on confirmed development/test databases.
- Never target production.
- Do not reset the database after every test.
- Reuse databases and schemas; reset only when genuinely necessary.

## 4. Testing
- Do not build after every code edit.
- Do not test after every small modification.
- Complete coherent implementation batches before testing.
- Prefer focused tests for changed functionality.
- Do not rerun unchanged passing tests.
- Perform one full backend verification at an appropriate milestone.
- Never weaken tests or remove assertions to obtain a successful build.
- Preserve meaningful integration coverage.

## 5. Git
- We are not currently committing or pushing.
- No repetitive git status/diff inspections.
- No staging, commits or pushes.
- Track modified files during implementation.
- Report changed files once at completion.
- Never use reset, clean, checkout or restore to discard user changes.

## 6. Security during development
- Defer exhaustive production security audits and compliance hardening.
- Preserve existing authentication, authorization and tenant isolation.
- Avoid implementing unnecessary future security frameworks.
- Never expose credentials or secrets.

## 7. Session opening
At the beginning of every new Docukore session:
- Give a concise status report.
- Identify the current task and last completed task.
- Identify unfinished work and blockers.
- Include the latest actually verified test results.
- Recommend the next action.
- Use existing reports; do not run expensive tests just to prepare the report.

## 8. Task execution
- One approval for the main agreed task, not every small edit.
- Implement the complete agreed scope without unnecessary interruptions.
- Preserve working functionality.
- Do not repeat completed work.
- Stop for genuine architectural blockers or destructive ambiguity.
- Deliver one concise, factual completion report per task.

## 9. Workspace boundary
- All Docukore work must remain inside the existing project directory.
- Do not create files or directories outside the project.
- Do not search the entire filesystem.
- Use known file paths directly.
- Do not modify global Claude settings or configuration.
- The existing root CLAUDE.md is the primary permanent project instruction file.
