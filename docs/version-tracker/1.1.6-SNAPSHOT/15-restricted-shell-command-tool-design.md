# Restricted shell_command Tool 设计

## 文档作用

- doc_type: requirement
- intended_for: execution-agent, reviewer
- purpose: 记录 BizWorker 后续内置受限 `shell_command` 工具的设计口径、命令边界和验收标准。

## 状态

- 状态: 设计记录，2026-05-23 已补充 Linux-only `command` 能力决策
- 日期: 2026-05-22
- 适用范围: `tools/langgraph-biz-worker`
- 关联文档:
  - `09-llm-submission-message-contract.md`
  - `12-agent-frame-and-skill-tool-boundary.md`
  - `14-account-workspace-resolver-and-delegated-mode.md`

## 背景

Codex worker 的文件操作体验更接近真实开发者终端: LLM 通过 `shell_command` 使用 `ls`、`rg`、`sed`、`cat` 等命令观察文件，再通过 `apply_patch` 修改文件。它不暴露大量细粒度 `read_file` / `write_file` 类工具，而是把“路径 + 命令”作为主要交互面。

BizWorker 当前已经有 `list_files`、`read_file`、`write_file`、`patch_file` 四个默认文件工具，适合业务 Agent 的受控文件操作。但在 delegated workspace / 更纯粹 Agent runtime 场景下，LLM 也需要一种更自然的“命令行式观察工作区”的能力。

因此后续可以在 BizWorker 内部实现一个受限版 `shell_command` 工具。它提供 Linux 风格命令格式，但不等于开放宿主机真实 shell。

2026-05-23 补充决策：文件读写不整体迁移到 command，`list_files`、`read_file`、`write_file`、`patch_file` 仍是受控文件操作的默认入口。但 BizWorker 在实际执行代码型或运维型任务时确实需要 `git`、`curl`、测试命令、构建命令等外部工具，因此需要新增真实命令执行能力。该能力不应混入受限 `shell_command` 的文件观察口径，建议作为单独的 `command` 工具推进，并先只在 Linux worker 环境下开启。

## 设计目标

1. 给 LLM 提供接近 Codex 的文件观察与轻量文本处理体验。
2. 命令格式以 Linux 常见命令为准，降低模型学习成本。
3. 工具能力受限，只操作当前 account/workspace resolver 返回的文件作用域。
4. 默认不执行真实系统 shell，不允许任意进程、网络、环境变量或系统目录访问。
5. 输出可进入 `llm-submissions` 复盘日志，便于排查“模型看到了什么”。
6. 对确需真实外部工具的 delegated workspace 场景，提供单独的 Linux-only `command` 能力；当前直接开放给具备可写 `workdir` 的 Linux worker，可通过 worker 配置关闭。

## 非目标

1. `shell_command` 不实现完整 Bash / POSIX shell。
2. `shell_command` 不支持任意命令、脚本执行、后台进程、包管理器、网络命令或系统管理命令。
3. 不替代 `invoke_business_function`、`invoke_business_skill`、`invoke_business_agent`。
4. 第一阶段不支持写操作命令，例如 `rm`、`mv`、`cp`、`mkdir`、`touch`、重定向写文件。
5. 第一阶段不支持管道、命令替换、变量展开、glob 高级语法或 shell profile。
6. Linux-only `command` 首期不承诺 Windows 兼容；Windows 语义、PowerShell/cmd 引号规则、编码和进程树清理另行设计。

## 双轨能力边界

后续实现应把两个能力分开建模：

1. `shell_command`: 受限命令解释器，面向文件观察、搜索和截取；默认不经真实宿主 shell，不开放 `git`、`curl`、`python`、`npm` 等外部工具。
2. `command`: 真实命令执行工具，面向代码型 delegated workspace 中的 `git`、`curl`、测试、构建和轻量诊断；首期 Linux-only，默认开启，可用 worker 配置关闭。
3. 文件修改仍优先使用 `patch_file` / `write_file`。除非后续另有安全设计，`command` 不作为“把文件读写都转成 shell”的默认路线。
4. `allowed_dirs` / `workdir` 是应用层 guard，不是硬沙箱。启用真实 `command` 的 worker 应优先部署在容器、WSL、VM 或其他可隔离环境中。

## 工具契约

建议工具名:

```text
shell_command
```

