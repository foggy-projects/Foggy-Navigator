---
doc_type: delivery-spec
delivery_type: bug
version: 1.4.3-SNAPSHOT
ticket: BUG-023
status: ACCEPTED
canonical: true
execution_mode: ultra
assurance_level: standard
bug_source: user-report
approved_by: project-owner-explicit-implementation-request
approved_at: 2026-07-27
open_questions: []
---

# Delivery Spec: Chat POSIX file link exact resolution

## Document Purpose

- intended_for: ultra-implementation / independent-signoff
- purpose: 修复聊天消息中的 POSIX 工作区绝对文件链接被降级为 basename 搜索、从而无法定位或可能误匹配同名文件的问题。
- canonical_path: `docs/version-tracker/1.4.3-SNAPSHOT/workitems/BUG-023-chat-posix-file-link-resolution.md`

## Goal

- version_goal: 让聊天输出中的工作区文件链接在 Linux/Windows 工作目录下均可稳定、精确地打开文件浏览器目标文件。
- target_outcome: 对当前目录根内的完整绝对 href，客户端保留完整路径语义并直接生成精确 `filePath` deeplink，不调用 basename 搜索。
- critical_outcomes:
  - POSIX 与 Windows 绝对路径在目录根内均精确定位，不受同名文件影响。
  - 根外绝对路径 fail closed，不降级为 basename 搜索或误开文件。
  - 既有相对路径、basename、行号、编码路径、文件浏览器 deeplink 和外部 HTTP(S) 链接行为保持兼容。
  - 现场 `search` 返回空的问题完成分层诊断；只有证明属于当前代码缺陷且无需新增公共契约时才在本 BUG 内修复。
- success_is_sufficient_when: focused regression tests、完整前端构建、静态检查及本地/目标运行态 smoke 对关键路径给出一致证据。

## Scope

- in_scope:
  - POSIX 绝对路径识别、目录根 containment 判断和精确相对路径 deeplink。
  - 根外绝对路径拒绝且禁止 basename fallback。
  - 点击外部 HTTP(S) 链接时不依赖当前工作目录选择。
  - 保留 Windows、相对路径和 basename 搜索兼容行为。
  - 对 `list`、`content`、`search` 的现场结果进行分层诊断；必要时修复已存在实现中的窄范围枚举缺陷。
  - 自动化回归测试、前端构建和浏览器/API smoke。
- affected_modules:
  - `packages/navigator-frontend`
  - `docs/version-tracker/1.4.3-SNAPSHOT`
  - 仅当诊断证明需要时：`addons/claude-worker-agent` 或 `tools/claude-agent-worker`
- external_dependencies: 用户指定的当前项目 Java 服务与对应 `tools/` Worker；同级 `foggy-data-mcp` 只作为只读文件目标。

## Non-Goals

- out_of_scope:
  - 新增 `/api/v1/file-browser/resolve` 或其他公共服务端路径解析契约。
  - 把完整 href 直接放入现有 basename-only `search.query`。
  - 修改同级 `foggy-data-mcp`、TMS 或 SIM 仓库。
  - 改变 File Browser 的文件读取、编辑、权限或目录 ownership 模型。
  - Worker 发布、升级远端非当前工作区实例或 production enablement。
- do_not_touch:
  - 与本 BUG 无关的现有 worktree 改动和本机配置。
  - 用户提供的 Authorization token；不得写入代码、测试、文档、命令历史或持久日志。
- non_blocking_or_waivable_items:
  - 若目标域网络或认证状态阻止浏览器 smoke，可记录精确阻断并保留本地运行态证据。

## Confirmed Decisions

| Decision | Rationale | Compatibility / Constraint |
|---|---|---|
| 当前目录根内的绝对 href 由客户端直接转换为 `filePath` | 客户端已持有 directory root 和完整 href，且 File Browser 已支持精确 deeplink | 不调用 search；必须先做规范化 containment 检查 |
| POSIX 和 Windows 绝对路径采用一致语义 | 当前缺陷只因绝对路径识别偏向 Windows | Windows 既有行为不得回归 |
| 根外绝对路径 fail closed | basename fallback 可能误开同名文件并掩盖路径越界 | 返回明确 warning，不进行 search |
| 相对路径和纯 basename 继续使用现有搜索能力 | 这类引用本身缺少可直接解析的目录根信息 | 多个同名结果继续提示补充完整路径，不任意选择 |
| 不在本 BUG 新增服务端 resolver API | 当前精确定位可由既有可信目录上下文和 deeplink 完成 | 若现场要求服务端真实路径解释或公共契约，转 `NEEDS_REPLAN` |
| 外部 HTTP(S) 链接先于目录上下文校验 | 外部链接不属于工作区文件解析 | 保持浏览器原有打开语义 |

