# WorkerHost Suite Upstream Worker Model

## 文档作用

- doc_type: requirement + implementation-plan
- intended_for: root-controller | sub-agent | reviewer
- purpose: 约束上游 Worker 创建从“散注册 worker endpoint”收敛为“一台 host 一套标准 worker suite”的外部契约与落地计划。

## 背景

School Sim 准备把执行环境统一迁到 WSL。当前本机已经有 WSL Claude Code Worker、Codex Worker、BizWorker 三类进程，但现有上游 bootstrap 仍暴露多条低层链路：

- `upstream worker create --file <json>` 创建 Claude/Directory 类 worker，并通过 `codexConfig.baseUrl` 间接配置 Codex Worker。
- `upstream worker-pool register-worker --file <json>` 以兼容命令注册 LangGraph BizWorker identity。
- `owner-smoke` 当前主要围绕单个 `PhysicalWorkerDiagnostic` 输出，无法稳定表达一台开发机上的 execution worker、directory worker、biz worker 分别路由到哪里。

这导致上游需要理解 Navi 内部 worker、worker-pool、codexConfig、identity 等细节，配置入口过宽，也不利于诊断 WSL 与 Windows worker 混用问题。

## 目标

1. 对普通上游暴露 WorkerHost suite 模型：一台 host 下声明一组标准 worker 进程。
2. 收紧上游可配置项：只允许标准 worker key，不暴露 backend、capability、worker-pool 等内部路由概念。
3. 保持现有运行时兼容：Codex 先统一走 Navi，通过 Claude worker 的 `codexConfig` 路由；BizWorker 仍可走现有 identity 注册。
4. 为后续真实 `OPENAI_CODEX` PhysicalWorker identity 留出不改外部 manifest 的演进空间，但本阶段不启用 Codex worker direct HTTP 调用。
5. 增强 CLI 与 `owner-smoke`，让上游能看到 host suite 下各 role 的最终脱敏 endpoint。

## 非目标

- 本阶段不迁移 Codex Agent 到独立 `OPENAI_CODEX` physical worker。
- 本阶段不允许普通上游在 manifest 中声明 `workers.codex.workerId`；Codex role 只表达 Codex worker endpoint，由 Claude worker `codexConfig` 承载。
- 本阶段不移除现有 `worker create`、`worker update`、`worker-pool register-worker` 命令。
- 本阶段不允许普通上游自定义任意 worker 类型或 backend 名称。
- 本阶段不把内部 capability、pool member、launcher adapter 细节写入上游 manifest。

## 外部 Manifest 契约

普通上游使用如下形态：

```json
{
  "workerHostId": "school-sim-wsl",
  "hostUrl": "http://127.0.0.1",
  "port": 3131,
  "install": "ensure",
  "wslUser": "navigator",
  "workers": {
    "claudeCode": {
      "enabled": true
    },
    "codex": {
      "enabled": true,
      "port": 3151
    },
    "biz": {
      "enabled": true,
      "port": 3161
    }
  }
}
```

### 字段规则

- `workerHostId`: 上游可读的 host suite id，同一上游系统内唯一。
- `hostUrl`: Navigator 访问该 host 的 scheme + host，不带端口。
- `port`: 顶层默认端口，等价于 `workers.claudeCode.port`。
- `install`: CLI 行为开关。`ensure` 表示本机/WSL 缺少标准 worker 时可尝试安装或给出安装引导；`none` 表示只做注册/更新。
- `wslUser`: 可选，仅 `worker-host install --install-shell wsl` 使用；用于指定 WSL 内实际安装/启动用户，避免默认 root 绕开已有 Claude/Codex 登录态。也可用命令行 `--wsl-user` 或环境变量 `NAVI_WSL_USER` 覆盖。
- `wslDistro`: 可选，仅 `worker-host install --install-shell wsl` 使用；用于指定 WSL 发行版。也可用命令行 `--wsl-distro` 或环境变量 `NAVI_WSL_DISTRO` 覆盖。
- `workers.claudeCode`: 默认存在、默认启用、必需；端口默认取顶层 `port`。
- `workers.codex`: 可选；启用时必须能解析出端口或完整 endpoint。
- `workers.biz`: 可选；启用时必须能解析出端口或完整 endpoint。
- 普通上游不得提交非标准 worker key。平台管理员高级模式另行保留裸 worker/identity 注册能力。
- `workers.codex.workerId` 当前禁止填写。Codex Agent 的 worker id 仍使用 `claudeCode` worker id，Codex role 只配置 `port` / `baseUrlOverride` / `model` / token 引用。

