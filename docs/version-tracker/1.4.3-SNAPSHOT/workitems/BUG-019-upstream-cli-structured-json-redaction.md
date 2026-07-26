---
doc_type: delivery-spec
delivery_type: bug
version: 1.4.3-SNAPSHOT
ticket: BUG-019
status: READY_FOR_SIGNOFF
canonical: true
execution_mode: ultra
assurance_level: elevated
bug_source: user-report
approved_by: project-owner-explicit-implementation-request
approved_at: 2026-07-26
open_questions: []
---

# Delivery Spec: Upstream CLI structured JSON redaction

## Document Purpose

- intended_for: ultra-implementation / independent-signoff / TMS revalidation handoff
- purpose: 修复 Navigator upstream CLI JSON 脱敏破坏 JSON 语法的问题，并生成 source-matched、可复用的新版本 CLI 制品。
- canonical_path: `docs/version-tracker/1.4.3-SNAPSHOT/workitems/BUG-019-upstream-cli-structured-json-redaction.md`

## Goal

- version_goal: 恢复 runtime read-only audit/readiness 输出作为可信机器可解析 artifact 的能力。
- target_outcome: 任意 JSON 类型的敏感字段值都被安全替换，同时完整输出结构、boolean side-effect assertions 和只读 Server contract 保持不变。
- critical_outcomes:
  - `runtime task-audit --json` 与 `runtime task-completion-readiness --json` 的 stdout 可被标准 JSON parser 直接解析。
  - 敏感字段覆盖范围不缩小，原值不泄露。
  - 十四项 side-effect fields 保留且仍为 boolean。
  - 新 CLI provenance 为 clean Git commit，且兼容 Server contract commit `9a4bbd7a08a5398661d91bd45c7bfadd0c6581b3`。
- success_is_sufficient_when: focused/affected tests、package、离线制品 smoke 和 provenance/integrity 检查通过；不需要 live ASK 或共享服务操作。

## Scope

- in_scope:
  - CLI JSON 输出的结构化字段和值脱敏。
  - CLI stdout JSON purity、runtime read-only command parser regression tests。
  - CLI version/provenance、双平台 package metadata 和本地可复用 artifact。
- affected_modules:
  - `navigator-open-sdk`
  - `tools/navigator-upstream-cli/dist`
  - `docs/version-tracker/1.4.3-SNAPSHOT`
- external_dependencies: none; 使用本地 HTTP fixture 和离线 package/install 验证。

## Non-Goals

- out_of_scope:
  - Server endpoint、authoritative completion evidence、ownership 或 Physical Worker 校验语义变更。
  - token issuance、ASK/dispatch、retry、resume、recovery、termination、reconciliation、redispatch、finalize 或 provisioning mutation。
  - 共享服务/Worker 的停止、重启、重建、升级或配置修改。
  - TMS、SIM 或其他 sibling workspace 修改。
  - OBS 上传或更新 remote `latest.json`。
- do_not_touch:
  - 既有 dirty worktree paths。
  - `.navigator/`、profile、credential 与共享运行配置。
- non_blocking_or_waivable_items:
  - Windows native execution 可由静态 package 校验和 archive inspection 覆盖；不豁免 Windows archive 生成与 checksum。

## Confirmed Decisions

| Decision | Rationale | Compatibility / Constraint |
|---|---|---|
| JSON 先转为 tree，再递归脱敏，最后序列化 | 避免对 JSON literal 做裸文本替换 | 普通文本输出继续使用既有内容脱敏规则 |
| 敏感字段整值统一替换为 JSON string `"[REDACTED]"` | 与现有 CLI 占位符一致且适用于所有原始 JSON 类型 | 不删除字段，不缩小敏感字段识别范围 |
| 已知敏感值替换只作用于 JSON string node | boolean/number/null 的词法碰撞不再破坏类型或语法 | string node 内仍不得泄露已知敏感值 |
| CLI 版本从 `1.0.34` 单调递增 | 不覆盖既有 provenance | source/published/package version 必须一致 |
| 只生成 clean local official artifact，不发布远端 pointer | 当前请求要求可复用制品但禁止共享配置操作 | TMS 使用明确 artifact path 和 checksum 重新验证 |

