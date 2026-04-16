---
type: bug
bug_source: user-report
version: 1.0.2-SNAPSHOT
ticket: BUG-002
severity: major
status: fixed
reproduction_status: confirmed
test_strategy: e2e-test
automation_decision: required
owner: navigator-frontend
---

# BUG Work Item

## Background

在桌面端 `Workers` 页面体验“转发会话”能力时，用户反馈转发链路存在两个连续问题：

1. 新建会话后，立即对刚生成的 assistant 消息执行转发，会出现 `Source message not found` 和 `Request failed with status code 400`。
2. 刷新页面后再次转发，不再报 `Source message not found`，但源会话与目标会话之间仍没有形成可感知的关联展示。

从现象看，第一阶段属于“前端已经拿到了可点击的消息，但后端消息持久化/可查询状态还没准备好”；第二阶段说明转发提交成功后，历史会话区没有展示预期中的父子/上下游关系标识，因此体验上等同于“转发成功了，但系统没有把两个会话关联起来”。

这个问题虽然源自 `1.0.1-SNAPSHOT` 期间交付的会话转发能力，但当前是作为 `1.0.2-SNAPSHOT` 体验阶段发现的缺陷，因此应记录在当前版本目录，而不是回填旧版本目录。

## Reproduction

1. 打开桌面端 `Workers` 页面。
2. 进入任一存在历史会话的工作目录。
3. 新建一个会话并等待 assistant 回复出现在主会话区。
4. 不刷新页面，立即点击该 assistant 回复下方的“转发”。
5. 选择“创建新会话”并提交。
6. 刷新页面后，重新进入同一源会话，再次对同一条 assistant 回复执行转发。
7. 完成转发并创建新会话，或转发到已有会话。
8. 观察右侧“历史会话”列表中的源会话与目标会话。

实际现象：

- 第一次即时转发失败，页面顶部提示 `Source message not found: <messageId>`，同时出现 `Request failed with status code 400`。
- 刷新页面后再转发，该 400 错误消失。
- 目标会话出现了，但没有明确的父子/上下游关联提示，用户无法直观看出它来自哪条源会话。
- 从用户视角看，会话之间像是两个独立会话，转发链路没有被建立或没有被展示出来。

## Expected vs Actual

Expected:

- 新建会话后，只有已经持久化、可被后端转发接口识别的 assistant 消息才能触发转发。
- 如果消息仍处于前端临时态或后端尚未落库，应禁用转发入口或给出“消息同步中，请稍后”的明确提示，而不是提交后 400。
- 转发完成后，源会话与目标会话应在历史会话区形成明确关联。
- 至少应满足以下任一可见性要求：
  - 父会话显示“子会话 N”入口；
  - 子会话显示“上游”或“来自 xxx”入口；
  - 点击后可在关联会话之间跳转。

Actual:

- 新建会话后立即转发，会用前端当前消息 ID 调用后端，但后端查不到对应 `sourceMessageId`，返回 400。
- 刷新页面后，消息从 DB 重新加载，转发不再触发 `Source message not found`。
- 转发后的目标会话缺少明确的关联展示。
- 用户无法从历史会话列表中快速判断该会话是否由转发产生，以及它关联到哪一个源会话。

## Impact Scope

- 桌面端 `Workers` 页面历史会话区
- 新建会话后立即转发的高频操作路径
- assistant 消息前端临时 ID 与后端持久化 ID 的一致性
- 会话转发后的关系可见性
- 用户对“转发成功且已建立关联”的心智确认
- 后续继续转发、回溯来源、理解子会话链路的操作体验

这是一个明显的主链路缺陷。第一阶段会直接阻断即时转发；第二阶段不一定意味着后端关系数据没有落库，但如果前端未正确展示，用户仍会把它理解为“没有建立关联”。

## Current Assessment

结合现有代码，当前问题应拆成两个子问题判断。

### BUG A: 新建会话后立即转发 400

