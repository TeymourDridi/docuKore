# Collaboration and Change Control

## 1. Discussion Is Read-Only by Default

The following activities do not authorize file modifications:
- Brainstorming.
- Architecture discussions.
- Research and technical comparisons.
- Planning and design.
- Code review.
- Explaining implementation approaches.

Never create project files, application scaffolding or configurations during these activities unless explicitly requested.

## 2. Implementation Requires Authorization

Before substantial implementation:
- Identify the agreed scope.
- Identify files expected to change.
- Explain important architectural decisions.
- Wait for approval when the user has requested collaborative step-by-step development.

An explicit instruction to implement a clearly defined task authorizes that task only.

"Next", "continue" and "what do you recommend?" do not independently authorize implementation.

## 3. Configuration Protection

Never modify these without explicit authorization:
- .claude/settings.json
- .claude/settings.local.json
- .mcp.json
- Global Claude Code settings.
- OmniRoute configuration.
- Environment variables and credentials.

Do not execute setup, repair, upgrade or diagnostic commands that modify configurations without first explaining their side effects.

## 4. Scope Discipline

- Work only on the currently agreed milestone.
- Do not generate future modules prematurely.
- Do not introduce unrelated refactoring.
- Do not create application scaffolding during architecture discussions.
- Respect explicit instructions to avoid subagents or background agents.
- Preserve existing working integrations.

## 5. Completion

Report actual modifications and verification results.
Stop when the agreed scope is completed.
