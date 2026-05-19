# Worker skill_name Boundary Decision

## 文档作用

- doc_type: workitem | decision-record | optimization
- version: 1.1.4-SNAPSHOT
- status: accepted-for-planning
- date: 2026-05-19
- source_type: integration-design-follow-up
- source: OpenAPI `rootAgentId` / internal skill routing discussion after #125
- intended_for: Navigator maintainer | BizWorker maintainer | upstream integration reviewer
- purpose: 记录 `skill_name` 作为 Worker 侧 canonical Skill 身份的决策，降低 LLM 与 BizWorker 对 Java `skillId` 模型的耦合

## 背景

#125 已将 OpenAPI `{agentId}` 语义调整为外部 `rootAgentId` route，Navigator 服务端再从 root agent metadata 派生内部 effective skill。TMS 不再需要传 `context.skillId`。

后续讨论发现，当前 Java 到 LangGraph BizWorker 的执行链路仍以 `skillId` 为中心：

```text
BusinessAgentTask.skillId
-> BusinessAgentWorkerTaskLaunchRequest.skillId
-> Langgraph form.agentId = skillId
-> context.skillId
-> Python root_graph 用 skillId 注册/选择 SkillManifest
-> LLM routing prompt 要求返回 skill_id
```

这会带来两个问题：

1. LLM 需要理解内部 skill 标识，增加不必要的选择与解释负担。
2. BizWorker 与 Java DB / grant model 耦合较强，不利于后续独立加载 `.codex/skills`、`.claude/skills` 或本地 `SKILL.md` 目录运行。

## 决策

统一把 `skill_name` 定义为 **Skill 文件夹名称**，即 skill directory basename。

Naming update:

- 概念层仍称为 skill name。
- BizWorker / Python / HTTP JSON 的最终字段名使用 `skill_name`。
- Java 代码内部可使用 `skillName` 属性以符合 Java 命名习惯，但发送给 BizWorker 的 JSON 字段应为 `skill_name`。
- 历史 `skill_id` / `skillId` 保留为兼容 alias，值语义必须等同于 `skill_name`。

示例：

```text
.codex/skills/navigator-upstream-llm-integration/
.claude/skills/claude-worker-agent/
tools/langgraph-biz-worker/skills/public/apps/{clientAppId}/tms-navigator-agent/
```

概念分层如下：

| Concept | Owner | Visibility | Meaning |
| --- | --- | --- | --- |
| `rootAgentId` | Navigator OpenAPI / upstream | upstream visible | 外部 OpenAPI agent route，TMS 调用 ask/readiness 使用 |
| `skill_name` | Worker skill runtime | worker visible, optionally tool visible | Skill 目录 basename，Worker 侧 canonical identity |
| `displayName` | Skill author / UI | LLM/UI visible | 人类可读名称，可变、可本地化 |
| `skillId` | Java legacy model | internal | Java DB、授权与历史 API 字段，后续逐步退场或映射到 `skill_name` |

## 命名规则

`skill_name` 必须是安全的单级路径段：

