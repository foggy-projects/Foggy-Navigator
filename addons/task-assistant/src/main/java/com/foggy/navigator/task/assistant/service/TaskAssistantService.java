package com.foggy.navigator.task.assistant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.common.dto.a2a.*;
import com.foggy.navigator.spi.assistant.TaskAssistantConfig;
import com.foggy.navigator.spi.assistant.TaskAssistantFacade;
import com.foggy.navigator.common.enums.LlmModelCategory;
import com.foggy.navigator.spi.config.LlmModelManager;
import com.foggy.navigator.task.assistant.entity.TaskAssistantConfigEntity;
import com.foggy.navigator.task.assistant.repository.TaskAssistantConfigRepository;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 任务助手服务 — TaskAssistantFacade 的 LangChain4j 实现
 * 直接调用 LLM 生成智能通知，无需远程 Claude Worker
 */
@Slf4j
@Service
public class TaskAssistantService implements TaskAssistantFacade {

    private static final String SKILL_PATH = "platform-skills/task-assistant/SKILL.md";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final TaskAssistantConfigRepository configRepository;
    @Nullable
    private final LlmModelManager llmModelManager;

    @Value("${agent.llm.openai.api-key:}")
    private String fallbackApiKey;

    @Value("${agent.llm.openai.base-url:https://api.openai.com/v1}")
    private String fallbackBaseUrl;

    @Value("${agent.llm.openai.model:gpt-4}")
    private String fallbackModel;

    private String cachedSkillPrompt;

    public TaskAssistantService(TaskAssistantConfigRepository configRepository,
                                 @Nullable LlmModelManager llmModelManager) {
        this.configRepository = configRepository;
        this.llmModelManager = llmModelManager;
    }

    @Override
    public Optional<A2aMessage> sendEvents(String userId, A2aMessage events) {
        TaskAssistantConfigEntity config = configRepository.findByUserId(userId).orElse(null);
        if (config == null || !Boolean.TRUE.equals(config.getEnabled())) {
            log.debug("Task assistant not enabled for userId={}", userId);
            return Optional.empty();
        }

        try {
            String eventJson = OBJECT_MAPPER.writeValueAsString(events.getParts());
            String userPrompt = "Platform events:\n" + eventJson;

            ChatLanguageModel chatModel = buildChatModel(userId);
            List<ChatMessage> messages = List.of(
                    SystemMessage.from(getSkillPrompt()),
                    UserMessage.from(userPrompt)
            );

            ChatResponse response = chatModel.chat(ChatRequest.builder().messages(messages).build());
            String responseText = response.aiMessage().text();

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
        // workerId and directoryId are no longer used in LangChain4j implementation
        // but we keep the SPI signature for compatibility
        TaskAssistantConfigEntity entity = configRepository.findByUserId(userId)
                .orElseGet(() -> {
                    TaskAssistantConfigEntity e = new TaskAssistantConfigEntity();
                    e.setUserId(userId);
                    e.setEnabled(false);
                    return e;
                });

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

    // --- Private helpers ---

    private ChatLanguageModel buildChatModel(String userId) {
        String apiKey = fallbackApiKey;
        String baseUrl = fallbackBaseUrl;
        String modelName = fallbackModel;

        // Try to resolve from platform model config (GENERAL category)
        if (llmModelManager != null) {
            // Use "default" as tenantId — task-assistant is per-user, resolve via default tenant
            var modelConfig = llmModelManager.getDefaultModel("default", LlmModelCategory.GENERAL);
            if (modelConfig.isPresent()) {
                var mc = modelConfig.get();
                baseUrl = mc.getBaseUrl();
                modelName = mc.getModelName();
                try {
                    apiKey = llmModelManager.getDecryptedApiKey(mc.getId());
                } catch (Exception e) {
                    log.warn("Failed to decrypt API key for model {}, using fallback", mc.getId());
                }
            }
        }

        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .temperature(0.3)
                .timeout(Duration.ofSeconds(30))
                .build();
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
                .enabled(entity.getEnabled())
                .foggySessionId(entity.getFoggySessionId())
                .build();
    }
}
