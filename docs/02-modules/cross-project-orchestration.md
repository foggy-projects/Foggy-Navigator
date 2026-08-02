# 跨项目退役与只读边界

## 1. 当前定位

旧 CrossProjectTask 阶段编排不再是当前产品能力。PC 顶部 `跨项目` 入口已移除，旧 `/cross-tasks` named route 重定向到 Workers；普通 Task / Session、Workers 工作台以及 Claude Worker 的 SSH、终端、目录、文件和 Git 能力不受影响。

本模块当前只保留两类兼容面：

- 对既有记录的 owner-scoped 只读查询；
- 对旧 mutation route 的默认关闭退役桩，避免旧客户端误触发副作用。

## 2. HTTP 契约

### 2.1 保留的只读查询

以下两条 GET 继续使用既有 `UserContext` owner resolver：

- `GET /api/v1/cross-project-tasks/page`
- `GET /api/v1/cross-project-tasks/{contextId}`

查询不得产生 dispatch、worktree、event 或 state mutation。既有 rows 保持原样；本退役不授权回填、清洗、重放、reconcile、删除或其他历史数据修复。

### 2.2 默认退役的 mutation

以下六条 route 保留稳定 route/action/principal/owner identity，但默认处于 `RETIRED_STUB`：

- `POST /api/v1/cross-project-tasks`
- `POST /api/v1/cross-project-tasks/{contextId}/start`
- `POST /api/v1/cross-project-tasks/{contextId}/review`
- `PUT /api/v1/cross-project-tasks/{contextId}/phases/{phaseId}/handoff`
- `POST /api/v1/cross-project-tasks/{contextId}/advance`
- `POST /api/v1/cross-project-tasks/{contextId}/cancel`

请求先经过认证；认证成功后，默认由服务端 gate 在读取 repository 或触发 target/provider/event effect 前返回：

- HTTP `410 Gone`
- `Cache-Control: no-store`
- stable reason code `CROSS_PROJECT_TASK_MUTATION_RETIRED`

未认证或无权限请求仍先返回原有 `401/403`，不得利用退役响应探测资源。

## 3. Rollback 边界

`NAVIGATOR_CROSS_PROJECT_TASK_MUTATIONS_ENABLED=true` 是唯一显式 rollback opt-in。默认值为 `false`；它不得由恢复流程、旧客户端、Skill 或文档示例自动开启，也不代表该能力重新进入产品主线。

若未来确需临时启用，必须由部署 owner 单独批准、限定环境和退出时间，并沿用既有 owner resolution。关闭开关后仍不得修改历史 rows 以“修复”兼容性。

## 4. 外部 Skill

仓库外仍有活动的 `navigator-cross-project-task` Skill 宣称通过 HTTP 创建、启动、推进和取消任务。Navigator 服务端 gate 是独立安全边界，不依赖该 Skill 先完成下线。

外部制品的 owner handoff、当前 digest 与停用验收见 [NAVI-CORE-001 S2-04 交接](../version-tracker/1.4.3-SNAPSHOT/workitems/NAVI-CORE-001-S2-04-cross-project-retirement-handoff.md)。不得把保留的两个 GET 包装成新的 orchestration Skill。

## 5. 历史说明

旧阶段模型、handoff、目录和分支字段仅用于解释既有记录，不再构成可创建或可推进的当前能力。历史文档与 evidence 可以保留当时事实，但不得覆盖本页当前契约。
