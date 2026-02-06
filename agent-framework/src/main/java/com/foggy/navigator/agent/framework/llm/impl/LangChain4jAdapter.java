package com.foggy.navigator.agent.framework.llm.impl;

import com.foggy.navigator.agent.framework.llm.*;
import com.foggy.navigator.agent.framework.tool.ToolDefinition;
import dev.langchain4j.agent.tool.ToolParameters;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * LangChain4j实现的LLM适配器
 */
@Slf4j
@Component
public class LangChain4jAdapter implements LlmAdapter {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${agent.llm.openai.api-key:}")
    private String openaiApiKey;

    @Value("${agent.llm.openai.base-url:https://api.openai.com/v1}")
    private String openaiBaseUrl;

    @Override
    public LlmResponse chat(LlmRequest request) {
        ChatLanguageModel model = buildChatModel(request);
        List<ChatMessage> messages = convertMessages(request);

        ChatRequest chatRequest = ChatRequest.builder()
                .messages(messages)
                .build();

        ChatResponse response = model.chat(chatRequest);
        AiMessage aiMessage = response.aiMessage();

        return LlmResponse.builder()
                .content(aiMessage.text())
                .inputTokens(response.tokenUsage() != null ? response.tokenUsage().inputTokenCount() : 0)
                .outputTokens(response.tokenUsage() != null ? response.tokenUsage().outputTokenCount() : 0)
                .finishReason(response.finishReason() != null ? response.finishReason().name() : null)
                .build();
    }

    @Override
    public void chatStream(LlmRequest request, LlmStreamHandler handler) {
        try {
            StreamingChatLanguageModel streamingModel = buildStreamingChatModel(request);
            List<ChatMessage> messages = convertMessages(request);
            List<ToolSpecification> toolSpecs = convertTools(request.getTools());
            StringBuilder fullContent = new StringBuilder();
            CountDownLatch latch = new CountDownLatch(1);

            log.debug("Starting stream with {} tools", toolSpecs != null ? toolSpecs.size() : 0);

            // 使用带 tools 的 generate 方法
            StreamingResponseHandler<AiMessage> responseHandler = new StreamingResponseHandler<>() {
                @Override
                public void onNext(String token) {
                    fullContent.append(token);
                    handler.onText(token);
                }

                @Override
                public void onComplete(Response<AiMessage> response) {
                    AiMessage aiMessage = response.content();
                    List<ToolCall> toolCalls = null;
                    String finishReasonStr = response.finishReason() != null ? response.finishReason().name() : null;

                    // 调试日志
                    log.debug("onComplete - aiMessage: {}", aiMessage);
                    log.debug("onComplete - finishReason: {}", finishReasonStr);

                    // 提取 tool calls（如果有）
                    if (aiMessage != null && aiMessage.hasToolExecutionRequests()) {
                        log.info("Found {} tool execution requests from streaming", aiMessage.toolExecutionRequests().size());
                        toolCalls = aiMessage.toolExecutionRequests().stream()
                                .map(req -> ToolCall.builder()
                                        .id(req.id())
                                        .name(req.name())
                                        .arguments(parseArguments(req.arguments()))
                                        .build())
                                .toList();

                        // 通知 handler 每个 tool call
                        for (ToolCall tc : toolCalls) {
                            handler.onToolCall(tc);
                        }
                    }
                    // LangChain4j 流式处理的 bug workaround：
                    // 当 finishReason 是 TOOL_EXECUTION 但 toolExecutionRequests 为 null 时，
                    // 回退到非流式请求来获取 tool calls
                    else if ("TOOL_EXECUTION".equals(finishReasonStr)) {
                        log.warn("finishReason is TOOL_EXECUTION but toolExecutionRequests is null, falling back to non-streaming request");
                        try {
                            toolCalls = fetchToolCallsWithNonStreaming(request);
                            if (toolCalls != null) {
                                log.info("Fetched {} tool calls via fallback", toolCalls.size());
                                for (ToolCall tc : toolCalls) {
                                    handler.onToolCall(tc);
                                }
                            }
                        } catch (Exception e) {
                            log.error("Failed to fetch tool calls via fallback", e);
                        }
                    }

                    LlmResponse llmResponse = LlmResponse.builder()
                            .content(fullContent.toString())
                            .toolCalls(toolCalls)
                            .inputTokens(response.tokenUsage() != null ? response.tokenUsage().inputTokenCount() : 0)
                            .outputTokens(response.tokenUsage() != null ? response.tokenUsage().outputTokenCount() : 0)
                            .finishReason(finishReasonStr)
                            .build();
                    handler.onComplete(llmResponse);
                    latch.countDown();
                }

                @Override
                public void onError(Throwable error) {
                    handler.onError(error);
                    latch.countDown();
                }
            };

            // 根据是否有 tools 选择不同的 generate 方法
            if (toolSpecs != null && !toolSpecs.isEmpty()) {
                streamingModel.generate(messages, toolSpecs, responseHandler);
            } else {
                streamingModel.generate(messages, responseHandler);
            }

            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            handler.onError(e);
        } catch (Exception e) {
            handler.onError(e);
        }
    }

