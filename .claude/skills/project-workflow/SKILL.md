---
name: project-workflow
description: Follow this project's engineering workflow when implementing features, fixing bugs, refactoring, changing architecture, modifying database schemas, or making security-sensitive changes. Use this to inspect first, research current documentation, plan appropriately, implement incrementally, and verify results.
---

# Docukore Engineering Workflow

## 1. Understand Before Acting

- Read the root CLAUDE.md.
- Read all applicable project rules.
- Inspect relevant existing files and established patterns.
- Understand the affected modules and their dependencies.
- Never assume the project structure or invent existing functionality.
- Avoid modifying unrelated files.

## 2. Classify the Task

Choose an appropriate level of process.

Small changes:
- Inspect the relevant code.
- Implement the requested change.
- Run focused verification.
- Avoid unnecessary planning documents.

Substantial changes:
- Identify affected modules and architectural boundaries.
- Identify dependencies and security implications.
- Prepare a short, ordered implementation plan.
- Identify important design decisions before coding.
- Present the plan and wait for approval when architectural choices or scope need agreement.

High-risk changes:
- Pay particular attention to database migrations, tenant isolation, authentication, authorization, medical data and subscription enforcement.
- Identify failure scenarios and potential data loss.
- Do not perform destructive or irreversible operations without explicit authorization.

## 3. Research

When work depends on a framework, library, API or CLI:

- Use the existing Context7 MCP integration and skill.
- Resolve the correct library documentation.
- Prefer documentation matching the actual selected version.
- Verify important API signatures and compatibility.
- Prefer official documentation and primary sources.
- Do not invent APIs or rely on outdated examples.
- Report unresolved compatibility issues honestly.

## 4. Plan Incrementally

For substantial work:

- Break the implementation into small, independently verifiable milestones.
- Prefer the smallest coherent implementation.
- Preserve established architecture and naming conventions.
- Avoid implementing future features prematurely.
- Do not introduce dependencies without justification.
- Document significant architectural decisions in ADRs when appropriate.

Respect requests for interactive, step-by-step development.

When the user asks to work incrementally, complete only the current agreed milestone and stop before starting the next one.

## 5. Implement Consistently

- Follow CLAUDE.md and applicable rules.
- Follow existing project conventions.
- Prefer explicit, readable and maintainable code.
- Avoid unnecessary abstractions.
- Keep responsibilities separated.
- Consider failure handling and validation.
- Keep frontend and backend contracts consistent.
- Enforce authorization on the backend.
- Preserve tenant isolation.
- Avoid unrelated refactoring.

Do not replace an established project pattern without a clear technical reason.

## 6. Verify

After meaningful changes:

- Run relevant compilation or build commands.
- Run focused unit tests.
- Run integration tests when database or infrastructure behavior is involved.
- Validate migrations.
- Check affected modules for regressions.
- Verify security-sensitive behavior with appropriate tests.
- Report commands that were executed and their actual results.

Do not claim a test passed if it was not executed.

Never hide failing tests or suppress errors simply to obtain a successful build.

## 7. Completion Report

Provide a concise report containing:

1. What changed.
2. Important implementation decisions.
3. Files created or modified.
4. Tests and verification actually performed.
5. Remaining issues or risks.

Avoid long reports for small changes.

If work is incomplete, clearly identify what remains.

## 8. Collaboration Rules

- Ask only when an unresolved decision materially affects the design or requirements.
- Prefer reasonable, reversible defaults for minor decisions.
- Do not ask the user to repeat information already documented.
- Explain important trade-offs in understandable language.
- Never silently expand the requested scope.
- Do not proceed to the next development milestone when the user requested step-by-step collaboration.

The goal is a consistent, secure and maintainable codebase, not maximum code generation.
