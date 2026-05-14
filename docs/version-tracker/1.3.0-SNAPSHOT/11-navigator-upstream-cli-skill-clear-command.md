# Navi Upstream CLI Skill 清空命令

## 文档作用

- doc_type: workitem
- intended_for: execution-agent, reviewer, signoff-owner
- purpose: 将 TMS 提交的旧公共 Skill 清理诉求纳入 Navigator 当前迭代，并跟踪 CLI、服务端 API 与验收结果。

## 基本信息

- version: 1.3.0-SNAPSHOT
- source_type: optimization
- priority: P1
- status: in-progress
- requester: TMS
- owner: Navigator upstream skill 管理能力
- upstream_source: `D:/workspace/tms-x6-dev/docs/v3.2.0/workitems/NAVI-CLI-public-skill-clear-command.md`
- related_tms_commit: `305595f1 refactor navigator skills by business domain`
- target_window: TMS Stage 3 下线旧 Skill 前提供可用清理入口

## 背景

TMS 已将历史大 Skill 切换为业务域 Skill：

- `tms-order-agent`
- `tms-pay-agent`
- `tms-fulfillment-agent`
- `tms-route-agent`
- `tms-ticket-agent`
- `tms-basic-agent`
- `tms-attachment-agent`
- `foggy-query-agent`

旧 Skill 将从 TMS 资源目录移除：

- `x3-tms-cli`
- `tms-x3-agent-v305`
- `tms-order-opening-v1`
- `tms-ticket-v1`

TMS 删除本地资源并重新 sync 后，Navigator 侧可能仍保留历史 public skill、client app skill grant、account materialized skill bundle 或相关缓存。需要由 Navigator 提供正式 CLI 运维能力来预览和清理这些数据。

## 问题陈述

当前 `navi upstream skill sync` 只负责新增或更新 Skill bundle，缺少删除旧 Skill 的正式入口。若上游停止上报某个旧 Skill，Navigator 不会自动推断该 Skill 应被删除。

风险：

- public skill registry 与 TMS 当前资源目录不一致。
- client app skill grant 仍可能暴露旧 Skill。
- account 维度 materialized bundle 可能继续带出旧 Skill。
- 回归环境无法区分新路由能力与历史持久化残留。
- 临时删库或一次性脚本缺少 dry-run 和确认机制。

## 目标结果

提供 `navi upstream skill clear-public` 与 `navi upstream skill clear-account` 运维命令，并在服务端提供对应 control-plane API。

目标能力：

- 支持按 `clientAppId` 清理 public skill。
- 支持按 `clientAppId + skillId` 清理指定 public skill。
- 支持按 `clientAppId + accountId` 清理账号 materialized bundle。
- 支持按 `clientAppId + accountId + skillId` 清理账号指定 skill bundle。
- 支持 `--dry-run` 预览命中对象和数量。
- 非 dry-run 必须显式传入 `--yes`。
- zero-match 必须明确输出，不静默伪装成删除成功。

## 实现范围

### CLI

- owned_path: `navigator-open-sdk/src/main/java/com/foggy/navigator/sdk/cli/UpstreamCli.java`
- 增加命令：
  - `upstream skill clear-public`
  - `upstream skill clear-account`
- 使用 control-plane credential：
  - `NAVI_CONTROL_API_KEY`
  - 或 admin fallback：`NAVI_ADMIN_TOKEN` / `NAVI_ADMIN_API_KEY`
- 输出 summary 时不得打印密钥或 token。

### SDK API

- owned_path: `navigator-open-sdk/src/main/java/com/foggy/navigator/sdk/api/BusinessAgentApi.java`
- 增加调用服务端 skill clear API 的方法。
- 增加请求和结果 DTO。

### 服务端

- owned_paths:
  - `business-agent-module/src/main/java/com/foggy/navigator/business/agent/controller/SkillRegistryController.java`
  - `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/SkillRegistryService.java`
  - `business-agent-module/src/main/java/com/foggy/navigator/business/agent/repository/*`
