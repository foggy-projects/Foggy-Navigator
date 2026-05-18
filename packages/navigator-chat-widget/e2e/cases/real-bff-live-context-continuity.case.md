# Real BFF Live Context Continuity E2E Test Case

## Overview

Verify that the Navigator Chat Widget keeps the returned business-agent `contextId` when talking to a real Observer BFF and Navigator backend.

## Preconditions

- [ ] Widget dev server can be started by Playwright.
- [ ] Observer BFF is running and reachable, default `http://127.0.0.1:5181`.
- [ ] Navigator backend is running and reachable from Observer BFF, default `http://127.0.0.1:8112`.
- [ ] Observer BFF has a usable auth mode, normally `client-app-runtime`, `api-key`, or `bearer`.
- [ ] The configured agent can answer two short business-assistant messages.

## Test Steps

### Step 1: Read Observer BFF config

- **API**: `GET /api/v1/observer/config`
- **Expected**: returns `code=0`, a non-missing `authMode`, and an `agentId`.

### Step 2: Open widget in Real BFF mode

- **Page**: widget observability page
- **Setup**: localStorage seeds `connectionMode=real`, `bffBaseUrl`, `navigatorBaseUrl`, and optional `agentId`.
- **Expected**: page shows `Real Navigator BFF` and BFF auth is not `missing`.

### Step 3: Send first message

- **UI action**: send `NAVIGATOR_CHAT_WIDGET_LIVE_FIRST_MESSAGE`, default `E2E 查询当前会话上下文`.
- **API**: `POST /api/v1/open/agents/{agentId}/ask`
- **Expected**: request body contains the first question and no previous `contextId`; response/UI flow returns a non-empty `contextId`.

### Step 4: Send second message

- **UI action**: send `NAVIGATOR_CHAT_WIDGET_LIVE_SECOND_MESSAGE`, default `继续基于上一轮回答补充说明`.
- **API**: `POST /api/v1/open/agents/{agentId}/ask`
- **Expected**: request body contains the second question and the same `contextId` returned by the first turn.

## Expected Results

- The second business-assistant turn is not treated as a new conversation.
- The captured second ask body contains the first turn's `contextId`.
- The widget renders both user messages and does not show a send failure.

## Test Data

| Data | Default | Override |
| --- | --- | --- |
| BFF base URL | `http://127.0.0.1:5181` | `NAVIGATOR_OBSERVER_BFF_BASE_URL` |
| Navigator base URL | `http://127.0.0.1:8112` | `NAVIGATOR_BASE_URL` or `NAVI_BASE_URL` |
| Agent ID | BFF config value | `NAVIGATOR_CHAT_WIDGET_AGENT_ID` or `NAVI_AGENT_ID` |
| Upstream user ID | BFF config value | `NAVIGATOR_CHAT_WIDGET_UPSTREAM_USER_ID` or `NAVI_UPSTREAM_USER_ID` |
| First message | `E2E 查询当前会话上下文` | `NAVIGATOR_CHAT_WIDGET_LIVE_FIRST_MESSAGE` |
| Second message | `继续基于上一轮回答补充说明` | `NAVIGATOR_CHAT_WIDGET_LIVE_SECOND_MESSAGE` |

## Involved Services and APIs

- Widget observability page: Playwright web server.
- Observer BFF: `/api/v1/observer/config`, `/api/v1/open/agents/{agentId}/ask`.
- Navigator backend: called through Observer BFF.

## Cleanup Strategy

- The test creates normal business-agent task/session records in the configured Navigator environment.
- No destructive cleanup is performed.
- Run against a disposable tenant/client-app when possible.

## Run Command

```powershell
$env:NAVIGATOR_CHAT_WIDGET_LIVE_E2E='1'
pnpm --filter @foggy/navigator-chat-widget test:e2e:live
```