## Acceptance Criteria

- [x] AC-1: 示例 href `/home/sa/workspace/foggy-data-mcp/foggy-data-mcp-bridge/docs/9.5.2/prototype/runtime-console-prototype.html` 在对应目录根下解析为精确 `filePath=foggy-data-mcp-bridge/docs/9.5.2/prototype/runtime-console-prototype.html`，且 `searchFiles` 调用次数为 0。
- [x] AC-2: POSIX 绝对路径支持 URL 编码字符和尾随行号；目录根本身可打开 File Browser 根目录。
- [x] AC-3: POSIX 或 Windows 根外绝对路径返回明确 warning，且不调用 basename 搜索。
- [x] AC-4: Windows 绝对路径、相对嵌套路径、纯 basename、同名歧义和 Navigator File Browser deeplink 的既有测试继续通过。
- [x] AC-5: 外部 HTTP(S) 链接即使当前未选择目录也可按外部链接打开，不显示“当前未选择工作目录”。
- [x] AC-6: File Browser 可根据生成的精确 deeplink 展开并选中目标 HTML 文件；同名文件不会改变目标。
- [x] AC-7: 现场 `list`、`content`、`search` 诊断能够区分路径可见性、目录绑定和搜索枚举问题，任何未修复的运行态差异均被记录。
- [x] AC-8: focused tests、`bash scripts/build-frontend.sh`、`git diff --check` 实际运行通过。

## Contract / Data / Security Constraints

- API or event contract: 不新增或修改服务端 API；`search` 继续保持 filename-substring 语义。
- data and migration: 无数据库或文件数据迁移。
- compatibility and rollback: 修复限于链接分类与 deeplink 生成；回滚后仅恢复旧的 POSIX 误分类行为。
- permissions and secrets: `directoryId` 仍是服务端授权边界；客户端传入的 root/path 不得成为服务端新授权依据；Authorization token 仅以内存/进程环境使用，不持久化。

## Test and Evidence Obligations

| Item | Classification | Risk | Required Validation | Reusable Evidence | Required Evidence |
|---|---|---|---|---|---|
| POSIX root内精确定位 | must-pass | major | failure-first focused Vitest | 用户提供的 href/root | zero search call、精确 deeplink assertion |
| 根外 fail closed | must-pass | major | focused Vitest | Windows 既有测试 | warning 与 zero search assertion |
| 兼容路径 | must-pass | major | existing + expanded resolver Vitest | 现有 resolver suite | Windows/relative/basename/deeplink pass |
| 外链无目录依赖 | must-pass | major | focused view/helper test 或等价可审查测试 | existing external URL resolver test | no directory warning/open URL evidence |
| 前端集成 | must-pass | major | full frontend build | workspace baseline | exact command output |
| 运行态路径可见性 | must-pass | major | list/content/search API smoke + browser smoke | 用户现场复现 | sanitized result summary；不得记录 token |

## Validation Budget and Evidence Sufficiency

- assurance_level: standard
- lightweight_validation: focused Vitest、source diff、API smoke、`git diff --check`，单次 `<5m`。
- medium_validation: `bash scripts/build-frontend.sh` 和受影响浏览器 smoke，单次 `5-30m`。
- expensive_validation: none。
- large_authority_or_replay_policy: prohibited-unless-user-approved。
- full_chain_recommendation_trigger: none；若需要新公共 API、安全边界或跨 Worker 公共契约，直接 `NEEDS_REPLAN`。
- estimated_full_chain_wall_clock: not-estimated。
- full_chain_prerequisites: none。
- user_approval_status: not-requested。
- decision_if_not_approved: proceed-with-focused-and-affected-validation。
- expensive_validation_trigger: none。
- maximum_expensive_attempts: 0。
- reusable_evidence: 既有 Windows/相对路径 resolver tests、用户提供的 href 与 API 空结果。
- stop_when_evidence_is_sufficient: AC-1 至 AC-8 均有实际测试、构建或脱敏运行态证据，且无需改变公共契约或安全边界。
- validation_not_required: Maven 全 reactor、Worker 发布链、跨项目修改、authority/replay/rehearsal。

