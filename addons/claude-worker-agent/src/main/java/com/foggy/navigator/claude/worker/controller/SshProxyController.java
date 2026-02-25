package com.foggy.navigator.claude.worker.controller;

import com.foggy.navigator.claude.worker.client.ClaudeWorkerClient;
import com.foggy.navigator.claude.worker.model.entity.ClaudeWorkerEntity;
import com.foggy.navigator.claude.worker.model.entity.WorkingDirectoryEntity;
import com.foggy.navigator.claude.worker.model.form.SshConnectForm;
import com.foggy.navigator.claude.worker.service.ClaudeWorkerService;
import com.foggy.navigator.claude.worker.service.WorkingDirectoryService;
import com.foggy.navigator.common.annotation.RequireAuth;
import com.foggy.navigator.common.context.UserContext;
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
    private final WorkingDirectoryService directoryService;

    /**
     * 建立 SSH 连接 — 代理到 Worker，返回 { sessionId, wsUrl }
     * 如果 directoryId 有值且目录已配置 SSH 凭证，则自动使用。
     */
    @PostMapping("/connect")
    public RX<Map<String, Object>> connect(@RequestBody SshConnectForm form) {
        ClaudeWorkerEntity worker = workerService.getWorkerEntity(form.getWorkerId());
        if (worker == null) {
            return RX.failB("Worker not found: " + form.getWorkerId());
        }

        String host = form.getHost();
        String username = form.getUsername();
        String password = form.getPassword();

        // 从目录配置自动填充 SSH 凭证
        if (form.getDirectoryId() != null && !form.getDirectoryId().isEmpty()) {
            String userId = UserContext.getCurrentUserId();
            try {
                WorkingDirectoryEntity dir = directoryService.getDirectoryEntity(userId, form.getDirectoryId());
                if (username == null || username.isEmpty()) {
                    username = dir.getSshUsername();
                }
                if (password == null || password.isEmpty()) {
                    password = directoryService.getDecryptedSshPassword(dir);
                }
            } catch (Exception e) {
                log.debug("Directory SSH credential lookup failed: {}", e.getMessage());
            }
        }

        // host fallback: Worker hostname
        if (host == null || host.isEmpty()) {
            host = worker.getHostname();
        }

        if (host == null || host.isEmpty() || username == null || username.isEmpty() || password == null || password.isEmpty()) {
            return RX.failB("SSH 连接信息不完整：需要 host、username、password");
        }

        ClaudeWorkerClient client = workerService.createClient(worker);

        Map<String, Object> body = new java.util.HashMap<>();
        body.put("host", host);
        body.put("port", form.getPort());
        body.put("username", username);
        body.put("password", password);
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
