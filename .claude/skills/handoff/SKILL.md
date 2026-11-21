---
name: handoff
description: Create or resume a concise project handoff when the user wants to continue work in a fresh Claude Code session, preserve progress, or transfer work to another session.
---

# Docukore Session Handoff

## When to Use
Use when explicitly asked to save progress, prepare a handoff, start a fresh session or resume previous work.

Do not automatically create handoff files after every task.

## Handoff Contents

Create a compact Markdown document containing:

1. Project objective and current milestone.
2. Confirmed technology stack and architectural decisions.
3. Important coding and security conventions.
4. Work completed.
5. Current project state.
6. Files created or modified.
7. Tests and commands actually executed.
8. Unresolved problems and risks.
9. Immediate next action.

## Rules

- Summarize decisions, not the entire conversation.
- Preserve important technical constraints.
- Distinguish confirmed decisions from proposals.
- Never invent implementation status.
- Never include API keys, passwords, tokens or other secrets.
- Never include real patient information.
- Keep the document concise and useful for a fresh session.
- Inspect existing handoff documents before creating duplicates.

## Suggested Location

docs/handoffs/

Use a date-based filename when creating a new handoff.

## Resuming

When resuming:
- Read the handoff.
- Inspect the current repository state.
- Verify that described files and configurations still exist.
- Identify discrepancies.
- Continue only from the confirmed current state.
