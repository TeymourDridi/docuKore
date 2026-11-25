---
paths:
  - "**/*.sql"
---

# PostgreSQL and Flyway Engineering Standards

## Installation Initialization

- Use one private installation `.env` for all connections; never generate another credential file.
- Complete Java bootstrap on maintenance `postgres` before Spring datasource/Flyway startup.
- Preserve administrator, runtime, migrator/provisioner and test roles, existing data and migration histories.
- Create absent roles with supplied passwords only; never rotate existing passwords during startup.
- Ordinary startup never initializes `docukore_test`; test maintenance requires explicit `--docukore.initialize-test`.
- Keep tenant provisioning and migration history separate from platform initialization.
- HOSTED waits for PostgreSQL health; image initialization applies only to fresh volumes.
- Preserve existing containers and volumes; no initializer containers or Testcontainers.

## 1. Database Naming

- Use snake_case for schemas, tables, columns, indexes and constraints.
- Use meaningful, consistent names.
- Prefer UUID primary keys where appropriate.
- Avoid reserved SQL keywords as identifiers.
- Use explicit constraint names.

## 2. Data Types

- Use appropriate PostgreSQL-native data types.
- Use timestamptz for timestamps representing real-world instants.
- Use date for date-only values.
- Use numeric for monetary amounts. Never use float or double precision for money.
- Use appropriate precision and constraints for numeric values.
- Avoid unnecessary JSONB fields when structured relational data is more appropriate.
- Avoid storing large medical attachments directly in PostgreSQL by default.

## 3. Relational Integrity

- Define explicit foreign keys.
- Use NOT NULL when data is mandatory.
- Add appropriate UNIQUE and CHECK constraints.
- Define deletion behavior explicitly.
- Avoid relying exclusively on application code for data integrity.
- Avoid unnecessary cascading deletes, especially for medical records.
- Use indexes that support real query patterns.

## 4. Multi-Tenant Schema Isolation

Docukore uses schema-per-tenant architecture.

- Maintain one shared platform schema.
- Maintain one isolated schema per medical cabinet.
- Never put clinical records in the shared platform schema.
- Never access a tenant schema using an untrusted schema name.
- Never construct schema identifiers directly from frontend input.
- Treat SQL identifiers differently from SQL values: identifiers cannot be safely protected using ordinary value parameters.
- Use trusted, validated and safely quoted identifiers through an approved backend mechanism.
- Never assume search_path alone guarantees tenant isolation.
- Never use session-level search_path changes on pooled connections without a proven reset mechanism.
- Prefer transaction-scoped or provider-managed tenant schema selection when appropriate.
- Never include writable, untrusted schemas in a security-sensitive search path.
- Explicitly qualify platform-table references where necessary.
- Test that connection reuse cannot leak a previous tenant's schema.
- Never claim schema-per-tenant isolation is equivalent to separate database credentials or separate databases.

The final tenant resolution strategy must be documented in an architecture decision record before implementation.

## 5. Flyway Migrations

- Flyway is the source of truth for database structure.
- Maintain separate platform and tenant migration histories.
- Use clear, ordered versioned migration names.
- Never modify a migration already applied in a shared or deployed environment.
- Correct existing schema problems using new forward migrations.
- Never depend on Hibernate auto-DDL in production.
- Never make undocumented manual production schema changes.
- Review migration compatibility with deployed application versions.
- Review table-locking and data-backfill implications before large migrations.
- Validate migrations before deployment.
- Do not hide schema drift using indiscriminate IF NOT EXISTS statements.

## 6. PostgreSQL Performance

- Design indexes around actual access patterns.
- Avoid indexing every column.
- Consider composite and partial indexes where justified.
- Evaluate EXPLAIN ANALYZE for expensive queries.
- Avoid unnecessary SELECT * queries.
- Review large table migrations carefully.
- Consider connection-pool limits and database capacity.
- Never optimize based only on assumptions.

## 7. Medical Data and Privacy

- Never place patient names, phone numbers or clinical information in migration logs.
- Use fictional fixtures for development.
- Avoid including sensitive information in SQL comments or error messages.
- Never expose data across tenant boundaries.
- Preserve medical-history integrity.
- Evaluate retention and deletion requirements before destructive operations.

## 8. Consistency

- Follow established database naming and migration conventions.
- Do not introduce a second migration tool.
- Do not introduce another database engine without architectural approval.
- Document exceptions and significant database decisions.