建议入参:

```json
{
  "command": "rg -n \"contextId\" .",
  "workdir": ".",
  "max_output_chars": 20000
}
```

字段说明:

- `command`: Linux 风格命令字符串，由 BizWorker 解析为受限 AST。
- `workdir`: 可选，当前 workspace 内的相对目录，默认 `.`。
- `max_output_chars`: 可选，服务端有硬上限，防止工具结果污染 prompt。

返回建议:

```json
{
  "ok": true,
  "exit_code": 0,
  "stdout": "...",
  "stderr": "",
  "truncated": false,
  "workdir": ".",
  "command": "rg -n \"contextId\" ."
}
```

新增真实命令工具建议名:

```text
command
```

建议入参:

```json
{
  "command": "git status --short",
  "workdir": ".",
  "timeout_seconds": 120,
  "max_output_chars": 30000
}
```

返回建议:

```json
{
  "ok": true,
  "exit_code": 0,
  "stdout": "",
  "stderr": "",
  "timed_out": false,
  "truncated": false,
  "duration_ms": 42,
  "workdir": ".",
  "command": "git status --short"
}
```

## 第一阶段命令子集

第一阶段只实现观察类命令。

### 目录与文件查看

```text
pwd
ls
ls -la
find . -maxdepth 2 -type f
cat path/to/file.md
head -n 80 path/to/file.md
tail -n 80 path/to/file.md
wc -l path/to/file.md
```

### 文本搜索

```text
rg "pattern"
rg -n "pattern" .
rg --files
rg --files path/to/dir
```

### 文本截取

```text
sed -n '1,120p' path/to/file.md
```

### JSON 观察

可选支持一个受限 `jq` 子集:

```text
jq '.field' file.json
jq '.items[] | {id, name}' file.json
```

如果第一阶段不实现 `jq`，应在错误中明确提示改用 `cat` / `sed` / `rg` 或后续专用 JSON 工具。

## 禁止命令

以下能力默认禁止:

```text
rm
mv
cp
mkdir
touch
chmod
chown
curl
wget
ssh
scp
git
npm
pip
python
node
powershell
cmd
bash
sh
```

禁止 shell 特性:

```text
>
>>
<
|
&&
||
;
`
$()
*
?
~
$VAR
```

上述禁止项约束的是受限 `shell_command`。2026-05-23 起，`git`、`curl`、测试和构建命令改由独立 `command` 工具承载，不再要求把它们塞进 `shell_command` 的受限解释器中。

## Linux-only command MVP

真实 `command` 首期按以下规则推进:

1. Worker 全局开关默认开启；如需禁用，可设置 `BIZ_WORKER_ENABLE_COMMAND=false`。
2. OS gate: 只在 Linux 环境暴露工具；Windows 环境不出现在 tool schema 中，避免 LLM 反复调用不可用工具。
3. 任务级工具 allowlist 不再单独拦截 `command`：只要满足 Linux、开关开启、非只读、合法 `workdir`，即使 `allowed_tools` 缺省或未包含 `command`，也可暴露和执行。
4. 路径 gate: 必须存在 `workdir`，且解析后的工作目录位于 `allowed_dirs` 内；无 `workdir` 或越权时直接拒绝执行。
5. 执行约束: 无 stdin、非交互、固定 timeout、输出截断、记录 exit code / stdout / stderr / duration。
6. 审计约束: tool audit 记录命令、cwd、退出码、耗时和截断状态；日志输出需要按现有规则做 token / secret 脱敏。
7. 命令面: 首期可优先覆盖 `git`、`curl`、`rg`、`python`、`node`、`npm`、`pnpm`、`pytest`、`mvn` 等开发常用工具；若 worker 未运行在强隔离环境，需再加 executable allowlist。
8. 实现方式: 可以在 Linux worker 中使用 `/bin/bash -lc` 执行命令字符串，但这意味着 `allowed_dirs` 不是硬沙箱；生产启用前必须确认 worker 运行边界足够隔离。
9. 上游策略: 只要上游提供合法 delegated workspace，真实命令能力直接可用；需要禁止命令时使用只读 workspace、不给 `workdir` 或在 worker 侧设置 `BIZ_WORKER_ENABLE_COMMAND=false`。

## Windows / WSL 调试约束

本仓库开发机是 Windows，但 `command` 首期按 Linux-only 开启。因此本地调试应在 WSL 中启动 `tools/langgraph-biz-worker`，并使用独立端口:

```text
3065
```

调试期约定:

1. WSL 内启动 BizWorker，监听 `0.0.0.0:3065` 或可被 Windows host 访问的地址。
2. 健康检查使用 `http://localhost:3065/health`。
3. 需要通过 Navigator / ClientApp 走真实 worker 时，将对应 `bizWorkerBaseUrl` 或 worker URL 指向 3065 端口。
4. Windows 原生 PowerShell / cmd 不作为 `command` 首期验收环境；Windows 兼容性后续另立需求。

