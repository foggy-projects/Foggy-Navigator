# Restricted shell_command Tool 设计

## 文档作用

- doc_type: requirement
- intended_for: execution-agent, reviewer
- purpose: 记录 BizWorker 后续内置受限 `shell_command` 工具的设计口径、命令边界和验收标准。

## 状态

- 状态: 设计记录
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

## 设计目标

1. 给 LLM 提供接近 Codex 的文件观察与轻量文本处理体验。
2. 命令格式以 Linux 常见命令为准，降低模型学习成本。
3. 工具能力受限，只操作当前 account/workspace resolver 返回的文件作用域。
4. 默认不执行真实系统 shell，不允许任意进程、网络、环境变量或系统目录访问。
5. 输出可进入 `llm-submissions` 复盘日志，便于排查“模型看到了什么”。

## 非目标

1. 不实现完整 Bash / POSIX shell。
2. 不支持任意命令、脚本执行、后台进程、包管理器、网络命令或系统管理命令。
3. 不替代 `invoke_business_function`、`invoke_business_skill`、`invoke_business_agent`。
4. 第一阶段不支持写操作命令，例如 `rm`、`mv`、`cp`、`mkdir`、`touch`、重定向写文件。
5. 第一阶段不支持管道、命令替换、变量展开、glob 高级语法或 shell profile。

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

后续如果确实需要 `git diff`、`python -m json.tool` 这类能力，应按单项能力设计和授权，不走通用 shell 放开。

## 路径与安全边界

1. 所有路径都必须通过 Account Workspace Resolver 和 path guard。
2. 命令中的路径统一按 Linux 风格 `/` 解析，再映射到宿主系统路径。
3. 禁止绝对路径，除非 delegated workspace 显式允许并经过 resolver 归一化。
4. 禁止 `..` 逃逸、符号链接逃逸和访问 workspace 外文件。
5. `workdir` 只能是 workspace 内相对目录。
6. 输出大小、单文件读取大小、搜索结果数量必须有硬限制。

## 实现建议

不要把 `command` 直接交给宿主机 shell。建议实现为:

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

提示词中应明确:

- 命令格式使用 Linux 风格。
- 只能使用已支持的受限命令。
- 观察优先使用 `shell_command`，修改优先使用 `patch_file`。
- 不要尝试系统管理、网络访问、安装依赖或执行脚本。

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

## 后续扩展

后续可按能力逐项开放:

1. 只读 `git status` / `git diff`，用于代码型 delegated workspace。
2. `jq` 更完整的只读 JSON 查询子集。
3. 安全的 `tree` 输出。
4. 受控 `python`/脚本运行沙箱，但必须作为独立工具或独立授权能力设计，不能混入第一阶段 `shell_command`。

任何扩展都必须满足:

- 命令 allowlist 明确。
- 不经宿主 shell。
- 路径经 resolver/path guard。
- 输出可预算、可截断、可复盘。
- 默认不获得网络和系统管理权限。
