---
paths:
  - "backend/**"
  - "frontend/**"
  - "infrastructure/**"
---

# Testing and Engineering Quality Standards

## 1. General Principles

- Test meaningful behavior, not implementation details.
- Prioritize correctness, reliability and maintainability.
- Test success paths, failure paths and important edge cases.
- Avoid brittle tests coupled to internal implementation.
- Do not write meaningless tests merely to increase coverage.
- Tests must be repeatable and independent.
- Never silently ignore failing tests.
- Never claim verification succeeded without executing it.

## 2. Backend Unit Tests

- Use JUnit and Mockito where appropriate.
- Test business rules independently.
- Prefer testing public behavior.
- Mock external dependencies only where justified.
- Avoid excessive mocking.
- Do not mock the behavior that the test is supposed to verify.
- Use clear test names describing expected behavior.
- Keep tests readable and deterministic.

## 3. Database Integration Tests

- Use real PostgreSQL for PostgreSQL-specific behavior.
- Prefer Testcontainers for isolated database integration tests.
- Do not use H2 as a substitute for PostgreSQL verification.
- Test Flyway migrations against the intended database engine.
- Test constraints, transactions and indexes where relevant.
- Test connection pooling and tenant schema isolation.
- Ensure integration tests clean up their resources.

## 4. Multi-Tenant Testing

Test independently:

- Tenant provisioning.
- Tenant schema selection.
- Tenant membership validation.
- Cross-tenant access rejection.
- Missing tenant context.
- Invalid tenant context.
- Connection reuse between tenants.
- Tenant migration execution.
- Asynchronous tenant context propagation.

Use at least two independently provisioned tenants in isolation tests.

## 5. Authorization and Security Testing

Test:

- Unauthenticated access.
- Unauthorized access.
- Doctor permissions.
- Secretary permissions.
- Platform administrator boundaries.
- Subscription entitlement restrictions.
- Read-only subscription behavior.
- Feature availability.
- Inactive tenant membership.
- Direct object access attempts.

Verify authorization on the backend.

Frontend tests cannot replace backend security integration tests.

## 6. Frontend Testing

- Follow the testing tooling supported by the selected Angular version.
- Test components based on visible behavior.
- Test user interactions.
- Test loading, success, empty and error states.
- Test service and API interaction.
- Test forms and validation.
- Test permission-dependent UI behavior.
- Avoid brittle tests based on private component implementation.
- Keep test fixtures fictional.

Do not introduce a second frontend test runner without architectural justification.

## 7. Architecture Testing

Evaluate automated architecture checks for:

- Package boundaries.
- Module dependencies.
- Forbidden dependency directions.
- Circular dependencies.
- Layer separation.
- Dependency injection conventions.

Architecture rules must reflect documented project decisions.

Do not impose arbitrary restrictions without a clear architectural reason.

## 8. Build and Verification

After meaningful implementation changes:

- Run relevant compilation checks.
- Run focused tests.
- Run integration tests when required.
- Validate database migrations.
- Run frontend type checks and linting.
- Run the appropriate quality gates.

Distinguish between tests that passed, failed, were skipped or were not executed.

Do not suppress errors to obtain a successful build.

## 9. Test Data and Privacy

- Use synthetic data exclusively.
- Never include real patient information.
- Do not place secrets in test fixtures.
- Avoid sensitive data in test failure messages.
- Ensure test environments cannot accidentally target production databases.

## 10. Consistency

- Follow one established testing convention per module.
- Do not introduce new testing dependencies without justification.
- Reuse existing test utilities where appropriate.
- Keep tests close to the code they verify.
- Prefer readable tests over highly abstract testing frameworks.

The objective is trustworthy verification, not maximum test count.
