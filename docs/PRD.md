# Product requirements document – EUR/MAD transfer and P2P exchange

## 1. Product statement

A consumer-facing, mobile-first web product allowing registered users to (a) simulate cross-border transfer quotes, choose a beneficiary, initiate and track simulated transfers; and (b) post EUR/MAD currency-exchange offers and match their intent against compatible users. Live funding, FX and payout must be performed by contracted, authorized providers, subject to corridor-specific legal approvals.

## 2. Target audience

- Spain-based sender who wants to send EUR to a Moroccan recipient paid in MAD.
- Morocco-based customer interested in permitted outbound MAD→EUR transactions (simulation first; live eligibility depends on purpose/residency/approval).
- User comparing exchange rates and seeking a match, where an authorized partner offers compliant settlement.
- Compliance/operations operator investigating exceptions, disputes, suspicious behavior and provider reconciliation.

## 3. Goals / non-goals

**Goals**
- Transparent indicative price: source, target, rate, fee, expiry, and total debit.
- Discover a compatible P2P counterparty without exposing account or identity data publicly.
- Model a robust quote→transfer→settlement state machine, first in a sandbox.
- Display accurate provider-backed status and maintain an auditable transaction history.
- Prove modular provider integration without writing to real money rails.

**Non-goals for the initial MVP**
- Holding user funds, balances, escrow, wallets, lending or internal cash-out.
- Peer-to-peer direct bank details exchange or offline settlement instructions.
- Crypto/USDT/stablecoin settlement or shadow FX clearing.
- Cash pickup or card funding unless supported/approved by licensed partner.
- Guaranteed rate until an authorized liquidity/payment provider has locked the quote.
- General unrestricted MAD outbound transactions.

## 4. Roles and permissions

- `CUSTOMER`: manage own beneficiaries, own quotes, own transfers, own offers and match confirmations.
- `SUPPORT`: read limited transaction and customer metadata, no KYC documents unless essential.
- `COMPLIANCE`: review flagged activity, hold/unhold within authorized policy, document decisions.
- `ADMIN`: configure providers, feature flags and policies; must not bypass regulatory gates unilaterally.
- Provider webhooks use separately authenticated machine-to-machine credentials, not customer JWTs.

## 5. Principal user journeys

### J1 — First-run onboarding
1. User registers/logs in using an OIDC identity provider with required MFA.
2. App creates idempotent local customer profile mapped to OIDC `sub`.
3. User selects residency and intended corridor; no assumption that geographic presence implies eligibility.
4. Sandbox verifies a mock identity record; production uses KYC/KYB provider and explicit screening outcome.
5. UI shows verification state and available operations. Rejected/expired cannot execute.

### J2 — EUR→MAD transfer (simulated)
1. User enters source amount, recipient country, purpose and preferred payout method.
2. App shows quote: EUR debit, MAD receive, rate source, total fees, expiry, and simulation label.
3. User adds/selects beneficiary, confirms information and accepts quote.
4. System checks KYC, eligibility, transfer limits, quote freshness, compliance rules and idempotency key.
5. Sandbox adapter issues provider-like status events; UI tracks `AWAITING_FUNDING` → `PROCESSING` → `COMPLETED` (or failure/cancellation/refund).
6. Receipts show simulated result; live receipts only after confirmed partner settlement.

### J3 — MAD→EUR transfer (simulated, outbound-restricted)
- Same conceptual journey with extra `purpose` and `residency`/source-of-funds rule checks.
- Live submission is hard-blocked until the legal basis, partner capability and permitted purpose are established.

### J4 — P2P offer and matching
1. Verified user creates a directional offer, e.g. `SELL EUR / BUY MAD` for a specified source amount and minimum desired rate.
2. Service validates positive source amount, fee policy, expiry, amount limits and compatibility.
3. Matching engine searches only reciprocal currency pairs, verifies rate compatibility, direction and remaining amounts.
4. An atomic reservation prevents double matching of remaining offer quantities; one or more partial matches may be proposed where allowed.
5. Both users explicitly accept a proposed match before expiry; no funds move.
6. Once legal/provider integration supports this use case, a partner-driven settlement workflow may be initiated. Otherwise status ends `AGREED_SIMULATION_ONLY`.
7. Never expose phone numbers, bank accounts, IDs or private user details in public listings.

### J5 — Operator reviews exception
- Compliance sees a queue of `REVIEW_REQUIRED` transfers/matches, reasons with minimal PII, and an append-only decision trail.
- Suspension/hold/rejection require reason code and operator ID; sensitive actions may require two-person authorization.

## 6. Functional requirements