### 派生规则

```text
claudeCode.baseUrl = hostUrl + ":" + topLevelPort
codex.baseUrl      = hostUrl + ":" + workers.codex.port
biz.baseUrl        = hostUrl + ":" + workers.biz.port
```

如果未来支持反向代理、path prefix、容器网络，可以为单个 worker 增加高级字段 `baseUrlOverride`，但普通文档与默认 CLI 不引导使用。

## 内部兼容映射

| 外部 worker key | 当前内部映射 | 当前行为 | 目标行为 |
| --- | --- | --- | --- |
| `claudeCode` | ClaudeWorkerEntity | 通过 upstream worker create/update 注册，承担目录与 Claude Code 执行 | 保持为 host suite 的必需 anchor worker |
| `codex` | ClaudeWorkerEntity.codexConfig | 写入 `codexConfig.baseUrl`，Codex Agent 仍绑定 Claude worker id；不注册独立 `OPENAI_CODEX` identity | 后续可迁移为独立 `OPENAI_CODEX` physical worker identity，外部 manifest 不变 |
| `biz` | BizWorkerIdentityEntity / LanggraphWorkerEntity 兼容链路 | 通过 worker identity 注册，必要时保持旧 Langgraph worker 可 launch | 统一为标准 physical worker identity，worker-pool 只作内部路由兼容 |

## CLI 设计

新增普通入口：

```bash
upstream worker-host apply --file worker-host.json [--target-tenant-id <tenantId>] [--write-profile]
upstream worker-host update --file worker-host.json [--target-tenant-id <tenantId>] [--write-profile]
upstream worker-host verify --worker-host-id <id>
upstream worker-host install --file worker-host.json [--install-shell auto|powershell|bash|wsl] [--wsl-user <user>] [--wsl-distro <name>] [--timeout-seconds <seconds>] [--no-start] [--dry-run]
```

### 命令语义

- `apply`: 注册/更新 Claude worker、写入 Codex 配置、注册 Biz worker identity；当前不隐式执行本机安装。
- `update`: 仅做注册信息与端口更新，不执行安装。
- `verify`: 按 host suite 输出当前解析到的 worker role、脱敏 endpoint 与健康状态。
- `install`: 显式执行本机/WSL worker 安装或更新，不改 Navigator 资源。默认根据 CLI 所在系统选择 PowerShell 或 Bash；Windows 上可用 `--install-shell wsl` 安装到指定或默认 WSL distro；安装后会把 manifest 端口写入对应 worker `.env`，并默认按 role 启动 worker；`--no-start` 可只安装不启动，`--dry-run` 只打印将执行的命令。

### 兼容要求

- `worker create/update` 保留为低层命令。
- `worker-pool register-worker` 保留为兼容命令，但 help 文案明确推荐 `worker-host apply`。
- `worker-host apply --write-profile` 至少写入：
  - `NAVI_WORKER_HOST_ID`
  - `NAVI_WORKER_ID`，指向 `claudeCode` worker id
  - `NAVI_BIZ_WORKER_ID`，当启用 `biz` 时写入
- 不把 worker auth token 打印到 stdout。
- CLI 必须拒绝 `workers.codex.workerId`，避免上游误以为当前支持直接绑定 `OPENAI_CODEX` identity。

## Agent 与 Directory 绑定口径

短期 1.0.8+ 兼容口径：

