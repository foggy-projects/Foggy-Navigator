---
quality_scope: feature
quality_mode: pre-coverage-audit
version: 1.4.0-SNAPSHOT
target: OPT-002-codex-model-catalog-boundary
status: reviewed
decision: ready-with-risks
reviewed_by: codex
reviewed_at: 2026-07-11
follow_up_required: yes
---

# Implementation Quality Gate

## Background

- 检查对象：Codex GPT-5.6 Sol/Terra/Luna 分组目录、reasoning 精确授权、PC/APP 单控件交互与 SDK/App Server Runtime 边界。
- 当前阶段：需求和方案已确认，Java/PC/APP 实现、定向测试、构建及真实浏览器 smoke 已完成。
- 本次目标：确认实现没有把 Codex 特有交互扩散到其他编程 Worker，没有因旧配置迁移扩大授权，并具备进入测试证据覆盖审计的条件。

## Check Basis

- requirement：`workitems/OPT-002-codex-model-catalog-boundary.md`
- bug work item：不适用；本事项为已确认优化需求，不是缺陷回归。
- implementation plan：需求文档中的“实现范围”“兼容归一化”“验收标准”。
- progress：`workitems/OPT-002-codex-model-catalog-progress.md`
- execution check-in：Java 精确授权、PC 分组目录、APP 订阅配置和分组选择均标记完成。
- test result summary：Java 55/55；PC 定向 32/32；APP 全量 44/44；PC type-check、PC production build、APP H5 build通过；PC 与 APP H5 浏览器 smoke 通过。

## Changed Surface

- changed files：`CodexTaskService.java` 及测试、PC `llmModelOptions.ts`/`ModelSelect.vue`/设置与 Worker 入口、APP `llmModelOptions.ts`/`CodexModelPicker.vue`/任务页，以及 1.4.0 版本文档。
- changed modules：`addons/codex-worker-agent`、`packages/navigator-frontend`、`packages/foggy-mobile`、`docs/version-tracker/1.4.0-SNAPSHOT`。
- declared completed scope：移除 Mini；三模型族分组；具体档位授权；旧 alias/真实模型值归一化；APP 订阅配置可选；Luna Ultra 禁止；SDK Ultra 边界保持不变。
- review exclusion：`tools/codex-agent-worker` 当前已有的发布、安装、更新脚本和 package 变更属于用户工作区既有改动，本事项未修改、未覆盖，也不纳入本质量结论。

## Quality Checklist

- scope conformance：通过。PC/APP 仍是一个模型选择值；只有 Codex 选项使用分组，Claude/Gemini/LangGraph 保持平铺或既有交互；APP 未新增配置管理。
- code hygiene：通过。目标改动中无 debug 输出、临时开关、未解释 TODO/FIXME 或凭据输出；`git diff --check` 通过。
- duplication and consolidation：有受控重复。PC 与 APP 是独立工程，各自集中到一个 catalog utility；Java 在服务边界保留同构归一化规则以避免信任客户端。跨工程不能直接共享源码，漂移风险由对应单测和版本文档约束。
- complexity and abstraction：通过。PC 以通用 `ModelSelect` 消费可选分组元数据，未在各入口复制 `el-option-group`；APP 只为 Codex 增加底部选择面板，其他 backend 继续走 action sheet。
- error handling and edge cases：通过。覆盖空名单不限制、已知名单精确授权、未知旧真实模型安全回退、`extra-high` 归一化、固定旧 alias 不被后缀扩大、Luna Ultra 拒绝、无可用选项清空选择等边界。
- readability and maintainability：通过。目录生成、归一化、授权过滤、分组渲染职责分离；规范值和展示标签可直接对应需求表。
- critical logic documentation：通过。PC utility、需求和进度文档说明规范值、Runtime 最终选路和旧配置兜底；Java 方法名明确表达已知目录归一化与不支持项拒绝。
- contract and compatibility：通过。继续复用 `availableModels` 和 `OPENAI_CODEX`，无数据库/API schema 迁移；旧 alias 可读且只映射到单个档位；未知未来模型仍保留动态透传兼容。
- documentation and writeback：通过。需求、验收勾选、测试/体验进度、Runtime 边界和质量结论均已回写 1.4.0 版本目录。
- test alignment：通过。Java 测试直接覆盖授权扩大与 Luna Ultra；PC/APP utility 测试覆盖目录、归一化和回退；PC 组件测试与真实浏览器覆盖分组下拉，APP H5 smoke 覆盖无 Key 订阅配置和 Terra High 回显。
- release readiness：目录与交互实现可进入覆盖审计。真实 Ultra 执行、App Server canary 和默认切流仍由 OPT-001 独立门禁控制，不能由本事项的 UI/授权测试替代。

## Findings

- 未发现需要返回实现阶段的阻断问题。
- PC 旧配置 `codex-deep`/`codex-latest` 等在实际设置页中被归一化为具体 Sol 档位；Worker 选择器只展示配置明确允许的 Sol Medium/High，未扩大授权。
- APP 实际选择无 API Key 的 Codex 配置 `11` 后，旧未知真实模型列表仅回退开放三组 Low/Medium/High/Extra High；Max/Ultra/Mini 均未出现，选择 Terra High 后正确回显。
- Worker 模型切换时当前值不合法会回退到第一个允许项，这是既有安全行为；验收标准应断言“落在允许集合内”，不固定要求恢复某个非首项。

## Risks / Follow-ups

- PC、APP、Java 分别维护目录映射；未来新增模型族、reasoning 档位或调整 Luna 能力时，必须同步三处及其测试，避免 catalog drift。
- 本地 Java `/actuator/health` 因 RabbitMQ 未连接返回 503，但数据库、认证和本次使用的模型/Worker API 可用；这是环境依赖风险，不是本事项功能缺陷。
- 浏览器 smoke 覆盖 PC Chromium 与 APP H5，尚未覆盖 Android/iOS 真机触控、滚动和安全区表现。
- Sol/Terra Ultra 的真实执行仍依赖 `codex-app-server-worker` readiness/canary；SDK Worker 必须继续 fail closed。

## Recommended Next Skills

- `foggy-test-coverage-audit`：建议下一步执行，建立 requirement/acceptance item 到 unit、build、browser smoke 证据的完整映射。
- `foggy-bug-regression-workflow`：当前不需要；后续若真机或 Ultra provider smoke 暴露缺陷再启动。
- `plan-evaluator`：当前方案已评审并实现，无需重复评估；能力矩阵变化时再复核。
- back to implementation：当前无需返回；只有覆盖审计发现证据缺口或后续 runtime 契约变化时再进入实现。

## Decision

- decision：`ready-with-risks`
- can_enter_coverage_audit：yes
- follow_up_required：yes，执行覆盖审计；发布签收前补 Android/iOS 真机 smoke，并继续遵守 OPT-001 Ultra Runtime 门禁。
