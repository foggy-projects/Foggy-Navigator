# Upstream Runtime Provisioning Handoff Prompt

## 文档作用

- doc_type: handoff-prompt
- intended_for: upstream-agent | upstream-developer
- purpose: 给上游项目执行 Navigator dev runtime provisioning 的交付提示词模板；真实 dev key 通过安全渠道另行交付，不写入本文档。

## 使用边界

- 不访问真实 TMS。
- 不读取 `accounts/`。
- 不打印、提交或粘贴 admin key、control key、runtime key、runtime access token、ClientApp secret、LLM key、claim token、cookie、真实账号或密码。
- 真实凭据只放 gitignored `.navigator/upstream.env`、`.navigator/tenants/<tenant>.env` 或平台 secret。
- owner-smoke 和 live smoke 通过前，不派发首轮 UI 巡检任务。

## Dev Key Package

通过安全渠道交付给上游的 dev credential package 应包含三条 lane：

| Lane | Profile Field | Purpose |
| --- | --- | --- |
| Upstream admin | `NAVI_ADMIN_API_KEY` | worker-host apply、ClientApp ensure、issue control/runtime key、upstream-system resource provisioning |
| ClientApp control | `NAVI_CONTROL_API_KEY` | modelConfig、grant/default、Agent sync、model/workspace/worker binding、upstream user grant |
| Runtime credential | `NAVI_CLIENT_APP_KEY` + `NAVI_CLIENT_APP_SECRET` | runtime-token exchange、readiness、owner-smoke、live smoke |

`NAVI_CLIENT_APP_ACCESS_TOKEN` 可以短期存在，但应可由 `NAVI_CLIENT_APP_KEY` + `NAVI_CLIENT_APP_SECRET` 重新换取。

## Prompt Template

```text
请在当前上游项目中完成 Navigator dev runtime provisioning 收口。

安全边界：
- 不访问真实 TMS。
- 不读取 accounts/。
- 不打印、提交或粘贴 token、secret、cookie、真实账号或密码。
- 真实 dev key 只能写入 gitignored .navigator/upstream.env 或 .navigator/tenants/<tenant>.env。
- owner-smoke 和 live smoke 通过前，不派发首轮 UI 巡检任务。

目标资源：
- upstreamSystemId: <upstreamSystemId>
- tenantId: <tenantId>
- clientAppId: <clientAppId>
- agentCode: <agentCode>
- upstreamUserId: <upstreamUserId>
- modelConfigId: <modelConfigId>
- directoryId: <directoryId>
- expectedBizWorkerId: <bizWorkerId>
- expectedActorHome: <actorHomePath>
- liveSmokePrompt: <promptFile>

Credential lanes：
- Upstream admin lane: 使用 NAVI_ADMIN_API_KEY，仅做 worker-host apply、ClientApp/bootstrap 和 upstream-system 管理动作。
- ClientApp control lane: 使用 NAVI_CONTROL_API_KEY，仅做当前 ClientApp 下 model/grant/Agent/workspace/worker binding。
- Runtime lane: 使用 NAVI_CLIENT_APP_KEY + NAVI_CLIENT_APP_SECRET 换取 runtime token，仅做 readiness、owner-smoke、ask/live smoke。

执行顺序：
1. 确认 profile 路径被 gitignore 覆盖，运行 profile 检查，不打印 secret。
2. 执行 runtime-token --write-profile，确认输出 runtimeToken.expiryStatus=OK。
3. 执行 admin-key inspect，确认 credential expiryStatus 不是 EXPIRED；如为 EXPIRING_SOON，记录轮换窗口后继续开发环境 smoke。
4. 执行 worker-host verify，再用 upstream admin lane 执行 worker-host apply --write-profile。
5. 使用 ClientApp control lane 确认 modelConfig / grant / Agent sync / model binding / workspace binding / worker binding。
6. 执行 verify-agent-readiness，要求 WORKER_HOST_ROLE_ROUTING=OK，workerRole role=biz source=BIZ_WORKER_IDENTITY，effectiveDirectoryId=<directoryId>。
7. 执行 owner-smoke，要求 readiness OK 和 resources OK。
8. 使用指定 liveSmokePrompt 执行 Actor Home live smoke；只验证 Actor Home 和 Navigator runtime，不访问真实 TMS。
9. 执行跨 upstream / 跨 ClientApp 负向 smoke，确认当前 credential 不能读取或绑定其他上游资源。
10. 回填 runtime profile、rehearsal run、版本 test record；tracked 文件只写资源 ID、命令类别和通过状态。
11. 对 staged diff 执行 secret-like scan 后再提交。

交付输出：
- 资源 ID 与绑定结果。
- readiness / owner-smoke / live smoke 通过状态。
- credential expiryStatus 摘要，不包含真实 key。
- 跨 upstream 负向 smoke 结果。
- 变更文件与测试命令。
```

## TMS Reviewer Filled Values

用于 TMS UI Experience Reviewer `tms-ui-experience-reviewer-a` 的已验证开发环境参数：

- upstreamSystemId: `tms`
- scope: `tms-ltl-ui-qa`
- role: `ui-experience-reviewer`
- clientAppId: `capp_2852124a-48f7-4098-9d5e-33eb736c4375`
- agentCode: `world-sim.biz-worker-browser-smoke.v1`
- directoryId: `20260705-228b`
- modelConfigId: `a8ed6f14-949c-4003-b108-99b78de65ff5`
- bizWorkerId: `tms-ui-experience-reviewer-biz`
- actorHome: `/home/navigator/foggy-world-sim-navi-validation/owner-aware-107fix/developer/tms-ui-experience-reviewer-a`
- liveSmokePrompt: `docs/scopes/tms/tms-ltl-ui-qa/rehearsals/prompts/ui-experience-reviewer-actor-home-live-smoke-20260705-001.md`

真实 dev key 不写入本文档；交付时通过安全渠道提供，或让上游在本地 gitignored profile 中接收。
