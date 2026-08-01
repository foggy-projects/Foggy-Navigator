# ARCH-001-ACT-001 Independent Activation Signoff — 2026-08-01

## Verdict

`AUTHORIZED_FOR_ONE_BOUNDED_CANARY`

This verdict authorizes no action in this review. It is only a prerequisite for
one later, separately confirmed bounded canary against the exact sealed target.

- `activation_gate`: `CLOSED`
- actual canary/model submission in this review: `0`
- activation/proof/controller mutation in this review: `0`
- non-fixture `ENFORCED` aggregate created in this review: `0`
- target/Worker/MySQL start or restart in this review: `0`
- canonical work item state changed in this review: `no`; remains `READY_FOR_SIGNOFF`
- GOV-001-P3 / production boundary: `not approved`; remains `DRAFT/BLOCKED`

## Reviewer independence and boundary

- The reviewer was a fresh audit/signoff role and did not implement or repair the
  candidate. The verdict was not delegated to an implementer or sub-agent.
- The root and applicable module instructions, canonical activation and parent
  work items, fifth-remediation signoff, GOV-001-P3, runtime-provisioning and
  delivery-signoff skills/checklist/template, full dirty inventory, focused diff,
  manifests, seal, final evidence and retained failures were reviewed.
- The pre-replan signoff previously stored at this path was treated only as
  `SUPERSEDED_BY_2026-08-01_PRESTART_REPLAN`; none of its old target verdict or
  target identity was reused.
- Assurance is `elevated`. Authority, candidate/seal integrity, credential
  boundary, MySQL/data correctness and default-closed behavior are non-waivable.
- No `accounts/`, sibling repository, credential profile value, real business
  data, prompt body, model response or Task `20260730-0e01` was read.

## Candidate and sealed-target identity

Identity was recomputed immediately before this record was overwritten.

| Field | Expected | Independently observed | Result |
|---|---|---|---|
| Branch | `main` | `main` | PASS |
| Baseline HEAD | `fdef79c9c55e7de9a5b01822c3c9dc0c75ca2e00` | same | PASS |
| Digest schema | `ARCH001-ACT001-CANDIDATE-V1` | exact prescribed byte stream | PASS |
| Pre-write dirty SHA-256 | `31c72a1d7502134009b076f0b5b785aceb91dc06d1c4992b7ab9b35eff167d81` | same | PASS |
| Dirty inventory | 78 tracked / 45 untracked | 78 / 45 | PASS |
| Candidate state | `READY_FOR_SIGNOFF` | same | PASS |
| Target class / provider lane | `ISOLATED_LOCAL_NON_FIXTURE` / `REAL_CODEX_MODEL` | same | PASS |
| Exact model | `gpt-5.6-sol` | same | PASS |
| runId / targetId | `arch001-act001-provisioning-20260801-04` / `arch001-act001-target-provisioning-20260801-04` | same | PASS |
| Canonical manifest digest | `0e18bfd8813ccbd8e73a482053bdf6068a82cb82c1667280e1bc8be84fffab64` | independently recomputed with sorted compact JSON | PASS |
| Seal digest | `43c1cc3ceb3bf79311187dfdb2e2aec9de6777d4e3d764159d937706a789a7b0` | independently recomputed after removing `sealDigest` | PASS |
| Six-controller digest | `b3535c60445a7f94f9a826ca366f9b10eed5bb1677745ae0a48d133548fcd64a` | independently normalized/recomputed | PASS |

The pre-write SHA-256 of the superseded record at this path was
`74dabede95daabdaaed5899b69310cd8898953a41d2a8a2e70b0603f16d4a7da`.
After writeback, the only permitted candidate delta is this exact file; the
post-write verification below records that result. Any later code, test,
configuration, migration, manifest, seal, profile-path, exact tuple or other
evidence delta invalidates this authorization.

## ACT-AC1 through ACT-AC15 evidence matrix

Each row maps source authority to executed tests and raw evidence. Exit files
were checked against raw logs; retained failures are not reported as passes.

