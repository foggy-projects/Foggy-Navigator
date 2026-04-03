package com.foggy.navigator.session.controller;

import com.foggy.navigator.common.entity.SharingKeyEntity;
import com.foggy.navigator.session.service.SharingKeyService;
import com.foggy.navigator.spi.worker.WorkerManagementFacade;
import com.foggyframework.core.ex.RX;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Shared API 文件浏览端点 — 通过 Sharing Key 在受控范围内读取 Agent 工作目录中的文件。
 * <p>
 * 安全约束：
 * <ul>
 *   <li>Sharing Key 有效性 + allowedOperations 检查</li>
 *   <li>directoryId 归属于 Sharing Key 的 ownerUserId（由 WorkerManagementFacade 校验）</li>
 *   <li>subPath 禁止包含 ".."（由 WorkerManagementFacade 实现层校验）</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/shared/files")
@RequiredArgsConstructor
public class SharedFileController {

    private final SharingKeyService sharingKeyService;
    @Nullable
    private final WorkerManagementFacade workerManagementFacade;

    /**
     * 列出目录内容
     *
     * @param directoryId 工作目录 ID
     * @param path        子路径（相对于工作目录，可空表示根目录）
     */
    @GetMapping("/list")
    public RX<Map<String, Object>> listFiles(
            @RequestHeader("X-Sharing-Key") String sharingKey,
            @RequestParam String directoryId,
            @RequestParam(required = false) String path) {
        try {
            SharingKeyEntity keyEntity = sharingKeyService.validateForKeyOnly(sharingKey);
            sharingKeyService.checkOperation(keyEntity, "files:list");
            checkFacadeAvailable();

            Map<String, Object> result = workerManagementFacade.listFiles(
                    keyEntity.getOwnerUserId(), directoryId, path);
            return RX.ok(result);
        } catch (IllegalArgumentException e) {
            return RX.failA(e.getMessage());
        } catch (Exception e) {
            log.warn("Shared files list failed: directoryId={}, error={}", directoryId, e.getMessage());
            return RX.failA("Failed to list files: " + e.getMessage());
        }
    }

    /**
     * 读取文件内容
     *
     * @param directoryId 工作目录 ID
     * @param path        文件子路径（相对于工作目录，必填）
     */
    @GetMapping("/read")
    public RX<Map<String, Object>> readFile(
            @RequestHeader("X-Sharing-Key") String sharingKey,
            @RequestParam String directoryId,
            @RequestParam String path) {
        try {
            SharingKeyEntity keyEntity = sharingKeyService.validateForKeyOnly(sharingKey);
            sharingKeyService.checkOperation(keyEntity, "files:read");
            checkFacadeAvailable();

            Map<String, Object> result = workerManagementFacade.readFile(
                    keyEntity.getOwnerUserId(), directoryId, path);
            return RX.ok(result);
        } catch (IllegalArgumentException e) {
            return RX.failA(e.getMessage());
        } catch (Exception e) {
            log.warn("Shared files read failed: directoryId={}, path={}, error={}", directoryId, path, e.getMessage());
            return RX.failA("Failed to read file: " + e.getMessage());
        }
    }

    /**
     * 搜索文件名
     *
     * @param directoryId 工作目录 ID
     * @param q           搜索关键词
     */
    @GetMapping("/search")
    public RX<Map<String, Object>> searchFiles(
            @RequestHeader("X-Sharing-Key") String sharingKey,
            @RequestParam String directoryId,
            @RequestParam String q) {
        try {
            SharingKeyEntity keyEntity = sharingKeyService.validateForKeyOnly(sharingKey);
            sharingKeyService.checkOperation(keyEntity, "files:search");
            checkFacadeAvailable();

            Map<String, Object> result = workerManagementFacade.searchFiles(
                    keyEntity.getOwnerUserId(), directoryId, q);
            return RX.ok(result);
        } catch (IllegalArgumentException e) {
            return RX.failA(e.getMessage());
        } catch (Exception e) {
            log.warn("Shared files search failed: directoryId={}, q={}, error={}", directoryId, q, e.getMessage());
            return RX.failA("Failed to search files: " + e.getMessage());
        }
    }

    private void checkFacadeAvailable() {
        if (workerManagementFacade == null) {
            throw new IllegalArgumentException("File browsing is not available — no Worker module loaded");
        }
    }
}
