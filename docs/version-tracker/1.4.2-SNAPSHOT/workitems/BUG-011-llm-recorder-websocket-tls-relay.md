---
doc_type: delivery-spec
delivery_type: bug
version: 1.4.2-SNAPSHOT
ticket: BUG-011
status: READY_FOR_SIGNOFF
canonical: true
approved_by: repository owner
approved_at: 2026-07-17
---

# Delivery Spec: LLM Recorder WebSocket TLS Relay

## Goal

修复 `llm-recorder-proxy` 在转发 Codex `/v1/responses` WebSocket 时因非阻塞 TLS `sendall()` 抛出 `SSLWantWriteError`、迫使 Codex 回退 HTTP 的问题，使 recorder 可以持续录制双向 WebSocket 帧而不改变 3071 Worker、Codex CLI 或供应商请求语义。

## Scope and Non-Goals

- in_scope: WebSocket 双向 relay、帧录制、超时/EOF/error 处理、回归测试、远端开发 recorder 更新与真实 Codex WebSocket 验证。
- non_goals: 不修改或重启 3071 Worker；不升级 Codex CLI；不修改 Java；不改变证书、hosts、鉴权或 recorder allowlist；不发布 Worker。
- approval_source: owner 于 2026-07-17 要求处理 recorder 的 `SSLWantWriteError`。

## Acceptance Criteria

- [x] TLS WebSocket relay 不再对非阻塞 socket 调用 `sendall()`；带缓冲的 `send()` 会按 `SSLWantWriteError`/`SSLWantReadError` 需要的 readiness 重试。
- [x] client→upstream 与 upstream→client 可双向转发并分别记录帧和已发送字节计数。
- [x] EOF、exceptional socket 或总超时会结束 relay；实现保持单线程事件循环，不引入 relay 子线程。
- [x] 自动化回归在旧实现失败、修复后通过。
- [x] 真实 Codex 请求保持 WebSocket，不再因 recorder `SSLWantWriteError` 回退 HTTP，并能记录请求及 terminal 返回帧。

## Safety

- recorder metadata 继续脱敏 Authorization/Cookie/API key；不把真实凭据写入本文件或测试。
- 远端更新只处理 `dev-kvm-jdk17.foggysource.com:/home/sa/foggy-llm-recorder` recorder 容器；保持 ingress 和 3071 Worker 不变。

## Implementation Evidence

- changed_paths: `tools/llm-recorder-proxy/llm_recorder_proxy.py`; `tools/llm-recorder-proxy/tests/test_websocket_relay.py`; `tools/llm-recorder-proxy/README.md`; 本 workitem。
- red_test: `python3 -m unittest discover -s tools/llm-recorder-proxy/tests -v`，旧实现 exit 1，`upstream_socket.sendall()` 在非阻塞模拟 TLS socket 上抛出 `ssl.SSLWantWriteError`。
- automated_result: 同一命令修复后 exit 0，3/3 passed；覆盖 TLS send 的 want-write、want-read、部分写，以及 TLS recv 的 want-write。`python3 -m py_compile ...` 与 `git diff --check ...` 均 exit 0。
- deployment: 仅更新并重启远端 `foggy-llm-recorder` 容器；未重启 ingress、3071 Worker 或其 app-server child。远端脚本更新前 SHA-256 `76afa69c...`，更新后与仓库源文件一致。
- live_result: 使用安装版 Worker 0.3.18/Codex CLI 0.144.3 启动端口 13071、独立 state/CODEX_HOME/runtime identity 的临时 Worker；task `bug011-ws-20260717T015925Z` terminal/completed。Recorder 记录 `GET /v1/responses` → `/v1/responses`、HTTP 101、client 2 帧、upstream 14 帧、含 `response.completed`、`error=null`，且该连接没有 HTTP fallback。
- cleanup: 临时 13071 listener、Worker 和 app-server child 均已退出；3071 保持 ready、Worker 0.3.18、CLI 0.144.3、原 runtime/instance 与一个 resident child。
- residual_risks: 已验证单次真实短 turn 和 TLS backpressure 状态机；尚未执行长连接、多并发 WebSocket soak。Recorder 保存完整提示词、工具 schema 和返回体，日志目录仍需按开发敏感数据管理。
- readiness: READY_FOR_SIGNOFF；未自行标记 `ACCEPTED`。
