# Independent review policy

Review the current uncommitted changes against `AGENTS.md`, the assigned backlog ticket, the API contract, and the product/security requirements. Review only; do not edit files or approve/merge the change.

Prioritize findings by severity and include file and line evidence. Check especially for:

- Any real-money execution, custody, wallet, escrow, informal offsetting, or live MAD→EUR path.
- Non-atomic P2P matching, oversubscription, incorrect reservation release, or concurrency races.
- Floating-point or rounding errors in authoritative money/FX calculations.
- Missing ownership checks, privilege bypass, unsafe webhook verification, replay or idempotency gaps.
- Duplicate money movement, invalid state transitions, unverified completion, or missing audit events.
- Leaky logs, secrets, sensitive data exposure, and missing negative/security tests.
- API/schema compatibility, migration safety, failure handling, and material gaps in ticket acceptance criteria.

For each finding, state severity, impact, and the smallest concrete correction. Separate confirmed defects from questions or follow-up suggestions. If no findings are present, say so and list meaningful residual risks; a successful review command is not itself approval.
