# Navigator Upstream CLI Requirement

## 文档作用

- doc_type: workitem
- version: 1.1.3-SNAPSHOT
- status: recorded
- date: 2026-05-11
- priority: P0
- source_type: requirement
- intended_for: navigator-execution-agent | upstream-llm-coding-agent | upstream-backend-developer | upstream-frontend-developer | reviewer
- purpose: 将 Navigator Upstream CLI 的需求、命令边界、安全约束、测试与交付标准纳入 1.1.3 上游接入迭代

## 迭代落点

本需求落到 `1.1.3-SNAPSHOT`。

选择原因：

1. `1.1.3-SNAPSHOT` 已经承载 ClientApp、Business Agent、runtime credential、upstream-user grant、task message polling、TMS 最小接入与自动 bootstrap 契约。
2. CLI 是现有上游接入链路的联调工具化交付，不是独立的新平台能力。
3. 上游系统当前痛点是 BFF/前端联调成本高、容易误用 REST/internal API，和 `upstream-integration` 文档组的目标一致。

不新建版本。若后续 CLI 扩展到正式运维、批量治理、跨租户管理或发布产物体系，再单独纳入后续版本。

## 背景

至少两个上游系统将接入 Navigator。当前上游联调依赖手工阅读文档、拼接 curl 或直接调用 SDK/REST，容易出现以下问题：

- 把 ClientApp secret 当成 ask 凭据长期传递。
- 忘记先交换 runtime access token。
- 在 ask 阶段误以为 Navigator 会自动授权未知 upstream user。
- 直接或间接调用 `/internal/worker-gateway/v1/**`。
- 将 token、`adapterConfigJson`、`manifestJson` 等敏感内容暴露给 LLM、前端 DTO、日志或联调报告。
- BFF/前端轮询协议不一致，无法稳定复现 task 消息流。

因此需要提供 Navigator 侧 CLI，封装上游常用联调动作，让上游 Agent 和开发者通过受控命令完成主链路验证。

## 目标结果

交付首版可运行 CLI，命令入口为 `navi` / `navi.ps1`，子命令为 `upstream`。

CLI 交付模型调整为“远程发布 + 上游项目本地安装”：

```text
upstream-project/
  tools/navigator-upstream/
  .navigator/upstream.env
```

每个上游项目安装自己的 CLI wrapper，并维护自己的 `.navigator/upstream.env`。不要求多个上游项目共用同一台机器上的全局环境变量或同一个 profile。

首版优先打通以下主链路：

```text
config check
  -> runtime-token
  -> ensure-grant(current upstreamUserId only)
  -> ask
  -> messages --poll --interval 4
  -> sessions / session-messages
```

TMS test-only helper 可以作为可选能力纳入，但必须遵守 token 只从 env/profile 读取、不打印明文的约束。

## 初始命令设计

```bash
navi upstream config check
navi upstream runtime-token --client-app-key <key> --client-app-secret-env NAVI_CLIENT_APP_SECRET
navi upstream ensure-grant --client-app-id <id> --upstream-user-id <id> --upstream-user-token-env TMS_STAFF_SESSION_TOKEN --admin-token-env NAVI_ADMIN_TOKEN
navi upstream ask --agent tms-agent-v305 --upstream-user-id 88801 --client-app-access-token-env NAVI_CLIENT_APP_ACCESS_TOKEN --message "..."
navi upstream messages --agent tms-agent-v305 --task-id <taskId> --poll --interval 4
navi upstream sessions --agent tms-agent-v305
navi upstream session-messages --agent tms-agent-v305 --context-id <contextId>
```

可选 TMS test-only helpers：

```bash
navi upstream tms token issue-staff --write-env .navi-upstream.env
navi upstream tms order create-self-pickup-sign-ready
navi upstream tms order readiness --order-identifier <orderIdentifier>
```

TMS helper 的 token 输出规则：

- `issue-staff` 可以把 token 写入本地 `.navi-upstream.env` 或指定 profile。
- 终端只允许输出写入结果、字段名、token hash 或前后 4 位脱敏值。
- 不允许把完整 token 写入文档、日志、LLM 可见输出或前端 DTO。
- 写入仓库内 `.navi-upstream.env` 前必须确认该文件已被 `.gitignore` 覆盖；否则拒绝写入并提示改写到 `temp/` 或用户 home profile。

## 配置来源

配置读取优先级：

1. CLI 参数。
2. 环境变量。
3. 显式 `--profile`、`NAVI_UPSTREAM_PROFILE`、项目本地 `.navigator/upstream.env`，或兼容旧 `.navi-upstream.env`。
4. 默认值。

