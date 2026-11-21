**Pair-programming CLI:** start with [`QUICKSTART.md`](QUICKSTART.md). The scripts follow each backlog ticket's assigned implementer and reviewer; FX-003 uses Codex implementation, Claude review, and Codex session resume for repair.

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

## Local development

Prerequisites: Docker Compose, Java 21, Node.js 24 (`.nvmrc`; `^22.13.0 || ^24.0.0 || >=26.0.0` supported, as required by the locked Vitest/Vite/ESLint toolchain — Node 20 is not supported), npm. Maven is provided via `services/api/mvnw`.

```bash
make infra-up   # PostgreSQL on 127.0.0.1:5432 and Redis on 127.0.0.1:6379
make install   # npm ci in apps/web
make contract-generate # validate OpenAPI and generate the TypeScript fetch client
make test      # Vitest (web) + JUnit (api)
make lint      # ESLint + tsc (web), compile with -Werror + enforcer (api)
make build     # next build + Spring Boot jar
make verify    # full harness: shell/python checks + web test/lint/typecheck/build + api verify
make web-dev   # http://localhost:3000
make api-run   # http://localhost:8080/actuator/health
make infra-down # stop local services (keeps PostgreSQL volume)
```

The API applies `db/migration/V1__core_domain.sql` with Flyway during startup. Local database credentials are synthetic development defaults only. Export `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, and `POSTGRES_PORT` before running Compose and the API to share overrides; `POSTGRES_HOST` defaults to `localhost`. `DATABASE_URL`, `DATABASE_USERNAME`, and `DATABASE_PASSWORD` can override the API connection directly. Test runs use a fresh PostgreSQL Testcontainers instance and do not connect to the shared development database. Redis is local ephemeral infrastructure and is not a source of truth for reservations or financial state.

Both apps run in **`SIMULATION`** mode only. The API binds `fintech.mode` to an enum whose only value is `SIMULATION` and refuses to start if `fintech.mode` is anything else or `fintech.live-enabled=true`. The web app likewise rejects any `NEXT_PUBLIC_OPERATING_MODE` other than `SIMULATION`. There is no configuration switch that enables live money movement.

### API contract checks

Edit `contracts/openapi.yaml` first. `make contract-generate` validates the spec and regenerates the TypeScript fetch client under `apps/web/src/lib/api/generated/` using the version pinned in `contracts/pom.xml`. The generated directory is ignored by Git. The root build, lint, test, web-dev and verify commands generate the client. Run `make contract-generate` before invoking the web npm scripts directly.

CI compares the contract in the proposed merge against the pull request base, or against the previous branch tip on pushes to `main`. On a new branch it uses the parent commit when that commit has a contract. The workflow verifies the oasdiff release checksum, then checks that removing an operation fails the compatibility gate and a TypeScript error fails `make verify`. It also fails on build and test failures or high- or critical-severity security findings.

CI sets `FINTECH_MAVEN=mvn` to use Maven from the runner for contract generation and backend checks. Local builds use the pinned wrapper by default.

Spring error responses use `application/problem+json` with `code`, `message`, and a server-generated UUID in both `correlationId` and the `X-Correlation-Id` header. The TypeScript client exposes the HTTP response on `ResponseError` and exports the generated `Problem` model.

## Layout

- `apps/web` – Next.js (App Router, TypeScript) frontend.
- `services/api` – Spring Boot (Java 21) modular-monolith API.

- `docs/PRD.md` – product scope, personas, user journeys, requirements, UX states, acceptance criteria.
- `docs/ARCHITECTURE.md` – modules, flows, state machines, invariants, APIs, NFRs.
- `docs/DATA_MODEL.md` – entities, constraints, indexes and data lifecycle.
- `docs/SECURITY_COMPLIANCE.md` – regulatory dependencies, threat model and release gates.
- `docs/BACKLOG.md` – dependency-ordered implementation tickets with acceptance criteria and agent ownership.
- `contracts/openapi.yaml` – OpenAPI 3.0 specification with representative endpoints and schemas.
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