- WorkingDirectory manifest 的 `workerId` 填 `claudeCode` worker id。
- Claude Code Agent 的 `workerId/workerRef` 填 `claudeCode` worker id。
- Codex Agent 的 `workerId/workerRef` 仍填 `claudeCode` worker id，实际 endpoint 由 `codexConfig.baseUrl` 决定。
- Biz Agent 的 `workerId/workerRef` 填 `biz` worker identity id；在 launcher 完成 identity 直连前，必须保证该 id 能被现有 LangGraph launcher 解析。

目标口径：

- Agent 资源支持 execution worker 与 directory worker 分离。
- WorkingDirectory 始终绑定目录 worker。
- Codex/Biz execution worker 绑定对应 role 的 physical worker identity。

## owner-smoke 诊断契约

短期必须增强输出，使 Scheme A 也可诊断真实 Codex endpoint：

```text
workerHost workerHostId=school-sim-wsl hostUrl=http://127.0.0.1
workerRole role=claudeCode workerId=... baseUrl=http://127.0.0.1:3131 usedAs=directory,claudeCodeExecution source=CLAUDE_WORKER
workerRole role=codex workerId=... baseUrl=http://127.0.0.1:3151 usedAs=codexExecution source=CLAUDE_WORKER_CODEX_CONFIG
workerRole role=biz workerId=... baseUrl=http://127.0.0.1:3161 usedAs=bizExecution source=BIZ_WORKER_IDENTITY
```

保留现有 `physicalWorker ... usedAs=execution,directory` 行以兼容旧解析器，但新增 role 级输出用于上游人类诊断与自动化断言。

## Module Responsibility

| 模块 | 责任 |
| --- | --- |
| root docs | 维护 WorkerHost suite 外部契约、迁移策略、验收标准 |
| `navigator-open-sdk` | 增加 `worker-host` CLI 命令、manifest 解析、输出与单测 |
| `addons/claude-worker-agent` | 增强 readiness / owner-smoke 数据，暴露 Codex delegated endpoint 的非敏感诊断 |
| `business-agent-module` | 维持 Biz worker identity 注册；补齐 identity 与 launcher 的一致性，避免 identity-only 不可 launch |
| `addons/codex-worker-agent` | 保持短期 `codexConfig` 兼容；为后续独立 `OPENAI_CODEX` identity 预留接口 |
| worker installers | 提供 Claude/Codex/Biz 的稳定 OBS 安装脚本，供 `worker-host install` 显式调用 |

## Code Inventory

| repo | path | role | expected change | notes |
| --- | --- | --- | --- | --- |
| root | `docs/version-tracker/1.3.0-SNAPSHOT/20-worker-host-suite-upstream-worker-model.md` | design | create | 本文档 |
| root | `docs/version-tracker/1.3.0-SNAPSHOT/README.md` | index | update | 增加文档索引 |
| root | `navigator-open-sdk/src/main/java/com/foggy/navigator/sdk/cli/UpstreamCli.java` | CLI entry | update | 增加 `worker-host` 命令分发、manifest apply/update/verify/install |
| root | `navigator-open-sdk/src/main/java/com/foggy/navigator/sdk/model/businessagent/WorkerHostManifest.java` | CLI model | create | 外部 manifest 结构 |
| root | `navigator-open-sdk/src/test/java/com/foggy/navigator/sdk/cli/UpstreamCliTest.java` | CLI tests | update | 覆盖 manifest 派生、禁止未知 worker key、兼容 API 调用 |
| root | `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/OpenApiAgentReadinessService.java` | readiness | update | 添加 role 级 worker 诊断 |
| root | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/model/dto/AgentReadinessDTO.java` | DTO | update | 兼容新增 role 级诊断集合 |
| root | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/BizWorkerPoolService.java` | Biz identity | update | 确认注册 identity 时的可 launch 一致性 |

## Implementation Plan

### Phase 1: CLI manifest 与兼容 apply

- 新增 manifest model 与校验：
  - `hostUrl` 不带端口。
  - 顶层 `port` 必填并派生 `claudeCode`。
  - 只允许 `claudeCode`、`codex`、`biz`。
- `worker-host apply` 内部调用现有 API：
  - upsert Claude worker。
  - 当 `codex.enabled=true` 时，把 `codexConfig.baseUrl` 写入 Claude worker。
  - 当 `biz.enabled=true` 时，调用 `/api/v1/upstream-admin/worker-identities` 注册 Biz identity。
