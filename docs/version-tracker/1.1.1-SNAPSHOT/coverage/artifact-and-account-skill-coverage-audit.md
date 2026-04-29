---
audit_scope: version
audit_mode: pre-acceptance-check
version: 1.1.1-SNAPSHOT
target: artifact-and-account-skill-runtime
status: reviewed
conclusion: ready-for-acceptance
reviewed_by: Codex
reviewed_at: 2026-04-29
follow_up_required: no
---

# Test Coverage Audit

## Background

This audit covers the 1.1.1-SNAPSHOT implementation for Worker-side artifact storage, account-scoped file tools, private Skill hot loading/routing, and the related quality-gate remediation work.

The implementation quality gate previously identified six blockers. Those blockers have been remediated and verified with targeted tests, full Worker tests on Windows, and Linux/WSL symlink-focused tests.

## Audit Basis

- Requirements and acceptance criteria: `docs/version-tracker/1.1.1-SNAPSHOT/README.md`
- Progress evidence: `docs/version-tracker/1.1.1-SNAPSHOT/progress/artifact-and-account-skill-progress.md`
- Quality evidence: `docs/version-tracker/1.1.1-SNAPSHOT/quality/artifact-and-account-skill-implementation-quality.md`
- Bug/remediation record: `docs/version-tracker/1.1.1-SNAPSHOT/workitems/BUG-quality-gate-blockers.md`
- Worker test suite:
  - `tools/langgraph-biz-worker/tests/test_artifact_store.py`
  - `tools/langgraph-biz-worker/tests/test_artifact_context_governance.py`
  - `tools/langgraph-biz-worker/tests/test_account_path_guard.py`
  - `tools/langgraph-biz-worker/tests/test_account_file_tools.py`
  - `tools/langgraph-biz-worker/tests/test_account_skill_routing.py`
  - `tools/langgraph-biz-worker/tests/test_llm_skill_agent.py`

## Coverage Matrix

| Requirement / Acceptance / Bug | Risk | Evidence | Layer | Coverage Conclusion |
| --- | --- | --- | --- | --- |
| AC-01 Artifact create/read/account/task isolation | high | `test_artifact_store.py`: create, read modes, task/account isolation, validation errors | unit | covered |
| AC-02 Long content externalization and retained-message scrubbing | high | `test_artifact_context_governance.py`; `test_llm_skill_agent.py` artifact tool behavior returns `artifact_id` without `content_ref` | unit | covered |
| AC-03 Account file tools share path permission and output governance | critical | `test_account_path_guard.py`; `test_account_file_tools.py` path rejection and allowed resource tests | unit | covered |
| AC-04 `write_file(mode=overwrite)` explicit overwrite, audit, and concurrency guard | high | `test_account_file_tools.py`: overwrite default rejection, explicit overwrite, audit record, `expected_sha256` | unit | covered |
| AC-05 `patch_file` unified diff, single-file, atomic reject/no partial write | high | `test_account_file_tools.py`: valid patch, conflict rejection, no partial write, nonexistent file, multi-file patch rejection, `expected_sha256` | unit | covered |
| AC-06 Private `SKILL.md` write then `SkillRegistry.load(account_id)` discovers new Skill | high | `test_account_skill_routing.py`: registry reload discovery after file write | unit | covered |
| AC-07 Privilege path rejection: other account, public Skill, builtin Skill, absolute path, traversal | critical | `test_account_path_guard.py`; `test_account_file_tools.py`; `test_artifact_store.py` boundary/tamper validation | unit | covered |
| AC-08 Query-time `userId/accountId` routes to newly created private Skill | high | `test_account_skill_routing.py`: query-time route reaches private Skill; `root_graph.py` account passthrough verified | unit | covered |
| AC-09 Linux path safety rejects path-chain symlink and target symlink | critical | WSL run of `test_account_path_guard.py` and `test_account_file_tools.py` with `-k symlink`: `6 passed` | platform unit | covered |
| AC-10 `.fsscript` is writable only under `references/**` or `assets/**`, cannot replace root `SKILL.md`, no execution semantics | medium | `test_account_path_guard.py`; `test_account_file_tools.py` `.fsscript` allowed/rejected path cases | unit | covered |
| AC-11 Full `tools/langgraph-biz-worker` Python tests pass | high | Windows full run: `304 passed, 6 skipped` | suite | covered |
| AC-12 Self-check, quality, coverage audit, acceptance record workflow | medium | self-check/progress updated; quality gate remediated; coverage audit completed; acceptance signoff completed | process | covered |
| BUG-F1 Root graph used global SkillRegistry and dropped account context | critical | `test_account_skill_routing.py`: `run_skill` passes account id to `LlmSkillAgent`; private routing tests | unit | covered |
| BUG-F2 `ArtifactStore` allowed unsafe path segments in account/task/artifact ids | critical | `test_artifact_store.py`: invalid account/task/artifact id validation | unit | covered |
| BUG-F3 `ArtifactStore.read_artifact` trusted stored `content_ref` too much | critical | `test_artifact_store.py`: tampered `content_ref` outside account is rejected | unit | covered |
| BUG-F4 Symlink chain under account root was not rejected before `resolve()` | critical | WSL symlink run: parent symlink and target symlink rejection tests pass | platform unit | covered |
| BUG-F5 `patch_file` accepted ambiguous/multi-file unified diff input | high | `test_account_file_tools.py`: multi-file patch rejected | unit | covered |
| BUG-F6 Progress/quality evidence was stale after remediation | medium | progress and quality documents updated with current Windows and WSL evidence | documentation | covered |