- 增加 public/account clear API。
- 删除或禁用范围至少覆盖：
  - `skill_bundle`
  - `skill`
  - `client_app_skill_grant`
  - `skill_function_allowlist`
  - account 维度 materialized bundle
- 若当前存储没有独立缓存/索引表，summary 中明确返回 `cacheCount=0`。

## 建议命令

```bash
navi upstream skill clear-public --client-app-id <clientAppId> --dry-run
navi upstream skill clear-public --client-app-id <clientAppId> --skill-id <skillId> --dry-run
navi upstream skill clear-public --client-app-id <clientAppId> --skill-id <skillId> --yes

navi upstream skill clear-account --client-app-id <clientAppId> --account-id <accountId> --dry-run
navi upstream skill clear-account --client-app-id <clientAppId> --account-id <accountId> --skill-id <skillId> --dry-run
navi upstream skill clear-account --client-app-id <clientAppId> --account-id <accountId> --skill-id <skillId> --yes
```

`upstreamRef` 暂不作为本轮硬依赖；若服务端已有稳定 `upstreamRef -> clientAppId` 映射，再补充别名参数。

## 验收标准

1. public dry-run 能列出将影响的 `skillId` 与对象数量。
2. public clear 后，该 `clientAppId` 下旧 public skill bundle、legacy skill、client app skill grant 和 skill function allowlist 不再命中。
3. account dry-run 能列出指定 `accountId` 下将影响的 bundle。
4. account clear 后，指定账号 materialized bundle 不再包含旧 Skill。
5. 非 dry-run 缺少 `--yes` 时 CLI 返回非 0，并提示需要确认参数。
6. zero-match 返回成功 summary，但必须输出 `matchedSkillCount=0` 或等价字段。
7. CLI 输出不泄漏 control key、admin token、client app token。
8. 单元测试覆盖 CLI 路由、服务端 dry-run、服务端 delete、zero-match。

## 非目标

- 不兼容或保留 TMS 旧 Skill。
- 不在本轮实现 TMS 侧资源目录下线。
- 不通过 TMS 脚本直接操作 Navigator 数据库。
- 不同时调整业务域 Skill 路由策略。

## Progress Tracking

### Development Progress

- status: implemented
- completed:
  - 已接收 TMS 侧需求文档并纳入 Navigator `1.3.0-SNAPSHOT`。
  - 已实现 clear form/result DTO。
  - 已实现服务端 `clear-public` / `clear-account` control-plane API。
  - 已实现 CLI `navi upstream skill clear-public` 与 `navi upstream skill clear-account`。
  - 已实现 worker materialized skill 清理入口 `/api/v1/skills/clear`。
  - 已补充 CLI 与 SkillRegistryService 单元测试。
  - 已登记到当前迭代 README。
- current:
  - 等待集成环境使用真实 TMS clientApp/account 做 dry-run 与 delete 验收。
- next:
  - TMS 下线旧 Skill 前，先执行 dry-run，确认命中对象后再带 `--yes` 清理。

### Testing Progress

- status: passed
- completed:
  - `mvn test -pl navigator-open-sdk -Dtest=UpstreamCliTest`
  - `mvn test -pl business-agent-module -am -Dtest=SkillRegistryServiceTest '-Dsurefire.failIfNoSpecifiedTests=false'`
  - `python -m py_compile tools/langgraph-biz-worker/src/langgraph_biz_worker/routes/skills.py`
- note:
  - 直接执行 `mvn test -pl business-agent-module -Dtest=SkillRegistryServiceTest` 会使用本地旧依赖 jar；带 `-am` 后已验证通过。

### Experience Progress

- status: N/A
- reason: 本项为 CLI 与服务端 control-plane API 能力，无 Web UI 交互变更。

## Execution Checklist

- [x] 文档已登记到当前迭代 README。
- [x] CLI 命令已实现。
- [x] SDK API 已实现。
- [x] 服务端 API 已实现。
- [x] worker materialized skill 清理入口已实现。
- [x] dry-run summary 覆盖对象数量。
- [x] 删除执行需要 `--yes`。
- [x] 单元测试通过。
- [x] 进度和验收状态已回写。
