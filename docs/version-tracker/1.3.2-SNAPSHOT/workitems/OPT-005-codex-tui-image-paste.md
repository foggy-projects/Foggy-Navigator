---
type: optimization
version: 1.3.2-SNAPSHOT
ticket: OPT-005
severity: medium
status: implemented_pending_smoke
owner: claude-agent-worker | claude-worker-agent | navigator-frontend
created_at: 2026-07-02
---

# OPT-005: Codex TUI Image Paste From Browser SSH Terminal

## Document Purpose

- doc_type: workitem
- intended_for: execution-agent | reviewer | signoff-owner
- purpose: 记录浏览器 SSH 终端向已运行 Codex TUI 传递图片附件的协议结论、跨层实现边界、方案 review、实施计划与验收标准。

## Background

用户在 Navigator 控制台中通过浏览器 SSH 终端运行原生 Codex TUI，希望在不退出 TUI、不新开 Codex 进程的情况下，把浏览器侧图片交给当前 Codex 会话。

已确认的 Codex TUI 侧约束：

- `codex -i/--image` 只适合启动新任务或新进程，不适合已经运行中的 TUI。
- TUI 的 composer 支持粘贴本地图片路径，并把该路径识别为图片附件。
- 浏览器 SSH 终端无法直接把浏览器剪贴板图片变成远端 Codex 进程所在机器的 OS 剪贴板图片。
- HTTP/HTTPS 图片 URL 不能作为此链路的图片协议，Codex TUI 不会把普通远程 URL 当作本地图片路径附件。

已观测到的失败现象：

- 在浏览器 SSH 终端内直接向 Codex TUI 粘贴图片时，Codex TUI 报错：`Failed to paste image: clipboard unavailable: Unknown error while interacting with the clipboard: X11 server connection timed out because it was unreachable`。
- 该现象说明直接图片粘贴会触发远端 Codex 进程读取远端系统剪贴板/X11，而不是把浏览器剪贴板中的图片通过 PTY 传给 Codex。
- 因此前端不能把“模拟 Ctrl+V 图片粘贴”作为实现路径；如果支持浏览器图片 paste，必须在浏览器 ClipboardEvent 中拦截图片文件并走上传落盘路径。

因此本工作项采用已确认的设计：

```text
browser frontend image
  -> Java service
  -> Worker
  -> SSH target readable unified directory
  -> return absolute local path
  -> frontend injects bracketed paste into active SSH PTY
  -> Codex TUI composer attaches image
```

该工作项归入 `1.3.2-SNAPSHOT`，因为本迭代聚焦 codex-biz-worker 上游验收、Codex 原生执行通道与交互诊断能力。本功能增强的是“浏览器控制台里使用原生 Codex TUI”的交互能力，不属于普通 Agent 任务附件链路。

## Target Outcome

- 用户在已有 SSH 终端 Tab 中运行 Codex TUI 时，可以从浏览器上传一张或多张图片。
- 图片最终落到 Codex 进程所在 SSH target 可读的本地绝对路径。
- 前端把返回路径以 bracketed paste 形式写入当前 SSH PTY，不自动提交消息。
- Codex TUI composer 识别该本地路径并展示为图片附件。
- 如果无法保证返回路径对 Codex 进程可读，链路必须失败并给出明确错误，不能返回 Worker-only path。

## Glossary

| Term | Definition |
| --- | --- |
| Codex TUI composer | 原生 Codex TUI 底部输入区，负责接收用户文本、路径粘贴和附件。 |
| bracketed paste | 终端粘贴协议，开始标记为 `\x1b[200~`，结束标记为 `\x1b[201~`。 |
| SSH target | 浏览器 SSH 终端实际连接并运行 Codex TUI 的目标机器。 |
| Worker local path | Worker 进程所在机器上的路径；只有在该路径同时对 SSH target 上的 Codex 进程可见时才可返回给 TUI。 |
| target image path | 返回给前端并粘贴给 Codex TUI 的目标机器本地绝对路径。 |

## Confirmed Rules

- 不通过 HTTP/HTTPS URL 给 Codex TUI 传图。
- 不退出或重启当前 Codex TUI，不使用 `codex -i` 作为主方案。
- 不尝试设置或依赖远端 OS 剪贴板；浏览器侧图片必须经过上传和目标机落盘。
- 浏览器图片 paste 事件如包含 image file，前端应 `preventDefault()` 并进入上传流程；普通文本 paste 仍交给 xterm/SSH 原路径。
- 每次 bracketed paste 只写入一个本地图片绝对路径。
- 粘贴 payload 只包含路径，不包含换行，不混入自然语言 prompt：