## Waiver Policy

- waivable_items: 仅目标域不可达或认证失效导致的远端浏览器 smoke。
- authorized_role: project owner / independent signoff owner。
- non_waivable_guards: root containment、zero basename fallback、Windows compatibility、完整前端构建、secret non-persistence。
- required_risk_record: 记录未执行的目标域步骤、原因和剩余现场验证项。

## Bug Context

- bug_source: user-report
- severity: major user-facing navigation defect
- environment: `dev-kvm-jdk17-2.foggysource.com`，Linux 工作目录，2026-07-27。
- current_behavior: 完整 POSIX href 未被识别为绝对本地路径，被移除前导 `/` 后按 basename 调用 `/file-browser/search`；搜索为空时提示无法定位，搜索命中多个同名文件时存在歧义。
- expected_behavior: 根内完整 href 直接、唯一地映射为当前 directory 的相对 `filePath`；不依赖 basename 搜索。
- reproduction_steps:
  1. 聊天消息渲染带 POSIX 绝对 href 的 HTML/Markdown 链接。
  2. 点击 `runtime-console-prototype.html`。
  3. 当前实现请求 `search?query=runtime-console-prototype.html` 并收到空结果，随后警告无法自动定位。
- reproduction_status: confirmed by request/response, screenshot and source inspection。
- existing_evidence:
  - 用户提供的 anchor href、空搜索响应和界面 warning。
  - resolver 仅识别 drive-letter Windows 绝对路径；现有测试缺少 POSIX absolute case。
  - File Browser 已支持 `filePath` deeplink 精确展开。
- existing_tests: `chatLinkResolver.test.ts` 已覆盖 Windows、relative、basename、ambiguity 和 external URL，但未覆盖 POSIX absolute path。
- regression_protection: required。
- waiver_reason_and_risk: N/A。

## Risks and Open Questions

- known_risks:
  - 路径比较若错误处理大小写、编码、尾随分隔符或父目录片段，可能产生误判；POSIX containment 必须保持大小写敏感。
  - 现场 search 空结果可能来自部署版本、目录/Worker 绑定、`.foggy-ignore` 或运行文件系统可见性，与前端路径分类缺陷并存。
  - 同级 `foggy-data-mcp` 只读；若目标文件未对目标 Worker 可见，本仓库只能记录 handoff，不能越界修改。
- open_questions: none。

## Ultra Execution Contract

- 先读取本文件、根 `AGENTS.md` 和受影响模块规范。
- 对稳定复现的 POSIX 路径缺陷先增加失败测试，再实施修复。
- 在 scope 内自主决定局部实现；不得把客户端 root/path 提升为服务端授权事实。
- 启动或复用服务前通过命令行、cwd、配置和健康检查确认属于当前工作区；不得操作其他工作区实例。
- 若必须新增服务端 resolver API、改变 search 合同或扩大跨项目范围，设置 `NEEDS_REPLAN` 并停止扩展。
- 完成后填写 `Implementation Result` 并设置 `READY_FOR_SIGNOFF`；不得自行设置 `ACCEPTED`。

## Implementation Result

- implementation_summary:
  - 聊天链接解析器新增 POSIX 绝对路径识别、规范化 containment 和精确 File Browser deeplink；根外路径 fail closed，且不再降级 basename 搜索。
  - 保留 Windows 路径语义，并分别覆盖 POSIX/Windows `file://` URI、编码路径、行号、目录根、大小写和父目录逃逸场景。
  - 外部 HTTP(S) 链接在目录上下文校验前处理，未选择目录时仍可正常打开。
  - Worker 搜索在父仓库 Git 枚举成功后继续发现可见的嵌套 Git worktree，并合并各自 `git ls-files --cached --others --exclude-standard` 结果；项目相对 `.foggy-ignore`、symlink/junction 剪枝、realpath containment 和 `allowed_cwds` 复验保持 fail closed。
  - 独立首轮验收发现的编码 `%23/%3F` 截断、junction 边界和路径型 `.foggy-ignore` 三项 blocker 已修复并经独立复核关闭。
