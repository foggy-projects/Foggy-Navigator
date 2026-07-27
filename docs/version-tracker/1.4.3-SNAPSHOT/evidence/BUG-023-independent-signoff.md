---
acceptance_scope: bug
version: 1.4.3-SNAPSHOT
target: BUG-023
status: signed-off
decision: accepted
signed_off_by: independent-review-agent
signed_off_at: 2026-07-27
reviewed_by: project-root-session
blocking_items: []
follow_up_required: yes
evidence_count: 8
assurance_level: standard
---

# BUG-023 Independent Delivery Signoff

## Background

- delivery_spec: `../workitems/BUG-023-chat-posix-file-link-resolution.md`
- target_outcome: POSIX/Windows 根内绝对文件链接精确生成 File Browser deeplink，不降级为 basename 搜索；嵌套 Git 搜索保持路径与权限边界。
- signoff_scope: 当前 BUG-023 source diff、回归测试和构建证据；不声明目标环境已经部署。
- non_blocking_follow_up: Navigator 前端部署、Claude Worker 升级和 `dev-kvm-jdk17-2` 真实聊天点击验收。

## Acceptance Basis

- changed paths 与 canonical spec 一致，未依赖或夹带工作区其他改动。
- 独立首轮审查发现并阻断了 `%23/%3F` 截断、junction/allowed-cwd 边界和路径型 `.foggy-ignore` 三项问题。
- 修复后由独立 reviewer 复核，三项 blocker 均关闭，无新增阻断项。
- 新验证：Navigator `27 files / 287 tests`，resolver `20 tests`；Worker route `70 passed`；Worker full `549 passed, 11 skipped`；完整前端 type-check/test/build 通过；`git diff --check` 无 whitespace error。

## Contract Conformance

| Item | Delivered / Evidence | Result |
|---|---|---|
| AC-1 | 用户示例 POSIX href 精确生成相对 `filePath`，`searchFiles` 为 0 次 | pass |
| AC-2 | 编码中文、`%23`、`%3F`、行号和工作区根路径均有 resolver 回归 | pass |
| AC-3 | POSIX/Windows 根外路径 fail closed，不执行 basename fallback | pass |
| AC-4 | Windows、relative、basename、ambiguity、deeplink 兼容测试通过 | pass |
| AC-5 | 无目录上下文时外部 HTTP(S) 链接仍可打开 | pass |
| AC-6 | 可重复 Playwright deeplink smoke 可展开并预览目标 HTML | pass |
| AC-7 | 已区分前端误分类与嵌套 Git 枚举；junction、realpath、allowed-cwd 和 `.foggy-ignore` 边界已有实现及测试 | pass |
| AC-8 | focused/full tests、完整前端构建和 diff check 实际通过 | pass |

## Implementation Quality

- scope and changed surface: 聚焦 resolver、宿主视图、Worker 文件枚举及对应测试/文档。
- maintainability: POSIX/Windows 规范化分离；Worker 统一通过项目相对排除匹配和 walk 剪枝复用安全逻辑。
- edge cases: 编码保留字符、父目录逃逸、大小写、symlink/junction、根外 realpath 和嵌套路径型 ignore 已覆盖。
- contract and security: 未新增 API；`directoryId` 和 Worker `allowed_cwds` 继续作为授权边界；未持久化认证凭据。

## Evidence Sufficiency

- assurance_level: standard
- sufficiency: 核心用户结果、兼容路径和不可豁免路径安全边界均有当前源码后的自动化证据；无需启动大型 full-chain。
- expensive_validation_omitted: 无公共 API、迁移或不可逆操作，不需要超过 30 分钟的 authority/replay 链。
- deployment note: 代码签收不等同于目标环境部署完成；部署和 live 点击作为有界 follow-up 顺序执行。

## Waivers

- none

## Risks / Follow-ups

- 部署前 `dev-kvm-jdk17-2` 仍可能运行旧前端或旧 Worker。
- 目标环境当前搜索接口曾返回 HTTP 503；部署后需核对服务健康、目录绑定、Worker 版本及真实点击网络请求。

## Final Decision

- decision: accepted
- rationale: 所有核心 acceptance criteria 和 non-waivable 路径安全 guard 已有充分证据，独立复核无存活 blocker。
- blocking_items: none
- follow_up_owner_and_due: project-root-session；继续执行选择性提交、部署、Worker 升级和 live 验收。

## Signoff Marker

- acceptance_status: signed-off
- acceptance_decision: accepted
- signed_off_by: independent-review-agent
- signed_off_at: 2026-07-27
- acceptance_record: `docs/version-tracker/1.4.3-SNAPSHOT/evidence/BUG-023-independent-signoff.md`
- blocking_items: none
- follow_up_required: yes
