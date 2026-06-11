# Upstream UI Artifact AG-UI Alignment Guide

## Purpose

This guide defines how upstream business systems such as TMS should return user-visible UI actions to NAVI.

NAVI is aligning with AG-UI's event-based agent-to-frontend model. In the first phase, upstream systems do not need to implement the full AG-UI event stream. They should return a standard `OPEN_ARTIFACT` action in tool results, usually inside `structured_output`. NAVI can then render the action and open the business UI through the generic artifact host.

Reference protocol:

- NAVI protocol: `docs/03-implementation/navigator-ui-artifact-protocol-v1.md`
- AG-UI events: https://docs.ag-ui.com/concepts/events
- AG-UI repository: https://github.com/ag-ui-protocol/ag-ui

## Alignment Model

AG-UI defines typed events between agents and UI clients, including tool call lifecycle events and `ToolCallResult`. NAVI's current compatibility layer treats the business function result as the source of UI intent.

The upstream function result should therefore follow this shape:

```json
{
  "summary": "已生成打印模板草稿，可打开预览、调整并发布。",
  "structured_output": {
    "type": "OPEN_ARTIFACT",
    "label": "查看模板预览",
    "artifact": {
      "kind": "iframe",
      "id": "print-template-preview:tpl_123",
      "title": "面单模板预览",
      "uri": "/print-template-preview?templateId=tpl_123",
      "openMode": "side_panel",
      "fallbackUrl": "/print-templates?templateId=tpl_123"
    },
    "context": {
      "businessDomain": "tms.print",
      "templateId": "tpl_123"
    }
  }
}
```

`summary` is natural language for the user. `structured_output` is machine-readable UI intent. The LLM should not invent URLs or route names when the function already returned this object.

## Required Action Contract

Use `OPEN_ARTIFACT` for every upstream result that asks NAVI to open a UI surface.

Required fields:

- `type`: must be `OPEN_ARTIFACT`.
- `label`: final user-facing Chinese button text.
- `artifact.kind`: one of `iframe`, `route`, or `link`.
- `artifact.uri`, `artifact.routeName`, or `artifact.routePath`: target location, depending on kind.
- `artifact.openMode`: one of `side_panel`, `dialog`, `new_tab`, or `current_page`.

Recommended fields:

- `artifact.id`: stable action id, for example `print-template-preview:tpl_123`.
- `artifact.title`: host surface title.
- `artifact.fallbackUrl`: trusted fallback when iframe is unavailable.
- `context.businessDomain`: business namespace, for example `tms.print`.
- `context.templateId`, `context.draftId`, or the relevant business id for audit and troubleshooting.

## Artifact Kind Rules

Use `iframe` when the UI is hosted by the upstream business system and should open inside NAVI.

```json
{
  "kind": "iframe",
  "uri": "/print-template-preview?templateId=tpl_123",
  "openMode": "side_panel",
  "fallbackUrl": "/print-templates?templateId=tpl_123"
}
```

Use `route` only when the target is a NAVI-owned internal route.

```json
{
  "kind": "route",
  "routeName": "SomeNavigatorRoute",
  "openMode": "current_page"
}
```

Use `link` when the expected behavior is an external or full-page navigation.

```json
{
  "kind": "link",
  "uri": "https://trusted.example.com/report/123",
  "openMode": "new_tab"
}
```

Do not use `routeName` to name a TMS frontend route unless NAVI owns that route. For TMS-hosted pages, use `iframe` or `link` with a URL.

## TMS Print Migration

Current TMS print result:

```json
{
  "type": "PRINT_TEMPLATE_PREVIEW",
  "label": "查看模板预览",
  "url": "/print-template-preview?templateId=tpl_123",
  "openUrl": "/print-templates?templateId=tpl_123",
  "routeName": "PrintTemplatePreview",
  "query": { "templateId": "tpl_123" }
}
```

Target result:

```json
{
  "type": "OPEN_ARTIFACT",
  "label": "查看模板预览",
  "artifact": {
    "kind": "iframe",
    "id": "print-template-preview:tpl_123",
    "title": "面单模板预览",
    "uri": "/print-template-preview?templateId=tpl_123",
    "openMode": "side_panel",
    "fallbackUrl": "/print-templates?templateId=tpl_123"
  },
  "context": {
    "businessDomain": "tms.print",
    "templateId": "tpl_123"
  }
}
```

Field mapping:

| Current field | Target field |
| --- | --- |
| `type=PRINT_TEMPLATE_PREVIEW` | `type=OPEN_ARTIFACT` |
| `label` | `label` |
| `url` or `previewUrl` | `artifact.uri` |
| `openUrl` | `artifact.fallbackUrl` |
| `routeName` | omit for TMS-hosted pages |
| `query.templateId` | `context.templateId` and `artifact.id` |

