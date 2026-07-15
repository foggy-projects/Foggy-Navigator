---
acceptance_scope: version
version: 1.4.2-SNAPSHOT
target: 1.4.2-SNAPSHOT
doc_role: acceptance-record
doc_purpose: 记录 1.4.2-SNAPSHOT 当前版本级正式验收结论、阻断项和重验入口
status: rejected
decision: rejected
signed_off_by: root-controller
signed_off_at: 2026-07-14
reviewed_by: project-owner-pending
blocking_items:
  - external-runtime-boundary-incomplete
  - task-ownership-live-matrix-incomplete
  - p4-and-p6-scope-incomplete
  - coverage-audit-needs-more-tests
follow_up_required: yes
evidence_count: 20
---

# Version Acceptance

## Document Purpose

- doc_type: acceptance
- intended_for: signoff-owner / reviewer / project-root-session
- purpose: 对 `1.4.2-SNAPSHOT` 整个版本执行正式验收判断，明确当前不能签收的原因，并为 Owner 评审和下一轮重验提供权威入口。

## Background

- Version: `1.4.2-SNAPSHOT`
- Scope: REQ-001、10 个计划工作项、2 个已关闭 BUG、P0-P7 和 13 项验收标准。
- Goal: 平台治理与历史能力收口，包括内外信任边界、Session/Task ownership、Biz Worker/upstream user 约束、可复现构建、历史切片清理、可维护性治理和正式门禁。
- Current state: 已交付构建基线、P2/P3 首批、P5 多个 dev-only 切片、P6 旧 Provider 契约子切片和隔离 Session live 验证；P2/P3/P4/P6 仍有明确未完成范围。
- Signoff context: 本次材料足以判断关键验收标准未满足，因此结论是 `rejected`，而不是因材料不可判断而 `blocked`。该结论不回滚已完成切片，也不允许启用 external 或改变生产路由。

## Acceptance Basis

- [Root Requirement](../requirements/REQ-001-platform-governance-and-legacy-cleanup.md)
- [Implementation Plan](../implementation-plan.md)
- [Module Responsibility](../module-responsibility.md)
- [Code Inventory](../code-inventory.md)
- [Progress and Evidence Register](../progress.md)
- [Executed Slices Implementation Quality Gate](../quality/executed-governance-slices-implementation-quality.md)
- [Version Test Coverage Audit](../coverage/1.4.2-coverage-audit.md)
- [Owner Decision Review](../owner-decision-review.md)

## Module Summary

| Module | Owner | Status | Acceptance Record | Notes |
|---|---|---|---|---|
| Build / CI baseline | root build / frontend / Worker owners | partial-passed | 本版本级记录 | 本机 clean 与 hosted Repository CI 7/7 已通过；main required checks/branch protection 未配置，修复后 nightly 未实跑 |
| External trust boundary / Business Agent / Worker Gateway | platform / Business Agent / Worker owners | rejected-incomplete | 本版本级记录 | 默认关闭和 credential/token/principal 首批已落地；真实 external、执行上限、Codex 安全转发、reliable audit 未完成 |
| Session / Task ownership | session / task owners | rejected-incomplete | 本版本级记录 | 统一门面和隔离 Session live 已通过；Task Provider、共享 DB、admin/system、全列表 tenant 未完成 |
| Monitoring / metadata-query / code-review / Echo | root / cleanup owners | partial-passed | 本版本级记录 | 仓内代码切片已退出并有 clean/hosted 证据；专项体验/脚本与正式模块签收未齐 |
| Legacy Provider API / SPI / DTO | Provider / SDK / client owners | partial-passed | 本版本级记录 | 仓内迁移和物理删除已完成；真实 Provider Task live 未覆盖 |
| P4 low-risk cleanup | root / owning modules | not-started | N/A | 候选尚未逐项扫描、删除和验证 |
| P6 state schema / large-class governance | owning modules | not-started | N/A | Provider state typed schema 和渐进拆分未实施 |
| Documentation alignment | documentation owner | in-progress | 本版本级记录 | 当前产品主线已对齐；失效 Skills/早期文档分级尚待 Owner 复核 |
| BUG-001 / BUG-002 | owning modules | closed | 本版本级记录 | 两个缺陷均有定向回归；BUG-001 所在 launcher 链有 hosted 复验，BUG-002 只有本机 Open SDK 定向与当时根 clean 证据，Repository CI Java lane 不含 Open SDK |

