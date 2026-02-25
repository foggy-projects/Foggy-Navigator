package com.foggy.navigator.claude.worker.controller;

import com.foggy.navigator.claude.worker.client.ClaudeWorkerClient;
import com.foggy.navigator.claude.worker.model.entity.ClaudeWorkerEntity;
import com.foggy.navigator.claude.worker.model.form.SshConnectForm;
import com.foggy.navigator.claude.worker.service.ClaudeWorkerService;
import com.foggy.navigator.common.annotation.RequireAuth;
import com.foggyframework.core.ex.RX;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * SSH 代理 API — 前端不直连 Worker REST（token 不暴露给浏览器），
 * 由 Java 后端代理 REST 调用，返回 WS 直连 URL（含 token）。
 */
@RestController
@RequestMapping("/api/v1/ssh")
@RequireAuth
@Slf4j
@RequiredArgsConstructor
public class SshProxyController {

    private final ClaudeWorkerService workerService;

    /**
     * 建立 SSH 连接 — 代理到 Worker，返回 { sessionId, wsUrl }
     */
    @PostMapping("/connect")
    public RX<Map<String, Object>> connect(@RequestBody SshConnectForm form) {
        ClaudeWorkerEntity worker = workerService.getWorkerEntity(form.getWorkerId());
        if (worker == null) {
            return RX.failB("Worker not found: " + form.getWorkerId());
        }
        ClaudeWorkerClient client = workerService.createClient(worker);

        Map<String, Object> body = new java.util.HashMap<>();
        body.put("host", form.getHost());
        body.put("port", form.getPort());
        body.put("username", form.getUsername());
        body.put("password", form.getPassword());
        body.put("cols", form.getCols());
        body.put("rows", form.getRows());

        @SuppressWarnings("unchecked")
        Map<String, Object> result = client.sshConnect(body)
                .block(java.time.Duration.ofSeconds(15));
        if (result == null) {
            return RX.failB("SSH connect failed: no response");
        }

        // Build WS URL: worker baseUrl + /api/v1/ssh/{sessionId}/ws?token=...
        String sessionId = (String) result.get("session_id");
        String wsBase = worker.getBaseUrl().replaceFirst("^http", "ws");
        String token = workerService.getDecryptedToken(worker);
        String wsUrl = wsBase + "/api/v1/ssh/" + sessionId + "/ws?token=" + token;

        return RX.ok(Map.of("sessionId", sessionId, "wsUrl", wsUrl));
    }

    /**
     * 关闭 SSH 会话
     */
    @PostMapping("/{sessionId}/close")
    public RX<Void> close(@PathVariable String sessionId,
                           @RequestParam String workerId) {
        ClaudeWorkerEntity worker = workerService.getWorkerEntity(workerId);
        if (worker == null) {
            return RX.failB("Worker not found: " + workerId);
        }
        ClaudeWorkerClient client = workerService.createClient(worker);
        client.sshClose(sessionId).block(java.time.Duration.ofSeconds(10));
        return RX.ok(null);
    }

    /**
     * 调整终端尺寸
     */
    @PostMapping("/{sessionId}/resize")
    public RX<Void> resize(@PathVariable String sessionId,
                            @RequestParam String workerId,
                            @RequestBody Map<String, Integer> body) {
        ClaudeWorkerEntity worker = workerService.getWorkerEntity(workerId);
        if (worker == null) {
            return RX.failB("Worker not found: " + workerId);
        }
        ClaudeWorkerClient client = workerService.createClient(worker);
        client.sshResize(sessionId, body).block(java.time.Duration.ofSeconds(5));
        return RX.ok(null);
    }
}