## Evidence Summary

- Targeted Windows verification after remediation:
  - `PYTHONPATH=src .\.venv\Scripts\python.exe -m pytest tests/test_artifact_store.py tests/test_account_file_tools.py tests/test_account_skill_routing.py tests/test_llm_skill_agent.py`
  - Result: `64 passed, 2 skipped`
- Broader targeted Windows verification:
  - `PYTHONPATH=src .\.venv\Scripts\python.exe -m pytest tests/test_artifact_store.py tests/test_account_path_guard.py tests/test_account_file_tools.py tests/test_account_skill_routing.py tests/test_llm_skill_agent.py`
  - Result: `100 passed, 6 skipped`
- Full Worker verification on Windows:
  - `PYTHONPATH=src .\.venv\Scripts\python.exe -m pytest tests`
  - Result: `304 passed, 6 skipped`
- Linux/WSL symlink verification:
  - `PYTHONPATH=src python -m pytest tests/test_account_path_guard.py tests/test_account_file_tools.py -k symlink`
  - Result: `6 passed, 67 deselected`

Result notes:

- Windows `6 skipped` are expected: they are Linux-only symlink tests skipped by `pytest.mark.skipif(sys.platform == "win32")`.
- WSL `67 deselected` is expected: the `-k symlink` filter selected only the six symlink tests and excluded unrelated tests in the two targeted files.

## Gaps

No blocking coverage gaps remain for pre-acceptance.

Known non-blocking residuals:

- Live external LLM provider integration is not covered. The current scope verifies the Worker tool loop and routing with deterministic fake model tests.
- Runtime audit persistence remains in-memory for this version and is covered at the tool behavior level.
- Acceptance signoff has been created at `docs/version-tracker/1.1.1-SNAPSHOT/acceptance/version-signoff.md`.

## Recommended Next Skills

- `foggy-acceptance-signoff`: create the final acceptance/signoff record for `docs/version-tracker/1.1.1-SNAPSHOT`.

## Conclusion

The 1.1.1-SNAPSHOT Worker implementation has sufficient requirement, bug-regression, path-security, platform-symlink, and process evidence for acceptance review.

Conclusion: `ready-for-acceptance`.
