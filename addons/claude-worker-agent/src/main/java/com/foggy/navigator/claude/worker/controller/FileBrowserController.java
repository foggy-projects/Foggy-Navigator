package com.foggy.navigator.claude.worker.controller;

import com.foggy.navigator.claude.worker.client.ClaudeWorkerClient;
import com.foggy.navigator.claude.worker.model.entity.WorkingDirectoryEntity;
import com.foggy.navigator.claude.worker.service.ClaudeWorkerService;
import com.foggy.navigator.claude.worker.service.WorkingDirectoryService;
import com.foggy.navigator.common.annotation.RequireAuth;
import com.foggy.navigator.common.context.UserContext;
import com.foggyframework.core.ex.RX;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;

/**
 * 文件浏览器 API — 代理 Worker 的文件/diff 端点
 */
@RestController
@RequestMapping("/api/v1/file-browser")
@RequireAuth
@Slf4j
@RequiredArgsConstructor
public class FileBrowserController {

    private final WorkingDirectoryService directoryService;
    private final ClaudeWorkerService workerService;

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    /**
     * 列出目录内容
     */
    @GetMapping("/list")
    public RX<Map<String, Object>> listFiles(
            @RequestParam String directoryId,
            @RequestParam(required = false, defaultValue = "") String subPath,
            @RequestParam(required = false, defaultValue = "false") boolean showHidden) {
        ClaudeWorkerClient client = resolveClient(directoryId);
        WorkingDirectoryEntity entity = getEntity(directoryId);
        String fullPath = buildPath(entity.getPath(), subPath);
        try {
            Map<String, Object> result = client.listFiles(fullPath, showHidden).block(TIMEOUT);
            return RX.ok(result);
        } catch (Exception e) {
            log.warn("Failed to list files for directory {}: {}", directoryId, e.getMessage());
            return RX.failA("列出文件失败: " + e.getMessage());
        }
    }

    /**
     * 读取文件内容
     */
    @GetMapping("/content")
    public RX<Map<String, Object>> readContent(
            @RequestParam String directoryId,
            @RequestParam String subPath) {
        ClaudeWorkerClient client = resolveClient(directoryId);
        WorkingDirectoryEntity entity = getEntity(directoryId);
        String fullPath = buildPath(entity.getPath(), subPath);
        try {
            Map<String, Object> result = client.readFileContent(fullPath).block(TIMEOUT);
            return RX.ok(result);
        } catch (Exception e) {
            log.warn("Failed to read file for directory {}: {}", directoryId, e.getMessage());
            return RX.failA("读取文件失败: " + e.getMessage());
        }
    }

    /**
     * 获取 Git diff 摘要
     */
    @GetMapping("/git-diff")
    public RX<Map<String, Object>> gitDiffSummary(@RequestParam String directoryId) {
        ClaudeWorkerClient client = resolveClient(directoryId);
        WorkingDirectoryEntity entity = getEntity(directoryId);
        try {
            Map<String, Object> result = client.getGitDiffSummary(entity.getPath()).block(TIMEOUT);
            return RX.ok(result);
        } catch (Exception e) {
            log.warn("Failed to get git diff for directory {}: {}", directoryId, e.getMessage());
            return RX.failA("获取 Git diff 失败: " + e.getMessage());
        }
    }

    /**
     * 获取单文件 diff
     */
    @GetMapping("/git-diff/file")
    public RX<Map<String, Object>> fileDiff(
            @RequestParam String directoryId,
            @RequestParam String file) {
        // Prevent path traversal
        if (file.contains("..")) {
            return RX.failA("file 参数不允许包含 '..'");
        }
        ClaudeWorkerClient client = resolveClient(directoryId);
        WorkingDirectoryEntity entity = getEntity(directoryId);
        try {
            Map<String, Object> result = client.getFileDiff(entity.getPath(), file).block(TIMEOUT);
            return RX.ok(result);
        } catch (Exception e) {
            log.warn("Failed to get file diff for directory {}, file {}: {}", directoryId, file, e.getMessage());
            return RX.failA("获取文件 diff 失败: " + e.getMessage());
        }
    }

    // ---- Helpers -----------------------------------------------------------

    private WorkingDirectoryEntity getEntity(String directoryId) {
        String userId = UserContext.getCurrentUserId();
        return directoryService.getDirectoryEntity(userId, directoryId);
    }

    private ClaudeWorkerClient resolveClient(String directoryId) {
        WorkingDirectoryEntity entity = getEntity(directoryId);
        return workerService.createClient(
                workerService.getWorkerEntity(entity.getWorkerId()));
    }

    private String buildPath(String basePath, String subPath) {
        if (subPath == null || subPath.isBlank()) {
            return basePath;
        }
        // Prevent path traversal
        if (subPath.contains("..")) {
            throw new IllegalArgumentException("subPath 不允许包含 '..'");
        }
        // Normalize to forward slashes
        String normalized = subPath.replace("\\", "/");
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return basePath + "/" + normalized;
    }
}