- 不能为空。
- 不能包含 `/`、`\`、`..` 或路径分隔语义。
- 推荐 `lower-kebab-case`，例如 `tms-navigator-agent`。
- 为兼容历史标识，可暂时接受 `.` 与 `_`，例如 `tms.navigator.agent`、`skill_01`。
- `displayName` 不参与路径、授权唯一性或 Worker manifest 主键。

## 目标

1. Java 与 BizWorker 的协议逐步从 `skillId` 迁移到 `skill_name`。
2. 单 root skill ask 场景中，LLM 不需要选择或输出 skill 标识。
3. BizWorker 能以 `skill_name + SKILL.md + resources + tools/functions` 独立运行。
4. Java 继续负责 ClientApp、upstream user、function grant、task-scoped token 等控制面边界。
5. 上游 TMS 继续只感知 `rootAgentId`，不要求传 `skill_name` 或 `skillId`。

## 非目标

- 本决策不要求立即重命名数据库列。
- 本决策不改变 #125 中 OpenAPI `{agentId}` 作为 `rootAgentId` 的语义。
- 本决策不要求对外公开 Java 内部 skill grant API 的字段名立刻改为 `skill_name`。
- 本决策不处理 LLM 多 skill 路由的完整产品体验，只定义后续迁移边界。

## 目标契约

Java -> BizWorker 任务启动协议目标形态：

```json
{
  "tenantId": "tenant_01",
  "businessTaskId": "bt_01",
  "clientAppId": "app_01",
  "upstreamUserId": "user_01",
  "skill_name": "tms-navigator-agent",
  "skillMarkdown": "...",
  "taskScopedToken": "<masked>",
  "runtimeContext": {
    "functionProvider": "navigator",
    "modelConfigId": "model_01"
  }
}
```

Worker 内部可用 `skill_name` 定位 manifest / frame / local files。LLM 默认接收：

- `displayName`
- `description`
- materialized markdown
- 可用 business function 摘要
- 用户消息与必要业务上下文

LLM 默认不接收：

- Java `skillId`
- ClientApp grant 明细
- task scoped token
- 上游或 Navigator secret

## LLM 路由原则

单 root skill ask：

- Java 已经根据 `rootAgentId` 解析出本次任务的 root skill。
- Worker 直接执行该 skill 的 materialized markdown。
- LLM 不需要输出 `skill_id` / `skill_name`。

多 skill 委派：

- LLM 可看到 `displayName + description`。
- 工具参数可以继续是内部稳定 key，但最终应命名为 `skill_name`。
- Worker 在工具执行层把 `skill_name` 映射到本地 manifest，不暴露 Java `skillId`。

## 迁移计划

Sequencing note:

本文的 Phase 1 表示 `skill_name` 边界的跨模块协议兼容阶段，包含 Java launch protocol。它不等同于 [02-bizworker-standalone-skill-agent-plan.md](./02-bizworker-standalone-skill-agent-plan.md) 的 standalone Phase 1。1.1.4 实现顺序应先做 standalone core contract extraction，再进入 Java launch protocol 收敛。

### Phase 1: Protocol compatibility

- `BusinessAgentWorkerTaskLaunchRequest` 增加 Java 属性 `skillName`，序列化给 BizWorker 时字段为 `skill_name`，保留 `skillId` deprecated。
- 短期 `skill_name = skillId` 或由 root agent profile 显式提供。
- LangGraph launcher context 增加 `skill_name`，不再把 `skillId` 拼进 prompt。

### Phase 2: Prompt decoupling

- 删除 launcher prompt 中的 `for skill <skillId>`。
- Python root graph 的 prompt context 过滤内部字段：`skillId`、`clientAppId`、`businessTaskId`、`rootAgentId` 等。
- dynamic manifest 使用 `skill_name` 注册，LLM 不默认看到内部标识。

### Phase 3: Worker autonomy

- BizWorker 支持按本地目录加载 `SKILL.md`，以 folder basename 作为 canonical `skill_name`。
- Java 只作为 manifest/materialized markdown、runtime token、function provider 的可选来源。
- 本地/测试模式允许 Worker 使用 mock function provider 独立运行。

### Phase 4: Java model cleanup

- DTO / Form / SDK 对外字段逐步引入 `skill_name` 或 Java 属性 `skillName` + JSON `skill_name`。
- 历史 `skillId` 字段维持兼容，语义上可先存储 `skill_name`。
- 数据库列名迁移另行评估，不作为本决策的前置条件。

## Ownership

| Area | Owner | Responsibility |
| --- | --- | --- |
| OpenAPI route | `addons/claude-worker-agent` | 继续以 `rootAgentId` 对外，服务端派生内部 skill |
| Java business runtime | `business-agent-module` | 授权、task-scoped token、function grant，新增/兼容 Java `skillName` / JSON `skill_name` 字段 |
| Java LangGraph launcher | `addons/langgraph-biz-worker` | Java -> Python Worker launch protocol，发送 `skill_name`，从 prompt 中移除 Java skill id |
| Python BizWorker runtime | `tools/langgraph-biz-worker` | 以 folder basename 加载/注册 SkillManifest，过滤内部 context |
| SDK / CLI | `navigator-open-sdk` | 后续在对外 control-plane 能力中逐步引入 `skill_name` 兼容字段 |

## Code Inventory

| Path | Role | Expected change | Notes |
| --- | --- | --- | --- |
| `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/worker/BusinessAgentWorkerTaskLaunchRequest.java` | Java -> Worker launch DTO | update | 增加 Java `skillName`，JSON 输出 `skill_name`，保留 `skillId` 兼容 |
| `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/BusinessAgentTaskService.java` | Business task orchestration | update | 构造 launch request 时提供 `skillName` / `skill_name` |
| `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/service/LanggraphBusinessAgentWorkerTaskLauncher.java` | LangGraph launch adapter | update | context 使用 `skill_name`，prompt 不再包含内部 skill id |
| `tools/langgraph-biz-worker/src/langgraph_biz_worker/graphs/root_graph.py` | Worker routing and prompt assembly | update | single root skill 不要求 LLM 输出 skill id；过滤内部 context；兼容 `skill_name` / `skill_id` |
| `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/skill_registry.py` | Worker skill manifest loading | update | 确认 folder basename 为 canonical identity |
| `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/OpenApiAgentRouteService.java` | root route -> effective skill | read-only-analysis | 保持 #125 语义，后续可从 metadata 派生 `skill_name` |
| `docs/version-tracker/1.1.3-SNAPSHOT/upstream-integration/20-navigator-upstream-cli-readiness-and-skill-artifact.md` | upstream readiness docs | update-later | 后续把 effective skill 描述补充为 `skill_name` 方向 |
| `docs/version-tracker/1.1.3-SNAPSHOT/upstream-integration/23-business-agent-bundle-registration-design.md` | agent bundle docs | update-later | 后续明确 bundle manifest 的 `skill_name` 字段兼容策略 |

## Acceptance Criteria

- TMS ask/readiness 仍只需要 `rootAgentId`，不新增上游必填输入。
- Java -> Worker launch request 可以携带 `skill_name`。
- 单 root skill ask 的 LLM prompt 中不再出现 Java `skillId`。
- Worker 可以用 `skill_name` 找到或注册当前 root skill manifest。
- 现有 `skillId` API / DB 字段保持兼容，不破坏历史测试。
- task-scoped token、function grant、ClientApp grant 的 Java 控制面校验不被绕过。

## Progress Tracking

| Area | Status | Notes |
| --- | --- | --- |
| Decision record | done | 本文档 |
| Development | pending | 待后续执行迁移 |
| Testing | pending | 需补 Java launch adapter、Python root graph、Worker registry 测试 |
| Experience | N/A | 纯后端 / Worker runtime contract，无 UI 体验变更 |

## Open Questions

- 是否在首个实现阶段直接把默认 root skill 从 `tms.navigator.agent` 迁移为 `tms-navigator-agent`，还是先保留点号命名兼容。
- 多 skill 委派时，LLM 工具参数是否命名为 `skill_name`，还是保持 `skill_id` 但值语义改为 folder basename。
- account private skill 与 ClientApp public skill 同名时，Worker 本地目录优先级是否沿用当前 `legacy < builtin < public < app-public < account`。
