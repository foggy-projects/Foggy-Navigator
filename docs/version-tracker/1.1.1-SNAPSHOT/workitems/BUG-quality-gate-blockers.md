---
type: bug
bug_source: acceptance-found
version: 1.1.1-SNAPSHOT
ticket: BUG-quality-gate-blockers
severity: major
status: closed
reproduction_status: confirmed
test_strategy: unit-test
automation_decision: required
owner: langgraph-biz-worker
---

# Quality Gate Blockers

## Background

`foggy-implementation-quality-gate` 对 `1.1.1-SNAPSHOT` 执行正式质量检查后，发现 Artifact/File tools 闭环存在阻断项。问题集中在真实图运行时工具上下文未传递、Artifact 路径边界校验不足、symlink 链路检查顺序、以及 `patch_file` 单文件契约。

## Reproduction

- 质量记录：`docs/version-tracker/1.1.1-SNAPSHOT/quality/artifact-and-account-skill-implementation-quality.md`
- 当前全量测试可通过，但缺少覆盖这些负面场景的回归测试。

## Expected vs Actual

Expected:

- `root_graph.run_skill` 能把 `data_root` 与 `account_id` 传给 `LlmSkillAgent`。
- `ArtifactStore` 拒绝 `account_id` / `task_id` / `artifact_id` 路径穿越。
- `read_artifact(mode=content)` 校验 metadata `content_ref` 仍在当前账号 artifact 根目录内。
- `AccountPathGuard` 在 follow symlink 前拒绝原始路径链路 symlink。
- `patch_file` 拒绝多文件 unified diff。

Actual:

- 上述场景存在实现缺口或缺少测试。

## Impact Scope

- LangGraph Biz Worker 账号私有 Skill 创建闭环。
- Artifact 外部化与读取隔离。
- LLM 账号目录文件工具安全边界。

## Test Strategy

- 补 ArtifactStore 单元负面测试。
- 补 AccountPathGuard Linux symlink 链路负面测试。
- 补 AccountFileTools 多文件 patch 负面测试。
- 补 root_graph 账号上下文传递单元测试。

## Code Inventory

- `tools/langgraph-biz-worker/src/langgraph_biz_worker/graphs/root_graph.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/artifact_store.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/account_path_guard.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/account_file_tools.py`
- `tools/langgraph-biz-worker/tests/test_artifact_store.py`
- `tools/langgraph-biz-worker/tests/test_account_path_guard.py`
- `tools/langgraph-biz-worker/tests/test_account_file_tools.py`
- `tools/langgraph-biz-worker/tests/test_account_skill_routing.py`

## Fix Checklist

- [x] 补失败测试
- [x] 修复 root_graph LLM agent data_root/account_id 传递
- [x] 修复 ArtifactStore 路径段与 content_ref 边界校验
- [x] 修复 AccountPathGuard symlink 检查顺序
- [x] 修复 patch_file 多文件 patch 拒绝
- [x] 跑定向测试
- [x] 跑全量 worker 测试
- [x] 回写质量记录和进度

## Verification

Executed on Windows:

```powershell
$env:PYTHONPATH='src'; .\.venv\Scripts\python.exe -m pytest tests/test_artifact_store.py tests/test_account_file_tools.py tests/test_account_skill_routing.py tests/test_llm_skill_agent.py
```

Result: `64 passed, 2 skipped`

Executed on Windows:

```powershell
$env:PYTHONPATH='src'; .\.venv\Scripts\python.exe -m pytest tests
```

Result: `304 passed, 6 skipped`

Linux/WSL symlink run:

```powershell
wsl -e bash -lc "cd /mnt/d/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/tools/langgraph-biz-worker && python3 --version && PYTHONPATH=src python3 -m pytest tests/test_account_path_guard.py tests/test_account_file_tools.py -k symlink"
```

Initial result: WSL Python available (`Python 3.12.3`), but `pytest` was not installed. Temporary venv creation was blocked because WSL was missing `python3-venv`.

Installed WSL dependencies:

```powershell
wsl -e bash -lc "sudo apt update && sudo apt install -y python3-venv python3-pip"
```

Executed after dependency installation:

```powershell
wsl -e bash -lc "rm -rf /tmp/foggy-lg-worker-test-venv && cd /mnt/d/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/tools/langgraph-biz-worker && python3 -m venv /tmp/foggy-lg-worker-test-venv && . /tmp/foggy-lg-worker-test-venv/bin/activate && python -m pip install -q -e '.[dev]' && PYTHONPATH=src python -m pytest tests/test_account_path_guard.py tests/test_account_file_tools.py -k symlink"
```

Result: `6 passed, 67 deselected`

## References

- `docs/version-tracker/1.1.1-SNAPSHOT/quality/artifact-and-account-skill-implementation-quality.md`