- 单测覆盖请求 body 与输出。

### Phase 2: owner-smoke role 级诊断

- 后端 readiness DTO 增加 role 级 worker 诊断集合。
- CLI 打印 `workerHost` 与多行 `workerRole`。
- 对 Codex 兼容模型输出 `source=CLAUDE_WORKER_CODEX_CONFIG`。
- 旧 `physicalWorker` 行保持不变。

### Phase 3: Biz identity launch 闭环

- 已核对 LangGraph launcher 与 stream/resume/report 链路仍通过 `LanggraphWorkerService#getWorkerEntity` 获取运行时 worker。
- 已选择 `BizWorkerIdentityEntity` 直连：当旧 `langgraph_workers` 表没有对应记录时，`LanggraphWorkerService` 会 fallback 到 backend 为 `LANGGRAPH_BIZ` 的 Biz worker identity，生成运行时 `LanggraphWorkerEntity` 视图。
- fallback 会校验 identity `ENABLED`、`HEALTHY`、`baseUrl` 非空；旧 `LanggraphWorkerEntity` 仍优先，兼容现有部署。
- 已加回归测试，避免 identity 注册成功但运行失败。

### Phase 4A: Navi-routed Codex 收口

- Codex 不做 direct HTTP 调用，不注册独立 `OPENAI_CODEX` physical worker identity。
- `worker-host` CLI 禁止 `workers.codex.workerId`，Codex role 输出使用 Claude worker id。
- `verify/install/apply` 对 Codex role 统一输出 `source=CLAUDE_WORKER_CODEX_CONFIG`，用于诊断实际 Codex endpoint。
- 上游 Agent manifest 中 Codex Agent 的 `workerId/workerRef` 继续填写 `claudeCode` worker id。

### Phase 4B: 显式 WorkerHost 安装入口

- `worker-host install --file <manifest>` 按标准 role 顺序调用 OBS 安装器：`claudeCode`、`codex`、`biz`。
- 支持 `--install-shell auto|powershell|bash|wsl`，其中 `wsl` 用于 Windows CLI 调用 WSL 内的 bash 安装脚本。
- 支持 `--wsl-user` / manifest `wslUser` / `NAVI_WSL_USER` 指定 WSL 内实际用户，避免安装到 `/root` 并绕开已有 Codex 登录态。
- 支持 `--wsl-distro` / manifest `wslDistro` / `NAVI_WSL_DISTRO` 指定 WSL 发行版；不指定时使用 `wsl.exe` 默认发行版。
- 支持 `--dry-run` 输出安装命令但不执行，支持 `--timeout-seconds` 控制每个安装器的最长运行时间。
- 安装后按 role 写入端口配置：`AGENT_WORKER_PORT`、`CODEX_WORKER_PORT`、`BIZ_WORKER_PORT`。
- 默认安装后按 role 启动 worker，并等待 BizWorker health 就绪；如只想升级文件和 `.env`，使用 `--no-start`。
- 安装命令只负责安装或更新本机 worker 包，不注册或修改 Navigator 资源；注册仍由 `worker-host apply/update` 执行。

### Deferred Phase 4C: 独立 Codex PhysicalWorker

- 引入或复用标准 physical worker identity 注册接口支持 `OPENAI_CODEX`。
- Codex Agent 支持 execution worker 指向独立 Codex identity。
- 保留 `codexConfig` 作为迁移 fallback。

## 当前落地状态

