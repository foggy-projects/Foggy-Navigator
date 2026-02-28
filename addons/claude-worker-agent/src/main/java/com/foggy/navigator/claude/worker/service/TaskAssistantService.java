package com.foggy.navigator.claude.worker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.claude.worker.client.ClaudeWorkerClient;
import com.foggy.navigator.claude.worker.model.entity.ClaudeWorkerEntity;
import com.foggy.navigator.claude.worker.model.entity.TaskAssistantConfigEntity;
import com.foggy.navigator.claude.worker.model.entity.WorkingDirectoryEntity;
import com.foggy.navigator.claude.worker.model.event.WorkerEvent;
import com.foggy.navigator.claude.worker.repository.TaskAssistantConfigRepository;
import com.foggy.navigator.claude.worker.repository.WorkingDirectoryRepository;
import com.foggy.navigator.common.dto.a2a.*;
import com.foggy.navigator.spi.assistant.TaskAssistantConfig;
import com.foggy.navigator.spi.assistant.TaskAssistantFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 任务助手服务 — TaskAssistantFacade 的 Claude Worker 实现
 * 使用专用 Claude Code 会话生成智能通知
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskAssistantService implements TaskAssistantFacade {

    private static final String SKILL_PATH = "platform-skills/task-assistant/SKILL.md";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final TaskAssistantConfigRepository configRepository;
    private final ClaudeWorkerService workerService;
    private final WorkingDirectoryRepository directoryRepository;

    private String cachedSkillPrompt;

    @Override
    public Optional<A2aMessage> sendEvents(String userId, A2aMessage events) {
        TaskAssistantConfigEntity config = configRepository.findByUserId(userId).orElse(null);
        if (config == null || !Boolean.TRUE.equals(config.getEnabled())) {
            log.debug("Task assistant not enabled for userId={}", userId);
            return Optional.empty();
        }

        try {
            // 构建 prompt：SKILL 指令 + 事件数据
            String eventJson = OBJECT_MAPPER.writeValueAsString(events.getParts());
            String prompt = getSkillPrompt() + "\n\nPlatform events:\n" + eventJson;

            // 获取 Worker 客户端
            ClaudeWorkerEntity worker = workerService.getWorkerEntity(config.getWorkerId());
            ClaudeWorkerClient client = workerService.createClient(worker);

            // 解析工作目录
            String cwd = resolveWorkingDirectory(config);

            // 同步调用 Worker（收集 SSE 流，提取最终结果）
            String[] result = collectStreamResult(client, prompt, cwd, config.getClaudeSessionId());
            String responseText = result[0];
            String newSessionId = result[1];

            if (responseText == null || responseText.isBlank()) {
                log.warn("Task assistant returned empty response for userId={}", userId);
                return Optional.empty();
            }

            // 更新 claudeSessionId（可能首次创建）
            updateSessionId(userId, newSessionId);

            // 解析 JSON 响应为通知消息
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
                .description("AI-powered task notification assistant for Foggy Navigator")
                .version("1.0.0")
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
        return configRepository.findByUserId(userId)
                .map(config -> Boolean.TRUE.equals(config.getEnabled()))
                .orElse(false);
    }

    @Override
    public Optional<TaskAssistantConfig> getConfig(String userId) {
        return configRepository.findByUserId(userId).map(this::toDTO);
    }

    @Override
    @Transactional
    public TaskAssistantConfig configure(String userId, String workerId, String directoryId) {
        // 验证 Worker 存在
        workerService.getWorkerEntity(workerId);

        TaskAssistantConfigEntity entity = configRepository.findByUserId(userId)
                .orElseGet(() -> {
                    TaskAssistantConfigEntity e = new TaskAssistantConfigEntity();
                    e.setUserId(userId);
                    e.setEnabled(false);
                    return e;
                });

        entity.setWorkerId(workerId);
        entity.setDirectoryId(directoryId);
        // 更换 Worker/Directory 时清除旧会话
        entity.setClaudeSessionId(null);

        return toDTO(configRepository.save(entity));
    }

    @Override
    @Transactional
    public void setEnabled(String userId, boolean enabled) {
        configRepository.findByUserId(userId).ifPresent(entity -> {
            entity.setEnabled(enabled);
            configRepository.save(entity);
        });
    }

    // --- Private helpers ---

    /**
     * 收集 Worker SSE 流的最终结果
     * @return [responseText, claudeSessionId]
     */
    private String[] collectStreamResult(ClaudeWorkerClient client, String prompt,
                                          String cwd, String claudeSessionId) {
        try {
            List<ServerSentEvent<String>> events = client.streamQuery(
                    prompt, cwd, claudeSessionId,
                    null, 3, null
            ).collectList().block(TIMEOUT);

            if (events == null) {
                return new String[]{null, null};
            }

            // 提取最终 result 事件的 content 和 sessionId
            for (int i = events.size() - 1; i >= 0; i--) {
                String data = events.get(i).data();
                if (data == null) continue;
                try {
                    WorkerEvent event = OBJECT_MAPPER.readValue(data, WorkerEvent.class);
                    if ("result".equals(event.getType())) {
                        String text = event.getContent() != null ? event.getContent() : event.getResult();
                        return new String[]{text, event.getSessionId()};
                    }
                } catch (Exception ignored) {
                    // skip non-JSON events
                }
            }
            return new String[]{null, null};
        } catch (Exception e) {
            log.error("Failed to collect stream result from Worker", e);
            return new String[]{null, null};
        }
    }

    @Transactional
    void updateSessionId(String userId, String claudeSessionId) {
        if (claudeSessionId != null) {
            configRepository.findByUserId(userId).ifPresent(entity -> {
                if (!claudeSessionId.equals(entity.getClaudeSessionId())) {
                    entity.setClaudeSessionId(claudeSessionId);
                    configRepository.save(entity);
                }
            });
        }
    }

    private String resolveWorkingDirectory(TaskAssistantConfigEntity config) {
        if (config.getDirectoryId() != null) {
            return directoryRepository.findByDirectoryId(config.getDirectoryId())
                    .map(WorkingDirectoryEntity::getPath)
                    .orElse(null);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Optional<A2aMessage> parseNotificationResponse(String responseText) {
        try {
            // 清理可能的 markdown 代码围栏
            String json = responseText.strip();
            if (json.startsWith("```")) {
                int start = json.indexOf('\n') + 1;
                int end = json.lastIndexOf("```");
                if (start > 0 && end > start) {
                    json = json.substring(start, end).strip();
                }
            }

            Map<String, Object> parsed = OBJECT_MAPPER.readValue(json, Map.class);

            // 构建 A2aMessage 响应
            A2aMessage message = A2aMessage.agent(List.of(
                    A2aPart.data(parsed)
            ));
            return Optional.of(message);
        } catch (Exception e) {
            log.warn("Failed to parse assistant notification JSON, returning raw text: {}", e.getMessage());
            // 回退：将原始文本作为 text part 返回
            A2aMessage message = A2aMessage.agent(List.of(
                    A2aPart.text(responseText)
            ));
            return Optional.of(message);
        }
    }

    private String getSkillPrompt() {
        if (cachedSkillPrompt == null) {
            try {
                ClassPathResource resource = new ClassPathResource(SKILL_PATH);
                cachedSkillPrompt = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.error("Failed to load task assistant SKILL.md", e);
                cachedSkillPrompt = "You are a task notification assistant. Generate concise JSON notifications.";
            }
        }
        return cachedSkillPrompt;
    }

    private TaskAssistantConfig toDTO(TaskAssistantConfigEntity entity) {
        return TaskAssistantConfig.builder()
                .userId(entity.getUserId())
                .workerId(entity.getWorkerId())
                .directoryId(entity.getDirectoryId())
                .claudeSessionId(entity.getClaudeSessionId())
                .foggySessionId(entity.getFoggySessionId())
                .enabled(entity.getEnabled())
                .build();
    }
}