本地 TMS 联调默认值：

```properties
NAVI_BASE_URL=http://localhost:8112
TMS_WEB_BASE_URL=http://localhost:12580
BASIC_BASE_URL=http://localhost:10001
NAVI_TENANT_ID=88800
NAVI_CLIENT_APP_ID=capp_2852124a-48f7-4098-9d5e-33eb736c4375
NAVI_AGENT_CODE=tms-agent-v305
NAVI_POLL_INTERVAL_SECONDS=4
```

敏感值只能通过 env/profile 提供：

```properties
NAVI_CLIENT_APP_SECRET=
NAVI_CLIENT_APP_ACCESS_TOKEN=
NAVI_ADMIN_TOKEN=
NAVI_ADMIN_API_KEY=
TMS_STAFF_SESSION_TOKEN=
```

env/profile 文件安全规则：

1. 默认优先读取当前 shell 环境变量；上游项目安装版 wrapper 默认传入本项目 `.navigator/upstream.env`。
2. 仓库内 profile 建议只落到 `.navigator/upstream.env`、`.navi-upstream.env` 或 `temp/**`，并且必须被 `.gitignore` 覆盖。
3. CLI 不自动扫描仓库查找 secret 文件，避免误读其他项目凭据。
4. `config check` 必须检查 profile 路径、git ignore 覆盖状态、敏感字段是否为空，以及输出是否只包含字段名和脱敏摘要。
5. 后续可补 `config scrub` 清理本地 profile；首版至少需要在文档中写明手工清理方式。
6. 多上游项目本机联调时，不共用全局 `.navi-upstream.env`；每个上游项目使用自己的 `.navigator/upstream.env`。

## 安全约束

CLI 和配套 skill 必须继承以下约束：

1. 不调用 `/internal/worker-gateway/v1/**`。
2. 不输出 token、ClientApp secret、runtime access token、upstream user token、`task_scoped_token`、`adapterConfigJson`、`manifestJson` 明文。
3. 默认输出脱敏；调试模式也只能显示 token hash 或前后 4 位。
4. `ask` 仍保持 fail-closed。缺少 upstream-user grant 时失败，不在 OpenAPI ask 内自动放行未知用户。
5. `ensure-grant` 是显式命令，只处理当前指定 `upstreamUserId`，不得枚举或批量授权上游所有用户。
6. TMS test token 只能从 env/profile 读取或写入本地 env/profile；不得打印完整明文。
7. 前端和 LLM 只接收非敏感 task/session/message 信息。

`ensure-grant` 授权身份规则：

1. `ensure-grant` 属于控制面授权操作，必须显式使用 admin/provisioning 类凭据，例如 `NAVI_ADMIN_TOKEN` 或后续确认的 provisioning credential。
2. `ensure-grant` 不得使用 ClientApp runtime access token 作为授权凭据；runtime access token 只用于 `ask`、`messages`、`sessions` 等 OpenAPI 调用。
3. 授权范围必须绑定 `tenantId + clientAppId + upstreamUserId`，缺少任一关键输入时 fail-closed。
4. 缺少授权凭据、授权失败、401/403 时，只输出非敏感错误摘要和下一步配置建议，不输出 token、secret 或上游用户 token。
5. 错误提示不得引导用户调用 `/internal/worker-gateway/v1/**` 或绕过 upstream-user grant。

## 技术落点

当前调研结论：

1. `navigator-open-sdk` 已封装 runtime token、upstream-user grant、ClientApp ask、task messages polling、sessions / session-messages 等主链路 API。
2. 根 `package.json` 目前只有 workspace/test/build 脚本，没有现成通用 Node/TS CLI 发布体系。
3. 首版 CLI 默认采用 Java CLI 复用 `navigator-open-sdk` 的 API 与 DTO，避免重复维护 REST path、Form/DTO 和响应模型。

首版落点原则：

1. 优先在 `navigator-open-sdk` 内或其相邻工具目录新增轻量 Java CLI 入口，使 CLI 与 SDK 同步编译、同步测试。
2. CLI 内部优先调用 SDK 方法，不直接拼接 REST；SDK 缺失能力时先补 SDK wrapper，再由 CLI 调用。
3. REST fallback 只允许作为临时兜底，并且必须以 `08-rest-api-reference.md` 和源代码 Form/DTO 为准，不调用 internal worker gateway。
4. 首版通过 `tools/navigator-upstream-cli/dist` 打包 Java CLI + runtime 依赖 + PowerShell wrapper，发布到远程对象存储；上游项目通过安装脚本安装到本项目目录。
5. 若后续需要 npm / pnpm 分发，再单独规划 TypeScript CLI 或 wrapper，不作为首版默认路径。