## E2E Request Samples

delegated workspace 只要提供合法 `workdir` / `allowed_dirs`，Linux worker 默认暴露 `command`；`allowed_tools` 不需要包含 `command`:

```json
{
  "runtime_context": {
    "execution_policy": {
      "workdir": "/workspace/tms-task",
      "allowed_dirs": [
        "/workspace/tms-task"
      ],
      "allowed_tools": [
        "list_files",
        "read_file",
        "write_file",
        "patch_file"
      ]
    }
  }
}
```

如果需要同时开放文件工具，可继续在 `allowed_tools` 中列出文件工具；`command` 不依赖该列表:

```json
{
  "runtime_context": {
    "execution_policy": {
      "workdir": "/workspace/delegated-task",
      "allowed_dirs": [
        "/workspace/delegated-task"
      ],
      "allowed_tools": [
        "list_files",
        "read_file",
        "write_file",
        "patch_file"
      ]
    }
  }
}
```

本地 WSL 调试环境建议:

```text
BIZ_WORKER_ENABLE_COMMAND=true
BIZ_WORKER_PORT=3065
```

## WSL Smoke Evidence

- date: 2026-05-23
- BizWorker: WSL `http://127.0.0.1:3065`
- Mock LLM: WSL `http://127.0.0.1:3066`
- traceId: `command-smoke-20260523b`
- taskId: `task_command_smoke_20260523b`
- contextId: `bctx_20260523_8b_command_smoke_b`
- workdir: `/tmp/foggy-command-smoke-command-smoke-20260523b`
- command: `git init >/dev/null && git status --short && curl --version | head -1`
- result: `command` tool result returned `ok=true`, `exit_code=0`, `timed_out=false`, and stdout began with `curl 8.5.0`; final skill result was `BizWorker command smoke completed`.
- audit log: `tools/langgraph-biz-worker/data/runtime/sessions/by-date/2026/05/23/8b/bctx_20260523_8b_command_smoke_b/logs/skill-tool-calls/task_command_smoke_20260523b.jsonl`

说明: 第一次 scripted smoke 曾因 mock LLM cursor 脚本重复返回 `command` 导致 max-iterations，命令本身已经返回成功；第二次 smoke 的脚本使用了旧式 `submit_skill_result` 结束回合。该终止工具只用于当时的 scripted finalization，不是 `command` 的必需后续步骤。

## Automated Mock LLM E2E Coverage

- date: 2026-05-23
- Python E2E: `tools/langgraph-biz-worker/tests/test_command_tool_e2e.py`
- 覆盖范围: Linux worker 中通过 mock LLM scripted turn 触发 `command`，执行 `git --version` / `curl --version`，再通过 tool result 中的 cursor 推进到自然语言最终答复；不依赖 `submit_skill_result`。
- 执行条件: 仅在 Linux 且存在 `bash`、`git`、`curl` 时执行；Windows 原生环境自动 skip，本机验证应在 WSL 中运行。
- Java L3 optional: `business-agent-module/integration-tests/tests/03-langgraph-biz-worker-mock-llm.test.ts`
- 覆盖范围: Java Navigator REST 控制面注册临时 tenant / ClientApp / Skill / WorkerPool / LangGraph Worker，将 BusinessAgent task 发往真实 BizWorker，并由 mock LLM 完成 `command -> assistant natural final`。
- 执行条件: 默认跳过；需显式设置 `BIZ_AGENT_E2E_LANGGRAPH_WORKER_SMOKE=true`，并准备 Java Navigator、WSL BizWorker `3065`、mock LLM service。

## WSL Real LLM Smoke Evidence

