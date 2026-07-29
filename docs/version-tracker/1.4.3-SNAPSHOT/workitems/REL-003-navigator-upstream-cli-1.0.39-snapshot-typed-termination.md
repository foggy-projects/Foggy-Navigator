---
doc_type: delivery-spec
delivery_type: cross-module
version: 1.4.3-SNAPSHOT
ticket: REL-003
status: ULTRA_EXECUTING
canonical: true
execution_mode: ultra
assurance_level: elevated
approved_by: project-owner-user-confirmed
approved_at: 2026-07-30
open_questions: []
---

# Delivery Spec: Navigator Upstream CLI 1.0.39-SNAPSHOT typed termination 发布

## Document Purpose

- intended_for: release implementation / independent-signoff / SIM operator
- purpose: 将 BUG-035 已验证的 typed termination/reconciliation SDK 契约发布到既有 OBS CLI 渠道，部署当前开发环境，并形成 SIM 可执行的受控联调交接。
- canonical_path: `docs/version-tracker/1.4.3-SNAPSHOT/workitems/REL-003-navigator-upstream-cli-1.0.39-snapshot-typed-termination.md`

## Goal

- version_goal: 让 SIM 从可复核的 OBS CLI 制品或本机 Maven snapshot 使用 BUG-035 typed contract，并连接当前 Navigator 开发实例验证。
- target_outcome: `origin/main` 包含已签收实现；CLI `1.0.39-SNAPSHOT` 的双平台归档与 installer 发布到既有 OBS 根；当前 8112 实例从该提交重建、重启并健康；提供不触碰 SIM 仓库和运行资源的联调提示词。
- critical_outcomes:
  - 不复用任何已有 SDK/CLI 版本承载不同字节；发布 provenance 为 clean、source-matched。
  - Linux/Windows CLI 归档内嵌同一 `navigator-open-sdk:1.0.39-SNAPSHOT`，并暴露 typed termination/reconciliation feature metadata。
  - OBS `latest.json`、双平台 SHA-256、git commit 与实际归档一致，远程 Linux installer smoke 通过。
  - 只重启经 PID、命令行和 cwd 确认为当前工作区的 8112 Navigator；不重启 Worker。
  - SIM handoff 仅允许使用既有资源执行 SIM-owned disposable task 的受控验证，不访问 TMS，不 provision 或改绑 Worker/model/Directory。
- success_is_sufficient_when: BUG-035 正式签收、focused/affected tests、SDK binary/sources install、CLI package/offline smoke、OBS upload/remote verification、main push、8112 health/provenance 和 SIM handoff 全部有可复核证据。

## Scope

- in_scope:
  - BUG-035 正式签收记录和版本索引回写。
  - SDK release-only 版本递增至 `1.0.39-SNAPSHOT`，CLI provenance 与发布 feature metadata 对齐。
  - 修复 Linux CLI packager 对 snapshot POM 的版本解析，避免误读依赖版本。
  - 构建/安装 SDK binary 与 sources，构建双平台 CLI，推送 `main`，发布既有 OBS CLI channel。
  - 重启当前工作区 8112 launcher 并验证健康与源提交。
  - 输出 SIM 联调提示词。
- affected_modules:
  - `navigator-open-sdk`
  - `tools/navigator-upstream-cli/dist`
  - `tools/navigator-chat-observer-bff`
  - `launcher`（仅构建、重启和健康验证）
  - `docs/version-tracker/1.4.3-SNAPSHOT`
- external_dependencies:
  - `origin/main`
  - 既有 Huawei OBS CLI bucket/base URL 与本机 gitignored 配置
  - 当前工作区 8112 MySQL-backed development launcher

## Non-Goals

- out_of_scope:
  - tag、GitHub/GitLab release、生产 Maven repository 发布或 production promotion。
  - 修改 `foggy-world-sim`、`tms-x3` 或其他 sibling workspace。
  - 读取 SIM/TMS profile 或 credential；代替 SIM 发起 live termination。
  - 创建、修改、重启、升级或 provision Worker、model、Directory、Agent、grant、credential 或其他运行资源。
  - production readiness、Gateway/external network enablement 或真实业务验收。
