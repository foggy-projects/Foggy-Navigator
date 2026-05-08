# Stage 10D Upstream Auto Bootstrap Contract Acceptance

## 文档作用

- doc_type: acceptance-record
- version: 1.1.3-SNAPSHOT
- stage: Stage 10D
- status: accepted
- decision: accepted
- date: 2026-05-05
- purpose: 验收上游自动 bootstrap 契约与 LLM-facing skill/runbook 更新，减少真实联调对手工提示词的依赖

## 验收范围

Stage 10D 不新增生产代码，目标是把上游接入流程标准化为可由上游 LLM coding agent 执行的自动化契约：

1. Navigator 文档新增 upstream auto bootstrap contract。
2. TMS 最小接入样例与自动化契约互相链接。
3. personal `navigator-upstream-llm-integration` skill 增加 TMS auto bootstrap runbook、manifest template 和 env template。
4. 明确上游自动化与 Navigator 内部 Worker Gateway 验证的边界。

## 验收结论

Accepted。

本阶段将真实联调从“交给上游一段长提示词”升级为：

- 非敏感 manifest：可提交到上游仓库。
- 本地 secret env：只保存在本地或 CI secret，不进入 prompt。
- bootstrap runner：由上游 LLM 自动实现/运行。
- Navigator E2E：仍由 Navigator 内部完成 Worker Gateway 和真实 invoke 验证。

## 安全边界

1. 上游 bootstrap 不调用 `/internal/worker-gateway/v1/**`。
2. 上游 token 只通过 SDK grant 提交到 Navigator 服务端，不进入 manifest。
3. `task_scoped_token` 不进入 LLM prompt、tool schema、前端状态或上游 bootstrap 输出。
4. TMS `expressOrderId` / `esOrderId` 只允许出现在安全红线说明和负向检查中，不进入 LLM-facing schema。
5. Manifest 不允许覆盖 `Authorization`、`X-TMS-Agent-Token` 或 `X-Navigator-*` header。

## Evidence

新增/更新文件：

- `upstream-integration/14-upstream-auto-bootstrap-contract.md`
- `upstream-integration/13-tms-minimal-onboarding-sample.md`
- `08-implementation-plan.md`
- `README.md`
- personal skill:
  - `navigator-upstream-llm-integration/SKILL.md`
  - `references/tms-auto-bootstrap-runbook.md`
  - `assets/tms-navigator-agent.manifest.template.json`
  - `assets/tms-navigator-agent.env.example`

## Remaining Risks

1. 本阶段只交付自动化契约和模板；TMS 仓库中的实际 bootstrap runner 仍需上游会话实现。
2. 正式联调环境尚未从当前开发环境拆出。
3. 真实 Worker invoke 仍需要 Navigator 侧服务和 LangGraph Biz Worker 同时运行。