- 前端 [`handlePaneForward(...)`](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/packages/navigator-frontend/src/views/ClaudeWorkerView.vue) 直接把当前聊天气泡的 `message.id` 作为 `sourceMessageId` 传给转发弹窗。
- 前端 [`useTaskPane.ts`](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/packages/navigator-frontend/src/composables/useTaskPane.ts) 在实时 SSE 阶段通过 `tutorAgentAdapter` 把 `raw.messageId` 转成聊天消息 ID。
- 后端 [`SessionForwardService.java`](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/session-module/src/main/java/com/foggy/navigator/session/service/SessionForwardService.java) 使用 `sessionMessageRepository.findById(request.getSourceMessageId())` 严格查源消息，查不到就抛出 `Source message not found`。
- 后端 [`SessionEventListener.java`](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/session-module/src/main/java/com/foggy/navigator/session/event/SessionEventListener.java) 使用 `@Async("sessionEventExecutor")` 异步持久化 Agent 消息，实时 SSE 到达前端与 DB 消息落库之间可能存在时间差。

当前判断：

- 这更像是“实时消息可见”和“消息持久化可转发”之间存在竞态。
- 刷新后可转发，说明 DB 里最终有了可识别的消息，或者刷新加载后前端使用的是 DB 消息 ID。
- 修复方向不应只是吞掉 400，而应统一“可转发消息必须具备后端可识别的稳定消息 ID”的语义。

### BUG B: 转发成功后关联不可见

- 后端 [`SessionForwardService.java`](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/session-module/src/main/java/com/foggy/navigator/session/service/SessionForwardService.java) 在 `NEW_SESSION` 路径会写入 `parentSessionId`，同时持久化 `FORWARD` 关系记录。
- 后端 [`SessionForwardService.java`](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/session-module/src/main/java/com/foggy/navigator/session/service/SessionForwardService.java) 在 `NEW_SESSION` 路径会写入 `parentSessionId`，同时持久化 `FORWARD` 关系记录。
- 前端 [`ClaudeWorkerView.vue`](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/packages/navigator-frontend/src/views/ClaudeWorkerView.vue) 当前历史会话关系展示主要依赖 `parentSessionId` 计算 `parentConversation / childConversations`。
- 前端 [`useForwardSession.ts`](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/packages/navigator-frontend/src/composables/useForwardSession.ts) 在“转发到已有会话”场景下只从当前会话池中过滤 `parentSessionId === sourceSessionId` 的目标会话，这意味着关系可见性与可选目标范围都偏向“父子会话视图”，没有真正消费 `session_relations`。

当前判断：

- 如果用户走的是“转发为新会话”，需要重点确认 `parentSessionId` 是否真正回到了历史会话列表的数据源里。
- 如果用户走的是“转发到已有会话”，则更可能是后端有关系表、前端却没有用关系表做展示，导致用户感知为“没有建立关联”。

以上判断是基于代码的初步推断，仍需结合一次真实复现确认到底是“消息 ID 竞态”“关系没落库”还是“关系已落库但 UI 没展示”。

## Test Strategy

本问题默认要求补自动化验证，优先 `e2e-test`：

- 这是典型的前端交互与关系可见性问题，最适合通过浏览器端回归验证。
- 如后端最终确认存在关系持久化遗漏，再补对应服务层测试。

建议验证覆盖：

1. 新建会话后立即点击转发，不应出现 `Source message not found` 这种后端 400。
2. 如果消息尚未达到可转发状态，前端应禁用转发或提示“消息同步中”。
3. 刷新前后同一条 assistant 消息的转发行为应一致。
4. 转发为新会话后，历史会话区应立即出现父子关系标识。
5. 点击父会话“子会话”入口，应能看到新转发出的目标会话。
6. 子会话卡片应能展示“上游”入口，且可跳回源会话。
7. 若支持“转发到已有会话”，也应明确展示关联，而不是仅在数据库内存在关系。

原始验证点：

1. 转发为新会话后，历史会话区应立即出现父子关系标识。
2. 点击父会话“子会话”入口，应能看到新转发出的目标会话。
3. 子会话卡片应能展示“上游”入口，且可跳回源会话。
4. 若支持“转发到已有会话”，也应明确展示关联，而不是仅在数据库内存在关系。

## Code Inventory