## Acceptance Criteria

- [x] AC-1: 敏感字段的 string、true、false、number、null、array 和 object 值统一输出为 `"[REDACTED]"`。
- [x] AC-2: 多个敏感/普通字段混合、嵌套敏感字段和已为 `[REDACTED]` 的输入保持合法、完整 JSON。
- [x] AC-3: 与已知敏感值词法相同的 boolean/number/null primitive 不被裸文本替换；普通 primitive 类型保持不变。
- [x] AC-4: `runtime task-audit --json` 的完整 stdout 可解析，且没有前缀、ANSI 或诊断混入。
- [x] AC-5: `runtime task-completion-readiness --json` 的完整 stdout 可解析，且没有前缀、ANSI 或诊断混入。
- [x] AC-6: 两个 runtime JSON regression fixtures 的十四项 side-effect fields 全部存在且为 boolean。
- [x] AC-7: synthetic secret sentinel 不出现在 stdout、stderr 或输出 snapshot。
- [x] AC-8: 两个 runtime 查询仍各自只发出一个 GET，不新增任何 mutation、token issuance 或 dispatch。
- [x] AC-9: owning module tests、CLI tests、affected build、package 和离线 artifact smoke 通过。
- [x] AC-10: 新 CLI provenance 记录 version、commit、branch、gitDirty、checksum 和相对 artifact location；`gitDirty=false`。
- [x] AC-11: Server commit compatibility review 证明无需 Server 改动或重启。
- [x] AC-12: 提交只包含 BUG-019 owning code、tests、release metadata 和本 canonical work item。

## Contract / Data / Security Constraints

- API or event contract: 不修改 runtime endpoint、query parameters、response DTO 或 read-only HTTP method。
- data and migration: 无数据库、migration 或 provisioning change。
- compatibility and rollback: 可继续使用 CLI `1.0.34` 的 Server contract；回滚仅是调用方继续使用旧 artifact，但旧 JSON redaction 缺陷仍存在。
- permissions and secrets: 不读取真实 profile/secret；测试只使用 synthetic sentinel，console/evidence 不记录原始 HTTP body 或 credential material。

## Test and Evidence Obligations

| Item | Classification | Risk | Required Validation | Required Evidence |
|---|---|---|---|---|
| JSON tree redaction shapes | must-pass | critical | focused `SecretMaskerTest` | test count/result |
| runtime task-audit JSON | must-pass | critical | loopback CLI fixture + standard parser | GET count, parser/type assertions |
| completion-readiness JSON | must-pass | critical | loopback CLI fixture + standard parser | GET count, parser/type assertions |
| secret/stream purity | must-pass | critical | stdout/stderr sentinel assertions | clean test result |
| owning module | must-pass | major | full `navigator-open-sdk` tests/package | Maven result |
| official artifact | must-pass | critical | clean worktree package, checksum, offline install/version/manifest inspection | provenance and SHA-256 |

## Validation Budget and Evidence Sufficiency

- assurance_level: elevated
- lightweight_validation: focused red/green tests、source diff、`git diff --check`，单次 `<5m`。
- medium_validation: full owning module tests、package、离线 install/archive inspection，单次 `5-30m`。
- expensive_validation: none。
- large_authority_or_replay_policy: prohibited-unless-user-approved。
- full_chain_recommendation_trigger: none。
- user_approval_status: not-requested。
- decision_if_not_approved: proceed-with-focused-and-affected-validation。
- maximum_expensive_attempts: 0。
- stop_when_evidence_is_sufficient: AC-1 至 AC-12 均有自动化或静态证据，clean artifact provenance/integrity 固定。
- validation_not_required: live Server/Worker/TMS/SIM runtime、ASK、deployment、OBS upload。

## Waiver Policy

- waivable_items: Windows native execution。
- authorized_role: independent signoff owner。
- non_waivable_guards: JSON validity、secret non-disclosure、十四项 boolean fields、read-only semantics、clean provenance、no shared runtime mutation。
- required_risk_record: Windows wrapper 未原生执行时记录静态覆盖范围。