## Checklist

- [ ] AC-01 内部 UI 和可信内网主工作流无大面积回归：仅 mock 全量和隔离 Session live，真实 Task/Worker/文件/终端主链不完整。
- [ ] AC-02 外部请求可追溯到 tenant、ClientApp、upstream user、task：服务端约束部分完成，缺真实双主体和可查询审计链。
- [ ] AC-03 外部审批、恢复、取消不能只凭 taskId：LangGraph 审批已迁移；恢复、取消和真实 Provider Task 矩阵未完成。
- [ ] AC-04 外部身份不得取自伪造请求字段：部分入口与 reviewer 已收紧，外部入口整体和运行态矩阵未闭合。
- [ ] AC-05 task-scoped token 不得跨任务/函数：unit/JPA/contract 较强，缺 pause/generation、真实跨 task/函数网络负向验证。
- [ ] AC-06 非 loopback external Worker 缺凭据 fail closed/unready：契约测试通过，真实 non-loopback 部署与组合开关未验证。
- [x] AC-07 Java clean 环境构建测试：本机 clean reactor 与两个 hosted heads 通过。
- [x] AC-08 纳入前端包 type/test/build：本机矩阵和 hosted Frontend job 通过；体验完整度另由 AC-01 约束。
- [x] AC-09 Node/pnpm/lockfile 可复现：精确版本、frozen lockfile 和 hosted runner 已验证。
- [ ] AC-10 所有删除项有扫描、替代和回滚：P5/P6 已实施切片有证据，P4 候选仍未执行。
- [ ] AC-11 获批 dev-only 能力完整退出：仓内代码面基本闭合，专项启动/页面/脚本和模块签收证据仍有缺口。
- [ ] AC-12 当前文档产品主线对齐：主文档已对齐，失效 Skills/历史文档分级尚未完成。
- [x] AC-13 隔离验收不等于生产批准：H2/loopback、not-run、external disabled 和生产边界均已显式记录。
- [x] 已执行正式 implementation quality gate，结论 `ready-with-risks` 仅适用于已执行切片。
- [ ] 覆盖审计允许进入正向验收：审计结论为 `needs-more-tests`，不允许。
- [x] requirement、plan、workitem、progress 和 evidence 已回写并可相对链接追踪。
- [ ] 所有版本级阻断项清零：未清零。

## Evidence

本次 `evidence_count: 20` 由 2 份正式门禁记录和 Progress Evidence Register 中 18 条执行证据组成。状态为 `partial` 或 `not-run` 的记录仍是有效边界证据，但不计为通过项。