- do_not_touch:
  - `/home/sa/workspace/foggy-world-sim`
  - `/home/sa/workspace/tms-x3`
  - 任意 Worker 安装目录、进程、配置和凭据。
- non_blocking_or_waivable_items:
  - Windows native wrapper execution 可由双平台 package structure、metadata、checksum 与 Linux remote installer smoke 覆盖；不得豁免 Windows archive 生成和远端 SHA 校验。

## Confirmed Decisions

| Decision | Rationale | Compatibility / Constraint |
|---|---|---|
| CLI 发布版本为 `1.0.39-SNAPSHOT` | `1.0.38-SNAPSHOT` 已安装过不同字节；release metadata 和 packager 修复会改变 SDK/CLI 制品 | 不覆盖远端 `1.0.34` 或本地未发布 `1.0.35`；后续变更继续递增版本 |
| snapshot 字符串作为 CLI VERSION 与内嵌 SDK JAR 的精确版本 | installer 清理和 wrapper 优选 JAR 依赖精确文件名 | Linux/PowerShell packager 必须解析同一 POM project version |
| 发布到既有 official CLI OBS 根 | 用户明确授权 CLI 发布，SIM 需要统一安装入口 | 只更新 CLI archive/installers/latest pointer；不发布 tag 或 Maven release |
| 当前 8112 只在 ownership 核验后原地重建重启 | SIM 需连接当前开发环境 | 不操作任何 Worker；健康不等于 production ready |
| response-loss 测试只查询原 request ID | typed reconciliation 必须保持只读与 fail-closed | `ACCEPTED` 不等于 terminal；`AMBIGUOUS|UNKNOWN` 禁止自动重发 |

## Acceptance Criteria

- [ ] AC-1: BUG-035 elevated signoff 有完整 AC/evidence matrix 且结论允许交付。
- [ ] AC-2: SDK/observer 版本为唯一 `1.0.39-SNAPSHOT`；binary/sources 本机安装，`javap` typed 签名与 SHA-256 可复核。
- [ ] AC-3: Linux packager 从 project POM 精确解析 `1.0.39-SNAPSHOT`，不再误读 SLF4J 或其他依赖版本；shell syntax 与实际 package 通过。
- [ ] AC-4: CLI 双平台归档的 VERSION、内嵌 SDK JAR、BUILD_INFO、feature metadata、commit 和 `gitDirty=false` 一致；离线 Linux install/version/help smoke 通过。
- [ ] AC-5: `main` 以非 force 方式同步到 `origin/main`，发布候选 commit 可从远端解析。
- [ ] AC-6: OBS archive、`latest.json`、`install.ps1`、`install.sh` 上传成功；远端 manifest、下载 SHA 和 Linux installer smoke 与本地候选一致。
- [ ] AC-7: 当前 8112 listener 的命令行/cwd 属于本工作区；重建重启后 health `UP`，运行 provenance 对应发布后的 main commit。
- [ ] AC-8: SIM handoff 明确目标 URL、SDK/CLI 版本与 SHA、credential lane、typed API、请求 ID 幂等/只读 reconciliation、禁止重发条件和 do-not-provision 边界。

## Contract / Data / Security Constraints

- API or event contract: endpoint/header/DTO 语义保持 BUG-035 已签收契约；本发布不新增 endpoint。
- data and migration: 无 schema migration；重启复用现有数据库。termination receipt 开关默认开启、保留 7 天、默认每日 02:00 清理。
- compatibility and rollback: deprecated Map API 与 legacy repair 保留；CLI 回滚通过完整旧 release pointer，不覆盖 archive；服务回滚使用上一 clean main artifact。
- permissions and secrets: `.env`、obsutil config、SIM profile、token/key/secret 不进入 Git、console 或 evidence；只记录配置位置与可用性。

## Test and Evidence Obligations