- 已落地 Phase 1 兼容入口：`navigator-open-sdk` 增加 `worker-host apply/update/verify/install`、外部 manifest 解析、标准 worker key 校验，以及 Claude worker + `codexConfig` + Biz identity 的兼容注册链路。
- 已落地 Phase 2 诊断主体：readiness 响应保留旧 `physicalWorkerDiagnostic`，同时新增 `physicalWorkerDiagnostics` role 列表；CLI 在旧 `physicalWorker` 行后输出 `workerRole` 行。
- Codex 兼容模型已能通过 `role=codex source=CLAUDE_WORKER_CODEX_CONFIG` 输出脱敏后的 `codexConfig.baseUrl`，用于确认真实执行 endpoint 是否为 WSL `3151`。
- 已落地 Phase 3 Biz identity launch 闭环：Biz Agent 或 worker pool member 绑定 Biz worker identity id 时，不再要求同步创建旧 `LanggraphWorkerEntity`。
- 已按“所有 worker 调用先走 Navi”的口径收紧 Phase 4A：CLI 不接受 `workers.codex.workerId`，Codex role 诊断固定显示 `CLAUDE_WORKER_CODEX_CONFIG`，并使用 `claudeCode` worker id 作为绑定入口。
- 已落地 Phase 4B 显式安装入口：`worker-host install` 会真实执行 OBS worker 安装器，`--dry-run` 可用于只看计划；`apply/update` 仍不隐式安装。
- `navigator-upstream-cli` 发布版本目标升为 `1.0.16`，避免在远端已发布版本上同版本覆盖 worker-host installer 行为；`1.0.11` 使用 `wsl.exe --exec` + base64 bash payload 修正 WSL install 参数转义问题；`1.0.12` 增加 WSL 用户/发行版选择，并让 `worker-host install` 默认安装后启动 worker；`1.0.13` 修复远端安装/自更新写回 `RELEASE_MANIFEST.json` 时 JSON 被 PowerShell 参数转义破坏的问题；`1.0.14` 补齐旧 wrapper 自更新时的兼容 fallback，旧参数中的 manifest 损坏时会从 `latest.json` 重新拉取合法 release metadata；`1.0.15` 修复 Biz bash starter 的 `&;` 语法问题，并将 Codex starter 改为 detached direct launch + health 稳定窗口，避免 READY 后进程立刻退出仍被误判为成功；`1.0.16` 在 WSL Codex starter 中恢复 `setsid -f` session detach，并用 `logs/worker.pid` 取实际 Node PID 做稳定性检查。
- `codex-worker` 发布版本目标升为 `1.0.4`，Linux/macOS `start.sh` 改为 `setsid -f` + pidfile + health endpoint readiness，stdin 断开，并在 READY 前增加稳定窗口。
- 尚未落地独立 `OPENAI_CODEX` physical worker identity 直连；该项仍属于后续 Deferred Phase。

## 验收标准

- 上游可通过一个 WorkerHost manifest 完成 WSL Claude/Codex/Biz worker 注册或更新。
- 普通上游文档不再要求理解 worker-pool、capability、codexConfig 等内部概念。
- `owner-smoke` 能显示 `claudeCode`、`codex`、`biz` 三个 role 的脱敏 endpoint。
- School Sim 可以确认：
  - directory 使用 WSL Claude worker `3131`
  - Codex 执行使用 WSL Codex worker `3151`
  - Biz actor 使用 WSL BizWorker `3161`
- 现有 `worker create/update`、`worker-pool register-worker` 命令仍可用。
- 所有新增/修改 CLI 行为有单元测试覆盖。

## 风险与约束

- 当前 Codex 实现仍以 Claude worker id 作为入口；普通上游不得配置独立 Codex worker id，不能承诺独立 Codex identity 可执行。
- Biz identity 直连当前使用 identity 的 `baseUrl` 作为运行时 endpoint；`identityToken` 只保存 hash，不作为 worker 调用 token。需要远程 Worker 鉴权时，应补 secret ref 或受控 token 材料模型。
- `hostUrl=http://127.0.0.1` 的含义是 Navigator 服务视角可达；当 Navigator 不在同一 host/WSL 网络命名空间时，上游必须填可被 Navigator 访问的地址。
- `worker-host install` 是显式本机/WSL 操作；`apply/update` 当前不会隐式执行安装。执行前可先用 `--dry-run` 审核命令，尤其是 Windows 调 WSL 的场景；WSL Codex 登录态依赖用户 home，开发机应显式传 `--wsl-user <login-user>` 或在 manifest/profile 中配置对应用户。