| Evidence | 内容 | 结论/用途 |
|---|---|---|
| E-01 | [Implementation Quality Gate](../quality/executed-governance-slices-implementation-quality.md) | 已执行切片 `ready-with-risks`，允许进入覆盖审计 |
| E-02 | [Test Coverage Audit](../coverage/1.4.2-coverage-audit.md) | 版本 `needs-more-tests`，不能进入正向签收 |
| E-03 | [EXEC-142-001](../progress.md#evidence-register) | Owner 的 dev/internal、external 显式开关和物理清理授权边界 |
| E-04 | [EXEC-142-002](../progress.md#evidence-register) | Monitoring 仓内切片退出，体验/外部资源边界保留 |
| E-05 | [EXEC-142-003](../progress.md#evidence-register) | Node/pnpm/lockfile/frontend/repository CI 本机基线 |
| E-06 | [EXEC-142-004](../progress.md#evidence-register) | code-review-agent 仓内物理退出 |
| E-07 | [EXEC-142-005](../progress.md#evidence-register) | 文档、配置、相对链接和工作树检查 |
| E-08 | [EXEC-142-006](../progress.md#evidence-register) | 五类 Worker clean 矩阵与 BUG-001 回归 |
| E-09 | [EXEC-142-007](../progress.md#evidence-register) | metadata-query 删除后 clean 与边界证据 |
| E-10 | [EXEC-142-008](../progress.md#evidence-register) | external default-off / unready 首批门禁 |
| E-11 | [EXEC-142-009](../progress.md#evidence-register) | 根 Java clean 与 BUG-002 回归 |
| E-12 | [EXEC-142-010](../progress.md#evidence-register) | P1/P2 文档一致性和轻量自检 |
| E-13 | [EXEC-142-011](../progress.md#evidence-register) | task capability v2 与 migration 证据 |
| E-14 | [EXEC-142-012](../progress.md#evidence-register) | Worker credential/terminal/route 与 MySQL migration |
| E-15 | [EXEC-142-013](../progress.md#evidence-register) | Gateway strict principal/lease 与 Worker secret 边界 |
| E-16 | [EXEC-142-014](../progress.md#evidence-register) | Session/Task ownership 首批与 launcher clean |
| E-17 | [EXEC-142-015](../progress.md#evidence-register) | Echo 默认制品退出与 test-only fixture |
| E-18 | [EXEC-142-016](../progress.md#evidence-register) | 旧 Provider HTTP/SPI/DTO 迁移和物理收口 |
| E-19 | [EXEC-142-017](../progress.md#evidence-register) | 两次 hosted repository CI 7/7 jobs success |
| E-20 | [EXEC-142-018](../progress.md#evidence-register) | 隔离 H2 Session ownership live 与明确 not-run 边界 |

## Blocking Items

| Blocker | 关联验收项 | 关闭条件 |
|---|---|---|
| external-runtime-boundary-incomplete | AC-02 至 AC-06 | 完成 ClientApp/upstream/task 主体链、Codex credential 安全转发、执行策略上限、pause/generation、reliable audit，并通过真实 external/non-loopback 负向矩阵 |
| task-ownership-live-matrix-incomplete | AC-01、AC-03、AC-04 | 建立安全隔离 Provider Task fixture，覆盖 Task ownership、审批/恢复/取消、admin/system、全列表 tenant；共享 DB 仅在明确授权目标上验证 |
| p4-and-p6-scope-incomplete | AC-10、AC-12 及版本目标 6/7 | 完成 P4 逐项清理证据、Provider state typed schema/version 策略和至少一批受控超大类拆分，或由 Owner 正式调整版本 scope |
| coverage-audit-needs-more-tests | 全部 critical AC | 按 coverage gaps 补实现/测试/体验后，将覆盖结论提升为 `ready-for-acceptance` 或可解释的 `ready-with-gaps` |

## Risks / Open Items

- main required checks/branch protection 尚未配置，修复后的 nightly 尚未实跑，均待 repository owner 实施；hosted 通过不能替代这些治理配置。
- Monitoring/metadata-query/Echo 的未知共享资源未被检查或删除，这是安全边界，不是“已经清理”的证据；如后续发现共享/生产资源必须重新授权。
- signed upstream assertion 已按 Owner 决策降为后续优先级，不是本次拒绝原因；ClientApp delegated mapping/grant、external 显式开关和审计标记仍是当前硬门。
- 根 `clean verify`、真实 Provider Task、真实 non-loopback、共享数据库和完整内部体验均未被虚构为已运行。
- 本轮未发现需要新建 BUG workitem 的具体稳定缺陷；拒绝原因是已知 scope/证据未完成，而不是未登记的软件缺陷。

## Final Decision

`1.4.2-SNAPSHOT` 当前正式验收结论为 **rejected**。

理由不是已完成切片质量不合格，而是多个 critical acceptance item 只有局部契约/隔离证据，P2/P3 关键边界、P4 和 P6 版本范围仍未完成，覆盖审计明确为 `needs-more-tests`。已完成提交继续作为下一轮基线；修复/补齐 blocker 后重新执行 implementation quality gate、coverage audit 和 version acceptance。

该结论不改变生产路由，不启用 external，不等同于生产拒绝或回滚决定。

## Signoff Marker

- acceptance_status: rejected
- acceptance_decision: rejected
- signed_off_by: root-controller
- signed_off_at: 2026-07-14
- acceptance_record: ./version-signoff.md
- blocking_items: external-runtime-boundary-incomplete, task-ownership-live-matrix-incomplete, p4-and-p6-scope-incomplete, coverage-audit-needs-more-tests
- follow_up_required: yes
