---
doc_type: test-record
version: 1.4.3-SNAPSHOT
related_workitems:
  - ../workitems/INT-001-synthetic-upstream-integration-harness.md
  - ../workitems/BUG-008-openapi-upstream-physical-langgraph-identity-context.md
scope: synthetic-disposable-local-only
status: READY_FOR_SIGNOFF
executed_at: 2026-07-21
---

# INT-001 / BUG-008 synthetic runtime 记录（2026-07-21）

## Result

fresh owned disposable exercise `int001-bug008-20260721-a1b2c3` 通过。它使用 synthetic upstream、隔离 MySQL/Launcher/Mock LLM/Biz fixture 和 run-owned carrier；没有访问真实 TMS/SIM、共享 `8112`/数据库、既有 Worker、真实 profile 或 `private/` 内容。

| Probe | Expected | Observed |
| --- | --- | --- |
| runtime token / readiness / owner-smoke | all pass | `PASS / PASS / PASS` |
| positive static no-tool ask | task and exactly one owned execution | `taskCreated=true`、Mock LLM submission `1`、Biz ingress `1` |
| runtime lane control mutation | deny before execution | task false, model/ingress `0` |
| runtime lane admin mutation | deny before execution | task false, model/ingress `0` |
| same-tenant other ClientApp | deny before execution | task false, model/ingress `0` |
| cross-tenant Agent | deny before execution | task false, model/ingress `0` |
| missing model grant | deny before execution | task false, model/ingress `0` |
| unavailable directory | deny before execution | task false, model/ingress `0` |
| ungranted upstream user | deny before execution | task false, model/ingress `0` |
| cleanup | owned resources gone / no retained carrier | `CLEANED`，`secretsRedacted=true` |

Root-level redacted runtime receipt reports `status=PASS` and `secretsRedacted=true`; it intentionally retains neither prompt/result nor credential/profile data.

## Focused verification

| Command / check | Result |
| --- | --- |
| `mvn -q -pl business-agent-module -am -Dtest=BusinessAgentTaskServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` | PASS |
| `mvn -q -pl addons/langgraph-biz-worker -am -Dtest=LanggraphBusinessAgentWorkerTaskLauncherTest,LanggraphWorkerServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` | PASS |
| `mvn -q -pl addons/claude-worker-agent -am -Dtest=OpenApiControllerMessageMappingTest#askAgent_doesNotForwardCallerUpstreamSystemIdIntoServerResolvedWorkerSelection -Dsurefire.failIfNoSpecifiedTests=false test` | PASS |
| `env -u INT001_SYNTHETIC_UPSTREAM_HARNESS -u INT001_RUNTIME_PROBE npm run test:synthetic` | PASS: 76 passed, 1 skipped |
| `npm run typecheck` (`business-agent-module/integration-tests`) | PASS |
| `PYTHONDONTWRITEBYTECODE=1 python3 -m unittest -v test_directory_facade test_biz_ingress_proxy` | PASS: 13 tests |
| three synthetic scripts `bash -n` | PASS |
| changed-surface secret scan | PASS: 29 files; no literal secret; compose variable interpolation was reviewed and excluded |
| `git diff --check` | PASS; only existing CRLF normalization warnings |

## Known non-scope reactor result

`mvn test -pl launcher -am` was re-run after the focused suites. It exits nonzero on the shared-tree, non-INT/BUG-008 `AuthorizationRouteManifestCoverageTest` drift:

```text
Missing registrations:
GET  /api/v1/tasks/{taskId}/termination-inspection
POST /api/v1/tasks/{taskId}/termination-retry
```

This record does not treat that failure as passing or alter its route catalog. The owner-context repair tests and fresh isolated runtime exercise above are the relevant evidence for this scope.

## Boundary confirmation

- `NAVIGATOR_EXTERNAL_ENABLED=true` appeared only inside the disposable target and only as the Open API route gate.
- `NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED=false` remained required throughout.
- BUG-008 uses server-derived ClientApp scope; caller metadata cannot supply the upstream scope or physical Worker selection.
- No ClientApp credential lane, Open API contract, schema, Worker Gateway, Codex route, WorkerPool/member or production setting was widened.
- This is not real SIM/TMS acceptance, Worker Gateway external readiness, Provider readiness or production readiness.
