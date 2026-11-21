---
paths:
  - "backend/**/security/**"
  - "backend/**/tenant/**"
  - "backend/**/auth/**"
  - "backend/**/*Tenant*.java"
  - "backend/**/*Security*.java"
  - "backend/**/*Permission*.java"
  - "backend/**/*Authorization*.java"
  - "frontend/**/*auth*"
  - "frontend/**/*guard*"
  - "frontend/**/*permission*"
---

# Security Conventions

Apply these requirements when changing authentication, tenant isolation,
authorization, subscription enforcement or access to sensitive data.

- Enforce authorization on the backend.
- Never trust frontend guards as security controls.
- Validate tenant membership before resolving tenant context.
- Resolve tenant schemas using trusted platform records.
- Keep user permissions separate from subscription entitlements.
- Never expose medical information in ordinary logs.
- Do not introduce cross-tenant access.

Testing should match the change:
- Ordinary features: test their actual business behavior.
- Authentication or authorization changes: test relevant access restrictions.
- Tenant-resolution changes: test tenant isolation and connection handling.
- Subscription changes: test applicable read/write restrictions.

Do not run a full security audit for unrelated changes.
Do not duplicate security checks without a specific reason.
