---
paths:
  - "backend/**"
  - "frontend/**/*.ts"
  - "frontend/**/*.html"
  - "**/*.sql"
---

# Security and Multi-Tenancy Engineering Standards

## 1. Security by Default

- Apply least privilege.
- Deny access by default.
- Enforce authorization on the backend.
- Never trust frontend visibility, disabled buttons or route guards as security controls.
- Validate all external input.
- Treat identifiers received from clients as untrusted.
- Avoid exposing internal implementation details through API errors.

## 2. Tenant Isolation

Docukore uses PostgreSQL schema-per-tenant isolation.

- Every cabinet has a distinct tenant identity and database schema.
- Every authenticated tenant operation must have a validated tenant context.
- Never derive database schema names from request parameters.
- A tenant identifier supplied by a client can only act as a selector, never as proof of authorization.
- Verify active user membership before resolving the tenant.
- Resolve schema names exclusively through trusted platform records.
- Fail closed when tenant context is missing, invalid or ambiguous.
- Never allow one tenant to read or modify another tenant's clinical data.
- Ensure asynchronous operations and background jobs carry explicit tenant context.
- Never assume ThreadLocal state alone guarantees isolation.

## 3. PostgreSQL Connection Safety

- Tenant schema switching must be transaction-safe.
- Never leave session-level tenant state on pooled connections.
- Evaluate Hibernate's multi-tenancy capabilities and transaction-scoped PostgreSQL schema selection before choosing an implementation.
- Never interpolate untrusted input into SQL identifiers.
- Verify that connections are safely reused across different tenants.
- Test schema isolation using real PostgreSQL integration tests.

Document the selected implementation in an architecture decision record.

## 4. Authorization

Keep the following concepts separate:

1. Authentication: who is making the request?
2. Tenant membership: which cabinet can the user access?
3. User permissions: what can the user do?
4. Subscription entitlements: what has the cabinet purchased?
5. Feature flags: which functionality is enabled?

All applicable conditions must be evaluated before a sensitive operation is allowed.

Use deny-by-default authorization.

Prefer centralized, reusable authorization policies over duplicated permission checks.

## 5. Platform Administrator

The SaaS administrator controls:

- Tenant lifecycle.
- Plans and subscriptions.
- Global features.
- Tenant-specific feature overrides.
- Subscription restrictions.
- Administrative configuration.

Platform administrator status must not automatically grant access to patient records or other clinical content.

Any exceptional support access to clinical data must require an explicit, separately authorized and audited mechanism.

## 6. Doctor and Secretary Permissions

The doctor owns and administers the cabinet.

The doctor may grant or revoke secretary permissions.

A secretary must never automatically inherit all doctor permissions.

Evaluate permissions independently for operations such as:

- Reading patient records.
- Creating patients.
- Updating patient details.
- Accessing consultations.
- Creating or modifying appointments.
- Accessing prescriptions.
- Accessing financial information.
- Exporting data.
- Managing cabinet members.

Avoid assuming that access to one module grants access to another.

Permission revocation must take effect according to the selected session and authorization-cache policy.

## 7. Subscription Enforcement

Subscriptions and permissions are independent.

Examples:

- A doctor may have permission to create consultations but lack an active subscription entitlement.
- A secretary may have a subscription-enabled module but lack permission to use it.

Evaluate all applicable restrictions on the backend.

A read-only subscription state must not accidentally permit write operations.

Never silently delete clinical information because of subscription expiration.

Preserve the ability to read and export existing medical information according to the configured policy.

## 8. Sensitive Information

- Never log patient names, phone numbers or clinical notes.
- Never place secrets in source control.
- Never expose authentication tokens in application logs.
- Avoid sensitive data in exception messages.
- Use fictional medical data in development and tests.
- Restrict access to medical documents.
- Use private object storage and tenant-scoped access mechanisms for future attachments.
- Do not expose internal storage paths or permanent public document URLs.

## 9. Auditability

Sensitive operations must be auditable.

Evaluate logging:

- Authenticated actor.
- Tenant identity.
- Operation.
- Target resource.
- Timestamp.
- Request correlation ID.
- Relevant authorization outcome.

Do not store complete medical content in audit events.

Audit records must not become a way to bypass tenant isolation.

## 10. Mandatory Security Tests

Every relevant security change must consider:

- Unauthenticated access.
- Unauthorized role access.
- Inactive memberships.
- Suspended tenants.
- Cross-tenant object access.
- Invalid tenant identifiers.
- Missing tenant context.
- Connection-pool tenant leakage.
- Read versus write restrictions.
- Expired subscriptions.
- Secretary permission restrictions.
- Feature entitlement restrictions.

Use integration tests for actual security boundaries.

Do not claim isolation is proven by unit tests that only mock tenant resolution.

## 11. Review Before Implementation

For any change involving authentication, tenant resolution, permissions, subscriptions or medical data:

1. Identify the security boundary.
2. Identify the authenticated actor.
3. Identify the tenant context.
4. Identify all applicable entitlements and permissions.
5. Consider unauthorized and cross-tenant scenarios.
6. Implement backend enforcement.
7. Add appropriate tests.
8. Report remaining risks.

Security-sensitive shortcuts must never be introduced merely to simplify development.
