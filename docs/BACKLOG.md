# Development backlog: implementation-first milestones

Each ticket must create a PR with implementation, migrations/contracts where applicable, tests, and reviewer sign-off. **No real-money transactions in MVP**.

## M0 – Repository and architecture foundation

### FX-001 · Monorepo scaffold (Implement: Claude; review: Codex)
- Create `apps/web` (Next.js TypeScript) and `services/api` (Spring Boot Java 21) in a single Git repo.
- Add root scripts, basic editor setup, `.gitignore`, README, static checks.
- **Done when:** clean clone builds frontend/backend; no secrets committed; default dev mode `SIMULATION`.

### FX-002 · Local infrastructure and migrations (Implement: Claude; review: Codex)
- Docker Compose PostgreSQL and Redis, Flyway integration, core migration with test fixtures.
- **Done when:** `docker compose up -d` and backend startup apply schema; integration tests use isolated DB, not shared dev data.

### FX-003 · Contract-first tooling and CI (Implement: Codex; review: Claude)
- Validate OpenAPI, generate TS client, error envelope, GitHub Actions lint/build/test/scan.
- **Done when:** CI rejects breaking schema drift and failed type-check; local generation reproducible.

## M1 – Identity and customer domain

### FX-004 · OIDC Resource Server (Implement: Claude; review: Codex)
- Validate JWT, issuer, audience, expiry, scopes; map `sub`→customer and guard ownership.
- **Done when:** malformed tokens and BOLA/IDOR tests fail securely; synthetic user fixtures work.

### FX-005 · KYC sandbox and user dashboard (Implement: Claude; review: Codex)
- Mock verified KYC flows and status screens; later integration via provider port.
- **Done when:** only approved/simulated users may submit eligible operations; raw docs never stored.

### FX-006 · Beneficiary domain (Implement: Claude; review: Codex)
- Create/list with destination token and masked display.
- **Done when:** cross-account reads are rejected; deleted entries unavailable; validation covers country/method.

## M2 – Transfer simulation

### FX-007 · Pricing module (Implement: Codex; review: Claude)
- Immutable FX quotes, source vs target rate convention, fee math and expiry.
- **Done when:** BigDecimal property tests, golden rounding tests and stale quote rejection pass.

### FX-008 · Transfer orchestration (Implement: Claude; review: Codex)
- POST and GET transfer APIs, state machine, quote consumption, idempotency table, lifecycle events.
- **Done when:** concurrent identical submissions create one transfer; same key different payload returns 409; no invalid transitions.

### FX-009 · Provider simulator and webhook handler (Implement: Claude; review: Codex)
- Sandbox provider, signature verification and webhook inbox dedupe; async outbox.
- **Done when:** duplicate, replayed, forged, late and out-of-order events are handled deterministically; no fake payout confirmation.

### FX-010 · Transfer experience (Implement: Claude; review: Codex)
- Quote→beneficiary→confirmation→status screens and transaction history.
- **Done when:** full browser E2E transfer simulation with clear `SIMULATION` labels passes.

## M3 – Marketplace & P2P matching

### FX-011 · Offer create/list/cancel (Implement: Claude; review: Codex)
- Direction, amount, rate, expiry, own-offer read and cancel.
- **Done when:** account isolation and no private user detail disclosure; cancellations guarded when reservations exist.

### FX-012 · Atomic matching engine (Implement: Codex; review: Claude)
- Reciprocal pair normalization; full-fill-only candidate matching in one DB transaction.
- **Done when:** rate compatibility is property-tested; 100+ parallel reservation attempt stress test never oversells or produces negative amount.

### FX-013 · Bilateral acceptance and expiry (Implement: Claude; review: Codex)
- Both users accept one match; expiry job releases reservation exactly once; never initiates payment.
- **Done when:** agreed match ends `AGREED_SIMULATION_ONLY`, duplicate accept/expiry is idempotent and rollback works.

### FX-014 · Marketplace UI (Implement: Claude; review: Codex)
- Search and filter offers, create offer, match review and confirmation.
- **Done when:** responsive RTL-ready app and E2E two-account match simulation pass.

## M4 – Compliance, resilience and release-readiness

### FX-015 · Corridor rules / purpose restrictions (Implement: Codex; review: Claude)
- Feature gates for direction, purpose, country, residency, verification and amount with hard-coded default simulation.
- **Done when:** all live attempts including MA→ES cannot run; flags cannot bypass server and environmental policy.

### FX-016 · Admin reviews and audit (Implement: Claude; review: Codex)
- Admin/COMPLIANCE RBAC, review queue, reasoned decision and immutable events.
- **Done when:** all approvals/denials audit actor and reason; sensitive operations have restricted access.

### FX-017 · SRE / telemetry / reconciliation mock (Implement: Codex; review: Claude)
- OpenTelemetry, trace correlation, alert on stuck transfers and unprocessed outbox, mock provider reconciliation.
- **Done when:** stuck states raise alerts, no sensitive payload in logs and missing provider events are detectable.

### FX-018 · Threat modeling & test hardening (Implement: Codex; review: Claude)
- Threat review, OWASP tests, fuzzing, dependency/security scans, rate limit, accessibility checks.
- **Done when:** critical vulnerabilities closed, known residual risks documented, secrets scan clean.

## M5 – Separate post-MVP partner discovery (no live code until approved)

### FX-019 · Partner/authorization feasibility (Owner: founder + regulated counsel + potential PSP)
- Confirm Spain-side operating model, Morocco-side pay-in/out, cross-currency authorization and limits per direction/purpose.
- **Done when:** signed legal mapping and partner capability matrix documents specifically authorize proposed live flows.

### FX-020 · Licensed settlement implementation (Owner: engineering with compliance approval)
- Only after FX-019, build provider adapters, partner-driven quotes, funding, settlement, refund/disputes and reconciliation.
- **Done when:** partner sandbox certification, audited controls and required approvals exist. P2P live remains unavailable unless partner expressly supports it.

## First 5 PRs to open

1. `feature/FX-001-scaffold`
2. `feature/FX-002-database-local`
3. `feature/FX-003-openapi-ci`
4. `feature/FX-004-oidc-authz`
5. `feature/FX-007-fx-quotes`

## Suggested agent prompts

**Claude Code – first ticket**

> Read AGENTS.md, docs/PRD.md, docs/ARCHITECTURE.md and FX-001 in docs/BACKLOG.md. Implement only FX-001 in the current feature branch. Create a Spring Boot Java 21 application and Next.js TypeScript app in the prescribed locations. Keep all payments simulation-only. Write smoke tests and run builds. Summarize modified files and commands run; do not touch unrelated modules or enable real-money APIs.

**Codex – review**

> Review the diff for FX-001 against AGENTS.md, PRD and acceptance criteria. Focus on potential deviations from simulation-only constraints, build reproducibility, dependency/security choices and scope creep. Report issues ordered by severity with filenames and lines. Do not edit source files unless explicitly assigned a fix ticket.
