---
type: bug
bug_source: user-report
version: 1.4.0-SNAPSHOT
ticket: BUG-023
severity: major
status: ready-for-verification
reproduction_status: partial
test_strategy: unit-test
automation_decision: required
owner: codex-agent-worker
---

# Windows Codex Worker 启动健康检查误报超时

## Background

Windows 用户更新 Codex SDK Worker 后，`start.ps1` 显示 Worker 已获得 PID，随后连续等待 30 秒并以启动超时退出；实际 Worker 进程和端口已经正常启动。

## Reproduction

1. 在 Windows 上通过发布包安装或更新 Codex SDK Worker。
2. 启动安装目录中的 `start.ps1`。
3. Worker 进程已经监听端口，但 PowerShell 对单一 `localhost` 健康地址的请求未在 30 秒窗口内通过严格 health 判定。
4. 观察脚本返回失败，而 Worker 进程仍在运行。原始 3051 端口现场已不在，当前只能确认安装脚本仍是单地址、30 秒的旧实现，不能完整重放当时的网络解析状态。

## Expected vs Actual

- Expected：发布包启动脚本能够通过 Worker 实际监听的 loopback 地址确认 `/health`，并报告 READY。
- Actual：发布包启动脚本只探测 `localhost` 且仅等待 30 秒；当该单一路径不可达或未通过严格 health 判定时，会在 Worker 进程已经启动的情况下误报超时。

## Impact Scope

- 影响 `tools/codex-agent-worker/release/start.ps1` 打包到 Windows 发布物后的启动流程。
- 同一风险存在于 full Worker 更新和 SDK 更新完成后的二次健康检查。
- Worker 服务本身已成功启动，但脚本返回非零退出码，可能让更新流程或运维脚本误判升级失败。
- 根目录开发启动脚本已具备 IPv4 fallback，发布脚本未同步该逻辑。

## Test Strategy

- 增加静态契约测试，保证发布版 Windows 启动脚本优先探测 `127.0.0.1`，再 fallback 到 `localhost`。
- 保证发布版与开发版使用一致的 60 秒 readiness window。
- 发布前执行 Worker 单元测试、类型检查和 release full smoke；Windows PowerShell 行为仍需在 Windows 候选包上最终确认。

## Code Inventory

- `tools/codex-agent-worker/release/start.ps1`
- `tools/codex-agent-worker/update-worker.ps1`
- `tools/codex-agent-worker/release/update.ps1`
- `tools/codex-agent-worker/start.ps1`
- `tools/codex-agent-worker/tests/windows-release-start.test.ts`

## Fix Checklist

- [x] 发布版健康检查增加 `127.0.0.1` 与 `localhost` 候选地址。
- [x] 封装并复用严格的 Worker health 判定。
- [x] 发布版 readiness window 从 30 秒同步为 60 秒。
- [x] full Worker 更新与 SDK 更新的重启后健康检查统一为 IPv4-first。
- [x] 增加防止发布脚本再次落后于开发脚本的自动化测试。
- [x] 完成单元测试、类型检查和发布候选 basic smoke。
- [ ] 在 Windows 候选包验证启动输出不再误报超时。

## Verification

已完成：

- `npm test`：146 个测试中 145 通过，1 个既有 Windows-only 测试在 Linux 跳过。
- `npm run typecheck`：通过。
- `npm run package:release -- --platform all --smoke basic --skip-verify`：三平台归档、checksum、归档结构和 forbidden-file scan 通过；此前同一轮已独立完成 test、typecheck、build。
- Windows ZIP 内的 `start.ps1` 已确认包含 IPv4-first 的双地址探测逻辑。
- Windows PowerShell 5.1 parser 已确认三个修改后的 `.ps1` 文件无语法错误。
- 当前 `C:\Users\oldse\.codex-worker` 的 1.0.14 Worker 在 3052 端口返回 `status=ok`；其安装脚本仍是本次修复前的单 `localhost`、30 秒版本。

待完成：

- Windows 上执行发布候选 `start.ps1`，确认 `/health` 成功且脚本输出 READY。
- full smoke 的候选安装和启动已执行，但 Linux/WSL 环境在清理临时 `node_modules` 时持续占用 CPU 未退出，本次手动终止；该清理问题不作为 BUG-023 已修复的证据。

## References

- 用户报告：Windows Codex Worker 更新后启动等待 30 秒超时，但实际进程已启动成功。
- 修复发布版本：Codex SDK Worker `1.0.15`。
