# Navigator Upstream CLI Install And Update Guide

## 文档作用

- doc_type: integration-guide
- version: 1.1.3-SNAPSHOT
- status: implemented
- date: 2026-05-12
- intended_for: upstream-llm-coding-agent | upstream-backend-developer | navigator-release-owner
- purpose: 指导上游项目把 Navigator Upstream CLI 安装到项目本地目录，并按项目隔离维护配置与更新

## 目标模型

CLI 不要求上游开发者在同一台机器上共用 Navigator 仓库、全局环境变量或同一个 profile。

推荐模型：

```text
upstream-project/
  tools/
    navigator-upstream/
      navi.ps1
      navi.cmd
      navi-e2e.ps1
      navi-e2e.cmd
      lib/
      VERSION
      RELEASE_URL
  .navigator/
    upstream.env
  .gitignore
```

每个上游项目独立安装一份 CLI，独立维护 `.navigator/upstream.env`。在项目目录执行命令时，wrapper 会自动把本项目的 `.navigator/upstream.env` 作为默认 profile 传给 CLI。

## 远程安装

从上游项目根目录执行：

```powershell
irm <release-base-url>/install.ps1 | iex
```

当前发布地址：

```powershell
irm https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/navigator-upstream-cli/install.ps1 | iex
```

安装脚本会：

- 下载最新 `navigator-upstream-cli-<version>-windows.zip`。
- 校验 `latest.json` 中的 SHA256。
- 安装到 `tools/navigator-upstream/`。
- 创建 `.navigator/upstream.env` 模板文件。
- 确保 `.gitignore` 覆盖 `.navigator/upstream.env` 与 `.navi-upstream.env`。
- 写入 `RELEASE_URL`，用于后续自更新。

安装后检查：

```powershell
.\tools\navigator-upstream\navi.ps1 version
.\tools\navigator-upstream\navi.ps1 upstream config check
.\tools\navigator-upstream\navi-e2e.ps1 config check
```

## 本地 Archive 安装

如果已经拿到发布包：

```powershell
Expand-Archive .\navigator-upstream-cli-<version>-windows.zip -DestinationPath .\temp\navi-upstream
powershell -ExecutionPolicy Bypass -File .\temp\navi-upstream\navigator-upstream\install.ps1 -ProjectRoot .
```

## 项目本地配置

安装脚本会创建：

```text
.navigator/upstream.env
```

填写当前上游项目自己的配置：

```properties
NAVI_BASE_URL=http://localhost:8112
NAVI_TENANT_ID=<tenantId>
NAVI_CLIENT_APP_ID=<clientAppId>
NAVI_CLIENT_APP_KEY=<clientAppKey>
NAVI_CLIENT_APP_SECRET=<clientAppSecret>
NAVI_CLIENT_APP_ACCESS_TOKEN=
NAVI_AGENT_CODE=<agentId>
NAVI_MODEL_CONFIG_ID=<modelConfigId>
NAVI_E2E_MOCK_LLM_URL=http://localhost:8200
NAVI_POLL_INTERVAL_SECONDS=4
```

这个文件必须留在本项目本地，不提交到 Git。不同上游项目使用各自的 `.navigator/upstream.env`，不共用全局 shell 环境变量。

`NAVI_MODEL_CONFIG_ID` 可为空；为空时 readiness/ask 由 Navigator 后端按当前 ClientApp 的默认 model config grant 解析。命令行 `--model-config-id` 会覆盖该值。

## 常用命令

```powershell
.\tools\navigator-upstream\navi.ps1 upstream config check
.\tools\navigator-upstream\navi.ps1 upstream runtime-token --write-profile
.\tools\navigator-upstream\navi.ps1 upstream verify-agent-readiness --upstream-user-id <id>
.\tools\navigator-upstream\navi.ps1 upstream ensure-grant --upstream-user-id <id>
.\tools\navigator-upstream\navi.ps1 upstream ask --upstream-user-id <id> --message "..."
.\tools\navigator-upstream\navi.ps1 upstream messages --task-id <taskId> --poll
```

`runtime-token --write-profile` 只写入当前项目 gitignored profile，不打印完整 token。带 `NAVI_CLIENT_APP_SECRET` 的项目中，后续 runtime 命令也会自动在内存中交换 fresh runtime token，避免上游手工复制 token。

常规使用不需要传 `--profile`。只有临时切换配置或排查问题时才使用：

```powershell
.\tools\navigator-upstream\navi.ps1 upstream config check --profile .\temp\another-upstream.env
```

E2E 回归使用独立 wrapper，仍读取同一个 project-local `.navigator/upstream.env`：

```powershell
.\tools\navigator-upstream\navi-e2e.ps1 config check
.\tools\navigator-upstream\navi-e2e.ps1 model ensure --standard biz-worker --set-default --write-profile
.\tools\navigator-upstream\navi-e2e.ps1 script register --file .\.navigator\e2e-script.json
.\tools\navigator-upstream\navi-e2e.ps1 debug requests --trace-id <e2eTraceId>
.\tools\navigator-upstream\navi-e2e.ps1 script cleanup --trace-id <e2eTraceId>
```