    @Override
    public String getName() {
        return "langchain4j";
    }

    @Override
    public boolean supportsModel(String model) {
        return model != null && (
                model.startsWith("gpt-") ||
                model.startsWith("claude-") ||
                model.startsWith("o1-") ||
                model.startsWith("glm-") ||
                model.startsWith("qwen-") ||
                model.contains("deepseek")
        );
    }

    private ChatLanguageModel buildChatModel(LlmRequest request) {
        return OpenAiChatModel.builder()
                .apiKey(openaiApiKey)
                .baseUrl(openaiBaseUrl)
                .modelName(request.getModel() != null ? request.getModel() : "gpt-4")
                .temperature(request.getTemperature())
                .build();
    }

    private StreamingChatLanguageModel buildStreamingChatModel(LlmRequest request) {
        return OpenAiStreamingChatModel.builder()
                .apiKey(openaiApiKey)
                .baseUrl(openaiBaseUrl)
                .modelName(request.getModel() != null ? request.getModel() : "gpt-4")
                .temperature(request.getTemperature())
                .build();
    }

    private List<ChatMessage> convertMessages(LlmRequest request) {
        List<ChatMessage> messages = new ArrayList<>();

        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isBlank()) {
            messages.add(SystemMessage.from(request.getSystemPrompt()));
        }

        if (request.getMessages() != null) {
            for (LlmMessage msg : request.getMessages()) {
                switch (msg.getRole()) {
                    case "system" -> messages.add(SystemMessage.from(msg.getContent()));
                    case "user" -> messages.add(UserMessage.from(msg.getContent()));
                    case "assistant" -> messages.add(AiMessage.from(msg.getContent()));
                }
            }
        }

        return messages;
    }

    /**
     * 当流式处理无法获取 tool calls 时，回退到非流式请求
     * 这是 LangChain4j 流式处理 bug 的 workaround
     */
    private List<ToolCall> fetchToolCallsWithNonStreaming(LlmRequest request) {
        try {
            ChatLanguageModel model = buildChatModel(request);
            List<ChatMessage> messages = convertMessages(request);

            ChatRequest chatRequest = ChatRequest.builder()
                    .messages(messages)
                    .build();

            ChatResponse response = model.chat(chatRequest);
            AiMessage aiMessage = response.aiMessage();

            if (aiMessage != null && aiMessage.hasToolExecutionRequests()) {
                return aiMessage.toolExecutionRequests().stream()
                        .map(req -> ToolCall.builder()
                                .id(req.id())
                                .name(req.name())
                                .arguments(parseArguments(req.arguments()))
                                .build())
                        .toList();
            }
        } catch (Exception e) {
            log.error("Error in fetchToolCallsWithNonStreaming", e);
        }
        return null;
    }

    /**
     * 解析 tool call 参数（JSON 字符串 -> Map）
     */
    private Map<String, Object> parseArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(argumentsJson, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to parse tool call arguments: {}", argumentsJson, e);
            return new HashMap<>();
        }
    }

    /**
     * 将 ToolDefinition 列表转换为 LangChain4j 的 ToolSpecification 列表
     */
    private List<ToolSpecification> convertTools(List<ToolDefinition> tools) {
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }

        List<ToolSpecification> specs = new ArrayList<>();
        for (ToolDefinition tool : tools) {
            ToolSpecification.Builder builder = ToolSpecification.builder()
                    .name(tool.getName())
                    .description(tool.getDescription());

            // 转换参数 schema
            if (tool.getParameters() != null && !tool.getParameters().isEmpty()) {
                ToolParameters toolParams = convertToToolParameters(tool.getParameters());
                builder.parameters(toolParams);
            }

            specs.add(builder.build());
            log.debug("Converted tool: {} -> {}", tool.getName(), tool.getDescription());
        }

        return specs;
    }

    /**
     * 将参数 Map 转换为 LangChain4j 的 ToolParameters
     */
    @SuppressWarnings("unchecked")
    private ToolParameters convertToToolParameters(Map<String, Object> params) {
        ToolParameters.Builder builder = ToolParameters.builder();

        // 从 parameters 中提取 properties
        Object propertiesObj = params.get("properties");
        if (propertiesObj instanceof Map<?, ?>) {
            Map<String, Map<String, Object>> properties = new HashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) propertiesObj).entrySet()) {
                String key = (String) entry.getKey();
                Map<String, Object> value = (Map<String, Object>) entry.getValue();
                properties.put(key, value);
            }
            builder.properties(properties);
        }

        // 提取 required 字段
        Object required = params.get("required");
        if (required instanceof String[] requiredArr) {
            builder.required(List.of(requiredArr));
        } else if (required instanceof List<?> requiredList) {
            builder.required((List<String>) requiredList);
        }

        return builder.build();
    }
}
