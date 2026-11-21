# Development backlog: implementation-first milestones

Each ticket must create a PR with implementation, migrations/contracts where applicable, tests, and reviewer sign-off. **No real-money transactions in MVP**.

### Surface and pairing rules

- The ticket's stated surface is authoritative: **Backend** means API/domain work; **Frontend** means web UI consuming existing APIs; **Full stack** means a contract-first API and its web flow in the same ticket; **Cross-cutting** means the explicitly named layers; **Governance** means documentation/legal work with no software changes. Do not add a UI flow to a backend-only ticket unless its acceptance criteria say so.
- Frontend and full-stack tickets must use the generated TypeScript OpenAPI client, include loading/empty/error/unauthenticated/ineligible states as applicable, and avoid JavaScript floating-point money math. Accessibility, responsive behavior, and RTL are required where specified below.
- `scripts/pair.sh` defaults to the ticket's named implementer/reviewer, and supports `--implementer claude|codex --reviewer claude|codex` for frontend or backend work. The roles must be different; role overrides do not broaden ticket scope.

## M0 – Repository and architecture foundation

### FX-001 · Monorepo scaffold (Implement: Claude; review: Codex)
- **Surface:** Full stack.
- Create `apps/web` (Next.js TypeScript) and `services/api` (Spring Boot Java 21) in a single Git repo.
- Add root scripts, basic editor setup, `.gitignore`, README, static checks.
- **Done when:** clean clone builds frontend/backend; no secrets committed; default dev mode `SIMULATION`.

### FX-002 · Local infrastructure and migrations (Implement: Claude; review: Codex)
- **Surface:** Backend (infrastructure and migrations).
- Docker Compose PostgreSQL and Redis, Flyway integration, core migration with test fixtures.
- **Done when:** `docker compose up -d` and backend startup apply schema; integration tests use isolated DB, not shared dev data.

### FX-003 · Contract-first tooling and CI (Implement: Codex; review: Claude)
- **Surface:** Cross-cutting (contract, frontend client, CI).
- Validate OpenAPI, generate TS client, error envelope, GitHub Actions lint/build/test/scan.
- **Done when:** CI rejects breaking schema drift and failed type-check; local generation reproducible.

## M1 – Identity and customer domain

### FX-004 · OIDC Resource Server (Implement: Claude; review: Codex)
- **Surface:** Backend.
- Validate JWT, issuer, audience, expiry, scopes; map `sub`→customer and guard ownership.
- **Done when:** malformed tokens and BOLA/IDOR tests fail securely; synthetic user fixtures work.

### FX-021 · Customer sign-in and application shell (Implement: Claude; review: Codex)
- **Surface:** Frontend (depends on FX-004).
- Integrate with a configured OIDC provider using authorization code + PKCE; provide sign-in, callback, sign-out, session-expiry handling, and authenticated navigation to dashboard, transfers, beneficiaries, and marketplace.
- Do not add app-managed passwords or store access tokens in URLs/logs. Keep the KYC dashboard's no-token state actionable.
- **Done when:** a stub OIDC provider covers success, cancellation, expiry, and logout; protected pages redirect safely; keyboard navigation works.

### FX-005 · KYC sandbox and user dashboard (Implement: Claude; review: Codex)
- **Surface:** Full stack.
- Mock verified KYC flows and status screens; later integration via provider port.
- **Done when:** backend eligibility gates and web KYC status/loading/error/ineligible states agree; only approved/simulated users may submit eligible operations; raw docs never stored.

### FX-006 · Beneficiary domain (Implement: Claude; review: Codex)
- **Surface:** Backend.
- Create/list with destination token and masked display.
- **Done when:** cross-account reads are rejected; deleted entries unavailable; validation covers country/method.

## M2 – Transfer simulation

### FX-007 · Pricing module (Implement: Codex; review: Claude)
- **Surface:** Backend.
- Immutable FX quotes, source vs target rate convention, fee math and expiry.
- **Done when:** BigDecimal property tests, golden rounding tests and stale quote rejection pass.

### FX-008 · Transfer orchestration (Implement: Claude; review: Codex)
- **Surface:** Backend.
- POST and GET transfer APIs, state machine, quote consumption, idempotency table, lifecycle events.
- **Done when:** concurrent identical submissions create one transfer; same key different payload returns 409; no invalid transitions.

### FX-009 · Provider simulator and webhook handler (Implement: Claude; review: Codex)
- **Surface:** Backend.
- Sandbox provider, signature verification and webhook inbox dedupe; async outbox.
- **Done when:** duplicate, replayed, forged, late and out-of-order events are handled deterministically; no fake payout confirmation.

### FX-010 · Transfer experience (Implement: Claude; review: Codex)
- **Surface:** Frontend (integrates FX-006–FX-009 APIs and FX-021 sign-in/session).
- Quote→beneficiary selection/create→confirmation→status screens and transaction history. Use the generated client; show fees, quote expiry, masked beneficiary details, simulation-only status, and idempotent retry behavior.
- Add a browser E2E runner and deterministic synthetic fixtures; the current scaffold has unit tests only.
- **Done when:** full browser E2E transfer simulation covers success, expired quote, API failure, ineligible customer, empty history, and retry; all screens retain clear `SIMULATION` labels and pass keyboard, responsive, and RTL checks.

## M3 – Marketplace & P2P matching

