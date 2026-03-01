package com.foggy.navigator.task.assistant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.common.dto.a2a.*;
import com.foggy.navigator.task.assistant.spi.TaskAssistantConfig;
import com.foggy.navigator.task.assistant.spi.TaskAssistantFacade;
import com.foggy.navigator.spi.claude.ClaudeWorkerFacade;
import com.foggy.navigator.spi.notification.UserNotificationSender;
import com.foggy.navigator.spi.task.AgentTaskManager;
import com.foggy.navigator.agent.framework.session.SessionCreateRequest;
import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.task.assistant.entity.TaskAssistantConfigEntity;
import com.foggy.navigator.task.assistant.repository.TaskAssistantConfigRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 任务助手服务 — 基于 Claude Code Worker 的 AI 编程会话管理助手
 * 通过 ClaudeWorkerFacade.syncQuery() 调用远程 Worker 进行纯文本分析
 *
 * 每次执行（事件处理 / 日报）都创建 AgentTask 记录，可在 /tasks 页查看。
 * 使用同一个 Claude 会话持续累积上下文（claudeSessionId）。
 */
@Slf4j
@Service
public class TaskAssistantService implements TaskAssistantFacade {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int PROMPT_SUMMARY_MAX_LENGTH = 200;

    private final TaskAssistantConfigRepository configRepository;
    private final UserNotificationSender notificationSender;
    @Nullable
    private final ClaudeWorkerFacade claudeWorkerFacade;
    @Nullable
    private final AgentTaskManager agentTaskManager;
    @Nullable
    private final SessionManager sessionManager;

    public TaskAssistantService(TaskAssistantConfigRepository configRepository,
                                 UserNotificationSender notificationSender,
                                 @Nullable ClaudeWorkerFacade claudeWorkerFacade,
                                 @Nullable AgentTaskManager agentTaskManager,
                                 @Nullable SessionManager sessionManager) {
        this.configRepository = configRepository;
        this.notificationSender = notificationSender;
        this.claudeWorkerFacade = claudeWorkerFacade;
        this.agentTaskManager = agentTaskManager;
        this.sessionManager = sessionManager;
    }

    // --- 新增：正式任务记录的事件处理 ---

    @Override
    public void processEvents(String userId, List<Map<String, Object>> events) {
        TaskAssistantConfigEntity config = configRepository.findByUserId(userId).orElse(null);
        if (config == null || !Boolean.TRUE.equals(config.getEnabled())
                || claudeWorkerFacade == null
                || config.getWorkerId() == null || config.getCwd() == null) {
            log.debug("Task assistant not ready for userId={}", userId);
            return;
        }

        // 1. 创建 AgentTask（正式记录）
        String agentTaskId = null;
        if (agentTaskManager != null) {
            String promptSummary = truncate(events.size() + " platform events", PROMPT_SUMMARY_MAX_LENGTH);
            agentTaskId = agentTaskManager.createTask(
                    ensureFoggySession(config),
                    userId,
                    "task-assistant",   // sourceAgentId
                    "task-assistant",   // targetAgentId
                    "ASSISTANT_EVENT",  // taskType
                    promptSummary,
                    null,               // targetSessionId
                    null                // externalTaskId
            );
        }

        try {
            // 2. 构建 prompt + 调用 syncQuery
            String eventJson = OBJECT_MAPPER.writeValueAsString(events);
            String prompt = "分析以下平台事件并生成通知：\n\n" + eventJson;

            Map<String, Object> result = claudeWorkerFacade.syncQuery(
                    userId, config.getWorkerId(), prompt,
                    config.getCwd(), config.getClaudeSessionId(), 1,
                    config.getModel());

            // 3. 更新 claudeSessionId（对话记忆连续性）
            updateSessionId(config, result);

            // 4. 完成 AgentTask
            String error = (String) result.get("error");
            if (agentTaskId != null) {
                agentTaskManager.completeTask(agentTaskId,
                        error != null ? "FAILED" : "COMPLETED",
                        truncate(resultOrError(result), PROMPT_SUMMARY_MAX_LENGTH));
            }

            // 5. 推送通知
            if (error == null) {
                String responseText = (String) result.get("resultText");
                if (responseText != null && !responseText.isBlank()) {
                    parseNotificationResponse(responseText)
                            .ifPresent(msg -> notificationSender.sendNotification(userId, msg));
                }
            } else {
                log.warn("Task assistant syncQuery error for userId={}: {}", userId, error);
            }
        } catch (Exception e) {
            log.error("Task assistant processEvents failed for userId={}", userId, e);
            if (agentTaskId != null) {
                agentTaskManager.completeTask(agentTaskId, "FAILED",
                        truncate(e.getMessage(), PROMPT_SUMMARY_MAX_LENGTH));
            }
        }
    }

