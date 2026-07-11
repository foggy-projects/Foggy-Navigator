---
type: bug
bug_source: acceptance-found
version: 1.4.0-SNAPSHOT
ticket: BUG-003
severity: major
status: closed-isolated
reproduction_status: confirmed
test_strategy: unit-test
automation_decision: required
owner: navigator-frontend
---

# Worker View Mobile Layout

## Background

The final Codex dual-runtime acceptance exposed a narrow-viewport regression in
`ClaudeWorkerView`. At 320px, the left Worker sidebar and right history panel stay in
the flex layout and reduce the main task area to roughly 10px.

## Reproduction

1. Open the Navigator PC frontend at a 320x900 viewport.
2. Select a Worker and a history conversation with an open task pane.
3. Collapse the history panel.
4. Observe the remaining Worker sidebar and main task pane.

Reproduction screenshot:
`C:\Users\oldse\AppData\Local\Temp\foggy-codex-fullchain-20260710-180706\evidence\pc-final-native-320-detail.png`

## Expected vs Actual

- Expected: at widths up to 720px, the main task area owns the viewport width. Worker
  navigation and history remain reachable as dismissible overlays.
- Actual: the side panels consume layout width, headings wrap one character per line,
  and the native-subtask strip is compressed to roughly 10px.

## Impact Scope

- Worker and directory navigation on narrow screens.
- Conversation history selection.
- Task panes, Codex native-subtask progress, and continuation controls.
- Touch accessibility of panel open/close controls.

Desktop behavior above 720px is not affected and must remain unchanged.

## Test Strategy

- Add component regression tests for narrow-viewport panel selection, automatic panel
  dismissal, mutually exclusive overlays, backdrop dismissal, and desktop behavior.
- Run the focused Vitest suite and `vue-tsc`.
- Re-run Playwright at 320x900 and desktop widths, checking overflow, control reachability,
  and the native-subtask strip.

## Code Inventory

- `packages/navigator-frontend/src/views/ClaudeWorkerView.vue`
- `packages/navigator-frontend/src/views/__tests__/ClaudeWorkerView.integration.test.ts`

## Fix Checklist

- [x] Give the main area full width at `max-width: 720px`.
- [x] Render Worker and history panels as dismissible overlays on narrow screens.
- [x] Keep both navigation panels reopenable from the main area.
- [x] Automatically dismiss a narrow-screen panel after selecting its content.
- [x] Provide touch-sized panel controls and prevent horizontal overflow.
- [x] Preserve the desktop three-column state behavior.
- [x] Add and pass component regression tests.
- [x] Capture post-fix 320px evidence.

## Verification

- Regression test before implementation: `1 failed, 17 passed`; the narrow-screen
  panel remained open after Worker selection.
- Focused component verification after implementation: `3` files and `23/23` tests
  passed (`ClaudeWorkerView`, `TaskPane`, and `NativeSubtaskBar`).
- Full `navigator-frontend` Vitest verification: `179/179` tests passed.
- Static verification: `pnpm run build:check` (`vue-tsc` + production Vite build) passed.
- Playwright at 320x900: the main area measured 320px wide, document `scrollWidth`
  equaled `clientWidth` at 320px, both navigation overlays remained reachable, and
  all measured panel/header/task controls were at least 44px high.
- The native-subtask strip measured 302px wide inside the viewport and rendered one
  completed row with `1/1` progress; browser console errors and failed responses were
  both zero.
- Post-fix screenshot:
  `C:\Users\oldse\AppData\Local\Temp\foggy-codex-fullchain-20260710-180706\evidence\pc-final-native-320-fixed.png`.
- Final fixed-task Playwright rerun passed on desktop and 320x900. The 320px main area,
  document and body all measured exactly 320px with no horizontal overflow; left/right
  overlays measured 276px, all open/close controls measured 44x44, and no browser or UI
  errors were recorded. Final evidence includes `pc-final-result-desktop.png`,
  `pc-responsive-320-overlay.png`, `pc-responsive-320-closed.png` and
  `pc-final-acceptance.json` in the isolated acceptance evidence directory.
- Desktop panel persistence remains covered by the component regression test. The final
  Ultra task also displayed native-subtask progress `1/1`; this defect is closed for the
  isolated P0-P2 acceptance. Production rollout remains separately gated by P3.

The separately recorded app-server delta-message fragmentation and final-result defects
are outside BUG-003 and do not change the static layout verification result, but all three
share the final fixed-task browser rerun gate.

## References

- Final Codex dual-runtime acceptance, 2026-07-10.
- Reproduction evidence listed above.
- [OPT-001 progress](./OPT-001-independent-codex-app-server-worker-progress.md#acceptance-defect-closure)
- [BUG-001 delta fragmentation](./BUG-001-app-server-delta-message-fragmentation.md)
- [BUG-007 final result aggregation](./BUG-007-app-server-final-result-aggregation.md)
