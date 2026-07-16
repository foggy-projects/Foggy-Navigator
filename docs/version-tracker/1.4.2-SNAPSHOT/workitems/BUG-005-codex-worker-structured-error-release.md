---
type: bug
bug_source: user-report
version: 1.4.2-SNAPSHOT
ticket: BUG-005
severity: major
status: ready-for-verification
reproduction_status: confirmed
test_strategy: integration-test
automation_decision: required
owner: Codex Worker release
---

# Codex Worker 未上报结构化错误详情

## 文档作用

- doc_type: bug
- intended_for: project-root-session | reviewer
- purpose: 跟踪旧版 Codex Worker 将上游失败降级为泛化错误，导致诊断详情为空的问题。

## Background

Navigator 的错误详情页支持展示脱敏后的诊断说明、异常类型与上游状态。实际任务在 `workerId=36508966` 上使用 `codex-terra:medium` 失败时，诊断快照中的上述字段均为空，页面只能显示 `CODEX_WORKER_REMOTE_ERROR`。

目标 Worker `192.168.31.119:3053` 运行版本 `1.0.14`。结构化错误事件字段由当前仓库提交 `93a7c8f8` 新增，旧 Worker 不会发送这些字段。

## Reproduction

1. 在 Worker `36508966` 提交 Codex 任务，指定 `codex-terra:medium`。
2. Worker 返回失败，任务进入 `FAILED`。
3. 调用错误诊断接口：`diagnosticText`、`exceptionType`、`providerStatus` 与 `httpStatus` 为 `null`。

## Expected vs Actual

- expected：Worker 对上游错误进行脱敏并传递结构化错误字段；Navigator 保存快照，前端“查看错误详情”展示可操作原因。
- actual：旧 Worker 仅返回泛化错误，后端只能显示 `CODEX_WORKER_REMOTE_ERROR` 与通用重试建议。

## Impact Scope

- `tools/codex-agent-worker` 发布版本与 Worker 运行时升级。
- Codex Worker 的 SDK SSE 错误事件与 Navigator 诊断快照。
- 不改变公开分享授权、错误脱敏规则或模型路由。

## Test Strategy

- 自动化：运行 Worker 单元测试、类型检查和发布候选 full smoke，覆盖结构化错误事件。
- 集成：发布后升级目标 Worker，复现 Terra 失败或等价受控失败，确认诊断接口含非空脱敏详情。
- 真实模型失败依赖外部上游，不能用固定断言替代运行态证据。

## Code Inventory

| 路径 | 作用 | 修改 |
|---|---|---|
| `tools/codex-agent-worker/package.json` | Worker 发布版本 | `1.0.15` 升级至 `1.0.16` |
| `tools/codex-agent-worker/package-lock.json` | 发布版本一致性 | 同步版本 |
| `tools/codex-agent-worker/src/diagnostics.ts` | 已有结构化脱敏错误事件 | 作为发布验证范围 |
| `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexStreamRelay.java` | 已有诊断快照消费 | 作为端到端验证范围 |

## Fix Checklist

- [x] 确认诊断快照字段为空而非前端隐藏。
- [x] 确认目标 Worker 为 `1.0.14`，低于包含结构化诊断的发布候选。
- [x] 发布包含结构化错误协议的 `1.0.16` Worker。
- [ ] 升级目标 Worker 并确认 `/health` 版本。
- [ ] 对失败任务验证脱敏诊断详情可见。

## Verification

- 发布前：`npm run package:release -- --platform all --smoke auto`。
- 发布后：下载校验、`latest.json` 校验和、目标 Worker `/health` 版本校验。
- 回归：提交 Terra 任务并读取诊断详情；不得记录或显示 API Key、Token、完整 URL 或本机路径。

## Blockers

- 目标 Worker 的 SSH 公钥认证当前被拒绝；发布后升级需要该主机的 SSH 权限，或由其管理员执行 `codex-worker upgrade --force`。

## Execution Check-in

- 发布：`1.0.16` 已发布到既有 Worker 更新源；公开 `latest.json` 确认版本为 `1.0.16`，对应提交 `e02fffb2`。
- 构建与测试：`npm run package:release -- --platform all --smoke full` 通过；177 个测试中 176 通过、1 个仅 Windows 用例跳过，类型检查、构建、三平台包和 full smoke 均通过。
- 发布验证：发布器已回读并校验 Linux、macOS、Windows 包、校验文件及发布证据后才更新 `latest.json`。
- 剩余：目标 Worker 尚为 `1.0.14`，需升级并复现 Terra 失败，确认诊断接口出现非空、已脱敏的详情；未完成前不得关闭本 BUG。
- self_check_decision: `continue-in-progress`；候选发布已完成，运行态端到端验证仍被目标主机访问权限阻断。
