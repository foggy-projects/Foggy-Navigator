# Navigator UI Artifact Protocol v1

## Purpose

Navigator UI Artifact Protocol v1 lets an agent or tool result ask NAVI to open a business UI without adding a business-specific component to NAVI.

NAVI recognizes the action, validates the target, and chooses the host surface. The business system owns the actual page.

## Action

```json
{
  "type": "OPEN_ARTIFACT",
  "label": "查看模板预览",
  "artifact": {
    "kind": "iframe",
    "id": "print-template-preview:xxx",
    "title": "面单模板预览",
    "uri": "/print-template-preview?templateId=xxx",
    "openMode": "side_panel",
    "fallbackUrl": "/print-template-preview?templateId=xxx"
  },
  "context": {
    "businessDomain": "tms.print",
    "templateId": "xxx",
    "draftId": "xxx"
  }
}
```

## Fields

`type`: `OPEN_ARTIFACT`.

`label`: button text shown in chat.

`artifact.kind`:

- `iframe`: open a hosted business page in NAVI.
- `route`: navigate to a NAVI internal route.
- `link`: open a trusted link.

`artifact.openMode`:

- `side_panel`
- `dialog`
- `new_tab`
- `current_page`

`artifact.uri`: relative or absolute URL for `iframe` and `link`.

`artifact.routeName` / `artifact.routePath`: internal route target for `route`.

`artifact.fallbackUrl`: URL used when iframe hosting is blocked.

`context`: optional audit/business metadata. NAVI does not execute it.

## Compatibility

Legacy actions such as `OPEN_TMS_PAGE` remain supported. If a legacy action contains `artifact`, NAVI opens the generic artifact first:

```json
{
  "type": "OPEN_TMS_PAGE",
  "label": "查看模板预览",
  "routeName": "PrintTemplatePreview",
  "query": { "templateId": "xxx" },
  "artifact": {
    "kind": "iframe",
    "uri": "/print-template-preview?templateId=xxx",
    "openMode": "side_panel"
  }
}
```

If `OPEN_TMS_PAGE` has no `artifact`, NAVI treats it as a `route` artifact using `routeName`, `routePath`, or `uri`.

## Security

NAVI does not execute HTML or scripts returned by an LLM.

Iframe URLs are allowed only when their origin is same-origin with NAVI or is listed in `VITE_NAVIGATOR_ARTIFACT_ALLOWED_ORIGINS`.

Iframe sandbox defaults to:

```text
allow-forms allow-scripts allow-popups allow-downloads
```

Operators can override it with `VITE_NAVIGATOR_ARTIFACT_IFRAME_SANDBOX`.

`javascript:` URLs and non-HTTP(S) URLs are rejected.

When an iframe URL is not allowed, NAVI falls back to `fallbackUrl` or `uri` in a new tab if that URL is trusted. Otherwise the action is rejected with a warning.

## Current Implementation

The chat core extracts artifact actions from structured payloads, tool result `output`, and tool result `data`. The chat UI renders action buttons below the source message. `ClaudeWorkerView` owns opening behavior, allowlist checks, iframe/dialog/drawer hosting, and route navigation.
