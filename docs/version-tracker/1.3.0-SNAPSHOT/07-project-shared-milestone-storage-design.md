---
doc_type: workitem
intended_for: execution-agent | reviewer | signoff-owner
purpose: 记录项目级共享里程碑的设计选择、推荐方向与后续实现边界
type: requirement
source_type: feature
version: 1.3.0-SNAPSHOT
ticket: FEAT-007
priority: high
status: design-review
owner: claude-worker-agent
---

# 项目级共享里程碑存储设计

## 背景

当前里程碑是按工作目录保存在数据库中的个人数据，其他人即使拉取了同一个 Git 项目，也无法自动获得相同的里程碑信息。

这与“项目级共享里程碑”的目标不一致。当前目标已经从“个人工作目录辅助信息”转向“项目资产”或“团队共享协作信息”。

## 问题定义

需要先确定项目级里程碑的真相源放在哪里，以及共享边界到底属于哪一层：

1. 版本控制服务侧共享：以 GitHub/GitLab milestone 为主。
2. 仓库文件侧共享：以仓库内 `/.foggy/milestones.yaml` 为主。
3. Foggy 平台侧共享：以平台内共享项目空间为主。

这三个方案会直接影响：

- 可见范围
- 权限模型
- 与 Git 流程的耦合度
- `docPath` 等本地扩展字段的可表达性
- 后续与 session、任务、验收文档的绑定方式

## 目标结果

形成一套统一的里程碑领域模型，并优先支持“项目拉取即可共享”的场景。

当前倾向方案：`方案 B - 仓库文件为主存储`

## 方案对比

### 方案 A：版本控制服务里程碑为主

真相源：

- GitHub milestone
- GitLab milestone

优点：

- 与团队现有研发平台一致
- issue / PR / MR 关联自然
- 不需要在仓库中新增自定义文件结构

问题：

- `docPath`、`startAt` 等 Foggy 本地字段没有天然映射
- 私有实例、企业版、多 provider 接入复杂
- 不适用于没有远端 milestone 能力或未绑定远端仓库的项目
- 平台能力会被外部系统约束

适用场景：

- 团队已经强依赖 GitHub / GitLab 做项目计划管理

### 方案 B：仓库文件为主

真相源：

- `<git-root>/.foggy/milestones.yaml`

优点：

- 最符合“拉代码即共享”
- 与 Git provider 无关
- 可自由扩展 `docPath`、`description`、`tags`、AI 协作字段
- 变更可进入 Git 评审与历史追踪

问题：

- 多人同时编辑会产生 merge conflict
- 需要定义 schema 和兼容升级策略
- 权限等同于仓库权限，平台无法单独细粒度授权

适用场景：

- 希望把里程碑定义为项目资产
- 希望与代码版本同步演进

### 方案 C：Foggy 平台共享为主

真相源：

- Foggy 平台数据库 / 共享项目空间

优点：

- 权限、共享范围、审计都由平台控制
- 支持跨仓库、跨目录、跨工作目录共享一套里程碑
- 更利于后续做任务、session、agent 的平台级协作

问题：

- 不满足“别人拉代码就能看到”
- 需要额外定义项目空间、成员关系、共享边界
- 平台与仓库状态容易分叉

适用场景：

- 平台希望成为主协作中心，而不是仓库附属工具

## 当前推荐

推荐首选：`方案 B - 仓库文件为主`

原因：

1. 最贴合当前核心诉求：“别人拉项目后也能拿到同一套里程碑”
2. 与现有工作目录模型衔接成本最低
3. 对 GitHub / GitLab / 本地 Git 都成立
4. 后续仍可增加与 GitHub / GitLab 的导入导出能力

## 统一领域模型

建议先抽象统一模型，再决定底层真相源。

### Milestone

- `id`
- `name`
- `status`
- `startAt`
- `endAt`
- `docPath`
- `description`
- `tags`
- `scope`
- `visibility`
- `sourceType`
- `sourceRef`
- `createdBy`
- `updatedBy`
- `createdAt`
- `updatedAt`

### MilestoneBinding

- `milestoneId`
- `directoryId`
- `sessionId`
- `bindingType`
- `boundAt`

## 方案 B 的建议落地形态

### 文件位置

固定使用仓库根目录：

- `/.foggy/milestones.yaml`

不建议把文件挂在某个任意工作目录路径下，否则同仓库不同目录的共享边界会变得模糊。

### 文件结构建议

```yaml
version: 1

project:
  key: foggy-navigator
  name: Foggy Navigator

milestones:
  - id: ms_202604_console_v1
    name: 管理台一期
    status: ACTIVE
    startAt: 2026-04-01T00:00:00+08:00
    endAt: 2026-05-01T23:59:59+08:00
    docPath: docs/version-tracker/1.3.0-SNAPSHOT/07-project-shared-milestone-storage-design.md
    description: 项目共享里程碑能力设计与落地
    tags:
      - milestone
      - foggy
```

### 关键规则

1. `id` 必须稳定，不能因为改名而变化。
2. `name` 可编辑，但不能作为 session 绑定主键。
3. `docPath` 保留，用于与版本跟踪文档、验收文档、经验文档关联。
4. `version` 必须保留，便于后续 schema 升级。

## 与现有实现的兼容原则

当前 session 直接绑定 `milestoneId`，因此不建议直接移除数据库中的 `milestonesJson`。

推荐兼容策略：

1. 仓库文件作为真相源。
2. 数据库中的 `milestonesJson` 作为缓存和兼容层保留一段时间。
3. 业务读取统一走 `MilestoneStore` 抽象，不再直接依赖 `milestonesJson`。

## docs/version-tracker 是否需要统一

结论：`需要统一最小规范，但不建议强制所有项目完全长成一样。`

建议统一的是“骨架”，不是“全部内容”。

建议统一的最小规范：

1. 固定入口：`docs/version-tracker/<version>/`
2. 每个版本目录必须有 `README.md`
3. 文件命名统一为 `NN-主题.md`
4. 文档头部统一包含最小元数据：
   - `doc_type`
   - `version`
   - `status`
   - `owner`
   - `purpose`
5. `docPath` 一律引用仓库内相对路径

允许项目自定义的部分：

- 具体版本号命名
- 是否增加 `acceptance/`、`quality/`、`evidence/`、`workitems/`
- 是否增加更细的 requirement / plan / progress / experience 分层

这样做的好处：

1. Foggy 的里程碑 `docPath` 可以有统一解析规则。
2. 不同项目仍能保留自己的交付形态。
3. 后续如果要做“从里程碑跳转版本文档”或“按版本聚合里程碑”，不需要为每个项目写特殊适配。

## 后续设计决策点

在正式实施前，还需要最终拍板以下问题：

1. 首发是否只支持方案 B
2. 是否允许后续从 GitHub / GitLab 导入导出
3. 是否把 Foggy 平台共享作为更高阶能力保留到后续版本
4. 版本文档最小规范是否在多个项目中推广

## 验收标准

本设计阶段完成标准：

1. 明确首选方案和非首选方案边界
2. 明确统一领域模型
3. 明确仓库文件存储位置和最小 schema
4. 明确 `docs/version-tracker` 的统一范围
5. 后续实现阶段可直接据此拆分接口、存储与迁移任务
