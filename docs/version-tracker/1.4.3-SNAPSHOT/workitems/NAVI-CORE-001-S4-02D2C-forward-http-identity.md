---
workitem: NAVI-CORE-001-S4-02D2C
status: IMPLEMENTED_REVIEWED
date: 2026-08-04
baseline: c4e142e6
prerequisite: NAVI-CORE-001-S4-02D2B1@c4e142e6
coordination_freeze: fd46f3f
canonical_contract: /home/sa/ultra/sim-navi/docs/decisions/NAVI-CORE-001-core-runtime-scope-and-complexity-convergence-delivery-spec.md
scope: FORWARD_HTTP_REQUEST_IDENTITY
---

# NAVI-CORE-001 S4-02D2C forward HTTP request identity

This slice exposes the already-reviewed D2B1 optional request-identity carrier on the existing
authenticated forward route. It does not create, validate or persist identity in the Controller.

## Exact scope

- Add optional `X-Navigator-Client-Request-Id` to the existing
  `POST /api/v1/session-relations/forward` method.
- Pass the raw nullable header, same body object, and `UserContext` owner/tenant synchronously to
  the existing four-argument service method.
- Keep the three-argument service API, GET incoming relation API, EXISTING_SESSION behavior,
  `@RequireAuth`, route, RX envelope and response shape unchanged.

## Identity boundary

- The Controller only distinguishes an absent header from an explicitly blank one and does not
  parse, trim, canonicalize or mint a UUID or branch on target mode. Missing identity remains null
  for server mint in the trusted factory.
- An explicitly blank non-null value is rejected with stable safe code
  `X_NAVIGATOR_CLIENT_REQUEST_ID_BLANK` before the service, because the legacy factory treats blank
  like absence. A malformed nonblank value remains unchanged for existing canonical validation.
- The header is not copied into the body, metadata, message, log or response and does not replace
  credential, owner, tenant, route or request-source authority.
- Missing headers do not promise cross-HTTP replay. A caller must intentionally reuse one canonical
  UUID to request replay semantics.

## Deliberate non-scope

- No service, factory, D0/D1/D2A2, DTO, entity, repository, schema, POM, SDK or CLI change.
- No new receipt, replay authority, retry, compensation, cleanup, repair, reconciliation or
  historical/existing-data mutation.

## Validation budget

1. Compile affected production sources with tests skipped.
2. Run only `SessionRelationControllerTest`.
3. Run one bounded seam of the two D2B1 service selectors for canonical ordering and preauth
   zero-effect plus four existing factory selectors for preauthorization, JWT fresh, API-key replay
   and invalid UUID behavior.
4. Obtain three independent read-only P1/P2 reviews of the exact three-path diff.

Do not run an entire class beyond the new Controller class, whole module/reactor, E2E/live Provider,
runtime or final joint full cycle. Final joint budget remains `0/3 consumed`.

## Implementation evidence

- Affected production compile: PASS (`17.184s`).
- The first Controller test invocation stopped at test compilation because the new fixture used a
  nonexistent no-argument `SessionRelationDTO` constructor; no test logic ran. Replacing that
  fixture with its existing builder required no production change.
- Final `SessionRelationControllerTest`: PASS (`5/5`, `26.429s`), covering exact canonical, null,
  blank and malformed header transport, same body and authenticated owner/tenant, EXISTING_SESSION,
  GET and the frozen authenticated route/header annotations.
- Bounded existing seam: PASS (`6/6`, `20.073s`): two D2B1 service order/zero-effect selectors and
  four trusted-factory preauthorization/JWT-fresh/API-key-replay/invalid-UUID selectors.
- Three independent final read-only reviews accepted the corrected exact staged diff with no
  remaining P1/P2 across authorization/identity, compatibility/RX and pipeline/effect boundaries.
- Initial independent review found that the legacy factory mints for blank as well as null, making
  the first raw-blank passthrough contract false. Coordination freeze `fd46f3f` now permits only a
  null-vs-blank presence fence in this Controller path. The corrected Controller class passed
  again (`5/5`, `30.513s`, including production and test recompilation); the downstream six-selector
  seam was not repeated because no service/factory path changed. Final re-review accepted the
  exact three-path candidate.
- No service, Worker or runtime was started and no business, runtime, historical or existing data
  was read or mutated.
- Whole module/reactor, E2E/live Provider, runtime and final joint full validation were not run;
  final joint budget remains `0/3 consumed`.

## Stop conditions

Stop and replan if this needs a fourth path, Controller behavior beyond the exact null-vs-blank
presence fence, UUID parsing/minting, metadata/body/query
identity, asynchronous thread switching, service/factory/DTO/schema/SDK changes, a second replay
authority, a route/auth/RX/response change, full/live validation or historical/existing-data
mutation.
