---
type: bug
bug_source: user-report
version: 1.4.0-SNAPSHOT
ticket: BUG-019
severity: major
status: ready-for-verification
reproduction_status: confirmed
test_strategy: unit-test
automation_decision: required
owner: metadata-config-module | codex-worker-agent | navigator-frontend
---

# App Server 模型配置未出现在任务下拉

## Background

LLM 配置页已经可以创建 `OPENAI_CODEX_APP_SERVER` 模型，但任务页请求
`listModelConfigs(workerId)` 时，metadata-config-module 使用实时 `modelAvailable` 过滤整个配置。
当 Runtime 为 Dark、刚同步或 freshness 暂时过期时，配置会在 API 返回前被删除，前端因此看不到
`oldseasoul-app-server`，无法选择或观察其模型就绪状态。

## Reproduction

1. 为物理 Worker 配置 App Server Endpoint，并创建全局 App Server LLM 配置。
2. 保持对应 Runtime 未启用，或让实时路由暂时不可用。
3. 打开该 Worker 的新任务面板并展开 LLM 配置下拉。
4. 观察普通 Codex/Claude 配置存在，但 App Server 配置缺失。

## Expected vs Actual

- Expected: 配置列表按 Endpoint manifest 的 `modelSupported` 保留 App Server 配置；模型与任务执行
  仍按 `modelAvailable` fail-closed。
- Actual: 列表、授权和执行共用 `supportsWorker`，导致“支持但暂不可执行”的配置被隐藏。

## Impact Scope

- Navigator 任务创建页及依赖 `listModelConfigs(workerId)` 的其他模型配置选择器。
- App Server Runtime 暂未启用、同步租约切换或路由暂不可用时最容易触发。
- 不影响已直接携带有效 modelConfigId 且 Runtime Ready 的任务执行校验。

## Test Strategy

- SPI 单测语义：新增配置支持检查，默认兼容现有后端。
- App Server 单测：`modelSupported=true`、`modelAvailable=false` 时配置可见但执行不可用。
- Metadata 单测：Worker 配置列表保留 supported App Server 配置，随后执行校验仍拒绝 unavailable Runtime。

## Code Inventory

- `navigator-spi/.../WorkerBackendConnectionTester.java`
- `addons/codex-worker-agent/.../CodexAppServerBackendConnectionTester.java`
- `metadata-config-module/.../LlmModelManagerImpl.java`
- 对应 Java 单元测试。

## Fix Checklist

- [x] 复现并确认配置在后端列表 API 阶段被过滤。
- [x] 拆分配置支持与实时执行可用性检查。
- [x] 列表、模型变体过滤和 Restricted 授权使用配置支持检查。
- [x] 任务执行校验继续使用实时可用性检查。
- [x] 完成定向测试和构建。

## Verification

- `LlmModelManagerImplTest` 23 项通过，其中回归用例验证 supported App Server 配置会被列表保留，
  同一 Runtime 的执行校验仍因 unavailable 被拒绝。
- `CodexAppServerBackendConnectionTesterTest` 5 项通过，验证 `modelSupported=true`、
  `modelAvailable=false` 时配置支持为 true、执行可用性为 false。
- Maven 9 模块 reactor 编译和定向测试通过，`BUILD SUCCESS`。
- Remaining live check: 部署平台 Java 服务后刷新任务页，确认 `oldseasoul-app-server` 出现在
  Worker 的 LLM 配置下拉；Runtime 未 Ready 时模型选择/任务提交仍保持禁用或 fail-closed。

## References

- [BUG-018 App Server capability 租约与模型目录](./BUG-018-codex-app-server-capability-lease-and-model-catalog.md)
