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
            return RX.failA(formatFriendlyError(e, "列出文件失败"));
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
            return RX.failA(formatFriendlyError(e, "读取文件失败"));
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
            return RX.failA(formatFriendlyError(e, "获取 Git diff 失败"));
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
            return RX.failA(formatFriendlyError(e, "获取文件 diff 失败"));
        }
    }

    // ===== Git Log / History =====

    private static final java.util.regex.Pattern HASH_PATTERN = java.util.regex.Pattern.compile("[0-9a-fA-F]{4,40}");

    /**
     * 获取 git log（分页 + 分支 ahead/behind）
     */
    @GetMapping("/git-log")
    public RX<Map<String, Object>> gitLog(
            @RequestParam String directoryId,
            @RequestParam(required = false, defaultValue = "50") int limit,
            @RequestParam(required = false, defaultValue = "0") int skip) {
        ClaudeWorkerClient client = resolveClient(directoryId);
        WorkingDirectoryEntity entity = getEntity(directoryId);
        try {
            Map<String, Object> result = client.getGitLog(entity.getPath(), limit, skip).block(TIMEOUT);
            return RX.ok(result);
        } catch (Exception e) {
            log.warn("Failed to get git log for directory {}: {}", directoryId, e.getMessage());
            return RX.failA(formatFriendlyError(e, "获取 Git 历史失败"));
        }
    }

    /**
     * 获取 commit 详情（文件列表 + 统计）
     */
    @GetMapping("/git-log/commit")
    public RX<Map<String, Object>> commitDetail(
            @RequestParam String directoryId,
            @RequestParam String hash) {
        if (!HASH_PATTERN.matcher(hash).matches()) {
            return RX.failA("hash 格式无效，需要 4-40 位十六进制字符");
        }
        ClaudeWorkerClient client = resolveClient(directoryId);
        WorkingDirectoryEntity entity = getEntity(directoryId);
        try {
            Map<String, Object> result = client.getCommitDetail(entity.getPath(), hash).block(TIMEOUT);
            return RX.ok(result);
        } catch (Exception e) {
            log.warn("Failed to get commit detail for directory {}, hash {}: {}", directoryId, hash, e.getMessage());
            return RX.failA(formatFriendlyError(e, "获取 commit 详情失败"));
        }
    }

    /**
     * 获取 commit 中单文件的 diff
     */
    @GetMapping("/git-log/commit/file-diff")
    public RX<Map<String, Object>> commitFileDiff(
            @RequestParam String directoryId,
            @RequestParam String hash,
            @RequestParam String file) {
        if (!HASH_PATTERN.matcher(hash).matches()) {
            return RX.failA("hash 格式无效，需要 4-40 位十六进制字符");
        }
        if (file.contains("..")) {
            return RX.failA("file 参数不允许包含 '..'");
        }
        ClaudeWorkerClient client = resolveClient(directoryId);
        WorkingDirectoryEntity entity = getEntity(directoryId);
        try {
            Map<String, Object> result = client.getCommitFileDiff(entity.getPath(), hash, file).block(TIMEOUT);
            return RX.ok(result);
        } catch (Exception e) {
            log.warn("Failed to get commit file diff for directory {}, hash {}, file {}: {}",
                    directoryId, hash, file, e.getMessage());
            return RX.failA(formatFriendlyError(e, "获取 commit 文件 diff 失败"));
        }
    }

    /**
     * 搜索文件名
     */
    @GetMapping("/search")
    public RX<Map<String, Object>> searchFiles(
            @RequestParam String directoryId,
            @RequestParam String query,
            @RequestParam(required = false, defaultValue = "50") int maxResults) {
        ClaudeWorkerClient client = resolveClient(directoryId);
        WorkingDirectoryEntity entity = getEntity(directoryId);
        try {
            Map<String, Object> result = client.searchFiles(entity.getPath(), query, maxResults).block(TIMEOUT);
            return RX.ok(result);
        } catch (Exception e) {
            log.warn("Failed to search files for directory {}: {}", directoryId, e.getMessage());
            return RX.failA(formatFriendlyError(e, "搜索文件失败"));
        }
    }

    /**
     * 搜索文件内容
     */
    @GetMapping("/search-content")
    public RX<Map<String, Object>> searchContent(
            @RequestParam String directoryId,
            @RequestParam String query,
            @RequestParam(required = false, defaultValue = "50") int maxResults,
            @RequestParam(required = false, defaultValue = "2") int contextLines,
            @RequestParam(required = false, defaultValue = "false") boolean caseSensitive,
            @RequestParam(required = false) String filePattern) {
        ClaudeWorkerClient client = resolveClient(directoryId);
        WorkingDirectoryEntity entity = getEntity(directoryId);
        try {
            Map<String, Object> result = client.searchContent(
                    entity.getPath(), query, maxResults, contextLines, caseSensitive, filePattern).block(TIMEOUT);
            return RX.ok(result);
        } catch (Exception e) {
            log.warn("Failed to search content for directory {}: {}", directoryId, e.getMessage());
            return RX.failA(formatFriendlyError(e, "搜索内容失败"));
        }
    }

    /**
     * 获取 .foggy-ignore 排除模式
     */
    @GetMapping("/ignore")
    public RX<Map<String, Object>> getFoggyIgnore(@RequestParam String directoryId) {
        ClaudeWorkerClient client = resolveClient(directoryId);
        WorkingDirectoryEntity entity = getEntity(directoryId);
        try {
            Map<String, Object> result = client.getFoggyIgnore(entity.getPath()).block(TIMEOUT);
            return RX.ok(result);
        } catch (Exception e) {
            log.warn("Failed to get foggy ignore for directory {}: {}", directoryId, e.getMessage());
            return RX.failA(formatFriendlyError(e, "获取排除规则失败"));
        }
    }

    /**
     * 添加排除模式到 .foggy-ignore
     */
    @PostMapping("/ignore")
    public RX<Map<String, Object>> addFoggyIgnore(@RequestBody Map<String, String> body) {
        String directoryId = body.get("directoryId");
        String pattern = body.get("pattern");
        if (directoryId == null || pattern == null) {
            return RX.failA("directoryId 和 pattern 不能为空");
        }
        ClaudeWorkerClient client = resolveClient(directoryId);
        WorkingDirectoryEntity entity = getEntity(directoryId);
        try {
            Map<String, Object> result = client.addFoggyIgnore(entity.getPath(), pattern).block(TIMEOUT);
            return RX.ok(result);
        } catch (Exception e) {
            log.warn("Failed to add foggy ignore for directory {}: {}", directoryId, e.getMessage());
            return RX.failA(formatFriendlyError(e, "添加排除规则失败"));
        }
    }

    /**
     * 从 .foggy-ignore 移除排除模式
     */
    @DeleteMapping("/ignore")
    public RX<Map<String, Object>> removeFoggyIgnore(@RequestBody Map<String, String> body) {
        String directoryId = body.get("directoryId");
        String pattern = body.get("pattern");
        if (directoryId == null || pattern == null) {
            return RX.failA("directoryId 和 pattern 不能为空");
        }
        ClaudeWorkerClient client = resolveClient(directoryId);
        WorkingDirectoryEntity entity = getEntity(directoryId);
        try {
            Map<String, Object> result = client.removeFoggyIgnore(entity.getPath(), pattern).block(TIMEOUT);
            return RX.ok(result);
        } catch (Exception e) {
            log.warn("Failed to remove foggy ignore for directory {}: {}", directoryId, e.getMessage());
            return RX.failA(formatFriendlyError(e, "移除排除规则失败"));
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

    /**
     * 将 Worker 调用错误转换为友好提示
     */
    private String formatFriendlyError(Exception e, String operation) {
        String msg = e.getMessage();
        if (msg == null) {
            return operation;
        }
        if (msg.contains("401") || msg.contains("Unauthorized")) {
            return "Worker 认证失败，请检查 Worker Token 配置是否正确";
        }
        if (msg.contains("403") || msg.contains("Forbidden")) {
            return "Worker 拒绝访问，请检查权限配置";
        }
        if (msg.contains("Connection refused")) {
            return "Worker 服务未运行，请检查 Worker 是否已启动";
        }
        if (msg.contains("timeout")) {
            return "Worker 响应超时，请稍后重试";
        }
        return operation + ": " + msg;
    }
}