## Bug Context

- bug_source: user-report
- severity: major acceptance blocker
- environment: Server/CLI contract commit `9a4bbd7a08a5398661d91bd45c7bfadd0c6581b3`，CLI `1.0.34`。
- current_behavior: JSON 先序列化为 text，再对 text 做敏感值替换；当敏感值与 JSON primitive literal 相同，stdout 可能包含未加引号的 `[REDACTED]` 并无法解析。
- expected_behavior: 脱敏后输出始终是合法 JSON，敏感字段不泄露，普通 primitive 与 side-effect boolean 类型不变。
- reproduction_steps: loopback runtime fixture 使用 synthetic literal-shaped runtime secret，再执行两个 `--json` read-only command 并解析完整 stdout。
- reproduction_status: confirmed
- existing_evidence: TMS 报告唯一 task-audit artifact 与早期 readiness output 因 JSON 损坏不可采信；不要求保留或重取原始 response。
- existing_tests: CLI 1.0.34 只检查文本片段，未解析完整 stdout，也未覆盖 literal-shaped sensitive values。
- regression_protection: required
- waiver_reason_and_risk: N/A

## Risks and Open Questions

- known_risks:
  - 字段名识别必须避免把 `accessTokenIssued`、`runtimeTokenIssued`、`taskTokenIssued` 等审计断言误判为 secret 值。
  - 仅完成本地 clean official artifact，不更新远端分发 pointer；TMS 必须使用明确制品而非未更新的 `latest.json`。
- open_questions: none

## Ultra Execution Contract

- 先读取本文件、项目 `AGENTS.md` 和 release scripts。
- 保留所有非 BUG-019 dirty paths，不读取 `.navigator/` 内容。
- 在 scope 内自主实现类型安全 redaction 和 focused tests。
- 不运行任何 live mutation、共享服务操作或 sibling workspace 操作。
- 如需改变 Server contract、authorization/ownership 或 provisioning boundary，设置 `NEEDS_REPLAN` 并停止扩展。
- 完成后填写 `Implementation Result` 并设置 `READY_FOR_SIGNOFF`；不得自行设置 `ACCEPTED`。

## Implementation Result

- implementation_summary:
  - root cause 为 `UpstreamCli#printJson` 在 Jackson 序列化后对整段 JSON text 调用 known-secret raw replacement；当 secret 值为 `false`、`true`、number 或 `null` 的 lexical form 时，JSON primitive 会被替换成未加引号的 `[REDACTED]`。
  - structured JSON redaction 的唯一 owning implementation 固定在 `SecretMasker`：先转 `JsonNode`，按字段名递归脱敏，再由 Jackson 序列化；普通 string node 继续应用原有 known-secret/content redactor。
  - 命中敏感字段的整个值不论原类型均统一替换为 JSON string `"[REDACTED]"`；字段不删除，普通 boolean/number/null 保持原类型。
  - `runtime task-audit` 与 `runtime task-completion-readiness` 的 endpoint、GET method、query、DTO 和 Server authoritative evidence/ownership/Physical Worker 语义均未修改。
- changed_paths:
  - `navigator-open-sdk/pom.xml`
  - `navigator-open-sdk/src/main/java/com/foggy/navigator/sdk/cli/SecretMasker.java`
  - `navigator-open-sdk/src/main/java/com/foggy/navigator/sdk/cli/UpstreamCli.java`
  - `navigator-open-sdk/src/main/resources/com/foggy/navigator/sdk/cli/authorization-provenance.properties`
  - `navigator-open-sdk/src/test/java/com/foggy/navigator/sdk/cli/SecretMaskerTest.java`
  - `navigator-open-sdk/src/test/java/com/foggy/navigator/sdk/cli/UpstreamCliTest.java`
  - `tools/navigator-upstream-cli/dist/package.ps1`
  - `tools/navigator-upstream-cli/dist/package.sh`
  - `docs/version-tracker/1.4.3-SNAPSHOT/workitems/BUG-019-upstream-cli-structured-json-redaction.md`
