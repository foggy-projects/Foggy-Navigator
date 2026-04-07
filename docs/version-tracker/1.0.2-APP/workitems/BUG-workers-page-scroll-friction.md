---
type: bug
bug_source: user-report
version: 1.0.2-APP
ticket: BUG-workers-page-scroll-friction
severity: major
status: fixed
reproduction_status: partial
test_strategy: manual-evidence-only
automation_decision: optional
fix_version: 1.0.29
owner: foggy-mobile
---

# BUG Work Item

## Background

User reported that vertical scrolling on the APP `Workers` page is very difficult and the page feels hard to operate.

This issue was raised after the `1.0.2-APP` upgrade verification cycle, so it should be tracked as a separate APP usability defect instead of being buried inside the acceptance report.

## Reproduction

Current source is `user-report`. Stable local device reproduction is not yet confirmed in automation.

Suggested reproduction path:

1. Open the APP and enter the `Workers` tab.
2. Expand one or more workers so the page becomes vertically scrollable.
3. Try to swipe up and down over worker cards, project headers, and directory rows.
4. Observe whether the page follows the finger smoothly or requires repeated / forceful swipes.

## Expected vs Actual

Expected:

- The `Workers` page should scroll smoothly with normal vertical swipe gestures.
- Tap targets should remain clickable, but should not noticeably interfere with vertical scrolling.

Actual:

- According to the user report, vertical scrolling is difficult and the page feels sticky / hard to drag.

## Impact Scope

- APP `Workers` tab main list.
- Expanded worker directory tree area.
- Potentially all rows with dense `@tap` / `@longpress` bindings inside the page scroll container.

This is not a data-loss bug, but it directly affects a primary navigation page and lowers basic usability.

## Current Assessment

The page structure suggests a likely gesture-conflict risk:

- [index.vue](D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/packages/foggy-mobile/src/pages/worker/index.vue) uses a page-level `scroll-view`.
- The same page contains many interactive child regions with `@tap`.
- Worker cards also expose `@longpress` through [WorkerCard.vue](D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/packages/foggy-mobile/src/components/WorkerCard.vue).

This combination is a plausible cause on `App-Plus`, especially when most visible surface area is made of clickable rows.

## Test Strategy

Primary strategy for this defect is `manual-evidence-only` for the first pass:

- Gesture smoothness on `App-Plus` is not reliably captured by the current mobile automated stack.
- A manual screen recording on a real device is the fastest way to confirm severity and affected gesture zones.

Automation decision is `optional`:

- If the root cause is narrowed to layout / scroll-container configuration, a lightweight regression test may be added later.
- If the issue is strictly gesture-feel on `App-Plus`, manual verification may remain the main validation method.

## Code Inventory

- [index.vue](D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/packages/foggy-mobile/src/pages/worker/index.vue)
  Worker page `scroll-view`, worker expand/collapse area, directory row tap handlers.
- [WorkerCard.vue](D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/packages/foggy-mobile/src/components/WorkerCard.vue)
  Full-card tap target; parent page also binds long-press behavior.
- [pages.json](D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/packages/foggy-mobile/src/pages.json)
  Tab page registration for `pages/worker/index`.
- [index.vue](D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/packages/foggy-mobile/src/pages/chat/index.vue)
  Useful comparison page with a simpler vertical list that is less interaction-dense.

## Fix Checklist

- Reproduce on a real APP build and capture a short screen recording.
- Confirm whether the problem happens only after expanding workers, or also on the collapsed list.
- Audit whether `@longpress` on worker cards is competing with normal vertical drag.
- Audit whether directory row click regions are too dense and consume gesture start area.
- Review `scroll-view` sizing / padding / flex behavior on `App-Plus`.
- If needed, reduce gesture competition by moving health check from long-press to an explicit action entry.
- Re-run manual verification on the APP after the fix.

## Verification

Manual verification should cover:

1. Collapsed worker list scrolling.
2. Expanded project tree scrolling.
3. Scroll start from worker card body, project header, and directory rows.
4. Confirm taps still work after any gesture-related fix.

## Fix Applied (v1.0.29)

**Root cause**: `@longpress` on WorkerCard competes with `scroll-view` vertical scrolling. On App-Plus, the system delays scroll initiation ~350ms to determine tap vs longpress, causing the page to feel "sticky".

**Changes**:
- `WorkerCard.vue`: Added explicit "检测" button with `@tap.stop` for health check; emits `health-check` event
- `index.vue`: Replaced `@longpress="doHealthCheck()"` with `@health-check="doHealthCheck()"`, eliminating gesture competition

**Published**: wgt v1.0.29 → uni-admin 升级中心 (2026-04-07)

## References

- Source: user report on `2026-04-07`
- Acceptance context: [04-upgrade-assessment-report.md](D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/docs/version-tracker/1.0.2-APP/04-upgrade-assessment-report.md)