- [SessionForwardService.java](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/session-module/src/main/java/com/foggy/navigator/session/service/SessionForwardService.java)
- [SessionEventListener.java](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/session-module/src/main/java/com/foggy/navigator/session/event/SessionEventListener.java)
- [JpaSessionManager.java](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/session-module/src/main/java/com/foggy/navigator/session/service/JpaSessionManager.java)
- [SessionRelationEntity.java](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/navigator-common/src/main/java/com/foggy/navigator/common/entity/SessionRelationEntity.java)
- [SessionRelationRepository.java](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/session-module/src/main/java/com/foggy/navigator/session/repository/SessionRelationRepository.java)
- [ClaudeWorkerView.vue](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/packages/navigator-frontend/src/views/ClaudeWorkerView.vue)
- [useTaskPane.ts](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/packages/navigator-frontend/src/composables/useTaskPane.ts)
- [useForwardSession.ts](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/packages/navigator-frontend/src/composables/useForwardSession.ts)
- [TutorAgentAdapter.ts](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/packages/navigator-frontend/src/adapters/TutorAgentAdapter.ts)
- [30-session-forward-v1-design.md](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/docs/requirement-tracker/2026-Q2/30-session-forward-v1-design.md)

## Fix Checklist

- 先复现并确认问题路径是“新建后立即转发 400”“转发为新会话关联不可见”还是“转发到已有会话关联不可见”。
- 确认即时聊天气泡里的 `message.id` 是否等于 `session_messages.id`。
- 确认 `SessionEventListener` 异步落库完成前，前端是否已经允许转发。
- 确认刷新页面后使用的 DB 消息 ID 与即时 SSE 消息 ID 是否一致。
- 明确可转发入口语义：只允许后端已持久化的 assistant 消息转发，或由后端支持按临时消息 ID/内容兜底定位。
- 校验后端是否已正确写入 `parentSessionId` 与 `session_relations`。
- 校验历史会话接口返回的 `parentSessionId` 是否被前端正确消费。
- 若关系已落库但 UI 未展示，补齐前端关系渲染逻辑。
- 若“已有会话转发”只写关系表、不更新父子展示语义，需要明确 V1/V2 的可见性规则并统一实现。
- 补自动化回归，防止转发链路后续再次退化。

## Verification

手工验证应覆盖：

1. 新建会话并等待 assistant 回复出现，不刷新页面，立即执行“转发为新会话”。
2. 页面不应出现 `Source message not found` 或 `Request failed with status code 400`。
3. 转发成功后，检查源会话是否出现“子会话”标识。
4. 检查目标会话是否出现“上游”标识，并能跳回源会话。
5. 刷新页面后，对同一源消息再次转发，行为应与刷新前一致。
6. 若支持“转发到已有会话”，验证追加后仍保留清晰关联提示。

自动化验证应覆盖：

1. 新建会话并等待 assistant 回复完成。
2. 不刷新页面，从消息气泡触发转发。
3. 提交后断言没有 400 错误提示。
4. 等待目标会话出现在历史列表。
5. 断言父子/上下游关系入口存在。
6. 断言点击关系入口可以在关联会话之间跳转。

## References

- 用户截图证据 A：本轮对话中的 `Workers` 页面截图，红线标注了转发后的目标会话，但界面未形成清晰关联感知。
- 用户截图证据 B：本轮对话中的 `Workers` 页面截图，显示新建会话后立即转发时报 `Source message not found: 5aae7fa0-4139-4898-89dc-6fefc61ff` 和 `Request failed with status code 400`。
- 历史设计基线：
  - [30-session-forward-v1-design.md](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/docs/requirement-tracker/2026-Q2/30-session-forward-v1-design.md)

## Fix Record

- fix_date: 2026-04-15
- fixed_by: Claude

### BUG A 修复：消息 ID 不一致导致即时转发 400

**根因**：`SessionEventListener.toSessionMessage()` 未将 `AgentMessage.messageId` 传递给 `Message.builder()`，导致 `JpaSessionManager.addMessage()` 生成新 UUID 存入 DB，与 SSE 传给前端的 messageId 不一致。

**修复**：在 `toSessionMessage()` 的 `Message.builder()` 中增加 `.id(msg.getMessageId())`，确保 SSE messageId 与 DB messageId 一致。

**修改文件**：`session-module/.../event/SessionEventListener.java`（1 行）

### BUG B 修复：转发响应缺少 parentSessionId

**根因**：`TaskDispatchFacade.toDispatchDTO(A2aTask, ...)` 是转发创建任务的 DTO 构建路径，该方法未设置 `parentSessionId`，导致前端转发 API 响应中 `parentSessionId` 为 null，关联标签无法在任务列表重载前显示。

**修复**：在 `toDispatchDTO()` 方法末尾增加从 `SessionEntity` 补充 `parentSessionId` 的逻辑。

**修改文件**：`session-module/.../service/TaskDispatchFacade.java`（6 行）