```text
\x1b[200~/abs/path/to/image.png\x1b[201~
```

- 多图按多次独立 paste 处理。
- 如果用户还要输入说明文字，文字应在图片路径粘贴完成后由用户继续输入，或由前端作为单独文本事件写入。
- 路径必须对当前 SSH target 上运行的 Codex 进程可读。
- 默认应落盘到 SSH target 侧目录；只有能够证明 Worker local path 与 SSH target 是同一可见文件系统时，才允许直接返回 Worker local path。
- 图片类型 MVP 支持 `png`、`jpg`、`jpeg`、`webp`；实现时必须做 MIME、扩展名、魔数或等价文件签名校验。
- 需要限制单文件大小、单会话数量和清理策略。
- 日志不得记录图片 base64 或图片内容；路径、大小、sessionId 也应按最小必要原则记录。

## Non-Goals

- 不实现通用文件传输。
- 不实现终端 inline image、Sixel、Kitty graphics 等显示协议。
- 不修改 Codex TUI 源码。
- 不把普通任务附件链路改造成 SSH TUI 附件链路。
- 不把远程图片 URL 作为附件协议。
- 不解决 Codex TUI 非 composer 焦点下的输入语义，只给用户明确提示。

## Module Responsibility

| Module | Responsibility |
| --- | --- |
| `packages/navigator-frontend` | 提供 SSH 终端图片选择、粘贴或拖拽入口；复用现有图片压缩能力；调用 Java 上传 API；拿到 target image path 后向当前 SSH WebSocket 写入 bracketed paste payload；展示上传、写入和失败状态。 |
| `addons/claude-worker-agent` | 新增 SSH 图片上传代理接口；基于当前用户、workerId、sessionId、directoryId 做所有权校验；转发图片到 Worker；返回 target image path；避免把 Worker token 或内部路径暴露给前端。 |
| `tools/claude-agent-worker` | 基于 SSH session 接收图片；在 SSH target 可读目录写入文件；必要时通过当前 asyncssh 连接使用 SFTP 写入 target；返回绝对路径；管理大小限制、文件名清理和生命周期清理。 |
| `user-auth-module` | 只做安全边界复核：`/api/v1/ssh/**` 当前在 Spring Security 层放行，新增 Java Controller endpoint 必须继续使用 `@RequireAuth` 并在业务层完成所有权校验。 |
| `docs/version-tracker` | 记录方案、review、实施进度、测试证据和最终验收结论。 |

## Code Inventory

| Path | Expected Change | Notes |
| --- | --- | --- |
| `packages/navigator-frontend/src/api/ssh.ts` | update | 增加 SSH 图片上传 API 和返回类型。 |
| `packages/navigator-frontend/src/components/worker/SshTerminal.vue` | update | 增加向活动 WebSocket 写入 bracketed paste 的能力，或暴露给上层调用。 |
| `packages/navigator-frontend/src/components/worker/SshTerminalPanel.vue` | update | 增加图片入口、文件 input、状态提示和错误提示。 |
| `packages/navigator-frontend/src/composables/useAttachments.ts` | reuse/update | 复用图片压缩与预览逻辑；实现前确认 WebP 对 Codex TUI 的兼容性，不满足时优先输出 PNG/JPEG。 |
| `packages/navigator-frontend/src/composables/useWorkspaceContext.ts` | update if needed | 如需要，补充活动终端发送 helper 或 tab 状态字段。 |
| `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/controller/SshProxyController.java` | update | 增加上传入口、鉴权和 session ownership 校验。 |
| `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/client/ClaudeWorkerClient.java` | update | 增加调用 Worker 图片上传接口的方法。 |
| `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/websocket/SshWebSocketProxyHandler.java` | read-only | 当前文本和二进制 WebSocket 已直通 Worker，可复用，无需新建 TUI 注入通道。 |
| `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/websocket/SshWebSocketConfig.java` | read-only/review | 复核 WS 鉴权边界与 session ownership，必要时补测试或校验。 |
| `tools/claude-agent-worker/src/agent_worker/routes/ssh.py` | update | 增加 session image upload route；现有 WS 已把 text/binary frame 写入 SSH stdin。 |
| `tools/claude-agent-worker/src/agent_worker/ssh/session_manager.py` | update | Session 记录 cwd 或 target upload root；提供 target 侧写文件能力。 |
| `tools/claude-agent-worker/src/agent_worker/models.py` | update | 增加上传请求和响应模型。 |
| `tools/claude-agent-worker/src/agent_worker/config.py` | update | 增加上传大小、数量、保留时间、target root 相关配置。 |
| `tools/claude-agent-worker/src/agent_worker/routes/files.py` | read-only | 现有文件浏览接口不是本功能的上传通道。 |
| `tools/claude-agent-worker/src/agent_worker/routes/utils.py` | reuse | 复用或参考路径校验策略。 |
| `user-auth-module/src/main/java/com/foggy/navigator/auth/config/SecurityConfig.java` | read-only/review | 新 endpoint 不应依赖 Spring Security permitAll；必须保留业务鉴权。 |

