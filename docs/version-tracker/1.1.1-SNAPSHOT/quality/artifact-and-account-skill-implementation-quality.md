---
doc_type: implementation_quality_gate
version: 1.1.1-SNAPSHOT
target: artifact-and-account-skill
mode: pre-coverage-audit
status: ready-for-coverage-audit
created_at: 2026-04-28T20:46:00+08:00
remediated_at: 2026-04-28T21:08:00+08:00
linux_symlink_verified_at: 2026-04-29T00:00:00+08:00
---

# Artifact 与账号私有 Skill 实现质量闸门

## Background

本次检查覆盖 `1.1.1-SNAPSHOT` 的 Artifact 外部化、账号目录文件工具、LLM 工具循环，以及 `SkillRegistry` 账号私有 Skill 热加载实现。用户声明 12 项验收标准全部满足，本闸门按阶段性交付前的正式质量检查执行。

## Check Basis

- `docs/version-tracker/1.1.1-SNAPSHOT/README.md`
- `docs/version-tracker/1.1.1-SNAPSHOT/01-biz-worker-artifact-externalization-design.md`
- `docs/version-tracker/1.1.1-SNAPSHOT/02-account-skill-write-file-permission.md`
- `docs/version-tracker/1.1.1-SNAPSHOT/03-artifact-and-account-skill-implementation-plan.md`
- `docs/version-tracker/1.1.1-SNAPSHOT/04-account-file-tools-design.md`
- `docs/version-tracker/1.1.1-SNAPSHOT/progress/artifact-and-account-skill-progress.md`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/account_path_guard.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/artifact_store.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/account_file_tools.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_skill_agent.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/skill_registry.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/graphs/root_graph.py`

## Changed Surface

- 新增 `AccountPathGuard`、`ArtifactStore`、`AccountFileTools`、`LlmSkillAgent`。
- 修改 `SkillRegistry` 以支持 `load(account_id)` 加载账号私有 Skill。
- 修改 `root_graph` 在 query-time 调用 `SkillRegistry.load(account_id)`。
- 新增 Artifact、路径权限、文件工具、上下文治理、账号 Skill 路由相关测试。

## Quality Checklist

| Check | Result | Notes |
|---|---|---|
| Requirement scope implemented | remediated | 图运行时已传递 `data_root` / `account_id`，并补回归测试 |
| Path and account isolation | remediated | `ArtifactStore` 已验证 `account_id` / `task_id` / `artifact_id` 路径段 |
| Symlink rejection policy | remediated | 已先检查原始路径链路；Linux/WSL symlink tests passed |
| Patch contract | remediated | 已拒绝多文件 patch 并补回归测试 |
| Context governance | pass | `create_artifact.content` 已在 LangChain/OpenAI-style tool call 中 scrub |
| Tests executed | pass | 全量 worker tests 通过 |
| Progress evidence accuracy | remediated | 进度文档已更新 Windows 全量测试、WSL symlink 验证、quality remediation 和 coverage/acceptance 当前状态 |

## Findings

### F1 - LLM 图运行时没有接通 Artifact/File tools

Severity: High

`root_graph` 创建 `LlmSkillAgent` 时未传入 `_data_root`，`run_skill` 调用时也未传入 `account_id`。而 `LlmSkillAgent.run()` 只有在 `self._data_root and account_id` 同时存在时才创建 `ArtifactStore` 和 `AccountFileTools`。因此真实图执行中，模型即使拿到 `create_artifact` / `write_file` 等工具 schema，也会得到 `artifact store not configured` 或 file tool 不可用，无法完成本版本闭环。

Evidence:

- `tools/langgraph-biz-worker/src/langgraph_biz_worker/graphs/root_graph.py:51`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/graphs/root_graph.py:186`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_skill_agent.py:69`

Required fix:

- 创建 `_llm_skill_agent` 时传入 `data_root=Path(_data_root)`。
- `run_skill` 调用 `LlmSkillAgent.run()` 时传入 `account_id=state["user_id"]` 或与 `route_skill` 一致的 context fallback。
- 增加图级测试，验证真实 `root_graph.run_skill` 下 `create_artifact` 和至少一个 file tool 可用。

### F2 - ArtifactStore 可被 account_id/task_id 路径穿越

Severity: High

`ArtifactStore.create()` 只检查 `account_id` 非空，随后直接拼接到 `<data_root>/accounts/<account_id>/artifacts`；`task_id` 也直接参与 `task/<task_id>` 路径拼接。`read()` / `_find_metadata()` 同样直接拼接 `account_id`。这破坏了“账号/任务范围隔离”的验收标准。

Evidence:

- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/artifact_store.py:92`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/artifact_store.py:112`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/artifact_store.py:226`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/artifact_store.py:233`

Required fix:

- 为 `account_id` 和 `task_id` 使用单路径段校验，至少拒绝空白、`.`、`..`、`/`、`\`。
- 对最终 artifact base path 做 `resolve().relative_to(data_root / "accounts" / account_id)` 边界校验。
- 增加 `account_id="../x"`、`task_id="../../x"` 的 create/read 负面测试。

### F3 - Artifact content_ref 读取未做边界校验

Severity: Medium

`read(mode="content")` 从 metadata 中取 `content_ref` 后直接 `self._data_root / content_ref` 读取，没有校验 `content_ref` 仍在当前账号 artifact 目录下。正常创建路径不会泄露 `content_ref`，但一旦 metadata 被污染，读取路径就缺少最后一道边界。

Evidence:

- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/artifact_store.py:214`

