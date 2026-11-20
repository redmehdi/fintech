**Pair-programming CLI:** start with [`QUICKSTART.md`](QUICKSTART.md). The included scripts orchestrate Claude Code implementation and Codex independent review.

# EUR–MAD Fintech MVP Blueprint

**Status:** Architecture baseline / simulation only. **Prepared:** 2026-11-20.

A full-stack product blueprint for two features: (1) money transfers and (2) P2P currency-exchange offer matching between Spain and Morocco.

## Safety and legal operating boundary

- No custody, escrow, stored value, payment execution, FX execution, or informal/netted cross-border settlement by the application itself.
- Both EUR→MAD and MAD→EUR are **simulation-only** until licensed partners and counsel confirm legality of each direction, use case, jurisdiction, and settlement mechanism.
- P2P matching expresses **intent**, not guaranteed execution or permission to bypass regulated payment/FX channels.
- An authorized partner must support settlement and provide independently verifiable completion notifications. Partner integration is **disabled by default** and cannot be activated solely by toggling production configuration.
- No live rates are included; displayed rates in examples are illustrative.

## Stack

- Frontend: Next.js, React, TypeScript; Tailwind CSS; shadcn/ui.
- API: Java 21, Spring Boot, Spring Security OAuth2 Resource Server, Hibernate/JPA or jOOQ.
- Infrastructure: PostgreSQL, Redis for ephemeral caching and limits, Docker Compose local, Flyway, GitHub Actions.
- Async/reliability: PostgreSQL transactional outbox initially; Kafka only if measurements justify it.
- Auth: trusted managed OIDC identity provider, MFA; *no homegrown credential storage*.
- Payment/FX/KYC: external adapter interfaces, backed by deterministic local sandbox mocks at launch.

## Layout

- `docs/PRD.md` – product scope, personas, user journeys, requirements, UX states, acceptance criteria.
- `docs/ARCHITECTURE.md` – modules, flows, state machines, invariants, APIs, NFRs.
- `docs/DATA_MODEL.md` – entities, constraints, indexes and data lifecycle.
- `docs/SECURITY_COMPLIANCE.md` – regulatory dependencies, threat model and release gates.
- `docs/BACKLOG.md` – dependency-ordered implementation tickets with acceptance criteria and agent ownership.
- `contracts/openapi.yaml` – initial OpenAPI 3.1 specification with representative endpoints and schemas.
- `db/migration/V1__core_domain.sql` – reference PostgreSQL schema for review; not certified for production.
- `AGENTS.md` and `CLAUDE.md` – shared coding-agent instructions.

## Decisions pending before any live launch

1. Legal entity and regulator-facing operating model: technology provider, agent/distributor of licensed PSP, or licensed entity.
2. Licensed payment institution and Moroccan bank/payment institution contracts (pay-in, FX, pay-out, refund, reconciliation, disputes).
3. Explicit feasibility/legal sign-off for each transfer direction, including Moroccan outbound exchange-control use cases.
4. Whether any licensed partner permits multi-counterparty P2P matching with its settlement rails.
5. Identity/KYC provider, sanctions/PEP screening supplier and data-processing agreements.
6. Tariff, maximum/minimum transaction limits, currencies, FX quote source, cash vs bank payout policy, and permissible transaction purpose.

## Official starting references (verify currency before live operation)

- Banco de España, payment-institution authorization: https://www.bde.es/wbe/en/punto-informacion/contenidos/autorizaciones-acreditaciones/autorizacion-inicio-actividad/entidades-pago.html
- Banco de España, reserved activity in cross-border remittances: https://clientebancario.bde.es/pcb/en/blog/sabias-que-la-intermediacion-en-un-simple-pago-con-tarjeta-o-en-el-envio-de-dinero-a-otro-pais-son-actividades-reservadas-.html
- Office des Changes, 2026 Instruction: https://www.oc.gov.ma/fr/actualites/publication-de-l-instruction-generale-des-operations-de-change-2026
- Office des Changes, outward transfers and authorized intermediaries: https://www.oc.gov.ma/fr/actualites/communique-mise-au-point-au-sujet-du-transfert-d-argent-a-destination-de-l-etranger
- EU Regulation 2023/1113: https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX:32023R1113
- AEPD international personal data transfers: https://www.aepd.es/derechos-y-deberes/cumple-tus-deberes/medidas-de-cumplimiento/garantias-transferencias-datos-personales

The technical blueprint is not a legal determination that a specific business model is authorized.
