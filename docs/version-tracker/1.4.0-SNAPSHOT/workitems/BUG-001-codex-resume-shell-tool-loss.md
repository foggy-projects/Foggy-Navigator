---
type: bug
bug_source: user-report
version: 1.4.0-SNAPSHOT
ticket: BUG-001
severity: major
status: ready-for-deployment
reproduction_status: confirmed
test_strategy: unit-test+local-integration+live-smoke
automation_decision: required
owner: codex-agent-worker
created_at: 2026-07-10
---

# BUG-001: Codex Resume 后 Shell 工具丢失

## 问题现象

同一个 Codex session 的早期回合能够产生 `command_execution`，但经过上下文压缩后使用
`session_id` 续接，模型只识别内建 `image_gen`，不再调用 Shell。Worker 启动参数仍包含：

```text
--sandbox danger-full-access
--config approval_policy="never"
```

因此问题不是操作系统权限或 sandbox 降级。

## 稳定复现证据

- Codex SDK / CLI：`0.144.1`。
- 稳定失败 session：`019f4be3-18bb-73a1-a051-3f851801cc5a`。
- 该 rollout 在早期回合存在 96 次 `exec` 调用，并存在持久化 `compaction` 记录。
- 修复前通过本地源码 Worker `3051` 对同一 session 强制请求 `pwd`，SSE 中没有
  `tool_use: command_execution`，模型直接声称没有 Shell。
- 安全代理截获的实际 Responses Lite 请求仍注册了 `exec`、`wait`、
  `request_user_input` 和 `collaboration`，但注册项是输入开头的
  `additional_tools`；同一输入后面存在持久化 `compaction`。
- 新建 thread 与 resume thread 的 model、cwd、sandbox、approval、network、
  developer instructions 和 thread options 没有权限差异；Worker 也没有
  `allowedTools` 过滤配置。

本地证据目录：

```text
temp/test-artifacts/codex-resume-shell-20260710/
```

## 根因

Codex `0.144.1` 对 `use_responses_lite=true` 的模型不使用 Responses API 顶层
`tools`。CLI 会把当前 custom tools 编码为 `ResponseItem::AdditionalTools`，并插入输入
历史的最前面。resume 时，rollout 中较后的持久化 `compaction` 状态覆盖了前面的
custom tool inventory，导致模型只剩内建工具可见。

这解释了为什么：

- CLI 进程参数、sandbox 和 approval 都正确；
- 代理能看到 `exec` 已注册；
- 普通短 session resume 正常；
- 包含 compaction 的历史 session 稳定失败；
- 禁用 WebSocket、重复 developer instructions 或重新触发 compaction 均无效。

上游 `rust-v0.144.1` 和调查时的 `main` 都仍采用此前置顺序：

- <https://github.com/openai/codex/blob/rust-v0.144.1/codex-rs/core/src/client.rs>

## 修复

只在真实 resume 路径且当前模型缓存标记为 `use_responses_lite=true` 时：

1. 读取 Codex 自己的 `models_cache.json`，保留完整模型元数据；
2. 为当前模型生成任务级临时 catalog，只把 `use_responses_lite` 改为 `false`；
3. 通过官方 `model_catalog_json` 配置启动同一个 resume thread；
4. turn 结束后删除临时 catalog。

标准 Responses 请求会在每次请求的顶层 `tools` 中携带 `exec`，不会被历史中的
compaction 覆盖。修复不改变 model、session_id、prompt、cwd、sandbox、approval、
network policy 或 developer instructions，也不会新建会话或重放 prompt。调用方显式提供
`model_catalog_json` 时保持调用方配置优先。

## 代码清单

- `tools/codex-agent-worker/src/codex/sdk-wrapper.ts`
  - 增加可注入的 Codex/thread factory 和进程探测依赖。
  - 增加 resume model catalog 兼容生成与清理。
- `tools/codex-agent-worker/tests/sdk-wrapper.test.ts`
  - 增加首轮和 resume 都必须产生 Shell 事件的自动化回归。
  - 锁定 start/resume thread options、developer instructions、sandbox、approval 和
    network 配置一致。
- `tools/codex-agent-worker/scripts/resume-shell-integration.mjs`
  - 增加真实 Worker 首轮 + 同 session resume SSE 集成脚本。
- `tools/codex-agent-worker/package.json`
  - 增加 `npm run test:resume-shell`。

## 测试证据

### 失败基线

在实现兼容逻辑前，定向回归测试失败：

```text
SyntaxError: ... sdk-wrapper.ts does not provide an export named
'prepareResumeToolsModelCatalog'
```

该失败测试随后由实现闭环，不是先写修复再补测试。

### 单元测试

```bash
cd tools/codex-agent-worker
npm test
```

结果：`116` tests，`115` pass，`1` platform-specific skip，`0` fail。

新增回归覆盖：

- first turn 产生 `tool_use: command_execution`；
- resume turn 仍产生 `tool_use: command_execution`；
- start/resume 使用相同 thread options；
- resume catalog 仅关闭当前模型 Responses Lite，并保留其余元数据；
- 临时 catalog 在 turn 完成后删除。

### 类型检查与构建

```bash
npm run typecheck
npm run build
```

结果：均通过。

### 真实两轮集成验证

```bash
npm run test:resume-shell
```

新 session `019f4cd8-5f91-7320-b2c8-678977ba769d`：

- first turn：真实执行 `pwd`；
- 使用相同 session_id resume：再次真实执行 `pwd`；
- 两轮输出均为 `/home/sa/workspace/Foggy-Navigator`。

### 历史 compaction session 验证

修复后再次续接稳定失败 session `019f4be3-18bb-73a1-a051-3f851801cc5a`：

- session_id 未变化；
- SSE 产生 `tool_use: command_execution`；
- `tool_result.is_error=false`；
- `pwd` 输出为 `/home/sa/workspace/Foggy-Navigator`。

代理同时确认标准 Responses 请求的顶层 `tools` 包含 `exec`。

## 发布与运行要求

源码 Worker `3051` 已使用项目 `start.sh` 重启并完成验证。当前正在运行的
`/home/sa/.codex-worker` 仍是旧发布目录，未被停止或覆盖；要让其获得修复，需要重新打包、
部署并重启该实例。

本地验证清理时还发现 `tools/codex-agent-worker/stop.sh` 原先使用
`lsof -ti :$PORT`，会把其他监听端口的网络进程一并选中。已改为只查询目标 TCP
监听端口，避免重启 Worker 时误伤 Java 或其他 Worker；该修正不扩大权限，也不改变
session resume 逻辑。
