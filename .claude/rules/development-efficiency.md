# Development Efficiency Rules

## DOCKER
- Absolutely no new Docker containers.
- No Testcontainers startup.
- No docker run or docker compose up creating containers.
- Reuse existing PostgreSQL infrastructure.
- Never delete containers or volumes.
- If PostgreSQL is unavailable, report the blocker instead of creating infrastructure.

## DATABASE
- Development database contents are disposable.
- Destructive operations on confirmed development/test databases are allowed.
- No production database access.
- Reuse databases and schemas across tests.
- Do not reset after every test.
- Reset only when a genuinely clean migration/test cycle requires it.

## TESTING
- NEVER build after every code modification.
- NEVER test after every individual edit.
- Do not rerun tests that already passed unless relevant code changed.
- Finish a coherent implementation batch before testing.
- Use focused tests only when they provide useful feedback.
- Run one complete backend verification at the appropriate milestone.
- Fix related failures together instead of repeatedly running one failing test.
- Preserve meaningful PostgreSQL integration coverage.
- No weakening assertions or disabling tests just to get green results.

## TOKEN AND TIME EFFICIENCY
- Do not spend lengthy periods repeating analysis already documented.
- Avoid unnecessary architecture reviews.
- Avoid printing enormous Maven logs into the context.
- Save verbose output to local log files.
- Return only concise summaries and relevant error excerpts.
- Do not repeatedly poll or wait on operations unnecessarily.
- Do not run duplicate commands.
- Prefer direct inspection over repeatedly launching expensive processes.
- Do not produce lengthy progress reports.

## GIT
- We are not currently committing or pushing.
- No repetitive git status/diff inspections.
- No staging, commits, pushes, reset, clean or restore.
- Keep track of modified files yourself.
- Report changed files at completion.
- Never discard user modifications.

## SECURITY DURING DEVELOPMENT
- Defer exhaustive production security audits and compliance hardening.
- Preserve existing authentication, authorization and tenant isolation.
- Do not build unnecessary future security frameworks.
- Never expose credentials or secrets.

## WORKFLOW
- One approval for the main task, not every small change.
- Implement complete coherent batches.
- Use subagents aggressively for independent work.
- Avoid repeated planning and documentation.
- Preserve completed functionality.
- Stop only for genuine blockers, destructive ambiguity or important scope changes.
- Complete the agreed task without unnecessary interruptions.