| Item | Classification | Risk | Required Validation | Reusable Evidence | Required Evidence |
|---|---|---|---|---|---|
| AC-1 | must-pass | critical | BUG-035 diff/evidence audit | focused/affected Maven results | signoff record |
| AC-2/3 | must-pass | major | SDK tests/install/sources/javap；shell syntax与版本解析 | BUG-035 typed tests | command results、artifact paths/SHA |
| AC-4 | must-pass | critical | clean package、archive inspection、offline Linux install | existing release scripts | BUILD_INFO、VERSION、features、SHA |
| AC-5 | must-pass | major | remote main fast-forward check/push | none | commit and push output |
| AC-6 | must-pass | critical | official upload script、remote manifest/download/installer smoke | existing OBS config | remote metadata and hashes |
| AC-7 | must-pass | major | listener ownership、launcher build/restart、health/info | current 8112 baseline | PID/cwd/health/provenance |
| AC-8 | must-pass | major | prompt boundary review | navigator runtime provisioning rules | final handoff text |

## Validation Budget and Evidence Sufficiency

- assurance_level: elevated
- lightweight_validation: diff/checklist、shell syntax、POM/provenance/manifest inspection，单次 `<5m`。
- medium_validation: focused SDK tests/install、CLI package/offline install、launcher build/restart、OBS upload/smoke，单次 `5-30m`。
- expensive_validation: none planned。
- large_authority_or_replay_policy: prohibited-unless-user-approved
- full_chain_recommendation_trigger: none；SIM live runtime 由下游在自己的受控 lane 执行。
- estimated_full_chain_wall_clock: not-estimated
- full_chain_prerequisites: none
- user_approval_status: not-requested
- decision_if_not_approved: proceed-with-focused-and-affected-validation
- expensive_validation_trigger: none
- maximum_expensive_attempts: 0
- reusable_evidence: BUG-035 typed contract focused/affected test，仅 release-only version/provenance/tooling 变化不使服务语义测试失效。
- stop_when_evidence_is_sufficient: AC-1 至 AC-8 均有实际证据，OBS/remote main/8112 状态可回读，worktree clean。
- validation_not_required: live SIM/TMS task、Worker restart/provision、frontend/Playwright、production soak、tag/release。

## Waiver Policy

- waivable_items: Windows native wrapper execution only。
- authorized_role: project owner。
- non_waivable_guards: typed API identity、accepted-not-terminal、no second effective dispatch when receipt enabled、read-only reconciliation、artifact version/hash/provenance、secret non-disclosure、sibling/Worker no-touch。
- required_risk_record: Windows native execution未运行时记录静态/归档覆盖范围。

## Risks and Open Questions

- known_risks:
  - official CLI `latest.json` 将指向 development snapshot；仅用于当前开发联调，不构成 production promotion。
  - 当前 8112 仅 loopback 监听，SIM 必须与其位于同一主机/WSL 网络域，或由 owner 单独授权网络暴露。
  - receipt disabled 或 receipt 已过期时，Navigator 无法证明丢失响应的原请求结果；调用方必须 fail closed。
- open_questions: none

## Ultra Execution Contract

- 先读取本文件、项目 `AGENTS.md`、BUG-035、CLI release scripts、`foggy-delivery-signoff`、`obs-upload` 与 `navigator-runtime-provisioning`。
- 在 scope 内自主决定局部 release-tooling 修复和证据组织。
- 如需改变 endpoint、credential/ownership、安全边界、OBS 根、生产开关或 sibling/Worker 资源，设置 `NEEDS_REPLAN` 并停止扩展。
- 按 `focused -> artifact -> push -> upload -> deploy/health` 顺序运行实际验证，不得声称未运行检查通过。
- 不运行 live SIM/TMS termination，不启动大型 authority/replay/full-chain。
- 完成后填写 `Implementation Result` 并设置 `READY_FOR_SIGNOFF`；不得自行设置 `ACCEPTED`。

## Implementation Result

- implementation_summary:
- changed_paths:
- tests_and_results:
- manual_or_experience_evidence:
- deviations: none
- residual_risks:
- reused_evidence:
- omitted_validation_and_reason:
- readiness:

## References

- requirement / issue: project owner request on 2026-07-30
- architecture / glossary: `docs/dev-specs/local-upstream-collaboration.md`
- related work items: `BUG-035-open-sdk-typed-termination-reconciliation-contract.md`