- changed_paths:
  - `packages/navigator-frontend/src/utils/chatLinkResolver.ts`
  - `packages/navigator-frontend/src/views/ClaudeWorkerView.vue`
  - `packages/navigator-frontend/src/__tests__/chatLinkResolver.test.ts`
  - `tools/claude-agent-worker/src/agent_worker/routes/files.py`
  - `tools/claude-agent-worker/tests/routes/test_files.py`
  - `docs/version-tracker/1.4.3-SNAPSHOT/README.md`
  - `docs/version-tracker/1.4.3-SNAPSHOT/workitems/BUG-023-chat-posix-file-link-resolution.md`
  - `docs/version-tracker/1.4.3-SNAPSHOT/evidence/BUG-023-independent-signoff.md`
- tests_and_results:
  - failure-first resolver suite：修复前新增 POSIX cases 为 `6 failed, 10 passed`，证明完整 href 错误进入 basename 搜索；最终 resolver `20 passed`，包含 `%23`、`%3F` 保留字符精确定位。
  - failure-first Worker nested-repository case：修复前目标搜索 `total=0`；最终 route suite `70 passed`，包含 junction-like 剪枝及路径型 `.foggy-ignore`。
  - Worker 全量：`PYTHONPATH=tools/claude-agent-worker/src tools/claude-agent-worker/.venv/bin/python -m pytest -q`，`549 passed, 11 skipped`；显式 `PYTHONPATH` 确保加载当前工作区源码而非用户级安装。
  - 前端全量：`bash scripts/build-frontend.sh` 通过；Navigator `287 passed`、Foggy Chat `115 passed`、Mobile `59 passed`、Widget `31 passed`，相关 type-check/build 均通过。
  - 静态检查：`git diff --check` 通过；仅输出工作区既有 CRLF 转换提示，无 whitespace error。
- manual_or_experience_evidence:
  - 当前仓库 Java `8112` health 为 `UP`；当前仓库 Claude Worker `3031` health 正常，未操作 `/home/sa` 用户级 Worker。
  - 当前 3031 对 `/home/sa/workspace/foggy-data-mcp` 搜索目标 basename 返回唯一结果 `foggy-data-mcp-bridge/docs/9.5.2/prototype/runtime-console-prototype.html`；content 返回目标绝对路径、`size=51117`、`line_count=855`。
  - Playwright 浏览器 smoke 使用精确 deeplink，按 `foggy-data-mcp-bridge/docs/9.5.2/prototype` 五级 listing 展开，只请求一次完整 content path，并成功渲染 `Runtime Console Prototype` HTML 预览。脱敏脚本和截图位于 git ignored 的 `temp/test-artifacts/BUG-023/`。
- deviations: none
- residual_risks:
  - `dev-kvm-jdk17-2.foggysource.com` 的现有目录记录可能绑定另一用户级或旧版本 Worker；本轮按 ownership 约束未重启该实例。完整绝对 href 已不依赖 search，但旧 Worker 上 basename-only 搜索仍需随该 Worker 后续发布/升级获得嵌套仓库修复。
  - 用户要求保留现有 SUPER_ADMIN token 并在手工验收后再处理轮换；本轮未把 token 写入代码、文档、测试或持久证据，目标域带认证的最终点击验收留给用户。
- reused_evidence: 既有 Windows、relative、basename、ambiguity、Navigator deeplink 和 external URL resolver tests 全部继续通过。
- omitted_validation_and_reason: 未运行 Maven 测试，因为没有 Java/API contract 变更；未发布或升级远端/用户级 Worker，因为不属于本轮授权的当前仓库实例。
- readiness: ACCEPTED

## Acceptance Status

- acceptance_status: signed-off
- acceptance_decision: accepted
- signed_off_by: independent-review-agent
- signed_off_at: 2026-07-27
- acceptance_record: `docs/version-tracker/1.4.3-SNAPSHOT/evidence/BUG-023-independent-signoff.md`
- blocking_items: none
- follow_up_required: yes；继续执行选择性提交、前端部署、Worker 升级和目标环境真实点击验收。

## References

- affected resolver: `packages/navigator-frontend/src/utils/chatLinkResolver.ts`
- affected host view: `packages/navigator-frontend/src/views/ClaudeWorkerView.vue`
- file browser deeplink consumer: `packages/navigator-frontend/src/views/FileBrowserView.vue`