CLI 首版不覆盖平台管理全量能力；只服务上游 BFF/前端联调主链路。

## Skill 交付

需要创建或更新上游 Agent 使用的 skill，使上游 Agent 在联调时优先使用 CLI，而不是手写 curl。

建议更新：

```text
C:/Users/oldse/.claude/skills/navigator-upstream-cli/SKILL.md
C:/Users/oldse/.claude/skills/navigator-upstream-llm-integration/SKILL.md
```

更新内容至少包括：

- CLI 可用性检查。
- 独立安装文档链接，安装细节不内嵌在 `SKILL.md`。
- 项目本地 `.navigator/upstream.env` 配置说明。
- runtime token、ensure grant、ask、messages polling 最小流程。
- TMS test-only helper 使用说明。
- 安全红线：不得调用 internal worker gateway，不得输出敏感明文。
- 当 CLI 缺少能力时再回退 SDK/REST，且必须说明原因。

## 验收标准

1. CLI 可在本地仓库运行，能完成 `config check`。
2. `runtime-token` 使用 ClientApp key + secret env 交换短期 access token，输出默认脱敏。
3. `ensure-grant` 对指定 `clientAppId + upstreamUserId` 幂等授权，不批量授权。
4. `ask` 使用 runtime access token 和 `X-Upstream-User-Id` 调用 OpenAPI，不发送 ClientApp secret。
5. `messages --poll` 默认 4 秒间隔，轮询 task/message，任务进入 `COMPLETED` / `FAILED` / `CANCELED` / `CANCELLED` 后停止；若 message API 后续提供 `terminal=true`，可作为补充信号但不能作为唯一终止依据。
6. `sessions` / `session-messages` 在现有 OpenAPI 支持时可用；若 API 缺失，CLI 给出非敏感错误提示。
7. 可选 TMS helper 不打印完整 staff session token，只允许写入本地 env/profile。
8. 配套 skill 已更新，并能指导上游 Agent 使用 CLI 完成最小联调。
9. 文档覆盖最小使用示例、安全与脱敏策略、测试命令和结果。
10. 最小测试覆盖配置解析、脱敏输出、profile gitignore 检查、命令参数校验、polling 终止条件和禁止 internal gateway 的路径检查。
11. CLI 可打包为 `navigator-upstream-cli-<version>-windows.zip`，并提供远程安装/本地更新脚本。
12. 上游项目安装后可直接运行 `.\tools\navigator-upstream\navi.ps1 upstream config check`，不需要每条命令传 `--profile`。

## 非目标

- 不做 Navigator 平台全量管理 CLI。
- 不做启动期上游全用户批量授权。
- 不把 ask 改为自动 ensure grant。
- 不暴露 Worker Gateway 给上游。
- 不把 token 或内部 manifest/adapter 配置作为 LLM、前端或日志输出。
- 不在首版承诺正式包管理分发；先保证仓库内可运行和可复用。

## Progress Tracking

### Development Progress

| Item | Status | Notes |
| --- | --- | --- |
| 需求落版 | done | 落到 `1.1.3-SNAPSHOT/upstream-integration/17-navigator-upstream-cli-requirement.md` |
| CLI 技术落点确认 | done | 首版默认 Java CLI 复用 `navigator-open-sdk`；执行前只需确认具体子目录和启动入口 |
| CLI 首版实现 | done | `navigator-open-sdk` 新增 Java CLI；主链路命令已接 SDK，TMS helper 首版返回非敏感未实现提示 |
| CLI 发布安装模型 | done | 新增 `tools/navigator-upstream-cli/dist` 打包、上传、远程安装、本地更新脚本；安装目标为上游项目 `tools/navigator-upstream/` |
| OpenAPI runtime auth 修正 | done | `runtime-token` 与 ClientApp runtime 访问路径不再依赖登录态；通过 appKey/accessToken 解析租户并校验 skill grant |
| skill 更新 | done | 新增独立 `navigator-upstream-cli` 技能；`navigator-upstream-llm-integration` 保留综合集成入口并指向 CLI 专用技能 |
| 文档补充 | done | 已补 CLI 使用手册、安装更新篇、版本索引、overview 索引和 SDK 指南入口 |

### Testing Progress

