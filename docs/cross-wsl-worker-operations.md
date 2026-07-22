# 跨 WSL Worker 运维与运行态漂移排查

> 适用对象：Navigator 部署在一个 WSL/主机，而 Claude、Codex Worker 运行在另一个 WSL 发行版时的日常恢复与诊断。  
> 最后更新：2026-07-22

## 先确认“哪台 Worker”，再操作

端口不是 Worker 身份。开始前必须从 Navigator 记录确认 Physical Worker ID、WorkerHost、Claude URL 和 Codex role URL；再确认目标发行版和安装目录。不要因为另一个 Worker 的端口健康，就把它当成冻结绑定的 Worker。

Windows 宿主机可用下列方式枚举并进入 WSL：

```bash
/mnt/c/Windows/System32/wsl.exe -l -v
/mnt/c/Windows/System32/wsl.exe -d <distro> -- bash -lc 'hostname; id; pwd'
```

在目标发行版中，先核验配置的端口和安装目录，再检查监听者的命令行、PID 与工作目录。只有确认归属于目标安装目录的既有 Worker，才允许启动、停止或重启。

## 最小诊断顺序

按以下顺序执行；前一步失败时不要跳到 Directory、runtime token 或真实 ask。

1. 在目标 WSL 检查 Claude/Codex 端口监听，以及各自 `/health`。
2. 在 Navigator 的 system-admin lane 对既有 Physical Worker 执行 `worker health` 和 `worker get`。
3. 执行 `worker processes`，确认受 bearer token 保护的管理路由也可用。
4. 用 `worker-host verify --file <manifest>` 核对 Claude 与 Codex role、base URL 和 source。

`/health` 无需 Worker bearer token。因此 `worker health=ONLINE` 只说明 Navigator 能访问健康端点；它**不能**证明受保护的管理 API 可调用。`worker-host verify` 只校验 manifest 结构与角色投影，也不做远程健康或鉴权探测。

## 常见漂移与安全恢复

### 两个端口未监听

如果 Navigator 中既有 Worker 的 Claude/Codex URL 指向某个目标发行版，但该发行版没有监听对应端口：

1. 在目标发行版确认 `~/.claude-worker`、`~/.codex-worker`（或实际安装目录）的 `.env` 中端口与 Navigator 记录一致。
2. 确认端口上不存在其他进程后，仅启动该安装目录中既有的 `start.sh` / `start.ps1`。
3. 重新执行四步最小诊断。

不要用另一台健康 Worker、另一组端口或新注册的 Worker 替代冻结的 Physical Worker。

### `worker processes` 报 401 或 `CLAUDE_WORKER_PROCESS_QUERY_FAILED`

这通常表示 Navigator 中该 Physical Worker 保存的 bearer token 与目标 Worker `.env` 的 Worker token 不一致，而不是端口问题。

可在 system-admin lane 对**同一个** Physical Worker 做原地 token 同步：

- 使用 `worker-host update --worker-id <existingPhysicalWorkerId>`；
- 保持 `workerHostId`、Claude/Codex URL、端口、名称、`authMode` 和 role 结构不变；
- 仅经 `authTokenEnv` 提供运行时 token，不把 token 写入 manifest、命令历史、日志或仓库文件；
- 更新后再次执行 `worker health`、`worker processes` 和 `worker-host verify`。

这不是新 WorkerHost 的 `apply`，也不应改变 owner、tenant/clientApp scope、Directory Worker、BizWorkerIdentity 或 WorkerPool member。

## 禁止事项

- 不因端口相同或“健康”而使用另一个 Physical Worker 替换既有绑定。
- 不使用 `worker-host apply` 修复一个已有 Physical Worker。
- 不创建第二个 Worker、BizWorkerIdentity 或 WorkerPool member 来绕过漂移。
- 不把 Worker bearer token 放进 Git、提交的 JSON、终端输出或交付材料。
- 不把 `NAVIGATOR_EXTERNAL_ENABLED` 或 Worker Gateway strict/production 开关当作恢复步骤。

## 完成证据

恢复完成后，交付信息应至少包含：

- 目标 WSL、Claude/Codex 监听端口与 Worker 版本；
- Physical Worker ID 的 `ONLINE` 状态；
- `worker processes` 成功结果（空列表也有效）；
- Codex role 的 source，预期为 `CLAUDE_WORKER_CODEX_CONFIG`；
- 是否重启 Navigator 或 Worker；
- Navigator 与 Worker build/version；
- 明确说明未创建、替换或跨 scope 操作任何资源。