| AC | Classification | Source / contract reviewed | Test and raw evidence | Result |
|---|---|---|---|---|
| ACT-AC1 | core-blocker | closed defaults in `application.yml`; activation authority/admission gates | `05-java-authority-focused` 20/20; `10-session-affected-focused` 32/32; `19-activation-default-source-audit`; current source recheck shows control/admission defaults both `false` | PASS |
| ACT-AC2 | core-blocker | no-body `LifecycleActivationController`; constant-time exact-target `LifecycleActivationControlAuthorizer`; identity-only `ProductionAdmissionRequest` | authority tests in `05`; source inspection found no fixture/evidence/readiness/proof authority field on the production request | PASS |
| ACT-AC3 | core-blocker | manifest/controller canonicalization and `FileLifecycleActivationArtifactSource`; fixed process/supervisor/manual-launcher/CI/timer/Docker inventory | `12-activation-harness` 10/10; `31-stopped-resource-doctor-harness` 19/19; final six-controller digest independently matched | PASS |
| ACT-AC4 | core-blocker | DB-time generation/instance/proof service, observer and quarantine; frozen conflict precedence | `05`, `10`, exact `11-mysql-8.0.44`; current parent/fifth-remediation source evidence rechecked | PASS |
| ACT-AC5 | core-blocker | reservation plus `LifecycleProductionAdmissionService.admitAndAuthorizeProviderEffect`; W/S/T snapshots, fact, three proof refs and durable outbox precede lazy provider subscription | `05` production integration 14/14 within 20/20; `09-codex-java-focused` 202/202; duplicate-outbox rollback is an asserted negative, not an unexpected failure | PASS |
| ACT-AC6 | core-blocker | exact synthetic `codex-biz-worker` tuple, receipt, identity/generation/epoch/build/protocol/capability/binding checks | `04-node-focused` 44/44; `05`; `09`; cross-tuple, receipt-disabled, SHADOW/mode and stale-proof negatives | PASS |
| ACT-AC7 | core-blocker | proof expiry/loss, identity/controller drift, Worker unavailable and monotonic quarantine | `05`, `10`, `13-session-full` 512 total with one separately covered MySQL skip; fifth-remediation source signoff preserves `LEGACY_WRITER_EXCLUSIVITY_LOST > WORKER_STATE_LOSS > EVIDENCE_CONFLICT > NONE` | PASS |
| ACT-AC8 | core-blocker | `activation_target.py` doctor/cleanup ownership boundaries | `12` 10/10 and `31` 19/19 cover every protected port, shared/unowned resources, unknown controller, late relaunch, exact cleanup and stopped-resource rules; final `12-final-stopped-doctor` has `writesPerformed=0`; `13-final-cleanup-plan` has `execute=false` | PASS |
| ACT-AC9 | core-blocker | additive migrations/readiness and exact MySQL contract | `11-mysql-8.0.44`: exact image/database 8.0.44, 1/1 pass, forward/reapply/validate, proof refs/outbox, rollback floor and destroyed-target cleanup | PASS |
| ACT-AC10 | core-blocker | closed runbook/package, route authorization and compatibility boundary | final Node/Session/Codex/Launcher lanes `04`–`15b` all green; route catalogs independently match at 468 rows and SHA-256 `4a8671b...`; `18`–`22` hygiene green; gate remains CLOSED | PASS |
| ACT-AC11 | core-blocker | `schema-plan-v1.json`, four exact tracked SQL inputs, apply/reapply/validate tooling | `23` 17/17; `24` plan verification; `25` exact MySQL 8.0.44, 93 tables/1508 columns, fresh/reapply/health/validate pass; `26` contract 1 pass plus separately covered opt-in MySQL skip; all four file hashes and plan digest `04dd9964...` independently match | PASS |
| ACT-AC12 | core-blocker | closed production-API provisioning and server-generated identity recovery | `27` 18/18 including partial recovery/no duplicates; final `07-production-api-provisioning` records 5 production API calls, three server IDs, restricted model lane, direct DML/activation/provider/model counts all zero | PASS |
| ACT-AC13 | core-blocker | immutable generated-ID target seal and stopped-state drift rejection | sealed input digests independently match: schema `8ed220...`, provisioning `e0c3ab...`, Worker readiness `13e9d1...`; final `09` authenticated complete inventory/active tasks 0; `10` stop gives exact 1 container/1 network/1 volume, running 0; `11` seal, `12` doctor and `13` dry cleanup plan pass | PASS |
| ACT-AC14 | core-blocker | separate database/runtime/Worker/provider/control/Navigator lanes and bootstrap purge | metadata-only check shows all six retained profiles are regular mode `0600`; bootstrap profile is absent; `34-final-credential-redaction-audit` reports seven in-memory credential values and zero evidence occurrences | PASS |
| ACT-AC15 | core-blocker | replan retirement and post-replan identity/evidence package | `32-final-candidate-identity`, `33-final-closed-boundary-audit` and `34` all exit 0; `33` records listeners/processes 0, Docker 1/1/1 with running 0, switches false, and all provider/model/activation counts 0 | PASS |