    // --- 旧接口（已废弃，内部委托 processEvents） ---

    @SuppressWarnings("deprecation")
    @Override
    public Optional<A2aMessage> sendEvents(String userId, A2aMessage events) {
        // 兼容旧调用：提取事件数据，委托给 processEvents
        try {
            List<Map<String, Object>> eventList = new ArrayList<>();
            for (A2aPart part : events.getParts()) {
                if (part.getData() != null) {
                    Object evts = part.getData().get("events");
                    if (evts instanceof List<?> list) {
                        for (Object item : list) {
                            if (item instanceof Map<?, ?> map) {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> eventData = (Map<String, Object>) map;
                                eventList.add(eventData);
                            }
                        }
                    }
                }
            }
            processEvents(userId, eventList);
        } catch (Exception e) {
            log.error("sendEvents delegation failed for userId={}", userId, e);
        }
        return Optional.empty();
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
    public TaskAssistantConfig createOrUpdate(String userId, String workerId, String directoryPath, String model) {
        if (claudeWorkerFacade == null) {
            throw new IllegalStateException("Claude Worker module is not available");
        }

        // 验证 Worker 存在
        claudeWorkerFacade.getWorker(userId, workerId);

        // 展开默认路径
        String path = (directoryPath == null || directoryPath.isBlank())
                ? "~/foggy-assistant" : directoryPath;

        // 初始化工作目录（创建 CLAUDE.md + settings.json）+ 注册为工作目录
        Map<String, String> initFiles = loadInitFiles();
        String directoryId = claudeWorkerFacade.initDirectory(userId, workerId, path, initFiles);

        // 创建/更新配置
        TaskAssistantConfigEntity entity = configRepository.findByUserId(userId)
                .orElseGet(() -> {
                    TaskAssistantConfigEntity e = new TaskAssistantConfigEntity();
                    e.setUserId(userId);
                    return e;
                });
        entity.setWorkerId(workerId);
        entity.setCwd(path);
        entity.setDirectoryId(directoryId);
        entity.setModel(model);
        entity.setEnabled(true);
        entity.setClaudeSessionId(null); // 新会话

        // 确保关联 Foggy 会话
        ensureFoggySession(entity);

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

    // --- 定时每日总结 ---

    /**
     * 每日 23:30 自动触发每日总结
     * 遍历所有已启用的助手配置，调用 syncQuery(maxTurns=5) 让助手：
     * 1. 读取 progress.md 汇总当天活动
     * 2. 生成每日归档到 notes/daily/YYYY-MM-DD.md
     * 3. 返回通知 JSON 推送给用户
     *
     * 每次执行创建 AgentTask(DAILY_SUMMARY) 记录。
     */
    @Scheduled(cron = "0 30 23 * * *")
    public void dailySummary() {
        if (claudeWorkerFacade == null) {
            return;
        }

        String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        List<TaskAssistantConfigEntity> configs = configRepository.findAllByEnabledTrue();

        for (TaskAssistantConfigEntity config : configs) {
            if (config.getWorkerId() == null || config.getCwd() == null) {
                continue;
            }

            try {
                executeDailySummary(config, today);
            } catch (Exception e) {
                log.error("Daily summary failed for userId={}", config.getUserId(), e);
            }
        }
    }

    private void executeDailySummary(TaskAssistantConfigEntity config, String today) {
        // 1. 创建 AgentTask
        String agentTaskId = null;
        if (agentTaskManager != null) {
            agentTaskId = agentTaskManager.createTask(
                    ensureFoggySession(config),
                    config.getUserId(),
                    "task-assistant",   // sourceAgentId
                    "task-assistant",   // targetAgentId
                    "DAILY_SUMMARY",    // taskType
                    "每日总结 " + today,
                    null,               // targetSessionId
                    null                // externalTaskId
            );
        }

        try {
            // 2. syncQuery(maxTurns=5)
            String prompt = "现在是每日总结时间（" + today + "）。请执行以下操作：\n\n"
                    + "1. 读取 `progress.md`，汇总今天记录的所有任务活动\n"
                    + "2. 将今天的总结归档到 `notes/daily/" + today + ".md`，包含：\n"
                    + "   - 今日完成的任务清单\n"
                    + "   - 今日失败/需关注的任务\n"
                    + "   - 关键统计（成功率、总耗时等）\n"
                    + "3. 清理 `progress.md` 中已归档的今日条目，保留未完成的任务\n"
                    + "4. 返回通知 JSON（severity 根据今天的整体情况判断）\n\n"
                    + "如果今天没有任何任务活动记录，返回 severity=info 的简短通知即可。";

            Map<String, Object> result = claudeWorkerFacade.syncQuery(
                    config.getUserId(), config.getWorkerId(), prompt,
                    config.getCwd(), config.getClaudeSessionId(), 5,
                    config.getModel());

            // 3. 更新 claudeSessionId
            updateSessionId(config, result);

            // 4. 完成 AgentTask
            String error = (String) result.get("error");
            if (agentTaskId != null) {
                agentTaskManager.completeTask(agentTaskId,
                        error != null ? "FAILED" : "COMPLETED",
                        truncate(resultOrError(result), PROMPT_SUMMARY_MAX_LENGTH));
            }

            // 5. 推送通知
            if (error != null) {
                log.warn("Daily summary syncQuery error for userId={}: {}", config.getUserId(), error);
                return;
            }

            String responseText = (String) result.get("resultText");
            if (responseText == null || responseText.isBlank()) {
                log.warn("Daily summary returned empty response for userId={}", config.getUserId());
                return;
            }

            parseNotificationResponse(responseText).ifPresent(msg ->
                    notificationSender.sendNotification(config.getUserId(), msg));

            log.info("Daily summary completed for userId={}, date={}", config.getUserId(), today);
        } catch (Exception e) {
            log.error("Daily summary execution failed for userId={}", config.getUserId(), e);
            if (agentTaskId != null) {
                agentTaskManager.completeTask(agentTaskId, "FAILED",
                        truncate(e.getMessage(), PROMPT_SUMMARY_MAX_LENGTH));
            }
        }
    }

    // --- Private helpers ---

    /**
     * 确保 config 持有一个 Foggy 会话 ID。
     * 已有则直接返回；没有则懒创建并持久化。创建失败时 fallback "task-assistant"。
     */
    private String ensureFoggySession(TaskAssistantConfigEntity config) {
        if (config.getFoggySessionId() != null) {
            return config.getFoggySessionId();
        }
        if (sessionManager == null) {
            return "task-assistant";
        }
        try {
            String foggySessionId = sessionManager.createSession(SessionCreateRequest.builder()
                    .userId(config.getUserId())
                    .agentId("task-assistant")
                    .taskName("任务助手")
                    .build());
            config.setFoggySessionId(foggySessionId);
            configRepository.save(config);
            log.info("Lazy-created foggy session {} for userId={}", foggySessionId, config.getUserId());
            return foggySessionId;
        } catch (Exception e) {
            log.warn("Failed to create foggy session for userId={}, falling back", config.getUserId(), e);
            return "task-assistant";
        }
    }

    private void updateSessionId(TaskAssistantConfigEntity config, Map<String, Object> result) {
        String newSessionId = (String) result.get("claudeSessionId");
        if (newSessionId != null && !newSessionId.equals(config.getClaudeSessionId())) {
            config.setClaudeSessionId(newSessionId);
            configRepository.save(config);
        }
    }

    private String resultOrError(Map<String, Object> result) {
        String error = (String) result.get("error");
        if (error != null) return "Error: " + error;
        String text = (String) result.get("resultText");
        return text != null ? text : "(no response)";
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "(null)";
        return text.length() <= maxLength ? text : text.substring(0, maxLength - 3) + "...";
    }

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
                .model(entity.getModel())
                .build();
    }
}