- date: 2026-05-23
- BizWorker: WSL `http://127.0.0.1:3065`
- LLM config: `tools/langgraph-biz-worker/.env.local` real provider/model config; secrets are intentionally not recorded.
- taskId: `task_real_llm_smoke_20260523_01`
- contextId: `bctx_20260523_97_977ec72ad91940048d406f21427dfc2a`
- expected marker: `OK_REAL_LLM_SMOKE_20260523`
- SSE events: `system -> skill_frame_open -> result`
- result: returned `OK_REAL_LLM_SMOKE_20260523`
- evidence: `tools/langgraph-biz-worker/data/runtime/sessions/by-date/2026/05/23/97/bctx_20260523_97_977ec72ad91940048d406f21427dfc2a/logs/llm-submissions/000001_conversation.root_task_real_llm_smoke_20260523_01_frm_1b9d124f82c3_iter01_attempt01.json`

## Java Navi Real LLM Smoke Evidence

- date: 2026-05-23
- Java Navigator: local `http://localhost:8112`
- BizWorker: WSL `http://localhost:3065`
- tenantId: `tenant_java_navi_smoke_20260523214658_13902c`
- workerId: `lgw_real_smoke_20260523214658_13902c`
- workerPoolId: `bwp_real_smoke_20260523214658_13902c`
- clientAppId: `capp_d18ff014-da09-4aa1-8146-7be3664f1bf1`
- skillId: `java-navi-real-llm-smoke-20260523214658_13902c`
- businessTaskId: `bt_d2b4ee94b75a4d84b0e459473eb1a9b7`
- workerTaskId: `lgt_c16ac4f9e3f948d9`
- contextId: `bctx_20260523_e9_e97b08d4508d4acf86832ac4d21b6414`
- expected marker: `OK_JAVA_NAVI_REAL_LLM_SMOKE_20260523`
- result: Java `LanggraphTaskDTO.status=COMPLETED`; `resultText` returned `OK_JAVA_NAVI_REAL_LLM_SMOKE_20260523`.
- backend evidence: `logs/backend.log` recorded `Task completed: taskId=lgt_c16ac4f9e3f948d9` and SSE stream completion.
- worker evidence: `tools/langgraph-biz-worker/data/runtime/sessions/by-date/2026/05/23/e9/bctx_20260523_e9_e97b08d4508d4acf86832ac4d21b6414/logs/llm-submissions/000001_conversation.root_lgt_c16ac4f9e3f948d9_frm_a58e14f99abb_iter01_attempt01.json`

## 路径与安全边界

1. 所有路径都必须通过 Account Workspace Resolver 和 path guard。
2. 命令中的路径统一按 Linux 风格 `/` 解析，再映射到宿主系统路径。
3. 禁止绝对路径，除非 delegated workspace 显式允许并经过 resolver 归一化。
4. 禁止 `..` 逃逸、符号链接逃逸和访问 workspace 外文件。
5. `workdir` 只能是 workspace 内相对目录。
6. 输出大小、单文件读取大小、搜索结果数量必须有硬限制。

## 实现建议

不要把受限 `shell_command` 直接交给宿主机 shell。建议实现为:

1. 使用 `shlex.split(posix=True)` 解析 Linux 风格命令。
2. 根据首个 token 匹配受限命令 registry。
3. 每个命令映射到 BizWorker 内部 Python 实现或已有文件工具实现。
4. 路径统一调用 resolver/path guard。
5. 输出统一做截断、行数限制和 JSON 序列化。

示例:

```text
shell_command("rg -n \"foo\" docs")
  -> RestrictedShellParser
  -> RgCommand(pattern="foo", path="docs", line_number=true)
  -> workspace_path_guard.resolve("docs")
  -> internal ripgrep-like search 或受控调用 rg binary
```

如选择调用本机 `rg` binary，也必须使用 argv 形式，不经 shell，并且执行目录锁定在 resolved workspace root 下。

真实 `command` MVP 当前采用 Linux worker 中的 Python `subprocess` 调用 `/bin/bash -lc` 执行命令字符串。该路径不是 `shell_command` 的安全解释器路线，必须依赖容器、WSL、VM 或其他 worker 运行边界隔离，并继续保留 worker 开关、Linux OS gate、非只读 `workdir` / `allowed_dirs` 校验。

## 与现有文件工具的关系

第一阶段默认文件工具仍保留:

```text
list_files
read_file
write_file
patch_file
```

`shell_command` 主要用于更自然的文件观察、搜索和截取。文件写入仍优先使用:

```text
patch_file
write_file
```

不建议第一阶段让 `shell_command` 承担写文件职责。这样可以保留 Codex 式探索体验，同时避免把写入、删除、移动等高风险能力混入命令字符串。

## Tool Body 暴露策略

默认建议:

1. 有 account/workspace 文件作用域时，可以默认暴露 `shell_command`。
2. 若上游传入 `ExecutionPolicy.allowed_tools`，仍由 allowlist 决定是否暴露。
3. managed account mode 下，`shell_command` 只操作 `<data_root>/accounts/<accountId>` 或 resolver 返回的稳定 workspace。
4. delegated workspace mode 下，`shell_command` 只操作上游授权的 delegated workspace。
5. `command` 随合法 delegated workspace 直接暴露，必须同时满足 worker 全局开关、Linux OS gate、非只读和合法 `workdir`。

提示词中应明确:

- 命令格式使用 Linux 风格。
- 只能使用已支持的受限命令。
- 观察优先使用 `shell_command`，修改优先使用 `patch_file`。
- 不要尝试系统管理、网络访问、安装依赖或执行脚本。
- 只有看到 `command` 工具时，才可使用真实 `git`、`curl`、测试或构建命令。

## 错误反馈

不支持的命令返回:

```json
{
  "ok": false,
  "exit_code": 127,
  "error_code": "UNSUPPORTED_COMMAND",
  "stderr": "Unsupported restricted shell command: git",
  "supported_commands": ["pwd", "ls", "find", "cat", "head", "tail", "wc", "rg", "sed"]
}
```

路径越权返回:

```json
{
  "ok": false,
  "exit_code": 126,
  "error_code": "PATH_NOT_ALLOWED",
  "stderr": "Path escapes current workspace scope."
}
```

输出过大返回:

```json
{
  "ok": true,
  "exit_code": 0,
  "stdout": "...",
  "truncated": true,
  "stderr": "Output truncated by max_output_chars."
}
```

## 测试与验收

1. `shell_command("pwd")` 返回 workspace 相对视角，不泄露未授权真实系统路径。
2. `shell_command("rg --files")` 只列出当前 workspace 允许文件。
3. `shell_command("cat MEMORY.md")` 能读取 resolver workspace 下的记忆文件。
4. `shell_command("sed -n '1,20p' AGENT.md")` 返回指定行范围。
5. `shell_command("rm MEMORY.md")` 返回 `UNSUPPORTED_COMMAND`。
6. `shell_command("cat ../../secret.txt")` 返回 `PATH_NOT_ALLOWED`。
7. `shell_command("cat big.log")` 按大小限制截断并标记 `truncated=true`。
8. 真实 `llm-submissions` body 中能看到 `shell_command` 工具 schema。
9. 工具结果作为 runtime-visible tool protocol 进入后续上下文，直到被裁剪或压缩。
10. Windows 宿主机上也按 Linux 命令格式解析，LLM 不需要知道底层系统是 Windows。
11. Linux-only `command` 默认出现在合法可写 workspace 的 tool schema；全局开关关闭、OS 非 Linux、无 `workdir` 或 read-only 时不暴露。
12. Windows 原生环境下 `command` 不出现在 tool schema；本机调试通过 WSL + 3065 端口完成。
13. Linux worker 中 `command("git status --short")`、`command("curl --version")` 可在授权 workspace 内返回 exit code/stdout/stderr，并写入 tool audit。
14. `allowed_tools` 缺省或未包含 `command` 时，只要 Linux worker 和 workspace gate 满足，`command` 仍可执行。

## Progress Tracking

### Development Progress

- status: implemented
- 2026-05-23 已落地 Python BizWorker `command` MVP: `BIZ_WORKER_ENABLE_COMMAND` 开关、Linux-only gate、`workdir`/`allowed_dirs` 校验、tool schema 暴露、dispatcher 接入、`subprocess` 执行器。
- 2026-05-24 已放开 `command` 的任务 `allowed_tools` gate: 合法 Linux delegated workspace 中默认可用，`allowed_tools` 不再需要显式包含 `command`。
- Java 侧当前已能传递 `workdir`、`allowed_dirs`、`allowed_tools`，首期不新增 Java 执行逻辑。
- Python `subprocess` API 本身支持 Windows，但 BizWorker `command` 首期实现显式限制为 Linux-only；Windows 原生命令、PowerShell/cmd 引号规则、编码和进程树清理另立设计。

