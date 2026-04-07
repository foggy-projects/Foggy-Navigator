---
acceptance_scope: feature
version: 1.0.0-SNAPSHOT
target: 07-chat-doc-link-open-file-browser-deeplink
status: signed-off
decision: accepted
signed_off_by: engineering-signoff
signed_off_at: 2026-04-07
reviewed_by: N/A
blocking_items: []
follow_up_required: no
evidence_count: 7
---

# Feature Acceptance

## Background

- Version: `1.0.0-SNAPSHOT`
- Target: `07-chat-doc-link-open-file-browser-deeplink`
- Owner: `navigator-frontend` + `@foggy/chat`
- Goal: 任务聊天区点击工作区文档链接后，能够按当前 `workerId` / `directoryId` 打开文件浏览器 deeplink，并自动展开目录树、打开目标文件、保持选中态。

## Acceptance Basis

- [07-chat-doc-link-open-file-browser-deeplink.md](../07-chat-doc-link-open-file-browser-deeplink.md)
- [08-playwright-experience-report.md](../08-playwright-experience-report.md)
- [11-playwright-revalidation-report.md](../11-playwright-revalidation-report.md)
- [chatLinkResolver.test.ts](../../../../packages/navigator-frontend/src/__tests__/chatLinkResolver.test.ts)

## Checklist

- [x] 聊天消息中的普通 `<a>` 点击已由共享聊天组件拦截并向宿主抛出 `link-click`
- [x] 宿主页会基于当前任务目录上下文把工作区内路径解析成 `/#/files?...&filePath=...` deeplink
- [x] 文件浏览器首次加载携带 `filePath` 时会自动展开目录、打开文件并保持选中态
- [x] 同一文件浏览器标签内 `filePath` 变化时可继续切换到新目标文件
- [x] 工作区外绝对路径与歧义文件名会给出明确 warning，不会静默失败
- [x] 关键自动化和真实浏览器验证证据齐全，可支撑签收

## Evidence

- Requirement:
  - [07-chat-doc-link-open-file-browser-deeplink.md](../07-chat-doc-link-open-file-browser-deeplink.md)
- Implementation:
  - [MessageBubble.vue](../../../../packages/foggy-chat/src/components/MessageBubble.vue)
  - [MessageList.vue](../../../../packages/foggy-chat/src/components/MessageList.vue)
  - [ClaudeWorkerView.vue](../../../../packages/navigator-frontend/src/views/ClaudeWorkerView.vue)
  - [chatLinkResolver.ts](../../../../packages/navigator-frontend/src/utils/chatLinkResolver.ts)
  - [FileBrowserView.vue](../../../../packages/navigator-frontend/src/views/FileBrowserView.vue)
- Test:
  - `pnpm --filter @foggy/navigator-frontend test -- src/__tests__/chatLinkResolver.test.ts`
  - 结果：`7 passed`
  - [chatLinkResolver.test.ts](../../../../packages/navigator-frontend/src/__tests__/chatLinkResolver.test.ts)
- Experience:
  - [08-playwright-experience-report.md](../08-playwright-experience-report.md)
  - [11-playwright-revalidation-report.md](../11-playwright-revalidation-report.md)
- Artifact:
  - [vt100-filebrowser-deeplink-afterwait.yml](../evidence/vt100-filebrowser-deeplink-afterwait.yml)
  - [vt100-11-filebrowser-same-tab-reretest-after-restartfix.yml](../evidence/vt100-11-filebrowser-same-tab-reretest-after-restartfix.yml)

## Failed Items

- none

## Risks / Open Items

- `packages/navigator-frontend/src/views/__tests__/ClaudeWorkerView.integration.test.ts` 当前运行失败，但失败原因是测试自身未完整 mock Element Plus 组件与 `/coding-agents` 请求，未直接指向 `07` 功能回退；应作为独立测试债处理，不阻断本次 feature 签收。

## Final Decision

本项判定为 `accepted`。

理由：

1. `@foggy/chat` 到 `ClaudeWorkerView` 的链接点击事件链已落地，满足“共享包抛事件、宿主层解析”的边界要求。
2. `resolveChatLinkTarget` 已覆盖 basename、相对路径、工作区内绝对路径、同源文件浏览器 deeplink、歧义文件名和外链兜底等核心分支。
3. `FileBrowserView` 既支持首次挂载 deeplink 导航，也支持同标签内 `filePath` 变化后的二次导航，满足 `07` 的验收标准第 1-5 项。
4. Playwright 体验报告和复测报告都提供了真实浏览器证据，且未留下阻断交付的问题。

## Signoff Marker

- acceptance_status: signed-off
- acceptance_decision: accepted
- signed_off_by: engineering-signoff
- signed_off_at: 2026-04-07
- acceptance_record: docs/version-tracker/1.0.0-SNAPSHOT/acceptance/07-chat-doc-link-open-file-browser-deeplink-acceptance.md
- blocking_items: none
- follow_up_required: no
