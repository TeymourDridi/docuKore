---
paths:
  - "backend/**/*.java"
  - "frontend/**/*.ts"
---

# API Contract Conventions

- Use REST conventions consistently.
- Use appropriate HTTP methods and status codes.
- Keep API DTOs separate from persistence entities.
- Use request and response types explicitly.
- Validate incoming data on the backend.
- Maintain consistent JSON naming conventions.
- Use a consistent error response format.
- Keep frontend API models aligned with backend contracts.
- Handle pagination, sorting and filtering consistently.
- Document non-obvious API behavior.
- Avoid exposing internal database structures.
- Do not introduce incompatible API changes without considering existing clients.
- Evaluate OpenAPI as the shared API contract and documentation format.
- Do not introduce a second API documentation standard.

Do not duplicate business validation between unrelated frontend components.
Backend validation remains authoritative.

Maintain existing API conventions rather than introducing different patterns for individual features.
