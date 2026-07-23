---
doc_type: delivery-spec
delivery_type: cross-module
version: 1.4.3-SNAPSHOT
ticket: REL-002
status: BLOCKED
canonical: true
execution_mode: ultra
approved_by: project-owner-user-confirmed
approved_at: 2026-07-23
execution_started_at: 2026-07-23
open_questions: []
---

# Delivery Spec: Navigator Upstream CLI 1.0.24 safe-ask 发布

## Document Purpose

- intended_for: release implementation / SIM operator / independent-signoff
- purpose: 将 BUG-016 已实现的 request-scoped 空工具面、空 BusinessFunction scope、dedicated safe-ask 与 POSIX profile `0600` 修复发布为 official CLI 1.0.24，并提供可复核的 SIM 升级入口。
- canonical_path: `docs/version-tracker/1.4.3-SNAPSHOT/workitems/REL-002-navigator-upstream-cli-1.0.24-safe-ask.md`

## Goal

- version_goal: 消除 SIM 当前 official CLI `1.0.23+aa4a944e7f25` 的 capability blocker。
- target_outcome: official OBS `latest.json` 指向 clean Git commit 构建的 `navigator-upstream-cli 1.0.24`；双平台 archive、installer、SHA、buildId 和 feature metadata 一致；发布验证通过后明确授权 SIM 升级，但不代替 SIM 执行 runtime smoke。

## Scope

- in_scope:
  - 将 CLI provenance 对齐为 `source.version=published.version=1.0.24`、`artifact.drift=SOURCE_MATCHES_PUBLISHED`。
  - 运行 focused SDK/CLI tests、shell syntax、双平台 package、离线 Linux install smoke。
  - Linux/WSL installer 对新建或既有 `.navigator/upstream.env` 强制收紧为 `0600`，远程安装 smoke 必须验证该 mode。
  - Linux/WSL installer 的普通安装与 `--upgrade` 路径均必须在成功后返回 exit `0`。
  - 从 clean detached release worktree 上传 Windows/Linux archive、`latest.json`、`install.ps1`、`install.sh` 到既有 official OBS 分发根，并执行内建远程 Linux installer smoke。
  - 验证 `safe-ask`、`ask-allowed-functions`、`runtime-profile-posix-0600` feature、buildId/gitCommit/SHA 和安装后 provenance。
  - 提交并推送 release metadata、交付记录和脱敏验证证据；提供 SIM 升级及 fail-closed safe-ask handoff。
- affected_modules:
  - `navigator-open-sdk`
  - `tools/navigator-upstream-cli/dist`
  - `docs/version-tracker/1.4.3-SNAPSHOT`
- external_dependencies:
  - existing Huawei OBS release bucket and `https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/navigator-upstream-cli`

## Non-Goals

- out_of_scope:
  - 不读取或修改 SIM runtime profile，不代替 SIM 安装 CLI。
  - 不获取 runtime token，不创建 task/context/correlation，不提交 ask 或 safe-ask，不 dispatch Worker/model。
  - 不创建或修改 Worker、Directory、Agent、model binding、BusinessFunction grants、Gateway/external/strict/production 配置。
  - 不把普通 ask 的 `allowedTools=[]` 描述为全部 Codex native tools 的零面证明；严格零面仅由 dedicated `safe-ask` no-runtime contract 提供。
- do_not_touch:
  - sibling workspace、真实 credential/profile 内容、运行中的 Worker 及业务数据。

## Confirmed Decisions

| Decision | Rationale | Compatibility / Constraint |
|---|---|---|
| 发布版本固定为 `1.0.24` | source POM 已为 1.0.24，远端 latest 为 1.0.23，满足单调升级 | 不覆盖或复用同版本 artifact |
| 从 clean detached worktree 构建和上传 | buildId/gitCommit/gitDirty 必须可验证 | gitignored release `.env` 不输出、不提交 |
| 发布前 provenance 改为 `SOURCE_MATCHES_PUBLISHED` | official artifact 不能继续声明 source newer than published | source/published/package 三者必须一致 |
| 使用既有 release scripts 和 official OBS 根 | 保持 installer/manifest 消费契约 | 上传后必须重新下载并核验 SHA |
| 发布成功后只授权 SIM 自行升级 | 保持跨仓和 credential 边界 | 本会话不进入 SIM runtime lane |

## Acceptance Criteria

- [ ] AC-1: focused `UpstreamCliTest` 和 affected SDK reactor tests 通过；provenance 为 `1.0.24 / 1.0.24 / SOURCE_MATCHES_PUBLISHED`。
- [ ] AC-2: package metadata 包含 `safe-ask`、`ask-allowed-tools`、`ask-allowed-functions`、`runtime-profile-posix-0600`，且 `gitDirty=false`。
- [ ] AC-3: Windows ZIP、Linux TAR.GZ、`latest.json`、双平台 installers 上传成功；remote version/buildId/gitCommit/SHA 与 clean release commit 对应。
- [ ] AC-4: fresh temporary Linux install 后 `navi version` 与 capability gate 通过；安装产生的 runtime profile 权限回归测试已实际通过。
- [ ] AC-5: release commit 与最终 evidence commit 推送到 `origin/main`；无 secret、profile 内容或 sibling workspace 改动进入 Git。
- [ ] AC-6: SIM handoff 明确先升级和验证 provenance/capability，再自行执行 dedicated `safe-ask`；普通 ask、STANDARD dispatch 和真实业务调用继续 fail closed。

## Contract / Data / Security Constraints