| Test Area | Status | Expected Evidence |
| --- | --- | --- |
| 配置解析与优先级 | pass | `mvn test -pl navigator-open-sdk` 覆盖 env/profile/CLI 参数基础路径、项目本地 `.navigator/upstream.env`、sandbox alias 映射；`config check` 可启动 |
| profile 文件安全 | pass | 单元测试覆盖项目本地 `.navigator/upstream.env`、`.navi-upstream.env` `.gitignore` 检查，未忽略 profile 拒绝通过，工作区外 sandbox profile 允许通过 |
| 敏感输出脱敏 | pass | 单元测试覆盖 runtime token、ClientApp secret/key、admin token、upstream user token 不出现在 stdout/stderr；本地发现 `runtime-token` 曾输出完整 appKey 后已修正为脱敏摘要 |
| runtime token exchange | pass | mock HTTP 验证 `X-Client-App-Key` + `X-Client-App-Secret` 请求头，输出只脱敏 access token |
| OpenAPI runtime auth | pass_compile | `mvn test -pl business-agent-module -Dtest=ClientAppRuntimeCredentialResolverTest` pass；`mvn test -pl addons/claude-worker-agent -am -DskipTests` pass |
| ensure-grant 幂等 | pass_mock_live_bootstrap | mock HTTP 验证必须使用 admin token，且只授权指定 upstreamUserId；本地 smoke 通过临时 TENANT_ADMIN 完成当前 upstream user 与 `echo-agent-default` skill 授权 |
| ask + messages polling | pass_partial_live | SDK smoke 覆盖 ClientApp ask header；CLI 测试覆盖 messages poll 在 task `COMPLETED` 时停止；本地 `echo-agent-default` ask 已通过，messages 真实轮询受 Echo 测试桩不落 session task 限制阻塞 |
| session list/messages | implemented_not_run | CLI 已接 SDK wrapper；仍需 worker-backed agent 补真实验证 |
| package/install/update | pass_remote_publish_install_update | `tools/navigator-upstream-cli/dist` 已提供 package/upload/remote install/project install/self update；已发布到 OBS，并通过远程 `install.ps1`、项目安装 smoke、`self update` 验证 |
| internal gateway 禁用 | pass | `rg -n "/internal/worker-gateway|task_scoped_token|adapterConfigJson|manifestJson" navigator-open-sdk/src/main/java/com/foggy/navigator/sdk/cli navigator-open-sdk/src/main/java/com/foggy/navigator/sdk/api/AgentApi.java navigator-open-sdk/src/main/java/com/foggy/navigator/sdk/internal/HttpHelper.java` 无命中 |

### Experience Progress

CLI 无浏览器 UI，Playwright 验证不适用。仍需要执行命令行联调体验检查：

| Check | Status | Notes |
| --- | --- | --- |
| 新上游 Agent 可按 skill 找到 CLI 流程 | pass_doc | skill 已加入 CLI first action 和最小命令 |
| 错误提示可定位缺失配置 | pass_live_partial | CLI 已统一非敏感错误输出；真实 smoke 覆盖过期凭证、agent 未注册、skill 未授权、Echo task 不落库等非敏感错误 |
| 默认输出不泄露敏感字段 | pass_local | 本机 sandbox profile `config check` 输出中 token/secret/key 已脱敏 |

### Execution Check-in 2026-05-11