Transition compatibility:

- TMS may keep legacy fields such as `previewUrl`, `openUrl`, `routeName`, and `query` during the rollout window.
- The canonical machine-readable action is still `type=OPEN_ARTIFACT` with `artifact` and `context`.
- NAVI should render the button from the canonical `OPEN_ARTIFACT` payload, not from legacy fields.
- TMS should not add new business-specific action types during this period.
- After the NAVI version with `structured_output` action extraction is deployed and smoke-tested, TMS can remove legacy fields in a separate cleanup.

Expected TMS code changes:

- Update `AgentPrintTemplateDraftResult.NavigatorStructuredOutput` to include `artifact` and `context`.
- Update `AgentTmsPrintService.buildResult` to set `OPEN_ARTIFACT`.
- Update the printed function output schema in `navigator-agent.manifest.json` or the schema generator source.
- Update `AgentTmsPrintServiceTest`, `AgentTmsPrintControllerTest`, and public skill sync tests to assert the new action shape.
- Update `tms-print-agent/SKILL.md` to instruct the model to reuse the returned `structured_output` as the page action.

## Function Schema Requirements

Every function that can open a UI must expose a `structured_output` schema compatible with `OPEN_ARTIFACT`.

Schema minimum:

```json
{
  "structured_output": {
    "type": "object",
    "properties": {
      "type": { "type": "string", "enum": ["OPEN_ARTIFACT"] },
      "label": { "type": "string" },
      "artifact": {
        "type": "object",
        "properties": {
          "kind": { "type": "string", "enum": ["iframe", "route", "link"] },
          "id": { "type": "string" },
          "title": { "type": "string" },
          "uri": { "type": "string" },
          "routeName": { "type": "string" },
          "routePath": { "type": "string" },
          "openMode": { "type": "string", "enum": ["side_panel", "dialog", "new_tab", "current_page"] },
          "fallbackUrl": { "type": "string" }
        },
        "required": ["kind", "openMode"]
      },
      "context": { "type": "object" }
    },
    "required": ["type", "label", "artifact"]
  }
}
```

Function-specific schemas should tighten requirements. For example, a print preview function should require `artifact.kind=iframe`, `artifact.uri`, and `context.templateId`.

## Skill Authoring Rules

When writing upstream Navigator skills:

- Tell the LLM to call the business function for creation or update.
- Tell the LLM to use the function's returned `structured_output` as the UI action.
- Do not ask the LLM to construct URLs from ids if the function result already contains URLs.
- Do not say a draft is published, effective, printed, bound to a device, or otherwise finalized unless the function actually did that.
- Keep user-facing labels explicit in the function result. NAVI must not infer Chinese labels from route names, function ids, or skill ids.

Example instruction:

```markdown
函数成功后，回复用户“已生成打印模板草稿，可打开预览、调整并发布”，并保留函数返回的 `structured_output` 作为页面动作。不要自行拼接预览 URL；不要声称模板已发布或已生效。
```

## Security Requirements

Upstream systems must not return raw HTML or JavaScript for NAVI to execute.

Iframe targets must satisfy all of the following:

- URL is same-origin with NAVI or the origin is configured in `VITE_NAVIGATOR_ARTIFACT_ALLOWED_ORIGINS`.
- URL uses `http` or `https`.
- TMS response headers allow NAVI to embed the page. Do not block the approved NAVI origin with `X-Frame-Options` or CSP `frame-ancestors`.
- The page handles authentication through existing trusted session or token mechanisms. Do not put long-lived secrets in `artifact.uri`.

NAVI remains responsible for allowlist checks, iframe sandbox policy, and fallback behavior.

## Acceptance Checklist

For each upstream UI action:

1. The business function response contains `structured_output.type=OPEN_ARTIFACT`.
2. The response has a user-facing `label`.
3. `artifact.kind` matches ownership of the page.
4. `iframe` and `link` artifacts use trusted URLs only.
5. `fallbackUrl` is present when iframe embedding can fail.
6. `context.businessDomain` and business ids are present for audit.
7. Unit tests assert the function response shape.
8. A NAVI chat smoke test shows the action button and opens the expected surface.
9. During migration, legacy fields are present only for compatibility and are not the source of NAVI's primary UI action.

## Future Direction

This guide standardizes the payload carried by tool results. A later phase can move closer to full AG-UI by emitting native AG-UI event streams end to end: text message events, tool call start/args/end/result, state events, and raw/custom events where needed.

Until then, upstream systems should avoid introducing new action types such as `PRINT_TEMPLATE_PREVIEW` or `OPEN_X_PAGE`. New capabilities should use `OPEN_ARTIFACT` with a business-specific `context.businessDomain`.
