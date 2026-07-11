---
audit_scope: feature
audit_mode: pre-acceptance-check
version: 1.4.0-SNAPSHOT
target: OPT-002-codex-model-catalog-boundary
status: reviewed
conclusion: ready-with-gaps
reviewed_by: codex
reviewed_at: 2026-07-11
follow_up_required: yes
---

# Test Coverage Audit

## Background

- 审计对象：OPT-002 Codex GPT-5.6 Sol/Terra/Luna 分组模型目录与 Runtime 边界。
- 当前阶段：实现质量闸门已通过，结论为 `ready-with-risks`；准备进入正式验收。
- 审计目标：逐项确认目录、分组交互、精确授权、旧配置兼容、APP 订阅配置和 Ultra Runtime 边界是否有足够证据承接。

## Audit Basis

- requirement：`../workitems/OPT-002-codex-model-catalog-boundary.md`
- implementation plan：requirement 中的“实现范围”“兼容归一化”“验收标准”。
- progress：`../workitems/OPT-002-codex-model-catalog-progress.md`
- bug work items：不适用。
- acceptance basis：requirement 的 7 项需求、兼容归一化约束和 6 项验收标准。
- test records：`CodexTaskServiceTest.java`、PC/APP `llmModelOptions.test.ts`、`ClaudeWorkerView.integration.test.ts`、SDK Worker `query-route-paths.test.ts`/`sdk-wrapper.test.ts`/`query-validation.test.ts`/`config.test.ts`。
- manual evidence：2026-07-11 在本地 Java 8112、PC 5174、APP H5 5175 上完成真实登录和交互 smoke；断言与结果已记录在 progress 和本审计。

## Coverage Matrix

| Item | Risk | Unit | Integration | E2E | Playwright | Manual | Evidence Path | Coverage |
|---|---|---|---|---|---|---|---|---|
| REQ-1 移除 Codex Mini | major | yes | no | no | yes | yes | PC/APP `llmModelOptions.test.ts`；PC Worker 与 APP picker smoke | covered |
| REQ-2 PC/APP 单控件内按 Sol/Terra/Luna 分组 | major | yes | yes | no | yes | yes | PC catalog tests；`ClaudeWorkerView.integration.test.ts`；PC 设置/Worker 与 APP H5 smoke | covered |
| REQ-3 设置页按族分组勾选具体档位、无组总开关 | major | yes | no | no | yes | yes | PC catalog tests；设置页真实编辑 `codexKEY` smoke | covered |
| REQ-4 `availableModels` 保存具体族+档位并精确授权 | critical | yes | no | no | yes | yes | `CodexTaskServiceTest.java`；PC/APP catalog tests；实际旧授权只显示 Sol Medium/High | covered |
| REQ-5 APP 不配置模型且无 Key 订阅配置仍可选 | major | yes | no | no | yes | yes | APP `llmModelOptions.test.ts`；实际选择配置 `11` 并打开 Codex picker | covered |
| REQ-6 SDK 最高 Max、全部 Ultra fail closed；Ultra 由 App Server 承接 | critical | yes | yes | no | no | no | SDK Worker 4 个定向文件 66/66；Java Runtime 选择测试；PC Ultra readiness integration tests | partially-covered |
| REQ-7 保持统一 `OPENAI_CODEX` backend/Provider | major | yes | yes | no | yes | yes | PC/APP catalog 与 Worker compatibility tests；实际配置选择链路 | covered |
| COMPAT-1 旧 alias/真实 GPT-5.6 值归一化且不扩大授权 | critical | yes | no | no | yes | yes | Java Terra/known-model grant tests；PC/APP normalization tests；PC 旧配置实际归一化 | covered |
| COMPAT-2 未知旧真实模型仅安全回退普通档位 | critical | yes | no | no | yes | yes | PC/APP fallback tests；APP 配置 `11` 实际只显示 Low/Medium/High/Extra High | covered |
| ACC-1 Luna 不显示/不接受 Ultra | critical | yes | no | no | yes | yes | Java Luna Ultra rejection；PC/APP 目录测试；设置页与 APP picker smoke | covered |
| ACC-2 选中值完整回显模型族与档位 | minor | yes | yes | no | yes | yes | `ModelSelect.vue`/`CodexModelPicker.vue`；APP Terra High 回显与 PC 下拉 smoke | covered |

## Evidence Summary

- 已有自动化测试：Java `CodexTaskServiceTest` 55/55；PC model catalog 16 个测试，与 Worker integration 合并定向 32/32；APP 全量 13 files / 44 tests；SDK Worker Ultra/alias/validation/config 定向 66/66。
- 已有构建证据：PC type-check 与 production build、APP H5 production build均通过。
- 已有浏览器验证：PC 实际设置页显示 Sol/Terra/Luna，Luna 无 Ultra，旧授权未扩大；Worker 单下拉仅显示允许的 Sol Medium/High；APP 实际选择无 Key 配置 `11`，三组仅开放普通四档，选择 Terra High 后完整回显；功能页就绪后无控制台错误。
- 已有回归保护：授权精确比较、旧 alias 单档映射、未知配置 Max/Ultra 不开放、Luna Ultra 拒绝、SDK 新建与 resume Ultra fail closed均有自动化测试。

## Gaps

- 尚无签入仓库、可重复运行的 Playwright 用例文件；本轮 browser smoke 是真实自动化执行，但持久证据是 progress/coverage 文档中的步骤和断言。鉴于核心授权逻辑已有单测保护，此缺口不阻断验收，但后续 UI 回归效率偏弱。
- 未执行 Sol/Terra Ultra 的真实 `codex-app-server-worker` 任务；当前只验证了 Java 选路/前端 readiness 与 SDK fail-closed。该证据属于 OPT-001 Dark Worker/canary 门禁，本事项不能据此签收真实 Ultra provider。
- APP 仅覆盖 H5 Chromium，未覆盖 Android/iOS 真机的触控、滚动、底部安全区和 action sheet 差异。
- 本地 RabbitMQ 未连接导致聚合 health 为 503；本次依赖的认证、模型配置和 Worker API 均正常，但正式环境 smoke 仍应在完整依赖健康条件下复核。

## Recommended Next Skills

- `integration-test`：当前无需为了目录逻辑新增 API 集成测试；若后续要固化真实 Ultra 路由链路，应在 OPT-001 下补 runtime integration/e2e。
- `webapp-testing`：建议后续把 PC 设置/Worker 与 APP H5 两条 smoke 固化为可重复 Playwright 用例。
- `foggy-bug-regression-workflow`：当前无新缺陷，不触发。
- `foggy-acceptance-signoff`：可以带上述明确缺口进入 OPT-002 功能验收，但不能代替 OPT-001 签收真实 Ultra provider。
- `plan-evaluator`：当前测试层级与风险匹配，无需额外评估。

## Conclusion

- conclusion：`ready-with-gaps`
- can_enter_acceptance：yes，OPT-002 的目录、配置、兼容和 SDK 边界证据足够；真实 Ultra provider 继续由 OPT-001 独立门禁约束。
- follow_up_required：yes，正式发布前补 Android/iOS 真机 smoke；后续固化 Playwright 用例；Ultra provider 按 OPT-001 完成真实链路与 canary 证据。
