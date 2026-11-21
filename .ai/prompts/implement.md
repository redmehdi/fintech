# Implementation task

Implement backlog ticket **{{TICKET_ID}}** in this repository.

Before changing files, read `AGENTS.md`, `README.md`, `docs/PRD.md`, `docs/ARCHITECTURE.md`, `docs/SECURITY_COMPLIANCE.md`, `contracts/openapi.yaml`, and the matching ticket in `docs/BACKLOG.md`. Follow the ticket's scope and acceptance criteria. Do not modify unrelated modules.

Keep all behavior simulation-only. Do not add wallets, custody, escrow, real payment or FX execution, informal settlement, or direct customer-to-customer payments. Treat MAD→EUR as simulation-only and fail closed for any live path.

Follow contract-first and money-integrity rules in `AGENTS.md`: integer minor units for money, decimal arithmetic for rates, explicit state transitions, ownership checks, idempotency where required, and auditability. Update the API contract, migrations, and tests together when the ticket requires them.

Implement the smallest complete change that meets the ticket. Do not commit, push, merge, switch branches, or deploy. Run relevant checks if the task environment permits, and report changed files, checks and results, contract/schema impact, and remaining risks.

For frontend or full-stack scope, use the generated TypeScript client from `contracts/openapi.yaml` rather than hand-maintaining API types or URLs. Do not use JavaScript `number` for authoritative money or FX calculations. Include the user-visible loading, empty, error, unauthenticated, and ineligible states that apply to the ticket. Keep simulation labeling visible, and cover keyboard access, responsive layouts, and RTL behavior where the ticket calls for them. Do not build screens for backend-only tickets unless their acceptance criteria explicitly include a web flow.
