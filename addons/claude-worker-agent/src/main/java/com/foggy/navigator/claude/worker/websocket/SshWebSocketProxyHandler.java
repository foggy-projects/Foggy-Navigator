package com.foggy.navigator.claude.worker.websocket;

import com.foggy.navigator.claude.worker.model.entity.ClaudeWorkerEntity;
import com.foggy.navigator.claude.worker.service.ClaudeWorkerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSH WebSocket 代理 — 双向桥接 Browser ↔ Java Backend ↔ Worker。
 * <p>
 * Browser 连接 /api/v1/ssh/{sessionId}/ws?workerId={wid}&token={jwt}，
 * 后端内部再连接 Worker 的 WS 端点，双向转发 binary/text 帧。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SshWebSocketProxyHandler extends AbstractWebSocketHandler {

    private final ClaudeWorkerService workerService;

    /** Browser session → Worker session 的映射 */
    private final Map<String, WebSocketSession> browserToWorker = new ConcurrentHashMap<>();
    /** Worker session → Browser session 的反向映射 */
    private final Map<String, WebSocketSession> workerToBrowser = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession browserSession) throws Exception {
        // 从 URI 路径提取 sessionId: /api/v1/ssh/{sessionId}/ws
        String path = browserSession.getUri().getPath();
        String sshSessionId = extractSessionId(path);

        // 从 handshake attributes 获取 workerId（由 interceptor 放入）
        String workerId = (String) browserSession.getAttributes().get("workerId");
        if (workerId == null || sshSessionId == null) {
            log.warn("Missing workerId or sessionId, closing browser session");
            browserSession.close(CloseStatus.BAD_DATA);
            return;
        }

        // 加载 Worker，构建内部 WS URL
        ClaudeWorkerEntity worker;
        try {
            worker = workerService.getWorkerEntity(workerId);
        } catch (Exception e) {
            log.warn("Worker not found: {}", workerId);
            browserSession.close(CloseStatus.SERVER_ERROR);
            return;
        }

        String workerToken = workerService.getDecryptedToken(worker);
        String wsBase = worker.getBaseUrl().replaceFirst("^http", "ws");
        String workerWsUrl = wsBase + "/api/v1/ssh/" + sshSessionId + "/ws?token=" + workerToken;

        log.info("SSH WS proxy: browser={} → worker={}, session={}", browserSession.getId(), workerId, sshSessionId);

        // 连接 Worker WS
        StandardWebSocketClient wsClient = new StandardWebSocketClient();
        try {
            WebSocketSession workerSession = wsClient.execute(
                    new WorkerRelayHandler(browserSession),
                    null,
                    URI.create(workerWsUrl)
            ).get(10, java.util.concurrent.TimeUnit.SECONDS);

            browserToWorker.put(browserSession.getId(), workerSession);
            workerToBrowser.put(workerSession.getId(), browserSession);

            log.info("SSH WS proxy established: browser={} ↔ worker={}", browserSession.getId(), workerSession.getId());
        } catch (Exception e) {
            log.error("Failed to connect to Worker WS: {}", e.getMessage());
            browserSession.close(CloseStatus.SERVER_ERROR);
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession browserSession, BinaryMessage message) throws Exception {
        WebSocketSession workerSession = browserToWorker.get(browserSession.getId());
        if (workerSession != null && workerSession.isOpen()) {
            log.debug("SSH WS proxy: browser→worker binary {} bytes", message.getPayloadLength());
            workerSession.sendMessage(message);
        } else {
            log.warn("SSH WS proxy: browser→worker binary dropped (worker session gone)");
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession browserSession, TextMessage message) throws Exception {
        WebSocketSession workerSession = browserToWorker.get(browserSession.getId());
        if (workerSession != null && workerSession.isOpen()) {
            log.debug("SSH WS proxy: browser→worker text {} chars", message.getPayload().length());
            workerSession.sendMessage(message);
        } else {
            log.warn("SSH WS proxy: browser→worker text dropped (worker session gone)");
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession browserSession, CloseStatus status) {
        log.info("SSH WS proxy: browser session closed: {}, status={}", browserSession.getId(), status);
        closeWorkerSession(browserSession.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession browserSession, Throwable exception) {
        log.warn("SSH WS proxy: browser transport error: {}", exception.getMessage());
        closeWorkerSession(browserSession.getId());
    }

    private void closeWorkerSession(String browserSessionId) {
        WebSocketSession workerSession = browserToWorker.remove(browserSessionId);
        if (workerSession != null) {
            workerToBrowser.remove(workerSession.getId());
            if (workerSession.isOpen()) {
                try {
                    workerSession.close(CloseStatus.NORMAL);
                } catch (IOException e) {
                    log.debug("Error closing worker session: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * 从路径 /api/v1/ssh/{sessionId}/ws 中提取 sessionId
     */
    private String extractSessionId(String path) {
        // path: /api/v1/ssh/{sessionId}/ws
        String[] parts = path.split("/");
        // parts: ["", "api", "v1", "ssh", "{sessionId}", "ws"]
        if (parts.length >= 6 && "ws".equals(parts[parts.length - 1])) {
            return parts[parts.length - 2];
        }
        return null;
    }

    /**
     * Worker 端 WebSocket handler — 将 Worker 消息转发回 Browser。
     */
    private class WorkerRelayHandler extends AbstractWebSocketHandler {
        private final WebSocketSession browserSession;

        WorkerRelayHandler(WebSocketSession browserSession) {
            this.browserSession = browserSession;
        }

        @Override
        protected void handleBinaryMessage(WebSocketSession workerSession, BinaryMessage message) throws Exception {
            if (browserSession.isOpen()) {
                log.debug("SSH WS proxy: worker→browser binary {} bytes", message.getPayloadLength());
                browserSession.sendMessage(message);
            } else {
                log.warn("SSH WS proxy: worker→browser binary dropped (browser session closed)");
            }
        }

        @Override
        protected void handleTextMessage(WebSocketSession workerSession, TextMessage message) throws Exception {
            if (browserSession.isOpen()) {
                log.debug("SSH WS proxy: worker→browser text {} chars", message.getPayload().length());
                browserSession.sendMessage(message);
            } else {
                log.warn("SSH WS proxy: worker→browser text dropped (browser session closed)");
            }
        }

        @Override
        public void afterConnectionClosed(WebSocketSession workerSession, CloseStatus status) {
            log.info("SSH WS proxy: worker session closed: {}, status={}", workerSession.getId(), status);
            workerToBrowser.remove(workerSession.getId());

            // 找到对应的 browser session 并关闭
            WebSocketSession bs = null;
            for (var entry : browserToWorker.entrySet()) {
                if (entry.getValue().getId().equals(workerSession.getId())) {
                    bs = null;
                    browserToWorker.remove(entry.getKey());
                    // 需要获取 browser session 来关闭它
                    break;
                }
            }

            // 通过反向映射关闭 browser session
            if (browserSession.isOpen()) {
                try {
                    browserSession.close(CloseStatus.NORMAL);
                } catch (IOException e) {
                    log.debug("Error closing browser session: {}", e.getMessage());
                }
            }
        }

        @Override
        public void handleTransportError(WebSocketSession workerSession, Throwable exception) {
            log.warn("SSH WS proxy: worker transport error: {}", exception.getMessage());
            if (browserSession.isOpen()) {
                try {
                    browserSession.close(CloseStatus.SERVER_ERROR);
                } catch (IOException e) {
                    log.debug("Error closing browser session: {}", e.getMessage());
                }
            }
        }
    }
}
