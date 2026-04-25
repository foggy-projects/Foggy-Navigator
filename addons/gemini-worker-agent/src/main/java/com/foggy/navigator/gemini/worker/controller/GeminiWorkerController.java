package com.foggy.navigator.gemini.worker.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.common.annotation.RequireAuth;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.gemini.worker.client.GeminiWorkerClientFactory;
import com.foggy.navigator.spi.worker.WorkerManagementFacade;
import com.foggyframework.core.ex.RX;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/v1/gemini-workers")
@RequireAuth
@Slf4j
@RequiredArgsConstructor
public class GeminiWorkerController {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final WorkerManagementFacade workerManagementFacade;
    private final GeminiWorkerClientFactory clientFactory;

    @GetMapping("/{workerId}/processes")
    public RX<Map<String, Object>> listCliProcesses(@PathVariable String workerId) {
        String userId = UserContext.getCurrentUserId();
        workerManagementFacade.validateWorkerOwnership(userId, workerId);

        var geminiConfig = workerManagementFacade.getGeminiConfig(workerId);
        if (geminiConfig == null || geminiConfig.getBaseUrl() == null || geminiConfig.getBaseUrl().isBlank()) {
            return RX.failA("Worker 未配置 Gemini 服务");
        }

        var client = clientFactory.getOrCreate(workerId, geminiConfig.getBaseUrl(), geminiConfig.getAuthToken());
        try {
            return RX.ok(client.listCliProcesses().block(Duration.ofSeconds(10)));
        } catch (Exception e) {
            log.warn("Failed to list Gemini CLI processes for worker {}: {}", workerId, e.getMessage());
            return RX.failA("获取 Gemini CLI 进程失败: " + e.getMessage());
        }
    }

    @PostMapping("/{workerId}/processes/{pid}/kill")
    public RX<Map<String, Object>> killCliProcess(
            @PathVariable String workerId,
            @PathVariable int pid,
            @RequestBody(required = false) Map<String, Object> body) {
        String userId = UserContext.getCurrentUserId();
        workerManagementFacade.validateWorkerOwnership(userId, workerId);

        var geminiConfig = workerManagementFacade.getGeminiConfig(workerId);
        if (geminiConfig == null || geminiConfig.getBaseUrl() == null || geminiConfig.getBaseUrl().isBlank()) {
            return RX.failA("Worker 未配置 Gemini 服务");
        }

        boolean force = body != null && Boolean.TRUE.equals(body.get("force"));
        var client = clientFactory.getOrCreate(workerId, geminiConfig.getBaseUrl(), geminiConfig.getAuthToken());
        try {
            return RX.ok(client.killCliProcess(pid, force).block(Duration.ofSeconds(10)));
        } catch (Exception e) {
            String detail = extractWorkerErrorDetail(e);
            log.warn("Failed to kill Gemini CLI process {} for worker {}: {}", pid, workerId, detail);
            return RX.failA("终止 Gemini CLI 进程失败: " + detail);
        }
    }

    private String extractWorkerErrorDetail(Exception error) {
        if (error instanceof WebClientResponseException responseException) {
            String body = responseException.getResponseBodyAsString();
            if (body != null && !body.isBlank()) {
                try {
                    Map<String, Object> payload = OBJECT_MAPPER.readValue(body, new TypeReference<>() {});
                    String detail = Stream.of(payload.get("message"), payload.get("error"))
                            .filter(Objects::nonNull)
                            .map(Object::toString)
                            .filter(text -> !text.isBlank())
                            .distinct()
                            .collect(Collectors.joining(" | "));
                    if (!detail.isBlank()) {
                        return detail;
                    }
                } catch (Exception ignored) {
                    return body;
                }
                return body;
            }
        }
        return error.getMessage();
    }
}