### FX-011 · Offer create/list/cancel (Implement: Claude; review: Codex)
- **Surface:** Backend.
- Direction, amount, rate, expiry, own-offer read and cancel.
- **Done when:** account isolation and no private user detail disclosure; cancellations guarded when reservations exist.

### FX-012 · Atomic matching engine (Implement: Codex; review: Claude)
- **Surface:** Backend.
- Reciprocal pair normalization; full-fill-only candidate matching in one DB transaction.
- **Done when:** rate compatibility is property-tested; 100+ parallel reservation attempt stress test never oversells or produces negative amount.

### FX-013 · Bilateral acceptance and expiry (Implement: Claude; review: Codex)
- **Surface:** Backend.
- Both users accept one match; expiry job releases reservation exactly once; never initiates payment.
- **Done when:** agreed match ends `AGREED_SIMULATION_ONLY`, duplicate accept/expiry is idempotent and rollback works.

### FX-014 · Marketplace UI (Implement: Claude; review: Codex)
- **Surface:** Frontend (integrates FX-011–FX-013 APIs and FX-021 sign-in/session).
- Search/filter offers, create/list/cancel own offers, review a match, and accept/decline as each participant. Public views must not reveal another customer's identity or contact details.
- Reuse FX-010's browser E2E runner and deterministic fixtures.
- **Done when:** two-account browser simulation covers matching, both accept/decline paths, expiry, cancellation conflict, empty/error states, and clear `AGREED_SIMULATION_ONLY` labels; keyboard, responsive, and RTL checks pass.

## M4 – Compliance, resilience and release-readiness

### FX-015 · Corridor rules / purpose restrictions (Implement: Codex; review: Claude)
- **Surface:** Backend.
- Feature gates for direction, purpose, country, residency, verification and amount with hard-coded default simulation.
- **Done when:** all live attempts including MA→ES cannot run; flags cannot bypass server and environmental policy.

### FX-016 · Admin reviews and audit (Implement: Claude; review: Codex)
- **Surface:** Backend.
- Admin/COMPLIANCE RBAC, review queue, reasoned decision and immutable events.
- **Done when:** all approvals/denials audit actor and reason; sensitive operations have restricted access.

### FX-017 · SRE / telemetry / reconciliation mock (Implement: Codex; review: Claude)
- **Surface:** Backend.
- OpenTelemetry, trace correlation, alert on stuck transfers and unprocessed outbox, mock provider reconciliation.
- **Done when:** stuck states raise alerts, no sensitive payload in logs and missing provider events are detectable.

### FX-018 · Threat modeling & test hardening (Implement: Codex; review: Claude)
- **Surface:** Cross-cutting (backend and frontend).
- Threat review, OWASP tests, fuzzing, dependency/security scans, rate limit, accessibility checks.
- **Done when:** critical vulnerabilities closed, known residual risks documented, secrets scan clean, and frontend accessibility/localization findings have owners and tracked fixes.

## Frontend gaps from the current audit

FX-010 and FX-014 already cover transfer and marketplace experiences. The audit found additional PRD screens and behaviors that were untracked or underspecified. Add these after their dependencies; they remain simulation-only:

### FX-022 · Compliance review web console (Implement: Claude; review: Codex)
- **Surface:** Full stack (depends on FX-016; includes a contract-first, audited, read-only support lookup if none exists).
- Display the review queue and minimal transaction/customer metadata; allow authorized COMPLIANCE users to submit a reasoned decision and inspect its audit result. Provide SUPPORT only a limited read-only view. The API remains the authority for role checks.
- **Done when:** role-based browser/API tests cover CUSTOMER, SUPPORT, COMPLIANCE, and ADMIN; unauthorized actions are unavailable in the UI and rejected by the API; support access is audited; no unnecessary PII is displayed.

### FX-023 · Customer notification center (Implement: Claude; review: Codex)
- **Surface:** Full stack (depends on transfer and marketplace lifecycle events).
- Define a contract and durable, deduplicated notification inbox for relevant transfer and match status changes, then add an authenticated web notification list and unread state. Do not send email/SMS or include sensitive destination/payment data in MVP notifications.
- **Done when:** owner isolation, duplicate-event handling, unread/read behavior, empty/error states, and safe links to the user's own resources are covered; every notice remains explicitly simulation-only where it describes an operation.

### FX-024 · Accessibility and localization completion (Implement: Claude; review: Codex)
- **Surface:** Frontend (depends on FX-010 and FX-014).
- Implement the PRD language and accessibility experience across customer journeys: English, Spanish, French, Arabic/Darija content strategy, correct RTL layout, keyboard/screen-reader behavior, and accessible money/status announcements.
- **Done when:** WCAG 2.2 AA checks cover primary transfer and matching flows in LTR and RTL; locale switching preserves form state; no critical untranslated or inaccessible customer action remains. Track anything blocked by unresolved product translations.

## M5 – Separate post-MVP partner discovery (no live code until approved)

### FX-019 · Partner/authorization feasibility (Owner: founder + regulated counsel + potential PSP)
- **Surface:** Governance (no code).
- Confirm Spain-side operating model, Morocco-side pay-in/out, cross-currency authorization and limits per direction/purpose.
- **Done when:** signed legal mapping and partner capability matrix documents specifically authorize proposed live flows.

### FX-020 · Licensed settlement implementation (Owner: engineering with compliance approval)
- **Surface:** Full stack (post-FX-019 approval only; prohibited in the current simulation MVP).
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
