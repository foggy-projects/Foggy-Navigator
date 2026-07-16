---
doc_type: delivery-spec
delivery_type: bug
version: 1.4.2-SNAPSHOT
ticket: BUG-009
status: READY_FOR_SIGNOFF
canonical: true
execution_mode: ultra
approved_by: repository owner
approved_at: 2026-07-16
open_questions: []
---

# Delivery Spec: owner-smoke readiness failure exit code

## Document Purpose

- intended_for: ultra-implementation / independent-signoff
- purpose: 固定 GitHub Issue #150 的 CLI 进程退出码契约：`owner-smoke` 报告 readiness 失败时，调用方必须得到非零退出码。
- canonical_path: `docs/version-tracker/1.4.2-SNAPSHOT/workitems/BUG-009-owner-smoke-exit-code-contract.md`

## Goal

- version_goal: 使 shell、CI 与上游自动化能可靠地将 `owner-smoke` readiness FAIL 判定为失败。
- target_outcome: 当 readiness `overallStatus` 非 `OK` 时，即使解析资源字段齐全，`upstream owner-smoke` 输出既有 FAIL 诊断并以退出码 `2` 结束；Windows PowerShell/CMD 入口都保留该退出码。

## Scope

- in_scope: `navigator-open-sdk` 的 owner-smoke 回归测试；Navigator Upstream CLI Windows CMD 包装器的退出码透传；Linux/WSL 原生打包、安装、自更新和 OBS 上传链路；本 work item 的实现、发布和验证证据。
- affected_modules: `navigator-open-sdk`、`tools/navigator-upstream-cli/dist`。
- external_dependencies: 无；使用 SDK HTTP mock 自动化验证，不请求真实上游运行态。

## Non-Goals

- out_of_scope: 不改变 readiness 检查、资源解析、模型/Directory/Worker Pool 语义；不提交 ask、创建 task、签发 token；不处理 PC 会话“错误消息但任务完成”的独立现象。
- do_not_touch: 其他未提交工作区改动、已安装 Worker 与服务进程、凭据和本地 profile。

## Confirmed Decisions

| Decision | Rationale | Compatibility / Constraint |
|---|---|---|
| readiness 非 OK 是 owner-smoke 的硬失败 | CI/shell gate 不能只依赖文本输出 | 保持既有 `2` 作为 CLI 业务失败码 |
| CMD 包装器显式 `exit /b %ERRORLEVEL%` | 防止 PowerShell 子进程状态在批处理入口被隐式吞没 | 不改变参数转发或输出 |
| 用完整资源 + FAIL readiness 做 SDK 回归 | 排除“资源缺失路径返回 2”掩盖 readiness 分支的问题 | 不接触真实 profile 或服务 |
| 以 `1.0.21` 作为双平台发布版本 | 远端 `1.0.20` 已存在，不能静默覆盖并且本次需交付 Windows 与 Linux 产物 | `latest.json` 必须同时包含 `files.windows` 与 `files.linux` 以及对应 SHA-256 |

## Acceptance Criteria

- [ ] AC-1: `owner-smoke` 在 readiness `FAIL` 且资源解析完整时返回 `2`，并输出 readiness FAIL 与 `resources SKIPPED` 诊断。
- [ ] AC-2: readiness `OK` 的 owner-smoke 继续返回 `0`。
- [ ] AC-3: `navi.cmd` 将其 PowerShell 子进程的 `%ERRORLEVEL%` 显式返回给调用 shell；发布包仍从该模板复制包装器。
- [ ] AC-4: 相关 Maven 自动化与包装器静态/可执行验证通过，且不产生真实 task/token/provider 副作用。
- [ ] AC-5: WSL/Linux 可生成、安装并自更新原生 `tar.gz` CLI；发布器同时上传 Windows ZIP 和 Linux TAR.GZ，并原子更新双平台 `latest.json`。

## Contract / Data / Security Constraints

