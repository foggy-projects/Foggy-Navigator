# Artifact 与账号私有 Skill 创建进度

## 文档作用

- doc_type: progress
- intended_for: execution-agent | reviewer | signoff-owner
- purpose: 跟踪 `1.1.1-SNAPSHOT` Artifact 外部化、上下文治理、账号私有 Skill 创建闭环的实现进度和测试证据

## 基本信息

- version: `1.1.1-SNAPSHOT`
- status: **implemented / quality-remediated / linux-symlink-verified**
- upstream_plan: `docs/version-tracker/1.1.1-SNAPSHOT/03-artifact-and-account-skill-implementation-plan.md`
- owner: langgraph-biz-worker / artifact runtime
- updated_at: 2026-04-29T00:00:00+08:00

## 前置条件检查

| Item | Status | Notes |
|---|---|---|
| 1.1.0 version signoff reviewed | done | `accepted-with-risks` follow-up 已迁入 1.1.1 |
| Artifact contract reviewed | done | schema 和错误边界已实现 |
| File tools scope reviewed | done | 6 tools: `read_file/list_files/write_file/edit_file/str_replace/patch_file` |
| `write_file(mode=overwrite)` included | done | 显式传入、权限、runtime audit record 测试通过 |
| `patch_file` included | done | unified diff、单文件、原子写入、冲突处理测试通过 |
| Linux symlink policy reviewed | done | 路径链路和目标 symlink 均拒绝；Windows 上 skip |
| `.fsscript` scope reviewed | done | 仅作为 `references/**` 或 `assets/**` 文本资源 |
| Test command available | done | `.venv/Scripts/python.exe -m pytest tests` |

## Development Progress

| Stage | Scope | Status | Notes |
|---|---|---|---|
| Stage 0 | 契约收口 | done | 工具 schema 在 `llm_skill_agent.py` `_KNOWN_TOOL_SCHEMAS` 中固定 |
| Stage 1 | Artifact Runtime 文件后端 | done | `artifact_store.py`: create/read, scope isolation, content_ref hidden |
| Stage 2 | LLM Tool Loop 接入 | done | `llm_skill_agent.py`: tool dispatch, context governance scrub |
| Stage 3 | 账号目录文件工具族 | done | `account_file_tools.py`: 6 tools + `account_path_guard.py` |
| Stage 4 | 账号私有 Skill 创建 | done | write_file → SkillRegistry.load → query-time routing verified |
| Stage 5 | 回归、质量与验收 | signed-off | Windows full: 304 passed, 6 skipped; WSL symlink: 6 passed; coverage audit and acceptance signoff completed |

## Implementation Self-Check

| Check | Status | Notes |
|---|---|---|
| Requirement scope closed | done | 所有 README 完成标准覆盖 |
| Changed surface reviewed | done | 见 Actual Changes；质量闸门补充了安全边界修复和回归测试 |
| No temporary/debug code retained | done | |
| Permission checks centralized | done | 所有工具共用 `AccountPathGuard` |
| Artifact physical paths hidden from model outputs | done | `content_ref` 从不出现在任何返回 dict |
| Long content not retained in active context | done | `_scrub_create_artifact_content` 替换为轻量占位 |
| Historical `create_artifact.content` scrubbed from Worker-retained messages | done | 测试 `test_artifact_context_governance.py` 验证 |
| Linux symlink paths rejected | done | 测试存在，Windows 上 skip |
| Account file writes serialized or checksum-guarded | done | 每文件 `threading.Lock` + `expected_sha256` |

## Actual Changes

| Area | File | Change |
|---|---|---|
| Path Guard | `runtime/account_path_guard.py` | NEW — centralised path security |
| Artifact Store | `runtime/artifact_store.py` | NEW — file-system artifact backend |
| File Tools | `runtime/account_file_tools.py` | NEW — 6 file tools with audit |
| LLM Agent | `runtime/llm_skill_agent.py` | MODIFIED — tool schemas, dispatch, context scrub |
| Tests | `tests/test_account_path_guard.py` | NEW — 31 tests |
| Tests | `tests/test_artifact_store.py` | NEW — 26 tests including path traversal/content_ref tamper regression |
| Tests | `tests/test_account_file_tools.py` | NEW — 30 tests including multi-file patch rejection |
| Tests | `tests/test_artifact_context_governance.py` | NEW — 4 tests |
| Tests | `tests/test_account_skill_routing.py` | MODIFIED — +3 tests (registry reload, query-time routing, account context passthrough) |
| Tests | `tests/test_llm_skill_agent.py` | MODIFIED — artifact tool availability with account context |
| Work Item | `workitems/BUG-quality-gate-blockers.md` | NEW — quality gate blocker remediation record |
| Quality | `quality/artifact-and-account-skill-implementation-quality.md` | NEW/UPDATED — decision `ready-for-coverage-audit` |
| Coverage | `coverage/artifact-and-account-skill-coverage-audit.md` | NEW — conclusion `ready-for-acceptance` |
| Acceptance | `acceptance/version-signoff.md` | NEW — decision `accepted` |

