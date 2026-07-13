---
type: bug
bug_source: user-report
version: 1.4.0-SNAPSHOT
ticket: BUG-022
severity: critical
status: ready-for-verification
reproduction_status: confirmed
test_strategy: unit-test+local-integration+live-resume-smoke
automation_decision: required
owner: codex-app-server-worker
created_at: 2026-07-13
---

# BUG-022: App Server 意外图片调用导致续接工具丢失

## 问题现象

Codex app-server 的长会话在后续 turn 中可能只向模型暴露图片生成工具，Shell、文件读取和
文件修改能力同时消失。模型随后正常结束 turn，并声明只能调用图片工具；从 app-server 的
协议视角这是一个 completed turn，但从编码任务视角属于能力丢失和错误完成。

## 已确认事实

- 受影响 Thread：`019f5968-138e-7003-8b3a-ae44fa04ef62`。
- 对应 rollout 文件约 28 MiB；第二轮包含 19 个 `image_generation_call`，其 base64 结果约占
  文件内容的 95%，随后模型报告执行工具不可用。
- Worker 已在 `thread/start`/`thread/resume` 配置中写入
  `features.image_generation=false`，但受影响 app-server/自定义 provider 仍产生图片项。
- 该现象与 OpenAI Codex 上游 issue `openai/codex#21952` 描述的自定义 provider 下图片与
  web 工具错误启用一致。当前不能把正常续接会话的工具集合退化解释为 Worker 权限策略。

## 期望与实际

- 期望：禁用图片生成时，续接 turn 只能使用编码任务允许的工具；即使上游意外产生图片项，
  也不能把大段 base64 写入 Worker SSE/Java 会话或把异常 turn 标记为成功。
- 实际：上游忽略禁用配置，图片调用污染 rollout；后续模型工具集合退化，turn 仍 completed。

## 修复边界

### P0：默认编码模式 fail-closed

- 继续强制 `features.image_generation=false`，且请求级 `codex_config` 不能覆盖 Worker 的最终值。
- 如果 `item/started` 或 `item/completed` 仍出现 `imageGeneration`：
  - 返回稳定错误 `APP_SERVER_UNEXPECTED_IMAGE_GENERATION`；
  - 中断当前精确 Thread/Turn；
  - 不向 SSE 转发图片项或 base64；
  - 淘汰该 app-server 进程；
  - 禁止对这类不可信执行结果做恢复期成功对账。

### P1：显式本地图片模式

- 默认关闭，仅 `CODEX_APP_SERVER_IMAGE_GENERATION_MODE=local` 显式开启。
- 只接受有正确文件签名的 PNG/JPEG/WebP/GIF，默认上限 16 MiB，硬上限 25 MiB。
- 文件按任务隔离，目录权限 `0700`、文件权限 `0600`；SSE 只携带 artifact 元数据，不携带
  base64。
- Worker 通过鉴权且绑定预期实例的任务路由读取图片；Java 使用任务所属的精确 runtime
  代理字节并生成同源 URL，不向浏览器暴露 Worker token 或 WSL 本地路径。
- 删除 terminal task/tombstone 时同步清理图片。

实验模式只解决安全落盘与交付，不代表上游续接工具丢失已经修复，也不应作为 Ultra 编码
会话的默认模式。

## 代码清单

- `tools/codex-app-server-worker/src/app-server/runtime.ts`
- `tools/codex-app-server-worker/src/app-server/event-bridge.ts`
- `tools/codex-app-server-worker/src/generated-image-store.ts`
- `tools/codex-app-server-worker/src/routes/tasks.ts`
- `tools/codex-app-server-worker/src/config.ts`
- `tools/codex-app-server-worker/src/task-manager.ts`
- `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/client/CodexWorkerClient.java`
- `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/controller/CodexTaskController.java`
- `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexStreamRelay.java`

## 回归清单

- [x] 禁用模式收到图片 started/completed 时失败并中断精确 turn。
- [x] 异常图片事件不会进入 Worker event journal/SSE。
- [x] 异常 app-server 实例被关闭，不再复用。
- [x] 请求级配置不能重新开启默认禁用的图片能力。
- [x] 本地模式将图片落盘，事件不含 base64，文件权限受限。
- [x] Worker 图片路由要求鉴权和预期实例证明。
- [x] Java 代理使用任务绑定的精确 app-server runtime，并移除 `local_path`。
- [x] terminal task 删除时清理图片文件。
- [x] 更新本机 Worker 后执行“新会话首轮 + 中止 + 同 Thread 继续 + 进程轮换后继续”实测。
- [x] Codex CLI 升级到 `0.144.3` 后复查上游 issue；issue 仍为 open，继续保留兼容保护。
- [ ] 上游官方修复后重新执行图片与 resume 回归，并评估删除兼容保护。

## Codex CLI 0.144.3 本机实测

2026-07-13 将本机 `/home/sa/.codex-app-server-worker` 更新到 Worker `0.3.15`、Codex CLI
`0.144.3`，默认图片模式保持 `disabled`。发布包验证结果为 `270` tests、`269` pass、
`1` Windows-only skip、`0` fail；schema、typecheck、build 均通过。

普通 Ultra resume smoke 使用 Thread `019f5aba-4ee9-7d21-a45d-6744c77fb2af`：

- 新会话首轮真实产生 `tool_use: command_execution`，读取 HEAD `8db49438f2a8`；
- 同 Thread 的 `sleep 60` 回合在观察到命令工具后精确中止，结果为 `TASK_ABORTED`；
- 中止后同 Thread 继续，仍真实产生 `command_execution` 并读取相同 HEAD；
- 清空 app-server pool 并重启 Worker 后，同 Thread 在新的 app-server 实例
  `702b9413-65ca-4205-8114-783999261d87` 上继续成功；轮换前实例为
  `4669b2fb-2ae6-4973-8b45-ea17446390e6`；
- 上述 SSE 均没有图片事件或 base64。

显式图片请求的补充探测使用 Thread `019f5abe-3699-7ce3-90a0-cab71f5e1f89`：

- app-server 没有产生图片工具事件或 base64，但模型仅以文本声称图片已完成，实际没有可读取图片；
- 紧随其后的一次 Ultra resume 在 `execution_committed` 后约 3 分钟无事件，已精确中止；
- 同 Thread 随后以 low effort 重试，真实 `command_execution` 成功并读取相同 HEAD，未形成持续性
  Thread 污染；
- 上游 issue `openai/codex#21952` 截至本次验证仍为 open，因此不能删除默认禁用和
  fail-closed 保护。

## 验收门槛

在上游正式修复前，默认模式必须保持图片生成关闭和 fail-closed。只有本机 live resume smoke
证明普通续接仍具备 Shell/文件工具，才能把本 BUG 从 `ready-for-verification` 转为已验收；
实验图片模式的成功测试不能替代该续接验收。