- API or event contract: 不新增本次 release 之外的 API；发布 BUG-016 已完成的 safe-smoke/allowedFunctions contract。
- data and migration: 无 schema/data migration。
- compatibility and rollback: installer 继续消费 `latest.json`；发布错误时使用更高修复版本或显式回指完整旧 release，不覆盖 1.0.24 伪造新制品。
- permissions and secrets: OBS config 使用前收紧为 `0600`；console/docs 仅记录 bucket-independent public metadata、SHA 和 count/source，不回显 AK/SK、token、profile 或 function inventory。

## Test and Evidence Obligations

| Item | Risk | Required Validation | Required Evidence |
|---|---|---|---|
| provenance/CLI | critical | focused CLI test plus SDK reactor | command, test count, version output |
| package | critical | shell syntax, clean worktree package, offline install | BUILD_INFO, features, archive SHA |
| OBS publish | critical | upload script and remote installer smoke | HTTP/obsutil success, remote manifest |
| security | critical | profile permission regression and secret scan | test assertion, scan result without values |
| handoff | major | exact SIM upgrade/capability/safe-ask sequence | final prompt and no-dispatch boundary |

Validation cost: focused checks are `<5m`; reactor/package/upload are `5-30m`. Run `focused -> affected reactor -> package/offline smoke -> upload/remote smoke -> final remote verification` once. If upload or remote installer smoke fails twice for an external reason, set `NEEDS_REPLAN`; do not bypass smoke or silently reuse an archive.

## Risks and Open Questions

- known_risks:
  - Windows native execution is unavailable in this Linux/WSL session; Windows archive and wrapper are validated statically while the remote executable smoke is Linux.
  - `safe-ask` returns a terminal synthetic evidence ID and does not dispatch runtime; SIM must not poll it as a Worker task.
- open_questions: none

## Ultra Execution Contract

- 先读取本文件、root `AGENTS.md`、`CLAUDE.md`、BUG-016、release scripts、`navigator-runtime-provisioning` 和 OBS 上传约束。
- 在 scope 内自主完成最小 release metadata、验证、commit/push、package/upload 和 evidence 回写。
- 不读取 SIM credential，不进入 runtime lane；若需要改变 API、安全边界、版本号、制品根或运行资源，设置 `NEEDS_REPLAN`。
- 完成后填写 `Implementation Result` 并设置 `READY_FOR_SIGNOFF`；不得自行设置 `ACCEPTED`。

## Implementation Result

- implementation_summary:
  - 已准备 clean CLI `1.0.24` release candidate，并在发布前补充 Linux/WSL installer 对新建/既有 profile 的 `0600` 收紧，以及普通安装成功后 exit `0` 的修复。
  - official OBS 上传未执行：发布凭据预检在任何 object write 前返回 `InvalidAccessKeyId`；远端 `latest.json` 仍为 `1.0.23+aa4a944e7f25`。
  - 因 official 发布未完成，main provenance 保持 truthful：`source.version=1.0.24`、`published.version=1.0.23`、`SOURCE_NEWER_THAN_PUBLISHED`。恢复凭据后必须生成新的 clean release commit/buildId，不复用未发布 candidate。
- changed_paths:
  - `tools/navigator-upstream-cli/dist/install.sh`
  - `tools/navigator-upstream-cli/dist/upload.sh`
  - CLI provenance/test
  - 本 work item 与版本索引
- tests_and_results:
  - PASS — `mvn -pl navigator-open-sdk -Dtest=UpstreamCliTest test`: 136/136。
  - PASS — `mvn -pl navigator-open-sdk -am test`: 177/177。
  - PASS — release shell syntax、`git diff --check`、差异敏感值扫描。
  - PASS — clean detached candidate `5b01d48ef63e126a5fc0ecb7b41f6bdd00fded1e`, `gitDirty=false`; Windows SHA `32eccbc1e42541548877cb6b53ac0545488303a0de758e37f0a8504a90cedf04`, Linux SHA `fbd4953d8f08a1ea5186dbc42379c3067e691f6bc6bfae45b36ee50a27597c92`。
  - PASS — offline Linux install/upgrade；`safe-ask`、`allowed-tools`、`allowed-functions` 和 provenance help 可见；新建与既有 profile mode 均为 `0600`。
  - BLOCKED — `/mnt/c/Users/oldse/.obsutilconfig` 已收紧为 `0600`，但 `obsutil ls -s` 返回 HTTP 403 `InvalidAccessKeyId`。
- manual_or_experience_evidence:
  - 2026-07-23 remote `latest.json` read-only verification remains `version=1.0.23`, `buildId=1.0.23+aa4a944e7f25`, `gitDirty=false`，且 feature list 不包含 `safe-ask`、`ask-allowed-functions`、`runtime-profile-posix-0600`。
  - 未读取或输出 AK/SK；未上传任何 object；未读取 SIM runtime profile，未获取 token，未创建 task，未 dispatch Worker/model。
- deviations:
  - 发布前离线 smoke 发现并修复了 Linux installer profile mode 与成功 exit code 两个 release-path 缺陷；均属于已批准的 POSIX profile/official installer 安全范围。
- residual_risks:
  - official 1.0.24 尚不存在，SIM 必须继续停在 provenance/capability gate，不得使用本地 candidate 或提交探测性 ask。
  - 需要 operator 在本机安全更新有效 OBS AK/SK；不得把 credential 粘贴进 Git、文档或发布日志。
- readiness: BLOCKED

## References

- implementation contract: `BUG-016-openapi-safe-ask-request-scoped-empty-surfaces.md`
- release baseline: `REL-001-navigator-upstream-cli-1.0.22.md`
- release mechanism: `tools/navigator-upstream-cli/dist/package.sh`, `tools/navigator-upstream-cli/dist/upload.sh`