Required fix:

- 对 `content_ref` resolve 后校验必须位于当前账号 artifact 根目录。
- 增加 metadata tamper 负面测试。

### F4 - symlink 链路检查顺序不满足“路径链路 symlink 均拒绝”

Severity: Medium

`AccountPathGuard._join_and_resolve()` 先调用 `candidate.resolve()`，随后 `_check_parent_symlinks()` 基于已解析后的真实路径检查。若账号目录内的 symlink 指向账号目录内另一个真实目录，原始路径链路中的 symlink 组件会被 `resolve()` 消解，后续检查可能无法发现。需求明确要求路径链路和目标 symlink 均拒绝。

Evidence:

- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/account_path_guard.py:237`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/account_path_guard.py:256`

Required fix:

- 在 follow-symlink 之前，沿原始 `account_root / segments` 逐段检查已存在组件是否为 symlink。
- 增加“账号内 symlink 指向账号内真实目录”的 Linux 负面测试。

### F5 - patch_file 未强制单文件 unified diff

Severity: Medium

`patch_file` 注释声明只支持单文件 patch，但 `_parse_unified_diff()` 只是跳过 preamble 后解析所有 hunk，不识别或拒绝第二组 `---`/`+++` 文件头。多文件 diff 可能被当成同一目标文件的 hunks 处理，和“单文件 patch，目标只由 relative_path 决定”的约定不一致。

Evidence:

- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/account_file_tools.py:365`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/account_file_tools.py:565`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/account_file_tools.py:594`

Required fix:

- 解析并限制最多一组 `---`/`+++` 文件头。
- 遇到第二个文件头或 hunk 归属不明时直接 `patch_conflict` / `invalid_patch`。
- 增加多文件 diff 负面测试。

### F6 - Progress 文档存在证据状态不准确

Severity: Low

进度文档宣称“仅修改 `llm_skill_agent.py`、`test_account_skill_routing.py`；新增 4 个实现文件 + 4 个测试文件”，但当前工作区显示还修改了 `config.py`、`root_graph.py`、`routes/query.py`、`skill_registry.py`、`skill_runtime.py`、README、配置样例等，并新增了更多测试文件。同时“quality / coverage / acceptance 完成”写为 done，但本质量闸门之前版本目录没有 quality/coverage/acceptance 记录。

Evidence:

- `docs/version-tracker/1.1.1-SNAPSHOT/progress/artifact-and-account-skill-progress.md:46`
- `docs/version-tracker/1.1.1-SNAPSHOT/progress/artifact-and-account-skill-progress.md:101`

Required fix:

- 更新进度文档的实际变更面。
- 将 quality/coverage/acceptance 状态改为分阶段证据文件，不用“本文档”替代。

## Verification

Remediation executed after the initial quality gate:

- F1 fixed by passing `data_root` into `LlmSkillAgent` construction and forwarding `account_id` from `run_skill`.
- F2 fixed by validating ArtifactStore `account_id`, `task_id`, and `artifact_id` as single path segments.
- F3 fixed by resolving `content_ref` and enforcing current-account artifact-root boundary.
- F4 fixed by checking original caller-provided path components for symlinks before `resolve()`.
- F5 fixed by adding explicit single-file unified diff validation and regression coverage.
- F6 fixed by updating progress/workitem state; coverage audit and acceptance signoff are now complete.

Executed:

```powershell
$env:PYTHONPATH='src'; .\.venv\Scripts\python.exe -m pytest tests/test_artifact_store.py tests/test_account_path_guard.py tests/test_account_file_tools.py tests/test_artifact_context_governance.py tests/test_account_skill_routing.py
```

Result: `91 passed, 5 skipped`

Superseded by the post-remediation targeted run:

```powershell
$env:PYTHONPATH='src'; .\.venv\Scripts\python.exe -m pytest tests/test_artifact_store.py tests/test_account_file_tools.py tests/test_account_skill_routing.py tests/test_llm_skill_agent.py
```

Result: `64 passed, 2 skipped`

Executed:

```powershell
$env:PYTHONPATH='src'; .\.venv\Scripts\python.exe -m pytest tests
```

Result: `292 passed, 5 skipped`

Superseded by the post-remediation full run:

```powershell
$env:PYTHONPATH='src'; .\.venv\Scripts\python.exe -m pytest tests
```

Result: `304 passed, 6 skipped`

Initial Linux/WSL symlink execution attempt:

```powershell
wsl -e bash -lc "cd /mnt/d/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/tools/langgraph-biz-worker && python3 --version && PYTHONPATH=src python3 -m pytest tests/test_account_path_guard.py tests/test_account_file_tools.py -k symlink"
```

Result: WSL was available with `Python 3.12.3`, but `pytest` was not installed. Creating a temporary venv was blocked because `python3-venv` was missing.

Installed WSL dependencies:

```powershell
wsl -e bash -lc "sudo apt update && sudo apt install -y python3-venv python3-pip"
```

Executed after dependency installation:

```powershell
wsl -e bash -lc "rm -rf /tmp/foggy-lg-worker-test-venv && cd /mnt/d/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/tools/langgraph-biz-worker && python3 -m venv /tmp/foggy-lg-worker-test-venv && . /tmp/foggy-lg-worker-test-venv/bin/activate && python -m pip install -q -e '.[dev]' && PYTHONPATH=src python -m pytest tests/test_account_path_guard.py tests/test_account_file_tools.py -k symlink"
```

Result: `6 passed, 67 deselected`

## Risks / Follow-ups

- 6 个 symlink 测试在 Windows 跳过，但已在当前 WSL 环境通过。
- 已补 `root_graph.run_skill` 账号上下文传递测试、`LlmSkillAgent` artifact tool 可用性测试，以及 frame-frozen manifest 回归测试。

## Recommended Next Skills

- `foggy-test-coverage-audit`: completed, conclusion `ready-for-acceptance`.
- `foggy-acceptance-signoff`: completed, decision `accepted`.

## Decision

`ready-for-coverage-audit`

理由：F1-F5 已修复并通过 Windows 全量测试；Linux/WSL symlink 关键用例也已通过。当前可进入测试覆盖审计。
