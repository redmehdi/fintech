# Shared coding-agent instructions for Codex and Claude

## Product boundary (mandatory)

This project is a simulation-only fintech MVP for EUR↔MAD transfers and P2P exchange offer matching. Never enable or implement direct real-money movement, wallets, escrow, informal offsetting, or customer-to-customer payments. Live settlement requires a separately reviewed legal and licensed-provider milestone. MAD→EUR is not presumptively authorized. All payment/FX/KYC providers are interfaces with deterministic mocks by default.

## How to operate

1. Read `README.md`, `docs/PRD.md`, `docs/ARCHITECTURE.md`, `docs/SECURITY_COMPLIANCE.md`, and relevant tickets in `docs/BACKLOG.md` before modifying code.
2. Follow `contracts/openapi.yaml` contract-first. If changes are needed, open an ADR/PR and update spec + tests together.
3. One ticket per branch. Do not modify unassigned modules. Do not merge your own PR without an independent review.
4. **Implementer:** produce code, migrations, tests and a concise PR summary. **Reviewer:** focus on threats, concurrency, arithmetic, contracts, authz, resilience and missing coverage; do not mix review with unrelated refactor.
5. Use Java 21 + Spring Boot backend and Next.js/TypeScript frontend. PostgreSQL is truth; Redis cannot be authoritative for order reservations.
6. All money values: integer minor units + ISO currency. Decimal FX rates: Java `BigDecimal`, database `NUMERIC`; no `double` or JS `number` for authoritative financial math.
7. Implement idempotency for transfer creation; row ownership; verified webhooks; audit events; explicit state transitions.
8. Unit, integration (Testcontainers), API contract, security and concurrency tests for touched code. Run lint/type-check/build.
9. Never introduce real secrets or log raw KYC/PII/financial credentials. Mock fixtures must be synthetic.
10. Prefer modular monolith; avoid microservices and Kafka without an approved decision record.

## Required pull request template

- Ticket / scope:
- Contract modifications:
- Database migration and compatibility:
- Threat model and money-integrity implications:
- Tests run / results:
- Known risks / follow-up:
- Reviewer sign-off:

## Recommended agent split

- ChatGPT/Codex: PRD, ADRs, contract design, concurrency and security review, adversarial tests.
- Claude Code: scoped Spring/Next implementation, integration tests, UI features, refactoring.
- Roles may switch by ticket, but require separate branches and independent review.

## Review focus for Codex native review

Follow `.ai/prompts/review_policy.md`. Independent review must flag live-money code paths,
non-atomic P2P matching, rounding errors, privilege bypass, duplicate money movement,
leaky logs and missing negative tests. Findings need severity and file/line evidence.
A successful Codex CLI exit does not constitute approval; a human must examine findings.
