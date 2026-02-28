package com.foggy.navigator.task.assistant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.common.dto.a2a.*;
import com.foggy.navigator.task.assistant.spi.TaskAssistantConfig;
import com.foggy.navigator.task.assistant.spi.TaskAssistantFacade;
import com.foggy.navigator.spi.claude.ClaudeWorkerFacade;
import com.foggy.navigator.task.assistant.entity.TaskAssistantConfigEntity;
import com.foggy.navigator.task.assistant.repository.TaskAssistantConfigRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 任务助手服务 — 基于 Claude Code Worker 的 AI 编程会话管理助手
 * 通过 ClaudeWorkerFacade.syncQuery() 调用远程 Worker 进行纯文本分析
 */
@Slf4j
@Service
public class TaskAssistantService implements TaskAssistantFacade {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final TaskAssistantConfigRepository configRepository;
    @Nullable
    private final ClaudeWorkerFacade claudeWorkerFacade;

    public TaskAssistantService(TaskAssistantConfigRepository configRepository,
                                 @Nullable ClaudeWorkerFacade claudeWorkerFacade) {
        this.configRepository = configRepository;
        this.claudeWorkerFacade = claudeWorkerFacade;
    }

    @Override
    public Optional<A2aMessage> sendEvents(String userId, A2aMessage events) {
        TaskAssistantConfigEntity config = configRepository.findByUserId(userId).orElse(null);
        if (config == null || !Boolean.TRUE.equals(config.getEnabled())
                || claudeWorkerFacade == null
                || config.getWorkerId() == null || config.getCwd() == null) {
            log.debug("Task assistant not ready for userId={}", userId);
            return Optional.empty();
        }

        try {
            String eventJson = OBJECT_MAPPER.writeValueAsString(events.getParts());
            String prompt = "Analyze these platform events and generate a notification:\n\n" + eventJson;

            Map<String, Object> result = claudeWorkerFacade.syncQuery(
                    userId, config.getWorkerId(), prompt,
                    config.getCwd(), config.getClaudeSessionId());

            // 更新 claudeSessionId（对话记忆连续性）
            String newSessionId = (String) result.get("claudeSessionId");
            if (newSessionId != null && !newSessionId.equals(config.getClaudeSessionId())) {
                config.setClaudeSessionId(newSessionId);
                configRepository.save(config);
            }

            String error = (String) result.get("error");
            if (error != null) {
                log.warn("Task assistant syncQuery error for userId={}: {}", userId, error);
                return Optional.empty();
            }

            String responseText = (String) result.get("resultText");
            if (responseText == null || responseText.isBlank()) {
                log.warn("Task assistant returned empty response for userId={}", userId);
                return Optional.empty();
            }

            return parseNotificationResponse(responseText);

        } catch (Exception e) {
            log.error("Task assistant sendEvents failed for userId={}", userId, e);
            return Optional.empty();
        }
    }

    @Override
    public A2aAgentCard getAgentCard() {
        return A2aAgentCard.builder()
                .name("Task Assistant")
                .description("AI-powered programming session management assistant for Foggy Navigator")
                .version("2.0.0")
                .skills(List.of(
                        A2aAgentSkill.builder()
                                .id("task-notification")
                                .name("Task Notification")
                                .description("Generate smart notifications for platform events")
                                .tags(List.of("notification", "task", "monitoring"))
                                .examples(List.of("3 tasks completed, 1 failed"))
                                .build()
                ))
                .capabilities(A2aAgentCapabilities.builder()
                        .streaming(false)
                        .pushNotifications(true)
                        .stateTransitionHistory(false)
                        .build())
                .build();
    }

    @Override
    public boolean isAvailable(String userId) {
        if (claudeWorkerFacade == null) return false;
        return configRepository.findByUserId(userId)
                .map(config -> Boolean.TRUE.equals(config.getEnabled())
                        && config.getWorkerId() != null
                        && config.getCwd() != null)
                .orElse(false);
    }

    @Override
    public Optional<TaskAssistantConfig> getConfig(String userId) {
        return configRepository.findByUserId(userId).map(this::toDTO);
    }

    @Override
    @Transactional
    public TaskAssistantConfig createOrUpdate(String userId, String workerId, String directoryPath) {
        if (claudeWorkerFacade == null) {
            throw new IllegalStateException("Claude Worker module is not available");
        }

        // 验证 Worker 存在
        claudeWorkerFacade.getWorker(userId, workerId);

        // 展开默认路径
        String path = (directoryPath == null || directoryPath.isBlank())
                ? "~/foggy-assistant" : directoryPath;

        // 初始化工作目录（创建 CLAUDE.md + settings.json）
        Map<String, String> initFiles = loadInitFiles();
        claudeWorkerFacade.initDirectory(userId, workerId, path, initFiles);

        // 创建/更新配置
        TaskAssistantConfigEntity entity = configRepository.findByUserId(userId)
                .orElseGet(() -> {
                    TaskAssistantConfigEntity e = new TaskAssistantConfigEntity();
                    e.setUserId(userId);
                    return e;
                });
        entity.setWorkerId(workerId);
        entity.setCwd(path);
        entity.setEnabled(true);
        entity.setClaudeSessionId(null); // 新会话

        return toDTO(configRepository.save(entity));
    }

    @Override
    @Transactional
    public void setEnabled(String userId, boolean enabled) {
        TaskAssistantConfigEntity entity = configRepository.findByUserId(userId)
                .orElseGet(() -> {
                    TaskAssistantConfigEntity e = new TaskAssistantConfigEntity();
                    e.setUserId(userId);
                    return e;
                });
        entity.setEnabled(enabled);
        configRepository.save(entity);
    }

    @Override
    @Transactional
    public void delete(String userId) {
        configRepository.findByUserId(userId).ifPresent(configRepository::delete);
    }

    // --- Private helpers ---

    @SuppressWarnings("unchecked")
    private Optional<A2aMessage> parseNotificationResponse(String responseText) {
        try {
            String json = responseText.strip();
            if (json.startsWith("```")) {
                int start = json.indexOf('\n') + 1;
                int end = json.lastIndexOf("```");
                if (start > 0 && end > start) {
                    json = json.substring(start, end).strip();
                }
            }

            Map<String, Object> parsed = OBJECT_MAPPER.readValue(json, Map.class);
            A2aMessage message = A2aMessage.agent(List.of(
                    A2aPart.data(parsed)
            ));
            return Optional.of(message);
        } catch (Exception e) {
            log.warn("Failed to parse assistant notification JSON, returning raw text: {}", e.getMessage());
            A2aMessage message = A2aMessage.agent(List.of(
                    A2aPart.text(responseText)
            ));
            return Optional.of(message);
        }
    }

    private Map<String, String> loadInitFiles() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("CLAUDE.md", loadResource("assistant-init/CLAUDE.md"));
        files.put(".claude/settings.json", loadResource("assistant-init/.claude/settings.json"));
        return files;
    }

    private String loadResource(String path) {
        try {
            ClassPathResource resource = new ClassPathResource(path);
            return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to load resource: {}", path, e);
            throw new RuntimeException("Failed to load init file: " + path, e);
        }
    }

    private TaskAssistantConfig toDTO(TaskAssistantConfigEntity entity) {
        return TaskAssistantConfig.builder()
                .userId(entity.getUserId())
                .enabled(entity.getEnabled())
                .foggySessionId(entity.getFoggySessionId())
                .workerId(entity.getWorkerId())
                .directoryId(entity.getDirectoryId())
                .claudeSessionId(entity.getClaudeSessionId())
                .cwd(entity.getCwd())
                .build();
    }
}
