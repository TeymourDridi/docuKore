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