| ID | Requirement | Priority | Acceptance |
|---|---|---|---|
| FR-01 | OIDC registration, MFA and session management | P0 | Backend verifies issuer, audience, expiry and scopes; no custom password store |
| FR-02 | Minimal customer profiles | P0 | Customer has stable UUID and unique OIDC subject |
| FR-03 | KYC status adapter | P0 | Mock session and callback available, no direct storage of ID documents |
| FR-04 | Beneficiary CRUD with protected fields | P0 | Ownership enforced, only masked destination display |
| FR-05 | Quote engine | P0 | Rate snapshot, fees, rounding policy, expiry and amount consistency |
| FR-06 | Simulated transfer orchestration | P0 | Idempotent create and provider-independent states |
| FR-07 | P2P offer book | P0 | Create/list/cancel self-owned offers, no private details leaked |
| FR-08 | P2P matching | P0 | Reciprocal pairs + compatible rates, atomic reserve, no oversubscription |
| FR-09 | P2P confirmation | P0 | Both sides accept, expiring unmatched reservations, no live money movement |
| FR-10 | Admin compliance decisions | P0 | RBAC, immutable audit, reason required |
| FR-11 | Provider webhook ingestion | P0 | Signature, timestamp/replay prevention, dedupe and state validation |
| FR-12 | Notifications | P1 | Async outbox, no confidential details in email or SMS |
| FR-13 | Payment integration | P1 legal-gated | Real partner qualification and legal approval required; default off |
| FR-14 | Reconciliation and support resolution | P1 | Compare provider statements with local orchestration events; alert mismatches |

## 7. Business rules

- `Money` is an ISO 4217 currency plus integer minor units; EUR and MAD default to 2 fractional digits (verify partner and payout representation).
- Rates are decimal `target_currency / source_currency`; never binary float.
- All calculations centralized; explicit `HALF_EVEN` rounding where contract and partner permit; final payout quote from provider is authoritative.
- Fees are displayed in source currency; `totalDebit = sourceAmount + fee` for the reference contract. Define alternatives explicitly if partner fees are deducted from payout.
- A quote is immutable after creation and has source, timestamp, expiry, fee breakdown, provider and quote type.
- A quote can be consumed at most once; ownership and currency pair must match transfer.
- Transfer completion requires authoritative confirmation of final payout from the licensed partner, never a UI click or unmatched webhook.
- Transfer cancellation requires confirmation of cancellation state; if already funded, refund may be needed.
- All state changes log actor, time, event, correlation ID and prior/next state; no direct editing of terminal status.
- P2P matching requires opposite directional pairs; rate compatibility must normalize both into a single canonical convention (e.g. MAD per EUR) before comparison.
- Reserves expire and return capacity exactly once. Each offer's `remaining` and `reserved` never go negative and never exceed original quantity.
- Default support: ES→MA simulated, MA→ES simulated; live enabled separately only with multi-party sign-off.

## 8. UX screens

1. Marketing landing + transfer estimator labeled indicative.
2. Login, registration, MFA and verification status.
3. Dashboard: transfer, exchange marketplace, history, notifications.
4. Transfer wizard: amount→quote→beneficiary→summary→confirmation→status.
5. Beneficiary list/detail and secure editing.
6. Offer marketplace: filters by direction, amount, rate and expiry.
7. Create offer, order details and double-sided match approval.
8. Transaction history with states, fees, recipient and receipt.
9. Support and compliance review dashboard.

## 9. Nonfunctional requirements / SLOs (targets, not measured commitments)

- Security: TLS, encrypted confidential records, managed secrets, OIDC MFA, signed webhooks, rate limits, principle of least privilege.
- Availability target for public read APIs: 99.9% after production readiness; separate business continuity target for payment workflows.
- Performance planning target: p95 read requests <300 ms on representative staging load, excluding third-party latency.
- Durable idempotent transfer create; at-least-once event consumption with idempotent handlers.
- Correlatable structured logs and OpenTelemetry traces; no PII/bank details in telemetry.
- Resilience: outbox dispatch retry, webhook dedupe, provider polling/reconciliation, dead-letter handling.
- Accessibility: WCAG 2.2 AA goal; English, Spanish, French and Arabic/Darija content strategy; RTL UI testing.
- Data protection: GDPR privacy-by-design, retention schedule with legal hold support, data-subject request workflow.

## 10. Product success metrics

- KYC completion rate; quote request and quote acceptance rates.
- Transfer simulation funnel completion and reasons for abandonment.
- Offer creation, match rate, match acceptance rate, reservation expiry rate.
- Zero duplicate bookings, zero negative reserves, zero untracked state transitions.
- Webhook processing lag, provider reconciliation discrepancy count, manual-review cases.

## 11. Unresolved business decisions

- Exact allowable remittance purposes and residency cohorts for MAD→EUR.
- Fees / margins, amount limits, payment methods and settlement partner contract.
- Whether licensed settlement can honor P2P-negotiated FX rates or only partner quotes.
- Whether partial P2P matches are supported in the first launch; recommendation: start with full-only, then add partials.
- Dispute model and chargeback exposure of each funding method.
