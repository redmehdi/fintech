# Security and regulatory release gates

This is an engineering assessment, not authorization or legal advice.

## Spain and Morocco operating considerations

**Spain/EU:** Bank of Spain supervision and PSD2-derived Spanish rules may require payment institution authorization if the business is itself providing payments. A technical intermediary model requires careful legal analysis of what the platform actually does, who contracts with users, who executes transfers and controls funds. Merely naming a PSP does not eliminate all duties.

**Morocco:** IGOC 2026 (effective 2026-01-01) governs exchange operations; outward remittances are restricted to permitted purposes and authorized intermediaries. Treat MAD→EUR as separate legal feasibility track, not simply reversal of EUR→MAD. Confirm whether a proposed exchange offer network is itself a regulated exchange/intermediation activity. Do not construct an informal offset/hawala-like mechanism or ask users to transfer funds directly to each other.

**EU transfer-of-funds information requirements:** Regulation (EU) 2023/1113 and EBA travel-rule guidance govern payment service providers. Required originator/beneficiary metadata and screening responsibilities should be confirmed with the contracted PSP, not omitted from integration assumptions.

**Personal data:** GDPR applies to the EU-side processing. Morocco is a third-country data transfer concern requiring case-specific safeguards where personal data is transferred there; evaluate SCCs, transfer-impact assessment, necessity, purpose limitation, and vendor processing agreements. Do not copy KYC images into application databases.

## Launch gate matrix

| Condition | EUR→MAD live | MAD→EUR live | P2P live |
|---|---|---|---|
| Legal memo confirms business operating model | Required | Required | Required |
| Spain-side regulated partner permission | Required | Required | Required |
| Morocco-side regulated partner and FX permission | Required | Required | Required |
| Purpose/residency/capital controls cleared | Required | Strictly required | Strictly required |
| Funding and payout contracts + licensed FX execution | Required | Required | Required |
| KYC/AML/sanctions/PEP workflows and data agreements | Required | Required | Required |
| Refunds, chargebacks, disputes and reconciliation tested | Required | Required | Required |
| Provider explicitly supports P2P price matching/settlement | Not applicable | Not applicable | Required |
| Independent security testing and operational sign-off | Required | Required | Required |

No live feature can be enabled unless **all** applicable checks pass. Default state is simulation for every direction and feature.

## Security baseline

- OIDC/OAuth2 with PKCE, MFA and issuer/audience validation; never trust a front-end role claim alone.
- Prevent IDOR/BOLA with per-record ownership checks; audit admin reads of sensitive data.
- Use `BigDecimal` in Java and `BIGINT` minor units for stored money; JSON money values are decimal-string representations or integer minor units, never JS floating-point money calculations for authoritative amounts.
- CSRF controls for cookie-authenticated flows; strict CORS, secure cookies, CSP and clickjacking protections.
- Idempotency and replay defense for all operations that change financial workflow state.
- Provider webhook: HMAC or asymmetric signature, timestamp tolerance, nonce/event ID dedupe, endpoint secret rotation, raw body signature verification, limited IP filters where documented.
- Abuse prevention: login throttling, quote abuse limits, user velocity limits, anomaly monitoring, anti-automation protection, safe error messages.
- Secrets from managed secret store, never commit secrets. Separate roles for DB migrator, service, admin and readonly operations.
- Encryption at rest and selective field encryption for sensitive beneficiary references; minimize data retention.
- Logging redaction: no passport/identity numbers, addresses, bank account full numbers, payment authorization payload or raw webhooks unless protected/redacted.
- OWASP ASVS and API Security Top 10 as testing guides; SAST, dependency scanning, secret scanning, DAST and penetration test before live launch.
- Data retention/deletion and subject access mechanisms must account for mandatory legal retention, audit and fraud evidence without purging required records.

## Threat scenarios and controls

| Threat | Engineering control |
|---|---|
| Duplicate transfer from retry | Idempotency key + request hash + unique DB constraint |
| Malicious webhook attempts to mark paid | Signed event verification + allowed transition + provider reconciliation |
| Overselling an FX offer | Transactional DB locks + balance/quantity invariants |
| Unauthorized user queries another user's transfer | JWT subject→customer mapping and row ownership |
| Stale FX rate | Quote expiry, provider binding and explicit reconfirmation |
| P2P off-platform settlement scam | No contact/bank details in listing, no direct settlement instructions, safety copy |
| Compromised operations user | MFA, least privilege, 4-eye approval for high-risk actions, full audit |
| Data leak via observability | Structured log redaction and integration tests |
| Unauthorized MAD→EUR transfer | Server-side hard gate by jurisdiction/direction/purpose and contractual permission |

## Sources

- https://www.bde.es/wbe/en/punto-informacion/contenidos/autorizaciones-acreditaciones/autorizacion-inicio-actividad/entidades-pago.html
- https://www.oc.gov.ma/fr/actualites/publication-de-l-instruction-generale-des-operations-de-change-2026
- https://www.oc.gov.ma/fr/actualites/communique-mise-au-point-au-sujet-du-transfert-d-argent-a-destination-de-l-etranger
- https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX:32023R1113
- https://www.eba.europa.eu/activities/single-rulebook/regulatory-activities/anti-money-laundering-and-countering-financing-terrorism/guidelines-information-requirements-relation-transfers-funds-and-certain-crypto-assets-transfers
- https://www.aepd.es/derechos-y-deberes/cumple-tus-deberes/medidas-de-cumplimiento/garantias-transferencias-datos-personales
