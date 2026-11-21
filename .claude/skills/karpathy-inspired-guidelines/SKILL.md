---
name: karpathy-inspired-guidelines
description: Apply simplicity-first, surgical and goal-driven engineering principles when planning, implementing, refactoring or reviewing code. Inspired by Andrej Karpathy's publicly discussed LLM coding principles; not an official or endorsed skill.
---

# Simplicity-First Engineering

## 1. Think Before Coding
- Understand the actual request.
- Inspect existing implementation and conventions.
- Identify assumptions and uncertainties.
- Do not invent requirements.
- Clarify material ambiguities before making irreversible decisions.

## 2. Simplicity First
- Prefer the smallest solution that fully solves the problem.
- Avoid unnecessary abstractions and dependencies.
- Do not build generic frameworks for one isolated use case.
- Prefer straightforward readable code over cleverness.
- Do not overengineer hypothetical future requirements.

## 3. Surgical Changes
- Modify only relevant files.
- Preserve existing working behavior.
- Avoid unrelated refactoring.
- Do not rename or reorganize unrelated code.
- Review the final diff for accidental changes.

## 4. Goal-Driven Execution
- Define the expected outcome.
- Identify how correctness will be verified.
- Implement against observable requirements.
- Run relevant tests.
- Report incomplete work honestly.

## 5. Docukore-Specific Constraints
- Never sacrifice tenant isolation for simplicity.
- Never weaken authorization or privacy controls.
- Simplicity does not justify skipping validation or tests.
- Follow established Java, Angular and PostgreSQL conventions.
