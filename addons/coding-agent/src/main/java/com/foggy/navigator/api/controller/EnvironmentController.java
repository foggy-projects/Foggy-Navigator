package com.foggy.navigator.api.controller;

import com.foggy.navigator.api.model.ApiResponse;
import com.foggy.navigator.api.model.CreateEnvironmentRequest;
import com.foggy.navigator.api.model.Environment;
import com.foggy.navigator.api.service.EnvironmentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 环境管理 API
 */
@RestController
@RequestMapping("/api/environments")
@Slf4j
public class EnvironmentController {

    @Autowired
    private EnvironmentService environmentService;

    /**
     * 创建编辑环境
     *
     * POST /api/environments
     */
    @PostMapping
    public ResponseEntity<ApiResponse<Environment>> createEnvironment(
            @RequestBody CreateEnvironmentRequest request) {

        log.info("收到创建环境请求: userId={}, projectId={}",
                request.getUserId(), request.getProjectId());

        try {
            // 参数校验
            if (request.getUserId() == null || request.getUserId().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error("INVALID_PARAM", "userId 不能为空"));
            }
            if (request.getProjectId() == null || request.getProjectId().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error("INVALID_PARAM", "projectId 不能为空"));
            }
            if (request.getGitRepoUrl() == null || request.getGitRepoUrl().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error("INVALID_PARAM", "gitRepoUrl 不能为空"));
            }
            if (request.getBranchName() == null || request.getBranchName().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error("INVALID_PARAM", "branchName 不能为空"));
            }

            // 创建环境
            Environment environment = environmentService.createEnvironment(request);

            return ResponseEntity.ok(ApiResponse.success("环境创建成功", environment));

        } catch (Exception e) {
            log.error("创建环境失败", e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("CREATE_FAILED", "创建环境失败: " + e.getMessage()));
        }
    }

    /**
     * 获取环境信息
     *
     * GET /api/environments/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Environment>> getEnvironment(@PathVariable String id) {
        log.info("查询环境: environmentId={}", id);

        try {
            Environment environment = environmentService.getEnvironment(id);
            return ResponseEntity.ok(ApiResponse.success(environment));

        } catch (Exception e) {
            log.error("查询环境失败: environmentId={}", id, e);
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 销毁环境
     *
     * DELETE /api/environments/{id}
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> destroyEnvironment(@PathVariable String id) {
        log.info("销毁环境: environmentId={}", id);

        try {
            environmentService.destroyEnvironment(id);
            return ResponseEntity.ok(ApiResponse.success("环境已销毁", null));

        } catch (Exception e) {
            log.error("销毁环境失败: environmentId={}", id, e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("DESTROY_FAILED", "销毁环境失败: " + e.getMessage()));
        }
    }
}