`model ensure` 需要 `.navigator/upstream.env` 中存在 `NAVI_CLIENT_APP_ID`，并提供 `NAVI_CONTROL_API_KEY`。它只维护当前 ClientApp 的标准 E2E model grant；不会修改租户默认模型。`NAVI_ADMIN_TOKEN` 或 `NAVI_ADMIN_API_KEY` 仅作为 Navigator 内部 fallback。

## 更新

```powershell
.\tools\navigator-upstream\navi.ps1 self update
```

更新逻辑：

- 优先读取环境变量 `NAVI_UPSTREAM_CLI_URL`。
- 否则读取安装目录下的 `RELEASE_URL`。
- 拉取 `<release-base-url>/latest.json`。
- 下载 Windows 发布包并校验 SHA256。
- 同版本也会比较本地 `RELEASE_MANIFEST.json` 中的包 SHA256；SHA 不一致时会刷新安装。
- 重新运行安装脚本覆盖 `tools/navigator-upstream/`。
- 保留项目自己的 `.navigator/upstream.env`。

## Navigator 侧打包发布

在 Navigator 仓库执行：

```powershell
powershell -ExecutionPolicy Bypass -File tools\navigator-upstream-cli\dist\package.ps1
```

产物位置：

```text
tools/navigator-upstream-cli/dist/output/navigator-upstream-cli-<version>-windows.zip
tools/navigator-upstream-cli/dist/output/navigator-upstream-cli-<version>-windows.zip.sha256
```

上传到 OBS：

```powershell
copy tools\navigator-upstream-cli\.env.example tools\navigator-upstream-cli\.env
# 填写 RELEASE_OBS_BUCKET / RELEASE_BASE_URL
powershell -ExecutionPolicy Bypass -File tools\navigator-upstream-cli\dist\package.ps1 -Upload
```

发布规则：

1. 上游可见版本必须单调递增，常规交付不要复用同一个 `SNAPSHOT` 版本。
2. `latest.json` 必须包含 `version`、`buildId`、`buildTimeUtc`、`gitCommit`、`gitDirty`、`features`、`files.windows` 和 `sha256.windows`。
3. 上传脚本默认拒绝同版本覆盖；`-AllowSameVersion` 只允许用于 OBS metadata repair，不作为正常交付路径。
4. 上传脚本默认执行 OBS 远端安装 smoke，验证 `version`、`upstream --help` 和 `upstream function --help`。

正常发版：

```powershell
powershell -ExecutionPolicy Bypass -File tools\navigator-upstream-cli\dist\package.ps1
powershell -ExecutionPolicy Bypass -File tools\navigator-upstream-cli\dist\upload.ps1 -Version <version>
```

上传脚本会发布：

```text
<release-base-url>/install.ps1
<release-base-url>/latest.json
<release-base-url>/<version>/navigator-upstream-cli-<version>-windows.zip
```

当前 OBS 发布根地址：

```text
https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/navigator-upstream-cli
```

发布校验：

- `latest.json` 可访问，版本与本次发布版本一致。
- `latest.json` 的 `features` 包含 `function-import`、`function-grant`、`function-grant-status`、`function-visible`。
- Windows 包 SHA256 与 `latest.json.sha256.windows` 一致。
- 临时上游项目执行远程安装命令成功。
- 安装后 `.\tools\navigator-upstream\navi.ps1 upstream config check` 成功，默认读取本项目 `.navigator/upstream.env`。
- 临时解压包执行 `.\navi.ps1 version`、`.\navi.ps1 upstream --help` 与 `.\navi.ps1 upstream function --help` 成功。

## 安全约束

- 不把 `.navigator/upstream.env` 提交到 Git。
- 不在命令、文档、截图、日志中打印 token、secret、runtime access token。
- 不把多个上游项目配置混放到同一个全局 profile。
- 不调用 `/internal/worker-gateway/v1/**`。
- `ClientApp secret` 只用于 runtime-token 交换，不发送到 `ask`、`messages`、`sessions` 或 skill 读取接口。

## 排障

- `java not found in PATH`：安装 JDK 17+。
- `No release URL configured`：通过远程安装脚本重新安装，或设置 `NAVI_UPSTREAM_CLI_URL`。
- `Profile path is not git-ignored`：确认上游项目 `.gitignore` 包含 `.navigator/upstream.env`。
- `verify-agent-readiness` 仍提示缺少 token：确认已经升级到包含 issue #104 修正的 CLI，并且 `.navigator/upstream.env` 中有 `NAVI_CLIENT_APP_KEY` 与 `NAVI_CLIENT_APP_SECRET`。
- `client app credential expired`：刷新当前上游项目自己的 ClientApp runtime credential。
- `Agent not found`：确认 `.navigator/upstream.env` 中 `NAVI_AGENT_CODE` 是当前环境已注册的 agent。