## Evidence reuse and new verification

### Reused evidence

- `ARCH-001-independent-fifth-remediation-signoff-2026-07-31.md` is reused for
  the already accepted parent lifecycle-owner baseline: conflict precedence,
  successful-checkpoint monotonicity, public/receipt/SHADOW compatibility and
  Worker-v1 semantics. Current candidate source and affected raw logs still
  support those assumptions.
- Pre-replan final logs `04`–`22` are reused only for ACT-AC1～ACT-AC10. The
  old target/signoff/packet is not reused. Replan-sensitive schema,
  provisioning, seal, credential and stopped-target claims use `23`–`34` and
  final target `-04` only.
- GOV-001-P3 is reused only as a negative boundary: it grants no production or
  external authority.

### Newly checked by this reviewer

- prescribed pre-write branch/HEAD/inventory/candidate digest;
- canonical manifest, seal, controller and four sealed-input digests;
- all four schema SQL file hashes and the aggregate plan digest;
- six profile paths as regular mode-`0600` metadata and bootstrap absence,
  without reading profile values;
- complete exit inventory and representative raw commands/counts/assertions for
  every final lane and each named retained failure;
- current default-closed source, production request/control surface, 468-row
  route-catalog identity and `git diff --check`;
- post-write delta restricted to this record.

No additional test, live doctor, Docker/database query, target start or real
model run was performed: the sealed evidence is sufficient for elevated
signoff, and those operations would not improve the decision without crossing
the signoff boundary.

## Failed-iteration closure

| Retained iteration | Failure observed in raw evidence | Closing evidence / disposition |
|---|---|---|
| historical `05a` | expected generic manifest invalid, actual earlier fail-closed capability mismatch; 19/20 | final `05` is 20/20 and fixes the expectation to the stable, more specific pre-observation rejection |
| historical `12a/12b` | missing Python module path; later transient syntax error | final `12` is 10/10; replan `23/27/31` are 17/18/19 green and Worker-v1 source audit remains closed |
| historical `13a/13b` | duplicate/leaking activation test configuration | final `13` is 512 with zero failure/error and one exact-MySQL test separately run in `11` |
| historical `15a` | five activation routes absent from authorization catalog | `15b` is 14/14; final Launcher succeeds; both 468-row catalogs have identical bytes/hash |
| target `-02` schema count | shell quoting caused a false non-empty rejection | final `-02` apply/reapply progressed, and fresh `-04` `03/04` prove 0→93 and 93→93 on the sealed plan |
| target `-02` env/runtime | shell sourcing/JDBC URL handling lost the exact environment; relative artifact was rejected | in-process profile loading plus absolute artifact/target cwd passes in final `-02` and is repeated in fresh `-04` `06`; raw auth/profile failures remain retained |
| target `-02` model category | `CHAT` rejected; partial state existed | `09b` recovered exactly one existing user/Worker without duplication; final provisioning used `GENERAL`; `27` adds permanent recovery coverage |
| target `-02` destructive cleanup | `docker compose down --volumes` destroyed the provisioned DB | `-02` is invalid retained evidence only and six profiles were purged; it is not used for the verdict |
| target `-03` self-cwd | schema/doctor from target cwd correctly failed ownership proof | final commands run outside target root; the failure remains a fail-closed regression |
| target `-03` stopped Docker contract | sealed preflight misclassified exact stopped 1/1/1 resources as late relaunch | `31` adds the exact `running=false` contract; `-03` Docker/profiles were purged; fresh `-04` final doctor passes |
| target `-04` `01a` | port 13309 was occupied | fail-closed with zero writes; fresh target selected 13310 |
| target `-04` `01b` | doctor invoked from target cwd found an unknown owned process | fail-closed with zero writes; final `01`, `03`, `04`, `06`, `07`, `09`, `10`, `11`, `12`, `13`, and package `31`–`34` all close the affected lane |

