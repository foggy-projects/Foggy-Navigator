# Worker 总控工作流 v1 代码触点盘点

## 文档作用

- doc_type: code-inventory
- intended_for: root-controller | execution-agent | reviewer
- purpose: 盘点 1.2.0-SNAPSHOT 首版总控工作流可能涉及的 repo、目录与职责边界，避免后续把治理规则、运行时逻辑和版本文档写错位置

## Version

- `1.2.0-SNAPSHOT`

## Status

- Draft
- 2026-04-17

## 1. 使用说明

本文件不要求首轮就修改全部触点。

目标是先回答：

1. 哪些路径应作为首版优先触点
2. 哪些路径只做只读分析
3. 哪些内容不应在首版误放

## 2. Code Inventory

| repo | path | role | expected change | notes |
| --- | --- | --- | --- | --- |
| workspace/docs | `docs/version-tracker/1.2.0-SNAPSHOT/README.md` | 版本索引与总览 | create | 本轮新增版本根 README |
| workspace/docs | `docs/version-tracker/1.2.0-SNAPSHOT/01-worker-governance-supervisor-mode-requirement.md` | 总控工作流需求基线 | create | requirement 主文档 |
| workspace/docs | `docs/version-tracker/1.2.0-SNAPSHOT/02-worker-governance-supervisor-mode-module-responsibility.md` | 模块职责 | create | ownership 基线 |
| workspace/docs | `docs/version-tracker/1.2.0-SNAPSHOT/03-worker-governance-supervisor-mode-code-inventory.md` | 代码触点盘点 | create | 本文档 |
| workspace/docs | `docs/version-tracker/1.2.0-SNAPSHOT/04-worker-governance-supervisor-mode-implementation-plan.md` | 实现计划 | create | root plan |
| workspace/docs | `docs/version-tracker/README.md` | 版本目录索引 | update | 增加 `1.2.0-SNAPSHOT` 入口 |
| workspace/root | `CLAUDE.md` | workspace 治理基线 | read-only-analysis | 首版继承当前根目录规则，不强制本轮改写 |
| python-worker | `tools/langgraph-biz-worker/src/langgraph_biz_worker/graphs/` | supervisor / implementer / reviewer graph 组织 | update | 首版建议承载总控流程图与非对称执行流 |
| python-worker | `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/` | workflow stage、技能调用结果、报告片段 | update | 首版建议承载阶段状态与流程推进逻辑 |
| python-worker | `tools/langgraph-biz-worker/src/langgraph_biz_worker/manifests/` | workflow manifests | update | 如采用 manifest 方式定义角色和节点，可放这里 |
| python-worker | `tools/langgraph-biz-worker/skills/builtin/` | worker 内建 skills | read-only-analysis | 首版不建议把外部治理 skills 复制进来，可按需增加 wrapper 或 routing 约束 |
| java-backend | `addons/langgraph-biz-worker/src/main/java/` | 任务入口、状态映射、审批接口 | update | 首版建议补 workflow task metadata 与阶段同步 |
| java-backend | `addons/langgraph-biz-worker/src/test/java/` | Java 回归测试 | update | 覆盖工作流状态推进与接口合同 |
| session-module | `session-module/src/main/java/` | 会话、任务、事件投影 | update | 如需总控阶段事件或报告索引，优先落这里 |
| session-module | `session-module/src/test/java/` | 会话与事件回归测试 | update | 覆盖阶段事件、SSE 或状态投影 |
| metadata-config-module | `metadata-config-module/src/main/java/` | 工作流配置与 skill 映射配置 | read-only-analysis | 首版可先不改，除非需要配置化模板 |
| frontend | `packages/navigator-frontend/src/` | 总控阶段与验收状态展示 | read-only-analysis | 首版不是强制项，后续如需可视化再打开 |

## 3. 模块归属判断

### 3.1 应优先放在 `tools/langgraph-biz-worker/` 的内容

1. 总控 graph
2. implementer / reviewer 路由
3. worker 内阶段推进逻辑
4. 面向 skill 调用结果的结构化状态

### 3.2 应优先放在 `addons/langgraph-biz-worker/` 的内容

1. Java 侧任务入口与 worker client
2. 平台任务状态与 workflow 状态映射
3. 审批、恢复、阶段透传接口

### 3.3 不应误放到部署壳或无关模块的内容

1. 不要把编排 Service 放到 `launcher/`
2. 不要把总控治理正文写进 `*-cloud-service` 类壳模块
3. 不要把外部 `SKILL.md` 内容全文复制到仓库里，只为“看起来统一”

## 4. 首版建议

1. 先完成 docs + runtime + Java backend 三段收口
2. 配置化和前端可视化放到第二阶段
3. 外部 skills 先按名称与阶段接入，不复制实现

## 5. 完成定义

代码触点盘点完成的标准是：

1. 后续执行 agent 不会把总控功能错误地下沉到无关模块
2. 规划层已经明确哪些路径是 `create`、`update`、`read-only-analysis`
3. 后续 implementation plan 可直接引用本文安排阶段交付
