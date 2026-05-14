---
doc_type: bug-regression
version: 1.1.3-SNAPSHOT
status: implemented
date: 2026-05-14
source: tms-upstream-validation
severity: major
scope: navigator-upstream-cli | business-agent-module | langgraph-biz-worker | navigator-common
---

# TMS E2E Agent Tenant Scope Bug

## 背景

TMS 复测 Navigator deterministic E2E 时，授权链路显示 Skill、upstream user、model grant 均已可见，但 `verify-agent-readiness` 的 `AGENT_REGISTERED` 失败，真实 `ask` 返回 `Agent not found: tms-x3-agent-v305`。

同时，TMS 重新执行 `upstream agent sync` 时返回：

```text
agentId already exists in another tenant: tms-x3-agent-v305
```

这说明 Skill Registry 已经按 `tenantId + ClientApp` 隔离，而 Agent Registry 仍把 `coding_agents.agentId` 当成全局唯一资源，导致同一个上游项目在不同交付租户中无法注册相同逻辑 Agent。

TMS 还反馈 `navi-e2e script register --file` 对 mock LLM 仍出现 HTTP 422 body missing；同一 JSON 直接 POST 可成功。该问题需要让 CLI 对 mock service 使用更保守的固定长度 HTTP/1.1 body 写入，避免中间层或 Java HTTP 客户端行为差异。

## 预期行为

1. Business Agent Bundle 的 `agentId` 在 Open API 路径上应由当前 `tenantId` 解析，允许不同 tenant 使用相同逻辑 `agentId`。
2. `agent sync` 应只更新当前 tenant 下的 Agent，不应被其他 tenant 的同名 Agent 阻塞。
3. LangGraph Agent Provider 在 Open API 场景应使用 `agentId + tenantId` 精确解析。
4. CLI `script register --file` 应向 mock LLM 服务发送带固定 `Content-Length` 的 JSON body。

## 修复清单

- [x] `coding_agents.agentId` 从全局唯一调整为 `tenantId + agentId` 唯一。
- [x] 增加 MySQL 启动期兼容迁移，删除旧的单列 `agentId` 唯一索引并创建 `uk_ca_tenant_agent_id`。
- [x] Business Agent Bundle sync 改为按 `agentId + tenantId` 查找/更新 Agent。
- [x] LangGraph Open API Agent 解析改为按 `agentId + tenantId` 查询。
- [x] `navi-e2e` mock service 调用从 Java `HttpClient` 改为固定长度 `HttpURLConnection` JSON body。
- [x] 补充 Agent tenant scope 与 CLI request body 回归测试。

## 验收标准

- TMS 重新安装 CLI 后，`navi-e2e script register --file` 不再返回 body missing。
- TMS 在当前 tenant 下重新执行 `upstream agent sync`，不再被其他 tenant 的同名 `agentId` 阻塞。
- `verify-agent-readiness --agent-code tms-x3-agent-v305 --model-config-id <e2eModelConfigId>` 的 `AGENT_REGISTERED` 为 OK。
- 使用 E2E model 发起 ask 后，mock LLM `debug requests` 能看到 scripted cursor 命中记录。

## 验证记录

2026-05-14 已完成 Navi 侧回归验证：

```bash
mvn -pl navigator-open-sdk,business-agent-module,addons/langgraph-biz-worker -am "-Dtest=E2eCliTest,BusinessAgentBundleServiceTest,LanggraphWorkerAgentProviderTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
mvn -pl business-agent-module,addons/langgraph-biz-worker,navigator-open-sdk -am test
```

结果均为 `BUILD SUCCESS`。

Navigator Upstream CLI 已重新打包并上传 OBS：

```text
version=1.0.0-SNAPSHOT
sha256=156e38cb1495d4b47142df840b1ccde63d924f87db6a758a1a282a1c43e326fd
install=irm https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/navigator-upstream-cli/install.ps1 | iex
```