No failure was rewritten as a pass, and neither invalid target `-02` nor `-03`
contributes final sealed-target authority.

## Finding classification

- `core-blocker`: none open; every non-waivable ACT-AC1～ACT-AC15 guard has
  sufficient current evidence.
- `scoped-risk`: the real one-shot execution is intentionally unrun; local
  evidence portability and retained exact-target credential paths remain
  bounded execution risks described below.
- `process-gap`: none affecting this authorization. The required later owner
  confirmation is a separate authority gate, not missing signoff evidence.
- `out-of-scope`: production/GOV-001-P3, external/dev-kvm promotion, live
  TMS/SIM, existing aggregate migration, other providers and release rollout.

## Findings, waivers and residual risks

### Blocking findings

None.

### Waivers

None. No security, authority, candidate/seal integrity, credential, data or
migration guard was waived.

### Residual risks

- The real model, controller mutation, proof mutation and first non-fixture
  aggregate have intentionally not run. This authorization permits at most one
  later bounded attempt after separate user confirmation; it does not predict
  that runtime execution will succeed.
- The six retained target-owned profiles remain credential material, not
  evidence. They must remain `0600`, must not be inspected or logged, and must
  be used only for the exact separately authorized target or purged.
- Evidence is local under gitignored `temp/`; loss of that directory prevents a
  later execution from relying on this authorization.
- MySQL proof applies only to the isolated exact 8.0.44 target. No conclusion is
  made about shared or production databases.

## Safety-boundary compliance

- Did not read `accounts/`, credential profile contents/values, TMS/SIM,
  sibling repositories, real business data or the historical real Task.
- Did not call a real model or incur model cost.
- Did not start/restart the target, probe the provider, write an observation,
  call activation mutation, acquire/renew/quarantine proof, or create a
  non-fixture `ENFORCED` aggregate.
- Did not stop/restart/upgrade/reuse an existing Navigator/Worker and did not
  mutate Docker or a database.
- Did not clean/reset/revert/checkout/switch, commit/push/tag/publish/release or
  deploy.
- Did not run a >30-minute authority/replay/rehearsal/full-chain.

## Post-write candidate identity

The final verification command was run after the last write to this record and
made no further filesystem changes. It confirmed:

- branch `main` and HEAD
  `fdef79c9c55e7de9a5b01822c3c9dc0c75ca2e00` are unchanged;
- tracked/untracked inventory remains 78/45;
- the full digest changed, as necessarily expected because this untracked
  signoff file is itself part of the digest input;
- the review's only write operation targeted this exact signoff path; no code,
  test, configuration, migration, manifest, seal, profile or other evidence
  path was written;
- the final verification re-ran `git diff --check` successfully.

The immutable activation authority remains the pre-write digest
`31c72a1d7502134009b076f0b5b785aceb91dc06d1c4992b7ab9b35eff167d81` plus
the single allowed signoff-record writeback described above. The post-write
full digest is audit metadata only and cannot replace that authority binding.

## Later execution preconditions

`activation_gate` remains `CLOSED`. Before any later bounded run, the user must
separately reconfirm all of the following as one exact execution packet:

1. runId `arch001-act001-provisioning-20260801-04` and targetId
   `arch001-act001-target-provisioning-20260801-04`;
2. the sealed generated user/Physical Worker/modelConfig tuple;
3. exact model `gpt-5.6-sol`;
4. all six target-owned profile paths without reading their values;
5. a new execution/cleanup window and explicit cost boundary;
6. `maximumSubmissions=1`, no retry;
7. unchanged candidate/seal/schema/controller identities and exact owned cleanup
   plan.

This verdict does not authorize a second attempt, production, GOV-001-P3,
external exposure, dev-kvm promotion, rollout, deployment or release.

## Signoff marker

- `acceptance_status`: `signed-off`
- `acceptance_decision`: `AUTHORIZED_FOR_ONE_BOUNDED_CANARY`
- `signed_off_by`: `independent-codex-reviewer`
- `signed_off_at`: `2026-08-01`
- `acceptance_record`: `docs/version-tracker/1.4.3-SNAPSHOT/evidence/ARCH-001-ACT-001-independent-activation-signoff-2026-08-01.md`
- `blocking_items`: `none`
- `follow_up_required`: `yes` — separate owner confirmation for the exact one-shot execution
