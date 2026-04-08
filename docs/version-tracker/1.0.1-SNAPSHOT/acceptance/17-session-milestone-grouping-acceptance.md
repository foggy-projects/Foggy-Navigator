---
acceptance_scope: feature
version: 1.0.1-SNAPSHOT
target: 17-session-milestone-grouping
status: signed-off
decision: accepted
signed_off_by: engineering-signoff
signed_off_at: 2026-04-08
reviewed_by: N/A
blocking_items: []
follow_up_required: no
evidence_count: 11
---

# Feature Acceptance

## Background

- Version: `1.0.1-SNAPSHOT`
- Target: `17-session-milestone-grouping`
- Owner: `session-module` + `claude-worker-agent` + `navigator-frontend`
- Goal: 为工作目录补充轻量里程碑定义能力，让会话可绑定单个里程碑，并在 Claude Worker 历史会话列表中按里程碑分组展示。

## Acceptance Basis

- [17-session-milestone-grouping.md](../17-session-milestone-grouping.md)
- [WorkingDirectoryEntity.java](../../../../navigator-common/src/main/java/com/foggy/navigator/common/entity/WorkingDirectoryEntity.java)
- [SessionEntity.java](../../../../navigator-common/src/main/java/com/foggy/navigator/common/entity/SessionEntity.java)
- [WorkingDirectoryService.java](../../../../addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/WorkingDirectoryService.java)
- [SessionMetadataService.java](../../../../session-module/src/main/java/com/foggy/navigator/session/service/SessionMetadataService.java)
- [SessionConfigController.java](../../../../session-module/src/main/java/com/foggy/navigator/session/controller/SessionConfigController.java)
- [ClaudeWorkerView.vue](../../../../packages/navigator-frontend/src/views/ClaudeWorkerView.vue)
- [SessionMetadataServiceTest.java](../../../../session-module/src/test/java/com/foggy/navigator/session/service/SessionMetadataServiceTest.java)
- [WorkingDirectoryServiceTest.java](../../../../addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/service/WorkingDirectoryServiceTest.java)

## Checklist

- [x] 工作目录编辑弹窗支持维护多个里程碑，字段覆盖名称、状态、文档相对路径。
- [x] 会话支持设置和清空 `milestoneId`，并通过独立接口回写配置。
- [x] 选中工作目录时，历史会话列表按里程碑分组展示。
- [x] 未设置里程碑的会话归入“未设置里程碑”分组。
- [x] worktree 创建路径会继承源工作目录的里程碑定义。
- [x] 后端校验会话设置的 `milestoneId` 必须属于该会话当前目录。
- [x] 自动化验证已完成：前端 `type-check` 通过，`SessionMetadataServiceTest` 与 `WorkingDirectoryServiceTest` 通过。
- [x] 真实 UI / 人工体验验证记录已补齐（Playwright 全链路验证）。
- [x] worktree 继承里程碑专项测试已补充（`createWorktree_inheritsMilestones`）。
- [x] 前端 `resolveConversationMilestone` 性能优化已完成（computed cache）。

## Evidence

- Requirement:
  - [17-session-milestone-grouping.md](../17-session-milestone-grouping.md)
- Implementation:
  - [WorkingDirectoryEntity.java](../../../../navigator-common/src/main/java/com/foggy/navigator/common/entity/WorkingDirectoryEntity.java)
  - [SessionEntity.java](../../../../navigator-common/src/main/java/com/foggy/navigator/common/entity/SessionEntity.java)
  - [WorkingDirectoryService.java](../../../../addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/WorkingDirectoryService.java)
  - [SessionMetadataService.java](../../../../session-module/src/main/java/com/foggy/navigator/session/service/SessionMetadataService.java)
  - [SessionConfigController.java](../../../../session-module/src/main/java/com/foggy/navigator/session/controller/SessionConfigController.java)
  - [ClaudeWorkerView.vue](../../../../packages/navigator-frontend/src/views/ClaudeWorkerView.vue)
- Test:
  - `pnpm --dir packages/navigator-frontend type-check`
  - 结果：`vue-tsc --noEmit` 通过
  - `mvn -pl session-module,addons/claude-worker-agent -am test "-Dtest=SessionMetadataServiceTest,WorkingDirectoryServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false"`
  - 结果：`SessionMetadataServiceTest` 6 passed，`WorkingDirectoryServiceTest` 22 passed（含新增 `createWorktree_inheritsMilestones`），构建成功
  - [SessionMetadataServiceTest.java](../../../../session-module/src/test/java/com/foggy/navigator/session/service/SessionMetadataServiceTest.java)
  - [WorkingDirectoryServiceTest.java](../../../../addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/service/WorkingDirectoryServiceTest.java)
- Experience (Playwright 全链路验证，2026-04-08):
  - AC1: 目录编辑弹窗维护 2 个里程碑（名称/状态/docPath），保存成功
  - AC2: 会话下拉菜单”设置里程碑”选择 v1.0，保存成功，toast “里程碑已更新”
  - AC3: 历史列表按里程碑分组：标题 “v1.0” + 状态标签 “进行中” + “1 个会话” + docPath
  - AC4: 未设置里程碑会话归入”未设置里程碑”分组
  - AC5: 详情弹窗显示”里程碑: v1.0” + “文档目录: docs/v1.0”
  - AC6: 后端校验由 SessionMetadataServiceTest 覆盖
- Artifact:
  - 工作目录保存里程碑配置：`WorkingDirectoryService` 会序列化 `milestones` 并在 DTO 回传时反序列化
  - 会话绑定里程碑：`SessionConfigController` 暴露 `/sessions/{sessionId}/config/milestone`，`SessionMetadataService` 校验目录归属
  - 前端分组展示：`ClaudeWorkerView` 使用 `groupedActiveConversations` 将无里程碑会话归入“未设置里程碑”

## Failed Items

- none

## Risks / Open Items

- 已全部解决。原有两项风险在复签时补齐：
  1. Playwright 全链路 UI 验证已完成（AC1-AC6 全覆盖）
  2. worktree 继承里程碑专项测试 `createWorktree_inheritsMilestones` 已补充并通过

## Final Decision

本项判定为 `accepted`。

理由：

1. 验收标准 1-6 在代码层均能找到直接实现映射：目录持有里程碑定义、会话存储 `milestoneId`、后端校验归属、前端分组展示”未设置里程碑”、worktree 继承源目录定义。
2. 前端类型检查与后端定向测试均通过，含新增的 `createWorktree_inheritsMilestones` 测试。
3. Playwright 全链路 UI 验证覆盖 AC1-AC6，前端交互和展示体验在浏览器中确认无回归。
4. 代码质量优化已完成：`resolveConversationMilestone` computed cache、`normalizeMilestone` 重复调用修复。

## Signoff Marker

- acceptance_status: signed-off
- acceptance_decision: accepted
- signed_off_by: engineering-signoff
- signed_off_at: 2026-04-08
- acceptance_record: docs/version-tracker/1.0.1-SNAPSHOT/acceptance/17-session-milestone-grouping-acceptance.md
- blocking_items: none
- follow_up_required: yes
