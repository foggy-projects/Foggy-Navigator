package com.foggy.navigator.claude.worker.controller;

import com.foggy.navigator.claude.worker.client.ClaudeWorkerClient;
import com.foggy.navigator.claude.worker.model.dto.SshSessionDTO;
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

import java.util.ArrayList;
import java.util.List;
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
     * SSH 凭证从 Worker 级别读取，directoryId 有值时自动设置 cwd。
     */
    @PostMapping("/connect")
    public RX<Map<String, Object>> connect(@RequestBody SshConnectForm form) {
        ClaudeWorkerEntity worker = workerService.getWorkerEntity(form.getWorkerId());
        if (worker == null) {
            return RX.failB("Worker not found: " + form.getWorkerId());
        }

        String host = form.getHost();
        int port = form.getPort();
        String username = form.getUsername();
        String password = form.getPassword();
        String cwd = null;

        // 从 Worker 读取 SSH 凭证
        if (username == null || username.isEmpty()) {
            username = worker.getSshUsername();
        }
        if (password == null || password.isEmpty()) {
            password = workerService.getDecryptedSshPassword(worker);
        }
        if (port == 22 && worker.getSshPort() != null) {
            port = worker.getSshPort();
        }

        // 从目录读取 cwd
        if (form.getDirectoryId() != null && !form.getDirectoryId().isEmpty()) {
            String userId = UserContext.getCurrentUserId();
            try {
                WorkingDirectoryEntity dir = directoryService.getDirectoryEntity(userId, form.getDirectoryId());
                cwd = dir.getPath();
            } catch (Exception e) {
                log.debug("Directory lookup failed: {}", e.getMessage());
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
        body.put("port", port);
        body.put("username", username);
        body.put("password", password);
        body.put("cols", form.getCols());
        body.put("rows", form.getRows());
        if (cwd != null) {
            body.put("cwd", cwd);
        }
        if (form.getDirectoryId() != null && !form.getDirectoryId().isEmpty()) {
            body.put("directory_id", form.getDirectoryId());
        }

        Map<String, Object> result;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> r = client.sshConnect(body)
                    .block(java.time.Duration.ofSeconds(15));
            result = r;
        } catch (Exception e) {
            String msg = e.getMessage();
            // Extract detail from Worker JSON error response
            if (msg != null && msg.contains("\"detail\"")) {
                int start = msg.indexOf("\"detail\":\"");
                if (start >= 0) {
                    start += 10;
                    int end = msg.indexOf("\"", start);
                    if (end > start) msg = msg.substring(start, end);
                }
            }
            log.warn("SSH connect via Worker failed: {}", msg);
            return RX.failB("SSH 连接失败: " + msg);
        }
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
     * 列出 Worker 上属于当前用户的活跃 SSH 会话，返回完整重连信息（含 wsUrl）。
     */
    @GetMapping("/sessions")
    public RX<List<SshSessionDTO>> listSessions(@RequestParam String workerId) {
        ClaudeWorkerEntity worker = workerService.getWorkerEntity(workerId);
        if (worker == null) {
            return RX.failB("Worker not found: " + workerId);
        }

        String userId = UserContext.getCurrentUserId();
        ClaudeWorkerClient client = workerService.createClient(worker);

        List<Map<String, Object>> workerSessions;
        try {
            workerSessions = client.listSshSessions()
                    .block(java.time.Duration.ofSeconds(10));
        } catch (Exception e) {
            log.warn("List SSH sessions from Worker failed: {}", e.getMessage());
            return RX.ok(List.of());
        }
        if (workerSessions == null) {
            return RX.ok(List.of());
        }

        String wsBase = worker.getBaseUrl().replaceFirst("^http", "ws");
        String token = workerService.getDecryptedToken(worker);

        List<SshSessionDTO> result = new ArrayList<>();
        for (Map<String, Object> ws : workerSessions) {
            String dirId = (String) ws.get("directory_id");
            if (dirId == null || dirId.isEmpty()) {
                continue; // skip sessions without directory binding
            }

            // Verify directory belongs to current user
            try {
                directoryService.getDirectoryEntity(userId, dirId);
            } catch (Exception e) {
                continue; // not this user's directory
            }

            String sessionId = (String) ws.get("session_id");
            String username = (String) ws.get("username");
            String host = (String) ws.get("host");
            int cols = ws.get("cols") instanceof Number ? ((Number) ws.get("cols")).intValue() : 80;
            int rows = ws.get("rows") instanceof Number ? ((Number) ws.get("rows")).intValue() : 24;

            SshSessionDTO dto = new SshSessionDTO();
            dto.setSessionId(sessionId);
            dto.setDirectoryId(dirId);
            dto.setLabel(username + "@" + host);
            dto.setWsUrl(wsBase + "/api/v1/ssh/" + sessionId + "/ws?token=" + token);
            dto.setCols(cols);
            dto.setRows(rows);
            dto.setConnectedAt(ws.get("connected_at") != null ? ws.get("connected_at").toString() : null);
            dto.setLastActivity(ws.get("last_activity") != null ? ws.get("last_activity").toString() : null);
            result.add(dto);
        }

        return RX.ok(result);
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