- completed: `navigator-open-sdk` 新增 `com.foggy.navigator.sdk.cli.UpstreamCli` 及配置解析、脱敏、profile ignore 检查、主链路命令。
- completed: `AgentApi` 补充 ClientApp access token 版本的 task/messages/sessions/session-messages SDK wrapper，CLI 不重复拼业务模型。
- completed: `.gitignore` 增加 `.navi-upstream.env`。
- tests: `mvn test -pl navigator-open-sdk` pass，24 tests, 0 failures。
- smoke: `mvn -q -pl navigator-open-sdk exec:java '-Dexec.args=upstream config check'` pass。
- completed: `18-navigator-upstream-cli-usage-guide.md`、版本 README、upstream overview、`11-llm-sdk-usage-guide.md` 已补 CLI 入口。
- completed: 新增 `navigator-upstream-cli` skill，覆盖安装文档链接、项目本地 `.navigator/upstream.env`、smoke flow、安全红线、自更新、排障和 GitHub issue 反馈规则；`navigator-upstream-llm-integration` 已改为引用该专用技能。
- completed: `business-agent-module` 增加无登录态 runtime token / access token 租户解析能力，`OpenApiController` 的 ClientApp runtime 路径改为使用 appKey/accessToken 解析租户。
- tests: `mvn test -pl business-agent-module -Dtest=ClientAppRuntimeCredentialResolverTest` pass，10 tests, 0 failures。
- compile: `mvn test -pl addons/claude-worker-agent -am -DskipTests` pass。
- local smoke: `mvn -q -pl navigator-open-sdk exec:java '-Dexec.args=upstream config check --profile C:/Users/oldse/.claude/skills/navigator-upstream-llm-integration/assets/current-dev-sandbox.local.env'` pass，敏感值脱敏。
- package: 停止旧 `localhost:8112` 进程后执行 `mvn package -pl launcher -am -DskipTests` pass，并以更新后的 `launcher/target/launcher-1.0.0-SNAPSHOT.jar` 重启本地 Navigator。
- local runtime-token: `mvn -q -pl navigator-open-sdk exec:java '-Dexec.args=upstream runtime-token --profile C:/Users/oldse/.claude/skills/navigator-upstream-llm-integration/assets/current-dev-sandbox.local.env'` 已到达 ClientApp 业务校验层，返回 `HTTP 400: client app credential expired`；说明 OpenAPI 登录态 401 已修正，完整 `ask/messages` 需先刷新 sandbox ClientApp 凭证。
- security fix: `runtime-token` 输出中的 appKey 已改为脱敏摘要，避免把 ClientApp key 明文写到终端；`mvn test -pl navigator-open-sdk` pass，24 tests, 0 failures。
- package model: CLI 增加项目本地 profile 默认读取逻辑，默认候选 `.navigator/upstream.env`，并提供 `NAVI_UPSTREAM_PROFILE` 作为可选覆盖。
- package scripts: 新增 `tools/navigator-upstream-cli/dist/package.ps1`、`upload.ps1`、`remote-install.ps1`、`install.ps1`、`bin/navi.ps1`、`bin/navi.cmd`，支持远程发布、上游项目安装和 `navi.ps1 self update`。
- package smoke: `powershell -ExecutionPolicy Bypass -File tools\navigator-upstream-cli\dist\package.ps1` pass，生成 `navigator-upstream-cli-1.0.0-SNAPSHOT-windows.zip`；临时上游项目安装后 `.\tools\navigator-upstream\navi.ps1 upstream config check` pass，自动使用本项目 `.navigator/upstream.env` 且 `profileGitIgnored=true`。
- OBS publish: `powershell -ExecutionPolicy Bypass -File tools\navigator-upstream-cli\dist\package.ps1 -Upload` pass，发布到 `https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/navigator-upstream-cli`；issue #104 修复后同版本刷新，Windows 包 SHA256 为 `d1977cd7ae945c8e2341a1efbce6bb84266671904ae113760b6c8de2b239826e`。
- remote install/update smoke: 临时上游项目执行 `irm https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/navigator-upstream-cli/install.ps1 | iex` pass；安装后 `version`、`upstream config check`、`self update` 均通过。
- local credential bootstrap: 原 sandbox ClientApp 凭证与 admin token 均已过期；本地通过临时 TENANT_ADMIN 刷新 runtime credential 并完成 smoke，临时 token/key/secret 仅写入 `temp/**`，不写文档。
- local runtime-token: 使用刷新后的本地 runtime credential 执行 `runtime-token` pass，输出仅包含脱敏 appKey/accessToken、hash 摘要、过期时间。
- local agent inventory: 当前本地 OpenAPI agents 仅发现 `echo-agent-default`；默认文档中的 `tms-agent-v305` 在该环境未注册，直接 ask 返回 `Agent not found: tms-agent-v305`。
- local ask: 补齐 `echo-agent-default` skill registry、ClientApp skill grant、upstream user grant 后，`ask --agent echo-agent-default` pass，返回 `taskId=echo-f3c03049`、`status=COMPLETED`、`contextId=20260511-e726`。
- local messages poll: `messages --poll --agent echo-agent-default --task-id echo-f3c03049` 返回 `HTTP 400: Task not found: echo-f3c03049`。原因是 Echo provider 每次 resolve 都创建新的 `EchoA2aAgent`，且 task messages API 查询 session task 表；Echo smoke 可验证 runtime auth 与 ask，但不能作为真实 messages polling 验收替代。
- remaining: 注册并连通 worker-backed agent 后，执行真实 `runtime-token -> ensure-grant -> ask -> messages --poll -> sessions -> session-messages`；TMS helper 视需要实现。

## 后续执行顺序

1. 在本地环境注册并连通 worker-backed agent，例如 `tms-agent-v305` 或真实 Claude/Codex agent。
2. 基于 worker-backed agent 执行 `runtime-token -> ensure-grant -> ask -> messages --poll`，补齐 messages polling 真实证据。
3. 继续验证 `sessions -> session-messages`，确认 contextId 与 task/session 消息查询一致。
4. 决定 TMS test-only helper 是否在首版实现；若不实现，文档中明确保持可选延期。