## Testing Progress

| Test | Command | Status | Result |
|---|---|---|---|
| AccountPathGuard unit tests | `pytest tests/test_account_path_guard.py` | passed | 28 passed, 3 skipped |
| Artifact store tests | `pytest tests/test_artifact_store.py` | passed | 16 passed |
| Account file tools tests | `pytest tests/test_account_file_tools.py` | passed | 25 passed, 2 skipped |
| Context governance tests | `pytest tests/test_artifact_context_governance.py` | passed | 4 passed |
| Skill routing regression | `pytest tests/test_account_skill_routing.py` | passed | 3 passed |
| Quality remediation targeted regression | `.venv/Scripts/python.exe -m pytest tests/test_artifact_store.py tests/test_account_file_tools.py tests/test_account_skill_routing.py tests/test_llm_skill_agent.py` | passed | 64 passed, 2 skipped |
| Full worker regression | `.venv/Scripts/python.exe -m pytest tests` | passed | **304 passed, 6 skipped** |
| WSL dependency setup | `sudo apt update && sudo apt install -y python3-venv python3-pip` | passed | Installed required Python venv/pip support in current WSL |
| WSL symlink regression | `wsl ... python -m pytest ... -k symlink` | passed | 6 passed, 67 deselected |

Notes:

- `6 skipped` in the Windows full run are the six Linux-only symlink tests marked with `pytest.mark.skipif(sys.platform == "win32")`: four in `test_account_path_guard.py` and two in `test_account_file_tools.py`.
- `67 deselected` in the WSL run means `pytest -k symlink` selected only the six symlink tests from the two targeted files and intentionally excluded the other non-symlink tests in those files.

## Unrun Tests and Reasons

| Test | Reason |
|---|---|
| Symlink rejection (6 tests) | Skipped on Windows, passed on WSL Linux |

## Acceptance Criteria Mapping

| Criteria | Status | Evidence |
|---|---|---|
| Artifact 创建、读取、账号隔离、任务隔离测试通过 | done | `test_artifact_store.py` — 26 tests |
| 长参数外部化后 Worker-retained 上下文不携带原始 `content` | done | `test_artifact_context_governance.py` — 4 tests |
| 账号目录文件工具族共用路径权限模型 | done | 所有工具共用 `AccountPathGuard` |
| `write_file(mode=overwrite)` 显式传入、权限、runtime audit record 测试通过 | done | `TestWriteFile::test_overwrite_explicit`, `test_write_audit_record` |
| `patch_file` unified diff 冲突整体失败、不半写入 | done | `TestPatchFile::test_conflict_full_reject`, `test_multi_file_patch_rejected` |
| Linux symlink 路径拒绝测试通过 | done | WSL Linux: 6 passed (`TestSymlinkRejection` — 4 tests; `TestSymlinkFileTools` — 2 tests) |
| `.fsscript` 文本资源允许路径测试通过 | done | `TestFsscript` — 3 tests; `TestFsscriptFileTools` — 2 tests |
| Skill 写入后 `SkillRegistry.load(account_id)` 能发现 | done | `test_write_skill_then_registry_reload_discovers_new_skill` |
| 越权路径拒绝测试通过 | done | `TestRejectAbsolute`, `TestRejectTraversal`, `TestRejectForbidden`, `TestPathRejection` |
| query-time 能路由到新建私有 Skill | done | `test_query_time_routing_reaches_new_private_skill` |
| 全量 Python 测试通过 | done | 304 passed, 6 skipped |
| quality / coverage / acceptance 完成 | done | quality remediation done; coverage audit ready-for-acceptance; version signoff accepted |

## Remaining Risks

| Risk | Assessment |
|---|---|
| Symlink tests only run on Linux | Windows CI will skip them; current WSL evidence is available. |
| Audit records in-memory only | Sufficient for 1.1.1; future versions may persist to file/DB. |
| `edit_file` anchor matching is heading-level based | May need refinement for complex documents. Low risk — `str_replace` is the primary edit tool. |
| No external LLM integration test | Tool dispatch tested via unit tests; full LLM loop requires live API key. Progress documented. |

## Quality Gate Remediation

| Finding | Status | Evidence |
|---|---|---|
| F1 LLM graph runtime artifact/file tools not configured | fixed | `root_graph.run_skill` forwards `account_id`; `LlmSkillAgent` receives `data_root` |
| F2 ArtifactStore account/task traversal | fixed | segment validation + regression tests |
| F3 Artifact content_ref boundary | fixed | `content_ref` resolve + account artifact-root boundary check |
| F4 symlink chain check after resolve | fixed and verified | original path symlink check before resolve + WSL symlink tests passed |
| F5 patch_file multi-file diff | fixed | explicit single-file diff validation + regression test |
| F6 progress evidence accuracy | fixed | this progress update + quality/workitem records |

## Blockers

No code or evidence blockers remain.

## Follow-up

No required follow-up remains for 1.1.1-SNAPSHOT signoff.