交付注意：server 侧修复需要 Navi 服务重启后执行启动期兼容迁移，删除旧的 `coding_agents.agentId` 单列唯一索引并创建 `tenantId + agentId` 复合唯一索引。完成部署后，TMS 再重新安装 CLI、执行 `agent sync`、`verify-agent-readiness` 和 deterministic E2E。

## Follow-up：迁移未加载修复

2026-05-14 TMS 继续复测时发现 `/api/v1/business-agent/agent-bundles/sync` 仍返回 HTTP 500。服务端异常为：

```text
Duplicate entry 'tms-x3-agent-v305' for key 'coding_agents.UKeddxfpd32ms7ipwct7muv5m9j'
```

根因是 `CodingAgentTenantScopeMigration` 已实现，但 `CommonAutoConfiguration` 只扫描了 `com.foggy.navigator.common.security`，没有扫描 `com.foggy.navigator.common.migration`，导致服务启动时兼容迁移未执行，旧的 `coding_agents.agent_id` 单列唯一索引仍保留。

本次补充：

- `CommonAutoConfiguration` 增加 `com.foggy.navigator.common.migration` component scan。
- `navigator-common` 增加自动配置扫描回归测试，防止迁移包再次脱离自动配置。

本地重启 `start-launcher.ps1` 后验证：

```text
coding_agents.UKeddxfpd32ms7ipwct7muv5m9j removed
uk_ca_tenant_agent_id(tenant_id, agent_id) exists
```

TMS workspace 复测：

```powershell
.\tools\navigator-upstream\navi.ps1 upstream agent sync --manifest .navigator\agent-bundle.json
.\tools\navigator-upstream\navi.ps1 upstream verify-agent-readiness
```

结果：

```text
agent sync ok
agentId=tms-x3-agent-v305
clientAppId=capp_9a878af4-aba5-4c26-a876-9b29b58751fb

verify-agent-readiness OK
agentCode=tms-x3-agent-v305
requestedModelConfigId=17124893-0150-4018-987d-23e0391c85a9
effectiveModelConfigId=17124893-0150-4018-987d-23e0391c85a9
AGENT_REGISTERED=OK
CLIENT_APP_SKILL_GRANT=OK
UPSTREAM_USER_GRANT=OK
MODEL_CONFIG_GRANT=OK
```

## Follow-up：OpenAPI/Provider 仍有全局 Agent 查询

2026-05-14 TMS 在 `28680b90` 基础上继续验证 deterministic attachments E2E，确认 `agent sync` 与 readiness 已恢复，但 OpenAPI ask 创建 task 后仍出现 HTTP 500。服务端异常为：

```text
IncorrectResultSizeDataAccessException: Query did not return a unique result: 2 results were returned
... CodingAgentRepository.findByAgentId
... OpenApiController.resolveAgentOwnerUserId
```

原因是旧全局唯一索引删除后，跨 tenant 同名 `agent_id` 已是合法状态；因此任何有 tenant 上下文的 OpenAPI/runtime 路径都不能再调用 `findByAgentId(agentId)` 后在 Java 内存中过滤 tenant，否则 Spring Data 会先因多行结果抛异常。

本次正式修复：

- `OpenApiController.listAgentTasks` 和 `resolveAgentOwnerUserId` 改为 `findByAgentIdAndTenantId(agentId, tenantId)`。
- `ClaudeWorkerAgentProvider`、`CodexWorkerAgentProvider`、`GeminiWorkerAgentProvider` 的 `OPEN_API` tenant 分支改为 tenant-scoped repository lookup。
- 对 Claude/Codex/Gemini provider 与 OpenAPI context resume 路径补充回归测试，断言 tenant 分支不会调用全局 `findByAgentId`。

TMS 本地验证补丁已证明修复后可通过 attachments deterministic E2E：

```text
taskId=lgt_ff6d6a88a6814f3c
result=ATTACHMENT_E2E_OK
mock debug matched=true
model=navigator-e2e-scripted
```
