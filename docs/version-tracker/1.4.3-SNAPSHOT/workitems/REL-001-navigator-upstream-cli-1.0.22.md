---
doc_type: delivery-spec
delivery_type: cross-module
version: 1.4.3-SNAPSHOT
ticket: REL-001
status: READY_FOR_SIGNOFF
canonical: true
execution_mode: ultra
approved_by: project-owner-user-confirmed
approved_at: 2026-07-22
execution_started_at: 2026-07-22
open_questions: []
---

# Delivery Spec: Navigator Upstream CLI 1.0.22 发布

## Document Purpose

- intended_for: release implementation / CLI operators / independent-signoff
- purpose: 将 GOV-002 的三 lane CLI 能力以单调递增的双平台安装包发布到既有 OBS 分发根，并记录提交、推送与可复核的发布证据。
- canonical_path: `docs/version-tracker/1.4.3-SNAPSHOT/workitems/REL-001-navigator-upstream-cli-1.0.22.md`

## Goal

- version_goal: 消除已发布 CLI 与 GOV-002 source 的能力漂移，使上游/SIM 可安装具备 `platform`、`app`、`runtime` 三 lane 的版本。
- target_outcome: 发布 `navigator-upstream-cli` `1.0.22` 的 Windows ZIP 与 Linux TAR.GZ，OBS `latest.json` 指向该版本，变更提交并推送至 `origin/main`。

## Scope

- in_scope:
  - 将 `navigator-open-sdk` 和 provenance metadata 升级至 `1.0.22`，并将 GOV-002 的 CLI lane 能力列入 release metadata。
  - 运行 SDK 回归、双平台离线打包/安装 smoke，并从干净 detached release worktree 上传既有 OBS 目标及其内建远端 Linux 安装 smoke。
  - 更新当前版本的 runbook/provenance 文本、发布交付单和版本索引；只提交本事项及 GOV-002 的相关路径并推送。
- affected_modules:
  - `navigator-open-sdk`
  - `tools/navigator-upstream-cli/dist`
  - `docs/version-tracker/1.4.3-SNAPSHOT`
- external_dependencies:
  - 既有 Huawei OBS release bucket、`obsutil` 本机配置及 `https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/navigator-upstream-cli`。

## Non-Goals

- out_of_scope:
  - 不改 Navigator backend、数据库、API、S1/S2 typed principal/lifecycle、真实 Tenant/ClientApp/credential、Worker、Gateway 或 external/production 配置。
  - 不在运行中的项目/上游工作区安装或执行带真实 profile 的 CLI 命令；不运行 real TMS/SIM smoke。
- do_not_touch:
  - 当前 worktree 中无关的 `scripts/local-dev-stack.sh`、`tools/codex-agent-worker/**`、`BUG-011-*`。
  - 真实 secret、`.navigator` profile、Worker/service 进程；本次无后端或 Worker 二进制变更，禁止为发布而重启它们。

## Confirmed Decisions

| Decision | Rationale | Compatibility / Constraint |
|---|---|---|
| 发布版本为 `1.0.22` | 远端 `latest.json` 已为 `1.0.21`，公开版本必须单调递增 | 不覆盖同版本 release；Windows/Linux 同步发布 |
| 从干净 detached worktree 发布 | 当前主工作区含用户未提交的无关改动，发布器拒绝 dirty tree | 临时 `.env` 仅置于 gitignored release worktree，绝不输出或提交 |
| 不重启服务/Worker | 发布物为独立 Java CLI archive，不改变运行中服务或 Worker | 仅发布并验证安装器，不安装到实际上游 |
| 发布后交付 SIM handoff prompt | 让 SIM 使用新 CLI 的 lane/profile 边界，而非猜测 typed authority | TMS legacy platform lane 仍不代表 `SAAS_PLATFORM` |

## Acceptance Criteria

- [ ] AC-1: `1.0.22` source/version/provenance 与 SDK JAR、Windows ZIP、Linux TAR.GZ 一致，release metadata 表示三 lane/profile-split 能力。
- [ ] AC-2: focused CLI tests、affected SDK reactor tests、shell syntax、离线 Linux 安装 smoke 均实际通过；不读取真实 profile/credential或调用 live Navigator/TMS/SIM。
- [ ] AC-3: 仅从 clean detached worktree 上传双平台 archive、`latest.json`、`install.ps1`、`install.sh`；OBS installer smoke 成功，远端 `latest.json` 的 version、commit、SHA 与本次 archive 对应。
- [ ] AC-4: 本事项/GOV-002 相关改动以明确 commit 推送到 `origin/main`；既有无关 dirty changes 不被 stage、commit、重置或重启。
- [ ] AC-5: 交付清楚说明无需服务/Worker restart，并提供 SIM handoff prompt，包含安装、验证、三 lane/profile 与权限边界。

## Contract / Data / Security Constraints

- API or event contract: 不改变 API、header、server authorization 或 CLI command contract；仅发布已验证 source。
- data and migration: 无 schema/data migration；OBS `latest.json` 是安装器指针，回滚应改指向先前完整 release，不能覆盖 archive 伪造版本。
- compatibility and rollback: `self update` 与 installer 继续消费双平台 `latest.json`；发现错误时创建更高修复版本或按既有发布规则回指，而不重用 `1.0.22`。
- permissions and secrets: `.env`、obsutil 配置、profile 和任何 credential 不得进入 diff、artifact source、console evidence 或 Git；发布命令仅使用现有本机配置。

## Test and Evidence Obligations

