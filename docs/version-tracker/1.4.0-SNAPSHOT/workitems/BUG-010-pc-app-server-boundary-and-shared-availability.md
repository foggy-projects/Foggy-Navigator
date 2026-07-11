---
type: bug
bug_source: acceptance-found
version: 1.4.0-SNAPSHOT
ticket: BUG-010
severity: major
status: closed-isolated
reproduction_status: confirmed
test_strategy: unit-and-integration
automation_decision: required
owner: codex-worker-agent | navigator-frontend
---

# PC App-server Boundary And Shared Availability

## Background

The PC process panel treated an app-server-managed physical Worker like a legacy CLI Worker and continued probing local Codex processes. Ultra readiness also depended on the owner-only detailed runtime list, so a tenant-granted user could use the Worker but could not obtain the minimum readiness decision. Review additionally found that `ALL_CANARY` at 0% still targets Ultra by contract, while the old frontend calculation treated it as unavailable.

## Correctness Contract

- `appServerManaged=true` disables all legacy Codex process probes for that physical Worker; runtime pool state remains owned by the app-server Worker.
- `GET /api/v1/codex-runtimes/availability` first calls `validateWorkerAccess(userId, tenantId, workerId)` and returns only `appServerManaged`, `ultraAvailable` and `blockReason`.
- Detailed runtime list, endpoint, revision, manifest and management operations remain owner-only.
- Ultra availability requires an enabled, READY, fresh APP_SERVER runtime with Ultra capability. `ULTRA_CANARY` requires rollout greater than zero; `ULTRA_DEFAULT`, `ALL_CANARY` including 0%, and `ALL_DEFAULT` make Ultra available.
- Late availability responses from a previously selected Worker cannot overwrite current Worker state.

## Code Inventory

- `addons/codex-worker-agent/.../CodexRuntimeAvailabilityDTO.java`
- `addons/codex-worker-agent/.../CodexRuntimeController.java`
- `addons/codex-worker-agent/.../CodexRuntimeRegistryService.java`
- corresponding Java controller/service tests
- `packages/navigator-frontend/src/api/codexRuntime.ts`
- `packages/navigator-frontend/src/types/codexRuntime.ts`
- `packages/navigator-frontend/src/utils/codexRuntime.ts`
- `packages/navigator-frontend/src/views/ClaudeWorkerView.vue`
- corresponding frontend tests

## Fix Checklist

- [x] Add the minimum-disclosure aggregate availability API.
- [x] Permit an accessible shared Worker without granting runtime ownership.
- [x] Keep detailed runtime list and management owner-only.
- [x] Align `ALL_CANARY@0` Ultra availability with router semantics.
- [x] Skip every legacy process probe for an app-server-managed Worker.
- [x] Preserve legacy probe fallback when no runtime is registered or discovery fails.
- [x] Guard against stale cross-Worker availability responses.
- [x] Java focused coverage and Codex addon `259/259` passed; raw full reactor is blocked by Windows Surefire fork/path infrastructure while affected scoped tests pass.
- [x] Frontend full `179/179`, `vue-tsc` and production build passed.
- [x] Complete shared-user contract and owner PC isolated live verification.

## Verification

- Java tests cover owner/shared access, denial before registry query, minimum DTO fields and owner-only detailed list.
- Routing tests cover DARK, DRAINING, ULTRA_CANARY 0/1, ULTRA_DEFAULT, ALL_CANARY 0 and ALL_DEFAULT.
- Frontend tests cover pool-managed empty state, legacy fallback, Ultra preflight and stale response suppression.
- Java coverage proves shared-user minimum disclosure and owner-only detail/management boundaries. The isolated live aggregate API returned `appServerManaged=true`, `ultraAvailable=true` and no block reason for `codex-ultra` without exposing runtime detail.
- PC Playwright displayed the managed-pool empty process state, `Codex Ultra 可用`, rev1 `Dark` disabled and rev2 `Ultra 默认` enabled/READY. The final task opened by stable session identity, showed native progress `1/1`, and survived refresh without duplicate result messages. No console, HTTP, application or UI errors were recorded.
- This closes the isolated PC/API defect; no production routing is approved because P3 remains unstarted.

## References

- [OPT-001 progress](./OPT-001-independent-codex-app-server-worker-progress.md#acceptance-defect-closure)