### Testing Progress

- status: automated-mock-e2e-added; complete-through-java-navi-real-llm-smoke
- 已补 Python 单测: 默认开启配置、环境变量关闭、Windows 隐藏、缺少 workdir 隐藏、`allowed_tools` 不含 `command` 仍可执行、workdir 越权拒绝、timeout、subprocess 调用参数、schema 文案。
- 已补 Python mock LLM E2E: `test_command_tool_e2e.py` 固化 scripted `command -> assistant natural final` 闭环，避免真实 LLM smoke 成为唯一回归手段。
- 2026-05-23 收口修正: 顶层 command E2E 已改为 `command -> assistant natural final`，避免误导为 frame result 工具是 command 的必需终止步骤；`submit_frame_result` 仅用于 Root 结构化状态提交或 non-root Agent frame 结构化完成/暂停，`submit_skill_result` 作为旧名兼容 alias 保留。
- Python 全量回归通过: `tools/langgraph-biz-worker` pytest 结果为 `668 passed, 6 skipped`。
- WSL 真实 smoke 通过: BizWorker 端口 3065，授权 workspace 内执行 `git init` / `git status --short` / `curl --version`，tool audit 已记录 `exit_code=0`。
- Java 专项单测已补: LangGraph launcher 将 `allowed_tools`、`workdir`、`allowed_dirs` 写入 hidden `runtime_context.execution_policy`；`command` 不再要求出现在 `allowed_tools` 中。
- 已补 Java Navi 可选 L3: `business-agent-module/integration-tests` 中新增显式开关的 mock LLM command smoke，用于真实 Java 控制面 + 真实 BizWorker 的自动化回归；默认不进入常规测试。
- Java Maven 验证通过: `mvn -pl addons/langgraph-biz-worker -am test` 已完成。期间修正既有测试断言，将 `ClientAppModelConfigGrantServiceTest.grantModelConfig_rejects_invalid_backend` 的非法 backend 样例从已允许的 `CLAUDE_CODE` 改为 `OTHER_BACKEND`。
- WSL 真实 LLM 直连 smoke 通过: `task_real_llm_smoke_20260523_01` 返回 `OK_REAL_LLM_SMOKE_20260523`，并生成 `llm-submissions` 证据。
- Java Navi 真实 LLM smoke 通过: Java REST 控制面注册临时 tenant/clientApp/skill/modelConfig/workerPool 后创建 BusinessAgent task，`lgt_c16ac4f9e3f948d9` 最终 `COMPLETED` 并返回 `OK_JAVA_NAVI_REAL_LLM_SMOKE_20260523`。
- 外部上游联调尚未执行；下一步应交由另一个真实上游或上游模拟 CLI 使用同一 Java Navi 控制面发起任务，重点验证其凭证、clientApp 绑定、modelConfig 授权和 task 创建链路。

### Experience Progress

- status: N/A
- 原因: 本事项为 BizWorker runtime/tool 能力，无直接 UI 页面或交互改动；验收以工具 schema、SSE/tool event、audit log 和真实命令 smoke 为准。

## 后续扩展

后续可按能力逐项开放:

1. `shell_command` 增加更完整的只读 `jq` 查询子集。
2. `shell_command` 增加安全的 `tree` 输出。
3. `command` 增加更细的 executable allowlist、网络访问策略、环境变量白名单和敏感输出脱敏。
4. `command` 的 Windows 支持另立设计，单独处理 PowerShell/cmd 语义、编码和进程树清理。
5. 受控 `python`/脚本运行沙箱若超出普通 `command`，必须作为独立授权能力设计。

任何扩展都必须满足:

- 命令 allowlist 明确。
- `shell_command` 不经宿主 shell；`command` 如经 `/bin/bash -lc`，必须运行在明确隔离的 Linux worker 边界内。
- 路径经 resolver/path guard。
- 输出可预算、可截断、可复盘。
- 默认不获得系统管理权限；网络能力仅在 `command` 可见的合法 Linux workspace 场景下按策略开启。
