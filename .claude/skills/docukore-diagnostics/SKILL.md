---
name: docukore-diagnostics
description: Diagnose Docukore failures from existing reports and bounded request-correlated logs, using safe evidence and the smallest authorized reproduction.
---

# Docukore Diagnostics

## Scope and Safety

- Use when logs can clarify a failure or incomplete business workflow.
- Diagnosis is read-only by default. Invoking this skill does not authorize launches, requests, tests, edits or log capture.
- Keep all work and artifacts inside the existing project. Do not create top-level reports, hooks or settings changes.
- Preserve authentication, authorization and tenant isolation; never collect real medical data or secrets.
- No automatic external requests, OAuth flows, email sends or production database access.
- Never start Testcontainers, create Docker containers or delete containers/volumes. Reuse existing PostgreSQL only when an authorized reproduction needs it; report unavailability as a blocker.
- Use the existing `context7-mcp` skill when diagnosis depends on a library/framework API; do not guess current API behavior.

## Evidence-First Workflow

1. Read current reports/handoffs and relevant existing artifacts first. Separate actually verified results from assumptions; do not rerun tests to discover project status.
2. For the affected request, ask for only the server-generated UUID in the response `X-Request-ID` header from browser Network. Do not request a HAR, full headers, cookies, URL/query, request/response bodies or screenshots containing sensitive data. If the header is absent, state that correlation is unavailable for that request; do not assume the running deployment includes the filter or invent an identifier.
3. Use `Grep` on an explicit existing log file or narrowly scoped artifact location, matching only the validated UUID with bounded output (`head_limit` and minimal context). Build a chronological timeline of safe fixed events, phases, outcomes and elapsed times. Do not search the whole filesystem, dump logs or repeat broad searches. Report only safe relevant excerpts, never raw sensitive lines.
4. Find the first actionable cause, not merely the final symptom. Distinguish the observed HTTP status from the business workflow outcome: HTTP 200 can still mean pending, not completed. Absence of an event is not proof that its phase never ran.
5. State a concise hypothesis tied to evidence, uncertainties and the smallest check that would confirm or reject it.
6. Within existing explicit authorization, perform only the smallest reproduction using fictional data. Otherwise request approval for its side effects before launching, making requests, editing, capturing logs or running tests. Do not trigger OAuth/email/external services as an implicit diagnostic step.
7. After an authorized coherent fix, use explicit focused tests for the changed behavior. One designated owner runs verification; do not duplicate another agent's builds/tests or rerun unchanged passing tests. Database checks use confirmed development/test PostgreSQL only, without routine resets.
8. Report the cause or remaining hypothesis, HTTP versus workflow outcome, authorized changes, actual verification and next action concisely. No extra report files.

## Current Runtime and Test Artifacts

- `run-backend.ps1` currently accepts `-Profile` only and writes runtime output to the console; it does not save a backend log file. Do not invent a log location or launcher argument support.
- Use existing console evidence if available. New capture or launch requires explicit approval; keep any approved capture inside an existing project artifact location and treat it as potentially sensitive.
- For approved focused verification, the existing test runner supports `run-backend-tests.ps1 -Tests <explicit> -Goal test -LogName <name>` and saves `backend/target/<name>.log`. Choose explicit test classes/methods and a safe non-sensitive log name, not the runner defaults for diagnosis.
- The test runner masks the values of only `DOCUKORE_TEST_JDBC_URL`, `DOCUKORE_TEST_USERNAME` and `DOCUKORE_TEST_PASSWORD`; this is not comprehensive secret redaction.
- `backend/target/surefire-reports` and other test artifacts may contain sensitive configuration, exceptions or data. Inspect narrowly and never paste raw reports or assume they are sanitized.

## Safe Logging Contract

- Follow `.claude/rules/java-spring.md`: a server-generated UUID returned as `X-Request-ID` and stored under MDC `requestId`, never trusted from inbound headers. Correlation is not identity or authorization.
- Apart from that opaque correlation value and normal timestamp/level/class metadata, application diagnostics allow only fixed event/operation/outcome/phase/failure-code categories, allowlisted HTTP methods, servlet dispatch types, bounded HTTP status (or fixed UNKNOWN/UNRESOLVED), elapsed duration and an allowlisted deployment category. Unknown values map to fixed fallbacks; no dynamic business identifiers or payloads.
- No names, usernames, emails, medical data, IDs, schema/account/tenant values, DTOs, headers/cookies, tokens/passwords, proof links, idempotency keys, raw paths/query strings, SQL, exception messages/stacks, provider details or configuration values.
- Meaningful business transitions use INFO; intermediate phases DEBUG; actionable failures WARN/ERROR. Never enable blanket DEBUG, SQL or parameter logging.
- Temporary DEBUG is limited to the relevant class via explicitly approved child-process arguments after checking the actual entry point supports them. Neither current launcher exposes arbitrary child arguments; do not pretend otherwise or modify a launcher implicitly.
- No persistent environment edits, protected configuration changes (`.claude/settings*.json`, `.mcp.json`, OmniRoute), hooks or global settings. Logging additions require implementation approval, class-local loggers and synthetic logging tests.
