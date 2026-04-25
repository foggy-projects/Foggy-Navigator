---
acceptance_scope: feature
version: 1.0.4-SNAPSHOT
target: 04-codex-worker-alias-only
doc_role: acceptance-record
doc_purpose: 记录 1.0.4 Codex alias-only 重构的正式验收结论与签收边界
status: signed-off
decision: accepted-with-risks
signed_off_by: release-owner
signed_off_at: 2026-04-25
reviewed_by: reviewer
blocking_items: []
follow_up_required: yes
evidence_count: 5
---

# Feature Acceptance

## Background

- Version: `1.0.4-SNAPSHOT`
- Target: `04-codex-worker-alias-only`
- Owner: `codex-agent-worker + navigator-frontend + foggy-mobile`
- Goal: 完成 Codex alias-only 收口，使前端、移动端、Java 后端与真实模型版本解耦，并将未来模型升级收口到 Worker 侧 `CODEX_MODEL_ALIASES`

## Acceptance Basis

- [04-codex-worker-gpt55-upgrade-and-model-alias-plan.md](../04-codex-worker-gpt55-upgrade-and-model-alias-plan.md)
- [05-codex-alias-only-release-note.md](../05-codex-alias-only-release-note.md)
- [codex-worker-alias-resolution.txt](../evidence/codex-worker-alias-resolution.txt)
- [settings-codex-alias-dropdown.png](../evidence/settings-codex-alias-dropdown.png)
- [clauseworkerview-codex-deep-alias.png](../evidence/clauseworkerview-codex-deep-alias.png)

## Checklist

- [x] Worker 新增 `CODEX_MODEL_ALIASES` 与 `resolveModelAlias`，并覆盖整串命中、`alias:reasoning` 拼接、alias 自带 reasoning、真实模型透传四类路径
- [x] Worker 默认模型已从真实模型串切换为 alias `codex-latest`
- [x] PC 设置页、PC 任务页、移动端任务页均已切换到 4 个 Codex alias，前端不再暴露 `gpt-5.x` 真实模型
- [x] 历史 `availableModels` 为真实模型名的数据已通过兼容兜底自动迁移，不阻塞旧配置继续使用
- [x] Worker `npm test`、`npm run typecheck`、Frontend `vitest`、前端构建、Playwright 目视验证均已给出有效证据
- [x] 运维依赖项与代码范围边界已明确记录，真实 Codex 鉴权 smoke 作为后续跟进项单独处理

## Evidence

- Requirement / Plan: [04-codex-worker-gpt55-upgrade-and-model-alias-plan.md](../04-codex-worker-gpt55-upgrade-and-model-alias-plan.md)
- Worker Alias Resolution Log: [codex-worker-alias-resolution.txt](../evidence/codex-worker-alias-resolution.txt)
- Settings Alias Dropdown Screenshot: [settings-codex-alias-dropdown.png](../evidence/settings-codex-alias-dropdown.png)
- ClaudeWorkerView Alias Screenshot: [clauseworkerview-codex-deep-alias.png](../evidence/clauseworkerview-codex-deep-alias.png)
- Automated Verification Summary: `Worker npm test 53/53 PASS`, `Worker npm run typecheck clean`, `Frontend vitest 8/8 PASS`, `bash scripts/build-frontend.sh clean`，详见 [04-codex-worker-gpt55-upgrade-and-model-alias-plan.md](../04-codex-worker-gpt55-upgrade-and-model-alias-plan.md)

## Failed Items

- none in feature scope

## Risks / Open Items

- Worker 主机在 `2026-04-25` 暴露出 Codex CLI 订阅 token 失效，导致真实任务无法完成最新一轮 live smoke；这属于运维鉴权依赖，不构成 alias-only 代码范围内的失败项
- 在 `codex login` 或有效 API Key 恢复前，端到端“真实 Codex 返回成功结果”的最新证据仍缺失，因此保留 follow-up
- 若后续 smoke 显示订阅模式与 API 模式存在差异，需要把差异回写到 Worker 运维文档或模型配置说明，但不需要回滚 alias-only 设计

## Final Decision

本功能结论为 `accepted-with-risks`。

签收范围限定为 `alias-only` 结构重构、兼容迁移、前端展示收口、Worker alias 解析与相关自动化验证。现有证据足以证明模型别名链路和升级收口点已经按设计工作，因此该改动可进入发布口径。

遗留风险仅在真实鉴权 smoke。它是当前 Worker 主机认证状态导致的环境依赖，而不是本次代码改动引入的行为回归。为避免把“环境凭据过期”误判成“alias-only 方案不成立”，本次签收不以该项作为阻断条件，但要求后续按执行清单完成补验。

## Signoff Marker

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: release-owner
- signed_off_at: 2026-04-25
- acceptance_record: docs/version-tracker/1.0.4-SNAPSHOT/acceptance/04-codex-worker-alias-only-acceptance.md
- blocking_items: none
- follow_up_required: yes