- API or event contract: 不改 HTTP API、CLI 参数、输出字段或 exit code `2` 的含义；只确保既有失败语义穿透到调用进程。
- data and migration: 无。
- compatibility and rollback: 成功路径保持 `0`；此前被误报成功的 CMD 调用改为正确非零。回退仅需恢复包装器和回归测试，无数据操作。
- permissions and secrets: 测试使用 mock token 文本并断言不回显；不读取、写入或提交本地 profile/凭据。

## Test and Evidence Obligations

| Item | Risk | Required Validation | Required Evidence |
|---|---|---|---|
| AC-1/2 | critical | `UpstreamCliTest` owner-smoke readiness FAIL/OK 分支 | 测试名称、命令和 0 failures/errors |
| AC-3 | major | Windows CMD 模板包含显式透传；如环境可用则运行包装器验证 | 模板 diff；环境不可用时记录限制 |
| AC-4 | major | `mvn` module tests、`git diff --check` | 精确命令与结果 |

## Bug Context

- bug_source: GitHub Issue #150
- severity: major
- environment: Navigator Upstream CLI `1.0.20`，Windows 上游 shell/CI gate。
- current_behavior: `owner-smoke` 可打印 readiness FAIL 和 remediation，但调用方观察到进程退出码 `0`。
- expected_behavior: readiness FAIL 必须返回非零（约定为 `2`），以阻止后续自动化继续。
- reproduction_steps: 配置完整的 owner-smoke runtime 资源，使 readiness API 返回 `overallStatus=FAIL`；经 Windows CLI 入口执行 `upstream owner-smoke` 并检查进程退出码。
- reproduction_status: 核心 Java 分支静态确认已返回 `2`；发现 Windows `.cmd` 包装器未显式透传其 PowerShell 子进程退出码，需以回归修复发布入口。
- existing_evidence: `UpstreamCli.ownerSmoke` 的 readiness 非 OK 分支输出 `resources SKIPPED` 后返回 `2`；`tools/navigator-upstream-cli/dist/bin/navi.cmd` 只有 PowerShell 调用，未显式 `exit /b %ERRORLEVEL%`。
- regression_protection: required.

## Risks and Open Questions

- known_risks: 当前 Linux 环境不具备 Windows `cmd.exe`/PowerShell，无法在本机执行真实 CMD 包装器；通过模板审查和 Java 自动化降低风险，Windows 发布前仍应执行命令级 smoke。
- open_questions: none

## Ultra Execution Contract

- 在 approved scope 内补齐最小回归，保持 `owner-smoke` 输出与现有 exit code 语义。
- 不得将本 work item 扩展为 readiness 规则、服务端或 PC UI 的修改；若需要改变这些边界，标记 `NEEDS_REPLAN`。
- 完成后填写 `Implementation Result`，将状态改为 `READY_FOR_SIGNOFF`；不得自行设置 `ACCEPTED`。

## Implementation Result

- implementation_summary: 保持 `UpstreamCli.ownerSmoke` 既有的 readiness 非 OK 返回 `2` 语义，并新增“资源字段完整、执行角色有效、但 `WORKER_POOL_MEMBERSHIP=FAIL`”的回归，防止资源缺失分支掩盖退出码契约。Windows 发布模板的 `navi.cmd` 和对称的 `navi-e2e.cmd` 现在在调用 PowerShell 后显式执行 `exit /b %ERRORLEVEL%`，确保子进程状态返回给 CMD/CI 调用方。CLI 版本升级至 `1.0.21`，新增 `package.sh` / `upload.sh`、Linux `navi` / `navi-e2e`、`install.sh` 与远端 `install.sh`，从 WSL 同时生成 Windows ZIP 和 Linux TAR.GZ 并发布双平台 `latest.json`。
- changed_paths:
  - `navigator-open-sdk/src/test/java/com/foggy/navigator/sdk/cli/UpstreamCliTest.java`
  - `tools/navigator-upstream-cli/dist/bin/navi.cmd`
  - `tools/navigator-upstream-cli/dist/bin/navi-e2e.cmd`
  - `tools/navigator-upstream-cli/dist/bin/navi`
  - `tools/navigator-upstream-cli/dist/bin/navi-e2e`
  - `tools/navigator-upstream-cli/dist/install.sh`
  - `tools/navigator-upstream-cli/dist/remote-install.sh`
  - `tools/navigator-upstream-cli/dist/package.sh`
  - `tools/navigator-upstream-cli/dist/upload.sh`
  - `tools/navigator-upstream-cli/.env.example`
  - `navigator-open-sdk/pom.xml`
  - `docs/version-tracker/1.4.2-SNAPSHOT/workitems/BUG-009-owner-smoke-exit-code-contract.md`