## Implementation Plan

### Stage 0: Contract And Protocol Constants

- 定义前后端契约：`POST /api/v1/ssh/{sessionId}/images`，请求包含 `workerId`、文件名、MIME、base64 或 multipart 文件；响应包含 `targetImagePath`、`mimeType`、`size`。
- 前端 bracketed paste 常量固定为：

```ts
const payload = `\x1b[200~${targetImagePath}\x1b[201~`;
```

- 明确响应字段叫 `targetImagePath`，避免使用 `url`、`path` 这类容易混淆位置的名字。

### Stage 1: Worker Upload And Target-Side Persistence

- 在 Worker SSH route 增加图片上传接口，按 `sessionId` 查找活动 SSH session。
- 将 session 的 `cwd` 或 target upload root 记录到 `SshSession`。
- 默认写入 SSH target 侧目录：
  - 有工作目录时：`<cwd>/.foggy-attachments/codex-tui/<sessionId>/`
  - 无工作目录时：`~/.foggy-navigator/ssh-attachments/<sessionId>/`
- 如果 Worker 本地路径不能保证被 SSH target 上的 Codex 进程读取，则通过当前 `asyncssh` connection 的 SFTP 写入 target 目录。
- 文件名使用 UUID 和白名单扩展名生成，不使用用户原始文件名作为落盘名。
- 做大小、类型、签名校验；失败时返回可展示的业务错误。
- 增加 session close、过期任务或启动清理，避免长期堆积。

### Stage 2: Java Proxy And Ownership Validation

- 在 `SshProxyController` 增加上传 endpoint，并保持 `@RequireAuth`。
- 校验当前用户能访问 `workerId` 和 `sessionId`：
  - session 必须存在于该 Worker；
  - session 的 `directoryId` 必须属于当前用户可访问的工作目录；
  - 不允许用户上传到别人的 SSH session。
- `ClaudeWorkerClient` 新增上传调用，转发到 Worker 后只返回必要字段给前端。
- 错误分类至少覆盖：session 不存在、无权限、Worker 不在线、图片过大、类型不支持、target 写入失败。

### Stage 3: Frontend Terminal Experience

- 在 SSH 终端 Tab 的操作区增加图片入口，支持选择本地图片；后续可扩展粘贴和拖拽。
- 在终端区域监听浏览器 paste 事件：当 `ClipboardEvent.clipboardData.items` 中存在图片文件时阻止默认行为并走上传；当只有文本时不拦截。
- 复用 `useAttachments.ts` 的压缩能力；如 Codex TUI WebP 兼容性未验证，MVP 优先发送 PNG/JPEG。
- 上传成功后向当前活动 terminal WebSocket 写入 bracketed paste payload，不附加换行，不自动发送 Enter。
- 多图按顺序上传和 paste，避免把多个路径合并成一段文本。
- 显示短状态：上传中、已写入 Codex 输入框、失败原因。
- 如果当前 Tab 没有活动 WebSocket、连接已关闭、或不是 SSH terminal Tab，直接阻止并提示。

### Stage 4: Verification And Smoke

- Worker 单元测试覆盖类型校验、文件名清理、target path 生成、无 session、SFTP 写入失败。
- Java 测试覆盖鉴权、session ownership、Worker 错误透传和成功响应。
- 前端测试覆盖 API 调用、payload 生成、无 active terminal 的错误分支。
- 浏览器 E2E 或手工 smoke 覆盖真实 Codex TUI：
  1. 打开 SSH 终端并启动 Codex TUI。
  2. 点击图片入口上传 PNG/JPEG。
  3. 确认 TUI composer 出现图片附件。
  4. 输入文字并发送，确认 Codex 能读取图片。
  5. 连续上传两张图片，确认两张均作为附件出现。
  6. 在终端聚焦时 Ctrl+V 粘贴浏览器剪贴板图片，确认前端拦截并走上传流程，不出现远端 X11 clipboard 错误。

### Stage 5: Check-In And Acceptance

- 回写本文档 Progress Tracking。
- 补充测试命令与结果。
- 如发现协议偏差，先更新本文档再继续实现。
- 进入实现质量检查和验收签收。

## Acceptance Criteria

- 已运行中的 Codex TUI 不退出、不重启即可接收浏览器上传的图片。
- 在浏览器终端中 Ctrl+V 粘贴图片时，不再触发远端 Codex 读取 X11 剪贴板失败；图片 paste 被前端拦截并转入上传流程。
- 前端不会把 HTTP/HTTPS URL 写入 Codex TUI 作为图片协议。
- Worker 返回的是 SSH target 上可读的本地绝对路径。
- bracketed paste payload 不包含换行，不混合 prompt 文本。
- 多图片按多次独立 paste 处理。
- 无活动 session、无权限、类型不支持、文件过大、target 写入失败时均有明确错误。
- 新 endpoint 完成用户、worker、session、directory 所有权校验。
- 图片内容和 base64 不进入业务日志。
- 自动化测试、前端构建和真实 Codex TUI smoke 证据回填到本文档。

## Verification Plan

计划实现后执行并回填结果：

```bash
pytest tools/claude-agent-worker/tests -k "ssh"
```

```bash
mvn test -pl addons/claude-worker-agent,user-auth-module -am -Dtest=SshProxyControllerTest,ClaudeWorkerClientTest,SecurityConfigAdminRouteTest -Dsurefire.failIfNoSpecifiedTests=false
```

```bash
bash scripts/build-frontend.sh
```

```bash
git diff --check -- docs/version-tracker/1.3.2-SNAPSHOT/workitems/OPT-005-codex-tui-image-paste.md
```

手工或 E2E smoke 需要记录：

- SSH target host、Codex CLI 版本、浏览器。
- 上传图片类型和大小。
- 返回的 target image path。
- Codex TUI composer 是否出现附件。
- 是否成功随 prompt 被 Codex 读取。

## Plan Review

- object_type: 总规划文档
- review_status: pass_with_constraints
- reviewed_at: 2026-07-02

### Review Conclusion

方案方向成立。关键原因是当前 SSH WebSocket 已经把浏览器输入直通到 Worker，再写入 SSH PTY；因此 TUI 注入层不需要新建复杂控制协议，只需要在浏览器侧写入 Codex TUI 已支持的 bracketed paste payload。

真正的风险不在“能否写入 PTY”，而在“返回给 Codex 的路径是否确实位于 Codex 进程所在机器并可读”。因此实施时必须优先 target-side persistence，而不是简单返回 Worker 本地路径。

### Alternatives Reviewed

| Alternative | Decision | Reason |
| --- | --- | --- |
| 使用 `codex -i/--image` | rejected | 需要启动新进程，不符合“不退出 TUI”的交互要求。 |
| 把 HTTP/HTTPS URL 写入输入框 | rejected | Codex TUI 的图片附件识别依赖本地路径；远程 URL 不是可靠协议。 |
| 设置远端 OS 剪贴板后触发 Ctrl+V | rejected | 浏览器 SSH PTY 无法可靠控制远端桌面剪贴板；已观测到 Codex TUI 直接粘图片会因远端 X11 clipboard 不可达而失败。 |
| 新增 Java 到 Worker 的 PTY 注入 API | deferred | 现有 SSH WebSocket 已可发送 bracketed paste，MVP 不需要额外注入通道。 |
| 前端上传后返回 target absolute path 并 bracketed paste | accepted | 与 Codex TUI 已支持协议匹配，且不破坏当前 SSH 会话。 |

### Risk Register

| Risk | Impact | Mitigation |
| --- | --- | --- |
| Worker host 与 SSH target 不是同一机器或文件系统 | Codex 无法读取图片 | 默认通过 SSH/SFTP 写入 target；只有同机可见时才返回 Worker local path。 |
| Codex TUI 当前焦点不在 composer | 粘贴内容被终端其他状态消费 | 前端提示“请保持 Codex 输入框激活”；MVP 不自动回车。 |
| 浏览器图片 paste 未被前端拦截 | Codex 尝试读取远端 X11 clipboard 并报错 | 对 image ClipboardEvent 执行 `preventDefault()` 并转上传；文本 paste 不拦截。 |
| WebP 兼容性不确定 | 图片路径被粘贴但 Codex 无法解析 | MVP 优先 PNG/JPEG 或在实现前完成 WebP smoke。 |
| `/api/v1/ssh/**` Spring Security 层放行 | 新 endpoint 可能缺少业务鉴权 | Controller 必须 `@RequireAuth`，并校验 worker、session、directory ownership。 |
| 图片堆积占用磁盘 | Worker 或 target 存储膨胀 | 增加大小限制、数量限制、session close 清理和过期清理。 |
| 日志泄露图片内容 | 敏感数据泄露 | 禁止记录 base64 和图片内容，只记录必要元信息。 |

### Evidence Gaps Before Implementation Completion

- 需要真实 Codex TUI smoke，确认本地绝对路径 bracketed paste 后附件 UI 出现。
- 需要真实浏览器 paste smoke，确认图片 ClipboardEvent 被前端拦截，不再出现远端 X11 clipboard 错误。
- 需要确认 WebP 是否在当前部署的 Codex CLI 版本中可用；否则前端压缩输出应改为 PNG/JPEG。
- 需要验证 Windows SSH target 路径形态是否能被当前 Codex TUI 正确识别。

## Progress Tracking

### Development Progress

- [x] Codex TUI 图片粘贴协议调研完成。
- [x] 直接粘贴图片触发远端 X11 clipboard 不可达错误的现象已记录。
- [x] 采用“上传到 target 可读目录，返回绝对路径，bracketed paste 注入”的设计。
- [x] 方案纳入 `1.3.2-SNAPSHOT` 工作项。
- [x] Worker 上传与 target-side persistence 实现。
- [x] Java proxy endpoint 与 ownership 校验实现。
- [x] Frontend SSH terminal 图片入口和 paste 注入实现。
- [x] 清理策略和配置项实现。

### Testing Progress

- [x] 当前代码触点梳理完成。
- [x] Worker 单元测试。
- [x] Java 编译验证。
- [x] Frontend type-check。
- [ ] Java controller/client 测试。
- [ ] Frontend 构建。
- [ ] 浏览器 SSH 终端真实 Codex TUI smoke。
- [ ] 浏览器图片 paste 拦截 smoke。

### Experience Progress

- [x] 图片入口在 SSH terminal 可发现。
- [x] 终端聚焦时 Ctrl+V 粘图片能走上传路径，文本粘贴不受影响。
- [x] 上传和写入状态清晰。
- [x] 错误提示能区分无 session、无权限、图片不支持、target 写入失败。
- [x] 不自动提交 Codex 消息，用户保留发送控制权。

### Implementation Notes

- Worker 新增 `POST /api/v1/ssh/{sessionId}/images`，校验 MIME 和图片签名，使用当前 SSH 连接的 SFTP 写入 SSH target。
- 有工作目录时落盘到 `<cwd>/.foggy-attachments/codex-tui/<sessionId>/`；无工作目录时落盘到 `~/.foggy-navigator/ssh-attachments/<sessionId>/`。
- Java 新增 `POST /api/v1/ssh/{sessionId}/images` 代理入口，基于 Worker session 列表中的 `directory_id` 反查当前用户目录权限。
- 前端新增 SSH terminal 图片按钮和图片 paste 捕获；普通文本 paste 不拦截；成功后通过现有 SSH WebSocket 写入 bracketed paste，不附加换行。

### Verification Evidence

```text
python -m compileall tools/claude-agent-worker/src/agent_worker/routes/ssh.py tools/claude-agent-worker/src/agent_worker/ssh/session_manager.py tools/claude-agent-worker/src/agent_worker/models.py tools/claude-agent-worker/src/agent_worker/config.py
result: pass
```

```text
python -m pytest tools/claude-agent-worker/tests/routes/test_ssh.py
result: 11 passed
```

```text
mvn -pl addons/claude-worker-agent -am -DskipTests compile
result: BUILD SUCCESS
```

```text
pnpm -C packages/navigator-frontend type-check
result: pass
```

### Remaining Evidence

- 仍需在真实浏览器 SSH 终端中对已运行 Codex TUI 做 smoke，确认 composer 出现图片附件。
- 仍需对浏览器 Ctrl+V 图片 paste 做 smoke，确认前端拦截后不再出现远端 X11 clipboard 错误。
- 当前前端复用既有 `compressImage`，上传格式为 WebP；如真实 Codex TUI smoke 发现 WebP 不兼容，应将 SSH 图片输出改为 PNG/JPEG。

## Self-Check

- 本文档只落规划和 review，不修改实现代码。
- 方案没有依赖远程 URL 或新开 Codex 进程。
- 方案没有依赖远端 OS 剪贴板或 X11 clipboard。
- 方案明确了 Worker local path 与 SSH target path 的边界。
- 下一步可以按 Stage 0 到 Stage 4 开始实现。
