---
type: optimization
version: 1.3.0-SNAPSHOT
ticket: OPT-032
severity: medium
status: open
owner: biz-worker-runtime + navigator-business-agent + upstream-integration
source: BUG-028 follow-up
---

# OPT-032: Attachment Preprocessing Governance Follow-up

## Document Purpose

- doc_type: workitem
- intended_for: execution-agent | reviewer | signoff-owner
- purpose: Split non-blocking attachment preprocessing and observability governance out of BUG-028 so BUG-028 remains closed on the verified ticket attachment delivery path.

## Background

BUG-028 confirmed and fixed the concrete regression: a TMS user could send a ticket request with an image attachment, and the final real TMS BFF + Navigator + Worker + LLM + TMS ticket creation path now preserves the attachment URL in `attachmentRefs`.

During closure, several broader governance items were identified. They are useful hardening work, but they are not required for BUG-028 because the accepted closure path is `tms-ticket-agent` directly consuming attachment URL/ref metadata to create the ticket.

Related item:

- `REQ-030-biz-worker-on-demand-attachment-analysis-and-vision-model-config.md` covers explicit on-demand image analysis through `analyze_attachment`.

## Problem Statement

Attachment handling now works for the direct ticket path, but the platform still needs clearer governance for cases where attachments should be observed, merged, or preprocessed before a downstream business skill runs.

Open questions:

1. When should root route to an attachment preprocessing skill before a domain skill?
2. How should per-hop attachment visibility be observed without leaking signed URLs or secrets?
3. Should legacy or upstream-specific `metadata.attachments` be merged into top-level `attachments`, or should callers be required to send only the normalized top-level field?
4. How should derived attachment analysis results be linked back to the original attachment ref in later business function calls?

## Target Outcome

- Direct attachment handoff remains the default for "create/submit with attachment" workflows.
- Explicit preprocessing is used only when user intent, skill policy, or required business fields need attachment content analysis.
- TMS BFF, Navigator OpenAPI, Java relay, Python Worker root, child skill, and business function dispatch each have a safe way to observe attachment presence and counts.
- Any merge rule for `metadata.attachments` is documented, tested, and does not create duplicate or conflicting refs.

## Scope

In scope:

- Define routing rules for direct attachment handoff vs `tms-attachment-agent` / `analyze_attachment` preprocessing.
- Add sanitized per-hop evidence points: attachment count, ids, names, provider/ref type, and redacted URL path or digest.
- Decide and implement the `metadata.attachments` compatibility rule if still needed by upstream callers.
- Ensure derived analysis output keeps a stable link to the original attachment id/ref.
- Add targeted tests for direct handoff, explicit preprocessing, and metadata merge behavior.

Out of scope:

- Reopening BUG-028.
- Automatically analyzing every image attachment.
- Storing raw signed URLs, tokens, or image bytes in normal task messages or docs.
- Replacing the existing `attachmentRefs` business function contract.

## Acceptance Criteria

- A policy doc or skill instruction states when attachment preprocessing is required and when direct handoff is correct.
- Direct ticket creation with an attachment still avoids image analysis and preserves `attachmentRefs`.
- A separate "analyze image then create ticket" path uses explicit analysis output and preserves original attachment refs.
- Per-hop evidence can prove attachment propagation without exposing secrets.
- If `metadata.attachments` is supported, duplicate refs are deduped and top-level `attachments` remains canonical.

## Progress Tracking

Development progress:

- [ ] Decide whether `metadata.attachments` compatibility is required for current upstream callers.
- [ ] Define per-hop sanitized attachment evidence fields.
- [ ] Implement merge/preprocessing routing only after policy is confirmed.

Testing progress:

- [ ] Add unit tests for merge/dedupe rules if `metadata.attachments` support is retained.
- [ ] Add scripted Worker E2E for explicit preprocessing before ticket creation.
- [ ] Keep existing BUG-028 real LLM ticket E2E as the direct-handoff regression.

Experience progress:

- N/A. This item is runtime/API governance only; no UI interaction is introduced in this work item.

## References

- `BUG-028-tms-ticket-agent-attachment-not-delivered.md`
- `REQ-030-biz-worker-on-demand-attachment-analysis-and-vision-model-config.md`
