# Independent review task

Review the uncommitted implementation for backlog ticket **{{TICKET_ID}}**. Read `AGENTS.md`, `docs/PRD.md`, `docs/ARCHITECTURE.md`, `docs/SECURITY_COMPLIANCE.md`, `contracts/openapi.yaml`, the relevant backlog ticket, and `.ai/prompts/review_policy.md`.

Inspect the current staged, unstaged, and untracked changes. If this session contains earlier findings, verify which ones still apply and check for regressions introduced by subsequent edits.

Review only. Do not edit files, run implementation commands, or commit. Focus on concrete defects and report each finding with severity, file and line, impact, and a minimal correction. Check ticket acceptance criteria, OpenAPI compatibility, migration safety, money arithmetic, authz, concurrency, resilience, and missing negative tests. Confirm the simulation-only boundary has not been weakened.

If no findings are present, say so and list material residual risks. Do not treat successful builds as reviewer approval.
