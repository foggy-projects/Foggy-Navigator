# OPT-002 Codex 分组模型目录进度

## 文档作用

- doc_type: progress
- intended_for: execution-agent | reviewer | signoff-owner
- purpose: 记录 Codex 分组配置、PC/APP 选择、授权归一化与验证进度。

## 基本信息

- version: `1.4.0-SNAPSHOT`
- status: coverage-audited
- workitem: [OPT-002](./OPT-002-codex-model-catalog-boundary.md)
- implementation_started_at: 2026-07-11
- last_updated_at: 2026-07-11

## Development Progress

| Step | 范围 | 状态 | 说明 |
|---|---|---|---|
| 1 | 需求落档与方案 review | completed | 复用 `availableModels`，确定规范值、旧 alias 归一化和 Runtime 双重约束 |
| 2 | Java 授权归一化与测试 | completed | 已识别目录项按规范值精确授权；旧 alias 不扩大授权；Luna Ultra 在任务边界直接拒绝 |
| 3 | PC 分组目录、设置与选择 | completed | 设置页与 Worker 各入口统一使用单控件分组目录，移除 Codex Mini |
| 4 | APP 分组选择与订阅配置修复 | completed | 新建任务使用分组面板；订阅配置不再依赖 API Key 才可选 |
| 5 | 定向测试、构建与文档回写 | completed | Java、PC、APP 测试与构建通过，证据已回写 |
| 6 | PC / APP 真实浏览器 smoke 与实现质量闸门 | completed | PC 设置/Worker 分组下拉和 APP 订阅配置/分组选择均通过；质量结论为 `ready-with-risks` |
| 7 | 测试证据覆盖审计 | completed | requirement/acceptance 到 Java、PC、APP、SDK Worker 与 browser smoke 的映射已完成，结论为 `ready-with-gaps` |

## Testing Progress

| Test lane | 状态 | Evidence |
|---|---|---|
| Java CodexTaskService | passed | `CodexTaskServiceTest`: 55 tests, 0 failures；`mvn -pl addons/codex-worker-agent -am test` reactor success |
| PC model catalog / component tests | passed | model catalog 16 tests；与 `ClaudeWorkerView.integration.test.ts` 合并定向运行共 32 tests |
| PC type-check / build | passed | `vue-tsc --noEmit` 通过；`bash scripts/build-frontend.sh` 通过 |
| APP model catalog tests | passed | model catalog 4 tests；APP 全量 13 files / 44 tests 通过 |
| APP H5 build | passed | H5 production build 通过 |
| PC browser smoke | passed | 实际登录后验证设置页 Sol/Terra/Luna 分组、Luna 无 Ultra、旧授权只开放 Sol Medium/High；Worker 单下拉仅显示配置授权的 Sol Medium/High，无 Mini，控制台无错误 |
| APP H5 browser smoke | passed | 实际登录并进入本机测试/Foggy Navigator；无 Key 的 Codex 配置 `11` 可选，旧真实模型名单安全回退为三组 Low/Medium/High/Extra High；选择 Terra High 后回显正确，任务页就绪后控制台无错误 |
| SDK Worker Runtime boundary | passed | `query-route-paths`、`sdk-wrapper`、`query-validation`、`config` 定向运行 66/66；新建/resume Ultra 在创建任务状态前 fail closed，Max 仍可解析 |

## Experience Progress

| 体验维度 | 检查项 | 状态 | Evidence |
|---|---|---|---|
| 页面可达性 | PC Worker 与设置入口可正常编译渲染 | passed-live-smoke | 实际登录并打开设置/LLM 配置与 Worker 页面，页面和模型控件可用 |
| 核心交互 | 单下拉按 Sol/Terra/Luna 分组选择 reasoning | passed-live-smoke | 设置页下拉显示三组；Worker 下拉按当前授权只显示 Sol Medium/High |
| 配置交互 | 勾选档位即开放对应模型族，无组总开关 | passed-live-smoke | 旧 `codex-deep`/`codex-latest` 等授权在编辑时归一化为具体 Sol 档位，未扩大到整个模型族 |
| APP 交互 | 订阅配置可选，分组模型面板可选择并回显 | passed-live-smoke | 无 API Key 的配置 `11` 可选；分组三组展示，Terra High 选择并回显成功 |
| 能力边界 | Luna 无 Ultra；SDK Ultra 不产生可执行误导 | passed | PC/APP 目录不生成 Luna Ultra；Java 定向回归验证请求拒绝；SDK Ultra 既有 fail-closed 保持不变 |

> 已完成 Chromium 下的 PC 与 APP H5 真实交互 smoke。尚未覆盖 Android/iOS 真机；headless 环境缺少中文字体只影响截图字形，不影响 DOM 文案、选项和值的断言。

## Self-Check

- [x] requirement scope 按已确认交互实现。
- [x] 未把其他编程 Worker 固化成双控件或 Codex 分组结构。
- [x] 旧 alias 兼容未扩大授权。
- [x] SDK/App Server Runtime 边界未被 UI 配置绕过。
- [x] 未覆盖用户已有 Worker 发布脚本改动。
- [x] 测试与构建已实际运行并回写。

- self_check_decision: passed
- formal_quality_gate_required: completed
- quality_gate: [OPT-002 implementation quality](../quality/OPT-002-codex-model-catalog-implementation-quality.md)
- coverage_audit: [OPT-002 test coverage audit](../coverage/OPT-002-codex-model-catalog-coverage-audit.md)

## 阻塞项

| Blocker | 状态 | 说明 |
|---|---|---|
| 真实 Ultra provider 执行 | inherited | 继续受 OPT-001 App Server readiness/canary gate 约束，不阻塞本事项本地实现 |

## Execution Check-in

- completed_work: 需求落档、方案 review、Java 精确授权边界、PC 分组配置与选择、APP 分组选择和订阅配置兼容均已完成
- touched_code_paths: `addons/codex-worker-agent`、`packages/navigator-frontend`、`packages/foggy-mobile`、`docs/version-tracker/1.4.0-SNAPSHOT`
- tests: Java 55/55；PC 定向 32/32；APP 全量 44/44；PC type-check、PC production build、APP H5 build 通过
- experience: PC 与 APP H5 真实浏览器 smoke 已完成；分组、精确授权、订阅配置选择和回显均符合需求
- remaining_risks: 真实 Ultra provider 执行仍受 OPT-001 readiness/canary gate 约束；PC/APP/Java 各自维护目录镜像，后续新增模型族或档位时必须同步更新并由测试防漂移；真机尚未覆盖
- acceptance_readiness: ready-for-acceptance-with-gaps
