---
doc_type: external-artifact-handoff
workitem: NAVI-CORE-001-S2-04
status: HANDOFF_READY
owner: external-agent-skill-distribution-owner
last_updated: 2026-08-03
---

# NAVI-CORE-001 S2-04 CrossProject retirement handoff

## 1. Outcome

Navigator 当前文档已对齐以下服务端事实：

- 六条 CrossProjectTask mutation 默认在认证后返回 HTTP `410`、`Cache-Control: no-store` 和 `CROSS_PROJECT_TASK_MUTATION_RETIRED`，并在 repository、dispatch、worktree、event 与 state effect 之前停止。
- 两条 GET 保留 owner-scoped 只读列表与详情；旧 rows 不回填、不清洗、不重放、不 reconcile、不删除或修复。
- PC 顶部 `任务`、`跨项目` 入口已移除，旧 `/tasks`、`/cross-tasks` named route 重定向到 Workers。
- 普通 Task / Session、Workers 工作台，以及 Claude Worker 的 SSH、终端、目录、文件和 Git 能力继续保留。
- `NAVIGATOR_CROSS_PROJECT_TASK_MUTATIONS_ENABLED=true` 只能由部署 owner 显式、临时 opt-in；不得由恢复流程、客户端或 Skill 自动开启。

服务端 gate 是独立安全边界，外部 Skill 尚未完成停用不影响 fail-closed 结论。

## 2. 当前外部制品快照

以下文件在 Navigator 仓库外，由公司 Agent Skill 分发/marketplace 维护方负责；本 workitem 未修改它们：

| Artifact | Declared version | SHA-256 (2026-08-03) | Current conflict |
|---|---|---|---|
| `/mnt/c/Users/oldse/.agents/skills/navigator-cross-project-task/SKILL.md` | 未声明版本 | `d4baef222c3a6cd198ab9135d42b605cabc176384db3c8aac2535d61d10c2c6a` | 仍说明通过 HTTP create/start/advance/cancel，并指向 `#/cross-tasks` |
| `/mnt/c/Users/oldse/.agents/skills/navigator-cross-project-task/agents/openai.yaml` | 未声明版本 | `624f09b24c78d6afe25d7ea3f3717782d9b25442bbbc6cc49416c54f8c991f8e` | `default_prompt` 仍要求 create and start |

这两个路径是当前交接目标，不是 Navigator 仓库的发布制品。不得在本仓库内创建副本来掩盖外部漂移。

## 3. 外部 owner 必须执行的动作

1. 由公司 Agent Skill 分发/marketplace 维护方确认具名执行 owner 和目标发布版本。
2. 将活动 Skill 替换为 no-HTTP deprecated tombstone：只说明能力已退役，不执行 `curl`、不读取 token、不调用任何 Navigator CrossProject route。
3. 删除 create/start/review/handoff/advance/cancel 示例、`#/cross-tasks` 入口和要求 create/start 的 default prompt。
4. 不得把保留的两个 owner-scoped GET 包装成新的 orchestration Skill；需要只读诊断时直接使用受控 API/SDK 能力。
5. 通过外部制品自己的发布/安装流程更新活动副本，并在关闭记录中填写实际 owner、版本、两个新 digest、发布/安装位置和静态复核证据。

## 4. Closure record

外部 owner 完成动作后，应在其制品系统留下以下最小记录，并回链本 handoff：

| Field | Required value |
|---|---|
| responsible owner | 具名外部 Skill/marketplace owner |
| artifact version | 可审计的 tombstone 发布版本；不得继续“未声明版本” |
| artifact digests | `SKILL.md` 与 `agents/openai.yaml` 发布后 SHA-256 |
| distribution evidence | 发布记录及实际活动安装路径 |
| semantic evidence | 无 HTTP/curl/token/mutation/default create-start prompt；没有 GET orchestration wrapper |
| completion time | 带时区时间戳 |

Navigator Stage 2 可以依据服务端 gate 与本 handoff 收口；外部制品动作作为 owner handoff 跟踪，不得通过重新启用服务端 mutation 来消除漂移。

## 5. Evidence boundary

- 本次只做文档和仓库外制品的只读 digest/字符串核对；未修改外部 Skill、marketplace、产品源码、配置、测试或历史数据。
- 当前 route catalog source 与版本 evidence 的 SHA-256 均为 `76c83b04f57ac4698cb8f3f4b2700643b546853300856dddae34fe62299fb837`；其中 CrossProjectTask 恰为六条 `RETIRED_STUB` mutation 与两条 `KEEP` GET。
- [GOV-001 P0.5 method-route manifest review](../evidence/GOV-001-p0.5-method-route-manifest-review.md) 是早期 route-count/disposition 历史快照，正文保持原样，不得用它覆盖当前 catalog 或本 addendum。
- 本次未运行 Maven、pnpm、浏览器或产品测试；文档变更只做静态检查。
