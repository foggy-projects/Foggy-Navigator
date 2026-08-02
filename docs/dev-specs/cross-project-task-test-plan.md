# 跨项目退役契约检查方案

## 检查边界

本方案只验证 CrossProjectTask 的退役、只读和兼容边界，不再验收“创建 → 启动 → 推进 → 完成”的编排生命周期，也不要求修复或生成历史数据。

## 1. 默认 mutation gate

对下列六条已认证 mutation 分别使用新建 disposable fixture 或无资源依赖的 controller/service fixture 检查：

- create
- start
- review
- update handoff
- advance
- cancel

预期：HTTP `410`、`Cache-Control: no-store`、reason code `CROSS_PROJECT_TASK_MUTATION_RETIRED`，并且 repository lookup、dispatch、worktree、event 与 state mutation 均为零。

## 2. 认证优先

未认证或无权限调用上述 route 时，预期仍由现有认证/授权边界先返回 `401/403`，不得提前暴露退役状态或目标是否存在。

## 3. Owner-scoped 只读查询

仅验证以下 GET：

- `GET /api/v1/cross-project-tasks/page`
- `GET /api/v1/cross-project-tasks/{contextId}`

预期：只能读取当前 owner 可见的既有记录，且无 dispatch、worktree、event 或 state mutation。禁止为通过检查而回填、清洗、重放、reconcile、删除或修改旧 rows。

## 4. UI 兼容

- 顶部导航不展示 `任务` 或 `跨项目`。
- `/tasks` 与 `/cross-tasks` 使用 named route 重定向到 Workers。
- Workers 日常工作台、普通 Task / Session 深链，以及 Claude Worker 的 SSH、终端、目录、文件和 Git 能力继续可用。

## 5. 显式 rollback

默认配置必须保持 `NAVIGATOR_CROSS_PROJECT_TASK_MUTATIONS_ENABLED=false`。只有单独批准的临时 rollback 检查才可显式设为 `true`；不得由自动恢复、旧 Skill 或测试默认值开启。

## 6. 外部 Skill 交接

活动的仓库外 `navigator-cross-project-task` Skill 需要由其制品 owner 替换为 no-HTTP deprecated tombstone。服务端 gate 的验收不得依赖该外部动作，也不得把两条 GET 重新包装成 orchestration Skill。交接记录见 [NAVI-CORE-001 S2-04](../version-tracker/1.4.3-SNAPSHOT/workitems/NAVI-CORE-001-S2-04-cross-project-retirement-handoff.md)。

## 7. 验证预算

实现阶段默认运行 focused tests 和 affected lane；全量测试只在用户明确授权的收口节点运行。本次文档同步不运行产品测试。
