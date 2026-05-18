---
type: optimization
version: 1.3.0-SNAPSHOT
ticket: OPT-032
severity: medium
status: in_progress
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

## Terminology

- Canonical attachments: the top-level OpenAPI `attachments` field sent by an upstream caller.
- Compatibility metadata attachments: legacy `metadata.attachments` values accepted at the OpenAPI boundary for older callers.
- Direct handoff: attachment refs are forwarded to the business skill/function without image/content analysis.
- Explicit preprocessing/analysis: attachment content is inspected only when the user request, skill policy, or required business fields need derived content.
- Per-hop evidence: sanitized propagation evidence such as counts, ids, names, media type, provider/ref type, and redacted URL path or digest.

## Problem Statement

Attachment handling now works for the direct ticket path, but the platform still needs clearer governance for cases where attachments should be observed, merged, or preprocessed before a downstream business skill runs.

Governance questions:

1. When should root route to an attachment preprocessing skill before a domain skill?
2. How should per-hop attachment visibility be observed without leaking signed URLs or secrets?
3. How should compatibility `metadata.attachments` be normalized with canonical top-level `attachments`?
4. How should derived attachment analysis results be linked back to the original attachment ref in later business function calls?

## Target Outcome

- Direct attachment handoff remains the default for "create/submit with attachment" workflows.
- Explicit preprocessing is used only when user intent, skill policy, or required business fields need attachment content analysis.
- TMS BFF, Navigator OpenAPI, Java relay, Python Worker root, child skill, and business function dispatch each have a safe way to observe attachment presence and counts.
- Compatibility `metadata.attachments` is accepted at the OpenAPI boundary, normalized into one canonical attachment list for Worker dispatch, and does not create duplicate or conflicting refs.

## Phased Plan

Phase 1: policy boundary.

- Direct handoff is the default for "create/submit/update with attachment" workflows.
- Automatic image analysis is not introduced.
- Explicit preprocessing uses the REQ-030 `analyze_attachment` capability only when the user asks for content analysis or a skill requires derived fields from the attachment content.

Phase 2: OpenAPI compatibility contract.

- Top-level OpenAPI `attachments` is canonical.
- Legacy `metadata.attachments` is accepted for compatibility.
- At dispatch time, Navigator emits a single normalized attachment list in message metadata.
- Duplicate refs are deduped by stable attachment id/ref, URL/href, then name + media type.
- When canonical and compatibility attachments conflict, top-level `attachments` wins.
- Unsupported compatibility shapes are ignored instead of being forwarded as raw conflicting metadata.

Phase 3: safe evidence.

- Add per-hop evidence fields for attachment propagation without storing raw signed URLs, tokens, or image bytes.
- Prefer URL digest or redacted path over full URL.
- Keep evidence attached to test records or structured runtime diagnostics, not normal task prompts.

## Scope

In scope:

- Define routing rules for direct attachment handoff vs `tms-attachment-agent` / `analyze_attachment` preprocessing.
- Add sanitized per-hop evidence points: attachment count, ids, names, provider/ref type, and redacted URL path or digest.
- Keep the `metadata.attachments` compatibility rule explicit and covered by regression tests.
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

- [x] Reuse REQ-030 as the explicit on-demand image analysis policy source.
- [x] Decide `metadata.attachments` compatibility rule: supported only as legacy input, normalized before Worker dispatch.
- [x] Implement OpenAPI attachment normalization and dedupe with top-level `attachments` as canonical.
- [ ] Define per-hop sanitized attachment evidence fields.
- [ ] Extend explicit preprocessing evidence for analysis-result-to-original-ref linkage.

Testing progress:

- [x] Add unit tests for OpenAPI compatibility merge/dedupe rules.
- [ ] Add scripted Worker E2E for explicit preprocessing before ticket creation.
- [ ] Keep existing BUG-028 real LLM ticket E2E as the direct-handoff regression.

Validation:

- 2026-05-18: `mvn -pl .\addons\claude-worker-agent -am "-Dtest=OpenApiAttachmentNormalizerTest,OpenApiControllerMessageMappingTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
- Result: passed, 16 tests run, 0 failures, 0 errors.

Implementation references:

- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiAttachmentNormalizer.java`
- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiController.java`
- `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiAttachmentNormalizerTest.java`
- `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiControllerMessageMappingTest.java`

Experience progress:

- N/A. This item is runtime/API governance only; no UI interaction is introduced in this work item.

## References

- `BUG-028-tms-ticket-agent-attachment-not-delivered.md`
- `REQ-030-biz-worker-on-demand-attachment-analysis-and-vision-model-config.md`