- tests_and_results:
  - pre-fix focused reproduction: 2 tests run，1 failure；synthetic secret `false` 破坏 `recoveryTriggered:false`，确认问题。
  - focused redaction/runtime CLI tests: PASS。
  - `mvn -q -pl navigator-open-sdk test`: PASS，197 tests，0 failures，0 errors，0 skipped。
  - `mvn -q -pl navigator-open-sdk package`: PASS。
  - `git diff --check` 与 `bash -n tools/navigator-upstream-cli/dist/package.sh`: PASS。
  - baseline contract comparison: Server/API runtime contract paths 与 `9a4bbd7a08a5398661d91bd45c7bfadd0c6581b3` 无差异。
- manual_or_experience_evidence:
  - clean local package: PASS；Linux 与 Windows archive 均生成。
  - checksum verification: PASS。
  - Linux wrapper `navi version`: PASS。
  - Linux runtime help 同时包含 `task-audit` 与 `task-completion-readiness`: PASS。
  - Linux/Windows archive 内 `BUILD_INFO.json` version/commit/branch/gitDirty/feature 一致: PASS。
- artifact_provenance:
  - version: `1.0.35`
  - build_id: `1.0.35+c1c44f64f229`
  - git_commit: `c1c44f64f22923ccb5ad97f086c649cdac5688fa`
  - git_branch: `main`
  - git_dirty: `false`
  - linux_artifact: `tools/navigator-upstream-cli/dist/output/navigator-upstream-cli-1.0.35-linux.tar.gz`
  - linux_sha256: `073b345c2b61386419b0baea3c416d4cf5d67597e94ee3b7f0aae9cac9594c72`
  - windows_artifact: `tools/navigator-upstream-cli/dist/output/navigator-upstream-cli-1.0.35-windows.zip`
  - windows_sha256: `0d8918f00a500a0f145bbd99119d00490861e165d8bd4bcfbc050babcfb23e2c`
  - metadata: `tools/navigator-upstream-cli/dist/output/BUILD_INFO.json`
  - release_manifest: `tools/navigator-upstream-cli/dist/output/RELEASE_MANIFEST.json`
- deviations: none
- residual_risks:
  - 当前 Linux 环境没有 `pwsh`，未原生执行 Windows wrapper；Windows archive 已由官方 package script 生成并通过 checksum、archive BUILD_INFO 和文件结构检查。
  - 未更新远端下载 pointer 或 OBS；TMS 必须显式安装上述 1.0.35 制品，不能继续使用缓存的 1.0.34。
  - 未执行 live ASK 或共享运行态检查；TMS 仍需在其受控验证窗口使用新 CLI 执行一次 clean live ASK，再运行两个只读 JSON 查询形成新 evidence。
- reused_evidence: Server/CLI contract baseline commit `9a4bbd7a08a5398661d91bd45c7bfadd0c6581b3`。
- omitted_validation_and_reason: live runtime/deployment/OBS upload prohibited by scope。
- readiness: READY_FOR_SIGNOFF

## TMS Revalidation Commands

安装新 artifact 后先核对：

```bash
navi version
```

预期 provenance：version `1.0.35`、commit `c1c44f64f22923ccb5ad97f086c649cdac5688fa`、branch `main`、gitDirty `false`。

在 TMS 自有受控 lane 完成一次 clean live ASK 后，只读获取新 evidence：

```bash
navi upstream runtime task-audit \
  --upstream-user-id <upstream-user-id> \
  --task-id <task-id> \
  --json \
  | jq -e .

navi upstream runtime task-completion-readiness \
  --upstream-user-id <upstream-user-id> \
  --task-id <task-id> \
  --expected-physical-worker-id <physical-worker-id> \
  --json \
  | jq -e .
```

两个命令均不得复用旧的损坏输出；新 stdout 应作为完整 JSON artifact 保存，并逐项核对十四项 side-effect fields 均存在且为 boolean。

## References

- Server/CLI contract baseline: `9a4bbd7a08a5398661d91bd45c7bfadd0c6581b3`
- related read-only audit: `FEAT-001-runtime-binding-task-read-only-audit.md`
- related completion readiness: `FEAT-003-runtime-task-completion-readiness.md`