- tests_and_results:
  - `mvn clean -pl navigator-open-sdk -am -Dtest=UpstreamCliTest -Dsurefire.failIfNoSpecifiedTests=false test`：通过；`UpstreamCliTest` 109 tests，0 failures / 0 errors。
  - `mvn test -pl navigator-open-sdk`：通过；SDK 150 tests，0 failures / 0 errors。
  - `rg -n -F 'exit /b %ERRORLEVEL%' tools/navigator-upstream-cli/dist/bin/navi.cmd tools/navigator-upstream-cli/dist/bin/navi-e2e.cmd`：两个发布模板均命中显式退出码透传。
  - `git diff --check -- navigator-open-sdk/src/test/java/com/foggy/navigator/sdk/cli/UpstreamCliTest.java tools/navigator-upstream-cli/dist/bin/navi.cmd tools/navigator-upstream-cli/dist/bin/navi-e2e.cmd`：通过，无空白错误。
  - `bash -n tools/navigator-upstream-cli/dist/bin/navi tools/navigator-upstream-cli/dist/bin/navi-e2e tools/navigator-upstream-cli/dist/install.sh tools/navigator-upstream-cli/dist/remote-install.sh tools/navigator-upstream-cli/dist/package.sh tools/navigator-upstream-cli/dist/upload.sh`：通过。
  - `bash tools/navigator-upstream-cli/dist/package.sh`：通过，离线生成 `navigator-upstream-cli-1.0.21-windows.zip` 和 `navigator-upstream-cli-1.0.21-linux.tar.gz`。
  - 离线安装 smoke：从 Linux TAR.GZ 安装后 `navi version` 输出 `1.0.21`、`upstream --help` 可用、`upstream ask --not-a-real-option` 返回 `2`；Windows ZIP root/bin CMD 包装器均包含 `exit /b %ERRORLEVEL%`。
- manual_or_experience_evidence: Windows 与 Linux 包均从同一 `1.0.21` SDK JAR 和依赖生成；Linux 安装器只在上游项目根目录写入 gitignored profile，调用 Java 时使用 `exec` 保留退出码。未执行真实 `owner-smoke`，避免访问真实 profile 与上游运行态。
- deviations: 未改变 Java production 分支，因为该分支已经在 readiness 非 OK 时返回 `2`；本次修复发布入口的退出码穿透缺口并为核心条件增加回归。现有 Windows PowerShell 发布脚本保留作为兼容路径；本次双平台正式发布以新增 WSL `package.sh` / `upload.sh` 为准。
- residual_risks: 当前 Linux 环境未安装 `cmd.exe`、`powershell` 或 `pwsh`，不能执行 Windows CMD 包装器的端到端 smoke；发布到 Windows 前应以一个已知返回 `2` 的 CLI 调用确认 `%ERRORLEVEL%` 为 `2`。OBS 上传与远端 install smoke 尚待本轮发布完成。
- readiness: READY_FOR_SIGNOFF

## References

- issue: `https://github.com/foggy-projects/Foggy-Navigator/issues/150`
- related work item: `BUG-008-codex-readiness-pool-membership-parity.md`