| Item | Risk | Required Validation | Required Evidence |
|---|---|---|---|
| AC-1/2 | critical | `mvn -pl navigator-open-sdk -Dtest=UpstreamCliTest test`、`mvn -pl navigator-open-sdk -am test`、`bash -n`、`package.sh`、离线 install smoke | version/CLI output、test counts、archive SHA |
| AC-3 | critical | `upload.sh --version 1.0.22` from clean release worktree; fetch remote `latest.json` | OBS responses, installer smoke, remote JSON fields |
| AC-4/5 | major | scoped diff/secret scan, `git diff --check`, staged file review, `git push origin main` | commit SHA, push result, no-restart conclusion, handoff text |

Validation cost: focused checks are `<5m`; reactor/package/upload are `5-30m`. Follow `focused -> reactor -> package/installation -> upload -> remote verification` once. If OBS upload or remote installer smoke fails twice for an external reason, stop publishing and mark `NEEDS_REPLAN`; do not retry indefinitely or bypass smoke.

## Risks and Open Questions

- known_risks:
  - Windows archive is built but Windows native execution is not available in this Linux/WSL environment; the release upload smoke validates Linux installer, while Windows wrapper content is validated statically.
  - The release changes provenance from historical source/published drift to the intended `1.0.22` published artifact; this does not alter typed authority availability.
- open_questions: none

## Ultra Execution Contract

- Read this work item, root `AGENTS.md`, `CLAUDE.md`, CLI package/upload scripts and GOV-002 before release work.
- Do not expand into service, Worker, real upstream, credentials or typed-authority changes. If a version/installer/API change beyond this release is required, set `NEEDS_REPLAN`.
- Record exact commands, commit IDs, remote metadata, changed paths and residual risk. Finish only at `READY_FOR_SIGNOFF`; do not self-assign `ACCEPTED`.

## Implementation Result

- implementation_summary: 已发布 `navigator-upstream-cli` `1.0.22`。archive 与 provenance 均对应 `c314850ad33213b2df5faf91c282adc350e5205b`，包含 `tms-saas-three-lane`、`tenant-credential-profile-split`；OBS `latest.json` 已指向该 release。实现提交 `c314850a feat(cli): release three-lane upstream workflow` 已推送至 `origin/main`。
- changed_paths:
  - `navigator-open-sdk/pom.xml`、CLI source/config/test 与 `authorization-provenance.properties`：发布版本与 GOV-002 三 lane/profile-split 证明。
  - `tools/navigator-upstream-cli/dist/package.sh`、`tools/navigator-upstream/scripts/synthetic-upstream-*.sh`：archive metadata 及 JAR version 对齐。
  - `.agents/skills/navigator-runtime-provisioning/SKILL.md`、`docs/version-tracker/1.4.3-SNAPSHOT/{README.md,runbooks/**,workitems/GOV-002-*.md}`：operator lane boundary、GOV-002 交付记录。
  - `docs/version-tracker/1.1.3-SNAPSHOT/upstream-integration/19-navigator-upstream-cli-install-update.md`：当前公开版本、archive 与 SHA 的安装指引。
  - 本 work item 与版本索引：发布证据与签收状态。
- tests_and_results:
  - `mvn -pl navigator-open-sdk -Dtest=UpstreamCliTest test`：`129` tests passed。
  - `mvn -pl navigator-open-sdk -am test`：`170` tests passed。
  - `bash -n tools/navigator-upstream-cli/dist/bin/navi tools/navigator-upstream-cli/dist/bin/navi-e2e tools/navigator-upstream-cli/dist/install.sh tools/navigator-upstream-cli/dist/remote-install.sh tools/navigator-upstream-cli/dist/package.sh tools/navigator-upstream-cli/dist/upload.sh`：passed。
  - detached clean worktree `package.sh` 与 offline Linux install smoke：passed；`navi version` 输出 `1.0.22`、commit `c314850a`、`gitDirty=false`，canonical three-lane help 及 invalid-option guard 均通过。
  - `git diff --check` 与 scoped secret scan：passed；未 stage/commit 既有 dirty changes。
- manual_or_experience_evidence:
  - upload command：`OBSUTIL_BIN="$HOME/.local/bin/obsutil" OBSUTIL_CONFIG_FILE=<local-obsutil-config> bash tools/navigator-upstream-cli/dist/upload.sh --version 1.0.22`，从 gitignored detached worktree 运行并 exit `0`；Windows ZIP、Linux TAR.GZ、`latest.json`、`install.ps1`、`install.sh` 均获 OBS HTTP `200`，内建远端 Linux installer smoke passed。
  - remote `latest.json` verification：`version=1.0.22`、`buildId=1.0.22+c314850ad332`、`gitCommit=c314850ad33213b2df5faf91c282adc350e5205b`、`gitDirty=false`；Windows SHA-256 `a02ea2922e7d654d06848f9284c57efc0bbba38d95835e9ad42bede1fc5b59ff`，Linux SHA-256 `357f21492b0c65421ca55d13c41899552196374b0c097806f19ea770c52ec3d4`。
  - 本次未重启任何 service 或 Worker：发布物仅为独立 CLI archive，未变更 backend、Worker binary/config 或运行时资源。
- deviations: none
- residual_risks: 当前 Linux/WSL 环境未执行 Windows native wrapper；Windows archive 已由 package metadata/static content 覆盖，远端 installer smoke 为 Linux。真实 TMS/SIM profile、credential、runtime 和 typed `SAAS_PLATFORM` lifecycle 均未触碰，仍需下游在自身受控环境完成接入验收。
- readiness: READY_FOR_SIGNOFF

## References

- feature delivery: `GOV-002-tms-saas-cli-lane-alignment.md`
- release mechanism: `tools/navigator-upstream-cli/dist/package.sh`, `tools/navigator-upstream-cli/dist/upload.sh`
- operator install guide: `docs/version-tracker/1.1.3-SNAPSHOT/upstream-integration/19-navigator-upstream-cli-install-update.md`
