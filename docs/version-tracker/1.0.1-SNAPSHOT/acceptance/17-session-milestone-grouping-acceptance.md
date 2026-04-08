---
acceptance_scope: feature
version: 1.0.1-SNAPSHOT
target: 17-session-milestone-grouping
status: signed-off
decision: accepted-with-risks
signed_off_by: engineering-signoff
signed_off_at: 2026-04-08
reviewed_by: N/A
blocking_items: []
follow_up_required: yes
evidence_count: 8
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
- [ ] 真实 UI / 人工体验验证记录已补齐。

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
  - 结果：`SessionMetadataServiceTest` 6 passed，`WorkingDirectoryServiceTest` 21 passed，构建成功
  - [SessionMetadataServiceTest.java](../../../../session-module/src/test/java/com/foggy/navigator/session/service/SessionMetadataServiceTest.java)
  - [WorkingDirectoryServiceTest.java](../../../../addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/service/WorkingDirectoryServiceTest.java)
- Experience:
  - [17-session-milestone-grouping.md](../17-session-milestone-grouping.md)
  - 现状：文档仍标记“待人工体验验证”，本次验收未发现补充的真实 UI walkthrough 或浏览器自动化证据
- Artifact:
  - 工作目录保存里程碑配置：`WorkingDirectoryService` 会序列化 `milestones` 并在 DTO 回传时反序列化
  - 会话绑定里程碑：`SessionConfigController` 暴露 `/sessions/{sessionId}/config/milestone`，`SessionMetadataService` 校验目录归属
  - 前端分组展示：`ClaudeWorkerView` 使用 `groupedActiveConversations` 将无里程碑会话归入“未设置里程碑”

## Failed Items

- none

## Risks / Open Items

- 缺少独立的真实 UI / 人工体验记录，当前无法用交互证据证明“编辑目录里程碑、会话切换里程碑、列表分组展示、详情 `docPath` 展示”在浏览器中全链路无回归。
- 现有自动化主要覆盖后端校验与目录配置序列化；`worktree` 继承虽然已在实现中写入 `milestonesJson`，但没有看到单独断言该字段继承成功的测试。
- Follow-up owner: `navigator-frontend` + `claude-worker-agent`
- Follow-up: 补一轮真实环境体验记录，或补充前端交互 / 集成测试后再做无风险复签。

## Final Decision

本项判定为 `accepted-with-risks`。

理由：

1. 验收标准 1-6 在代码层均能找到直接实现映射：目录持有里程碑定义、会话存储 `milestoneId`、后端校验归属、前端分组展示“未设置里程碑”、worktree 继承源目录定义。
2. 本次重新执行了需求文档中声明的自动化验证，前端类型检查与后端定向测试均通过，说明核心数据模型、接口与校验逻辑处于可交付状态。
3. 该需求的主要用户价值落在前端交互和展示体验，但当前仍缺少独立的人工体验或浏览器自动化证据，因此不适合直接判定为 `accepted`。
4. 现有缺口属于证据完整性风险，不是已确认的功能失败项，因此不提升为 `blocked` 或 `rejected`。

## Signoff Marker

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: engineering-signoff
- signed_off_at: 2026-04-08
- acceptance_record: docs/version-tracker/1.0.1-SNAPSHOT/acceptance/17-session-milestone-grouping-acceptance.md
- blocking_items: none
- follow_up_required: yes
