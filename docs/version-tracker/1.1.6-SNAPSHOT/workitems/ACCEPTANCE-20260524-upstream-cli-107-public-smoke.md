# Upstream CLI 1.0.7 Public Install And Owner-aware Smoke

## 文档作用

- doc_type: acceptance-record
- date: 2026-05-24
- status: partial-pass-blocked
- scope: navigator-upstream-cli 1.0.7 public install, fresh ClientApp bootstrap, owner-aware resource smoke
- purpose: 记录 1.0.7 公共安装包验证结果，以及当前上游示例 Agent 迁移到 PhysicalWorker / backend capability 口径前的阻塞点

## 验收环境

- Navi branch: `qd-win11/dev`
- Navi package commit: `bc705bab3bdd353cbee2ad505dea33ad381584a7`
- CLI version: `navigator-upstream-cli 1.0.7`
- public package SHA256: `f8a98300c727e0055ef3c2d00f3f88cd91f9b28cacc11f88ac04aef859d811fb`
- upstream project: `foggy-world-sim`
- clean tenant profile: `.navigator/tenants/school-owner-smoke-108.env`
- ClientApp: `capp_ec57540a-a33a-4ca5-a4fc-70468f941a1c`
- upstreamSystemId: `foggy-world-sim`
- upstreamUserId: `sim-upstream-user-local`

## 已通过

1. 公共安装命令可用，项目本地 CLI 安装后显示 `1.0.7`。
2. `BUILD_INFO.gitCommit` 与发布 commit 对齐。
3. 覆盖安装后项目本地 `lib` 没有旧 `navigator-open-sdk-*.jar` 抢先加载。
4. clean ClientApp 创建成功。
5. `client-app issue-runtime-key` 成功，未要求 root / TENANT_ADMIN 绕行。
6. `client-app issue-control-key` 成功。
7. `runtime-token --write-profile` 成功。
8. `model grant` 成功，可见模型 grant 输出包含 `backend=LANGGRAPH_BIZ`。
9. `skill sync`、`agent sync`、`ensure-grant` 成功。
10. `directory client-init` 在补齐 `workspaceScope=CLIENT_APP_SHARED` 后成功。
11. `agent set-default-model`、`agent set-default-workspace`、`agent set-default-worker` 服务端侧成功生效。

## 阻塞点

### 1. 旧示例 Agent 仍是 WorkerPool-only 路径

`owner-smoke` 最终仍返回：

```text
readiness OK
resources FAIL missing=effectiveWorkerBackend,effectivePhysicalWorkerId
internalRoute.workerPoolId=foggy-world-sim-codex-pool
workspace.effectiveDirectoryId=20260524-aa8a
workspace.scope=CLIENT_APP_SHARED
```

说明 credential、ClientApp、model grant、skill、agent、workspace grant 都已打通；当前阻塞集中在 runtime resolver 对 PhysicalWorker/backend capability 的有效解析。仅存在 `internalRoute.workerPoolId` 不再足以通过新 owner-aware runtime 验收。

### 2. 旧目录 manifest 缺少 workspaceScope

原 `.navigator/school-codex-directory.json` 未带 `workspaceScope`，`directory client-init` 返回：

```text
workspaceScope is required
```

已通过临时 ClientApp-shared manifest 验证补齐后可创建目录。上游样例和文档需要明确 `workspaceScope` 必填。

### 3. 绑定命令存在 CLI JSON 打印问题

`agent set-default-model`、`agent set-default-workspace`、`agent set-default-worker` 服务端侧成功，但 CLI 在打印 DTO 时出现：

```text
Java 8 date/time type `java.time.LocalDateTime` not supported by default
```

该问题不影响服务端绑定写入，但会导致 CLI 命令 exit code 异常，影响脚本化 smoke。需要为 CLI ObjectMapper 注册 JavaTimeModule，或避免直接打印未配置时间模块的 DTO。

## 结论

1. `1.0.7` 公共安装和 fresh ClientApp credential bootstrap 链路通过。
2. 本轮不进入真实 `ask/messages`，因为 owner-aware resource smoke 未达到 `resources OK`。
3. 下一步应优先修复 / 迁移：
   - Agent / model / workspace / worker resolver 输出 `effectiveWorkerBackend`。
   - Directory 或 backend capability 输出 `effectivePhysicalWorkerId`。
   - CLI JSON 打印 `LocalDateTime`。
   - 上游示例 manifest 补齐 `workspaceScope`，并从 WorkerPool-only 迁移到 PhysicalWorker/backend capability 口径。

## 安全说明

本记录不包含任何真实 token、secret、api key 或 profile 内容。
