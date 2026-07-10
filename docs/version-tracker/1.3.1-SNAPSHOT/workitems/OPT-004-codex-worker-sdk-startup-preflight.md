# OPT-004 Codex Worker SDK 启动前置检查与固定版本自修复

## 文档作用

- doc_type: requirement-and-implementation-record
- intended_for: implementation-agent | reviewer | release-owner
- owner: `tools/codex-agent-worker`
- target_release: `codex-worker 1.0.10`

## 目标

Codex Worker 启动前必须确认已安装的 `@openai/codex-sdk` 满足 Worker 声明的最低版本。缺失或过低时，默认只修复到当前 Worker 已验证的固定版本，不允许启动流程追踪 `latest`；修复失败时阻断启动。

## 需求

1. 最低版本和修复版本由独立、不可被 npm 安装改写的运行要求文件声明。
2. 启动时读取已安装 SDK 版本并进行 semver 比较。
3. SDK 缺失、非法或低于最低版本时，默认修复到固定验证版本。
4. 修复后必须复检；安装或复检失败时退出非零且不启动 Worker。
5. 当前 npm registry 安装失败时，回退到官方 npm registry 重试一次。
6. `upgrade-sdk` 不允许显式安装低于最低要求的版本；仅开发恢复允许显式 `force`。
7. `CODEX_WORKER_AUTO_UPDATE_SDK=false` 可关闭启动自动修复，关闭后版本不满足应直接阻断。

## 扩展完成条件

- `/health` 暴露已安装版本、最低版本和兼容状态。
- Windows、Linux/macOS 启动就绪判断必须要求 SDK available 且 compatible。
- 自动化测试覆盖版本比较、缺失、低版本、非法版本、固定版本修复、复检失败、registry fallback、降级保护和关闭开关。
- 全量 Worker 单测、类型检查、构建、发布包内容检查和真实启动 smoke 通过。

## 进度

- [x] 用户确认实施方向
- [x] 建立不可变运行要求文件和共享 preflight 核心
- [x] 接入启动脚本
- [x] 接入固定版本修复、复检与 registry fallback
- [x] 接入 `upgrade-sdk` 降级保护
- [x] 接入关闭开关
- [x] 补充健康状态与 READY 判断
- [x] 自动化测试与发布包验证
- [x] OBS 发布与远端归档校验

## 实现摘要

- `runtime-requirements.json` 独立声明最低版本与固定修复版本 `0.144.1`。
- `scripts/ensure-sdk.mjs` 负责 semver 检查、固定版本修复、修复后复检、registry fallback 和目标版本降级保护。
- 开发版与发布版 Windows/Linux 启动脚本均在创建 Worker 进程前执行 preflight。
- `CODEX_WORKER_AUTO_UPDATE_SDK=false` 关闭自动修复；不满足版本要求时启动返回非零。
- `/health` 增加 `codex_sdk_version`、`codex_sdk_minimum_version`、`codex_sdk_compatible`，READY 必须同时满足 SDK available 与 compatible。

## 验证证据

- `npm test`: 109/109 PASS。
- `npm run typecheck`: PASS。
- `npm run build`: PASS。
- PowerShell 与 Bash 启动、安装、升级、CLI 脚本语法检查：PASS。
- Windows 发布包正向 smoke：Worker `1.0.10`，SDK `0.144.1`，minimum `0.144.1`，compatible `true`；`codex-latest` 解析到 `gpt-5.6-sol` 并返回 `FINAL_SDK_PREFLIGHT_OK`。
- Windows 发布包负向 smoke：伪造 SDK `0.142.5` 且设置 `CODEX_WORKER_AUTO_UPDATE_SDK=false`，启动退出码 `3`，端口未监听。
- OBS `latest.json`: `1.0.10`，发布日期 `2026-07-10`。
- 远端归档 SHA-256：Linux/macOS `0599836DF4A4C642D64CDB08739C80645E8BCC2C4947CF58B47566184C946A8B`；Windows `5B53F6DC53D51358C665411F5D31BB1002BAE64D259B0F23684D12D9452CD556`。
