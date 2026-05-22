# LLM Recorder Proxy

Minimal OpenAI-compatible recorder proxy for capturing the exact HTTP body sent by a worker to an LLM provider.

Default local URL:

```text
http://127.0.0.1:18787
```

Health check:

```text
http://127.0.0.1:18787/__recorder/health
```

Config file:

```text
tools/llm-recorder-proxy/.env.local
```

Put the upstream key in `LLM_RECORDER_API_KEY`. With the default `LLM_RECORDER_FORCE_API_KEY=true`, the proxy sends that key upstream even if the client supplied a placeholder `Authorization` header. Authorization and cookie headers are redacted from proxy logs.

Default upstream:

```text
LLM_RECORDER_UPSTREAM_BASE_URL=https://codex2.qlfloor.com:8443/v1
```

Captured files are written under:

```text
tools/llm-recorder-proxy/logs/<run-id>/<sequence>_<method>_<path>/
```

Important files:

- `request_body.raw`: exact request body bytes forwarded to upstream.
- `request_body.pretty.json`: pretty JSON view when the request body is JSON.
- `request.json`: request metadata with sensitive headers redacted.
- `websocket_client_messages.jsonl`: decoded WebSocket payloads sent by the worker to the upstream LLM endpoint.
- `response_body.raw`: exact upstream response body bytes.
- `response.json`: response metadata.
- `index.jsonl`: per-run request index.

Run manually:

```powershell
python tools/llm-recorder-proxy/llm_recorder_proxy.py --env-file tools/llm-recorder-proxy/.env.local
```
