# 17 Session Milestone Grouping

## Date

- 2026-04-08

## Version

- `1.0.1-SNAPSHOT`

## Type

- Requirement
- UX Enhancement
- Lightweight Data Model Extension

## Priority

- P1

## Status

- Code & Tests Verified (2026-04-08)
- Acceptance Signed Off With Risks (2026-04-08)
- 风险：缺少独立真实 UI / 人工体验验证记录

## Background

当前 Claude Worker 会话历史已经具备“按会话查看、按状态筛选、按目录过滤”的能力，但仍缺少一个非常轻量的版本归属语义。

实际使用中，大部分会话并不是孤立存在，而是稳定归属于某个版本或某个阶段性目标，例如：

- `1.0.0-SNAPSHOT`
- `1.0.1-SNAPSHOT`
- 某个专项修复批次

如果没有这个归属信息，历史列表会长期堆在一起，用户只能依赖标题、时间和上下文记忆去判断“这个会话属于哪个版本阶段”，成本偏高。

## Problem Statement

当前工作流缺少一个足够轻、但足够稳定的“版本归属”层：

1. 工作目录无法维护自己的里程碑清单
2. 会话无法声明自己属于哪个里程碑
3. 历史会话列表无法按里程碑聚合展示

这会导致：

- 同一工作目录下，不同版本阶段的会话混杂在一起
- 后续回看版本开发轨迹时，缺少自然的分组维度
- 与 `docs/version-tracker/...` 的文档组织方式没有形成直接映射

## Target Outcome

为每个工作目录提供一个轻量里程碑能力，并允许会话绑定其中一个里程碑，在历史列表中按里程碑分组展示。

首版只解决三件事：

1. 目录维护里程碑定义
2. 会话记录所属里程碑
3. 历史列表按里程碑分组

## Milestone Model

首版里程碑保持极简，只包含：

- `id`
- `name`
- `status`
- `docPath`

其中：

- `name` 用于展示，例如 `1.0.1-SNAPSHOT`
- `status` 用于表示当前阶段，例如 `PLANNED / ACTIVE / COMPLETED / ARCHIVED`
- `docPath` 使用相对路径，例如 `docs/version-tracker/1.0.1-SNAPSHOT`

不引入的内容：

- 时间线字段
- 描述富文本
- 多层级父子里程碑
- 复杂权限控制
- 独立里程碑管理页

## Scope

### In Scope

- 每个工作目录可维护自己的里程碑列表
- 单个会话可设置一个 `milestoneId`
- 历史会话在选中工作目录时按里程碑分组
- 会话详情中展示所属里程碑与文档路径
- worktree 继承源目录的里程碑定义

### Out Of Scope

- 全局跨目录共享里程碑池
- 一个会话同时属于多个里程碑
- 基于里程碑的权限、统计或报表
- 自动从文档目录扫描生成里程碑
- 按里程碑进行后端分页查询优化

## Design Decision

采用“目录持有定义，会话只存引用”的最小实现：

- `WorkingDirectoryEntity` 保存里程碑定义列表 JSON
- `SessionEntity` 只保存 `milestoneId`
- 前端根据当前目录的里程碑定义解析名称、状态与 `docPath`

这样做的原因：

- 改动面最小
- 不需要新增独立表和复杂关联
- 能满足当前“归属 + 分组”的核心目标
- 后续若里程碑能力扩大，仍可再演进为独立实体

## Code Inventory

- `navigator-common/src/main/java/com/foggy/navigator/common/entity/WorkingDirectoryEntity.java`
- `navigator-common/src/main/java/com/foggy/navigator/common/entity/SessionEntity.java`
- `navigator-common/src/main/java/com/foggy/navigator/common/dto/DirectoryMilestoneDTO.java`
- `session-module/src/main/java/com/foggy/navigator/session/service/SessionMetadataService.java`
- `session-module/src/main/java/com/foggy/navigator/session/controller/SessionConfigController.java`
- `session-module/src/main/java/com/foggy/navigator/session/dto/SessionConfigDTO.java`
- `session-module/src/main/java/com/foggy/navigator/session/service/TaskDispatchFacade.java`
- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/WorkingDirectoryService.java`
- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/model/dto/WorkingDirectoryDTO.java`
- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/model/form/UpdateWorkingDirectoryForm.java`
- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/model/form/DirectoryMilestoneForm.java`
- `packages/navigator-frontend/src/types/index.ts`
- `packages/navigator-frontend/src/api/claudeWorker.ts`
- `packages/navigator-frontend/src/composables/useClaudeWorker.ts`
- `packages/navigator-frontend/src/views/ClaudeWorkerView.vue`

## Acceptance Criteria

1. 用户可以在工作目录编辑弹窗中维护多个里程碑，字段至少包含名称、状态、文档相对路径。
2. 用户可以为单个会话设置或清空所属里程碑。
3. 在选中某个工作目录时，历史会话列表会按里程碑分组展示。
4. 未设置里程碑的会话会归入“未设置里程碑”分组。
5. worktree 新建后会继承源工作目录的里程碑定义。
6. 后端会校验会话设置的 `milestoneId` 必须属于该会话当前目录。

## Constraints And Non-Goals

本次不追求：

- 把里程碑做成复杂项目管理系统
- 提供跨目录统一里程碑看板
- 自动维护里程碑和版本文档的双向同步

当前目标只是补齐一个稳定、低心智负担的会话归档维度。

## Tracking

### Development Progress

- 已完成目录层里程碑定义存储，采用 JSON 数组挂在工作目录实体上
- 已完成会话 `milestoneId` 字段与更新接口
- 已完成历史会话在目录上下文中的按里程碑分组展示
- 已完成会话详情和会话下拉菜单中的“设置里程碑”入口
- 已完成 worktree 对源目录里程碑定义的继承

### Testing Progress

- 前端类型检查已通过
- `session-module` 定向测试已通过，覆盖：
  - 会话可绑定目录内已有里程碑
  - 会话不可绑定目录外里程碑
- `claude-worker-agent` 定向测试已通过，覆盖：
  - 工作目录里程碑配置可正确序列化和回传

已执行：

```bash
pnpm --dir packages/navigator-frontend type-check
mvn -pl session-module,addons/claude-worker-agent -am test "-Dtest=SessionMetadataServiceTest,WorkingDirectoryServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

### Experience Progress

- 当前状态：待人工体验验证
- 建议重点验证：
  - [ ] 在单个工作目录下创建两个以上里程碑并保存
  - [ ] 为多个历史会话分别设置不同里程碑
  - [ ] 确认历史列表按里程碑分组且“未设置里程碑”分组正常
  - [ ] 确认 worktree 新建后会带出源目录里程碑
  - [ ] 确认详情弹窗中 `docPath` 展示符合预期

## Remaining Follow-up

后续如要继续增强，优先级建议如下：

1. 增加按里程碑筛选会话
2. 增加默认里程碑能力，例如新会话自动继承目录当前激活里程碑
3. 增加点击 `docPath` 直接跳转或联动打开版本文档目录

## Acceptance Status

- acceptance_status: signed-off
- acceptance_decision: accepted
- signed_off_by: engineering-signoff
- signed_off_at: 2026-04-08
- acceptance_record: docs/version-tracker/1.0.1-SNAPSHOT/acceptance/17-session-milestone-grouping-acceptance.md
- blocking_items: none
- follow_up_required: no
