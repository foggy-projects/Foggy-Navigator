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
            if (toolSpecs != null) {
                for (ToolSpecification ts : toolSpecs) {
                    log.debug("  Tool: name={}, hasParams={}", ts.name(), ts.parameters() != null);
                }
            }

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
                try {
                    log.debug("Calling streamingModel.generate with {} tools", toolSpecs.size());
                    streamingModel.generate(messages, toolSpecs, responseHandler);
                } catch (Exception e) {
                    log.error("Error calling streamingModel.generate with tools: {}", e.getMessage(), e);
                    // 如果带工具调用失败，尝试不带工具
                    log.warn("Falling back to generate without tools");
                    streamingModel.generate(messages, responseHandler);
                }
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
            List<ToolSpecification> toolSpecs = convertTools(request.getTools());

            // 使用带 tools 的 ChatRequest
            ChatRequest.Builder chatRequestBuilder = ChatRequest.builder()
                    .messages(messages);

            if (toolSpecs != null && !toolSpecs.isEmpty()) {
                chatRequestBuilder.toolSpecifications(toolSpecs);
                log.debug("Fallback request with {} tools", toolSpecs.size());
            }

            ChatResponse response = model.chat(chatRequestBuilder.build());
            AiMessage aiMessage = response.aiMessage();

            log.debug("Fallback response - aiMessage: {}, finishReason: {}",
                    aiMessage, response.finishReason());

            if (aiMessage != null && aiMessage.hasToolExecutionRequests()) {
                log.info("Fallback got {} tool execution requests", aiMessage.toolExecutionRequests().size());
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
            try {
                ToolSpecification.Builder builder = ToolSpecification.builder()
                        .name(tool.getName())
                        .description(tool.getDescription());

                // 转换参数 schema - 只有当有 properties 时才设置
                if (tool.getParameters() != null && !tool.getParameters().isEmpty()) {
                    Object propertiesObj = tool.getParameters().get("properties");
                    log.debug("Tool {} parameters: type={}, propertiesObj={}",
                            tool.getName(),
                            tool.getParameters().get("type"),
                            propertiesObj != null ? propertiesObj.getClass().getSimpleName() : "null");

                    if (propertiesObj instanceof Map<?, ?> propsMap && !propsMap.isEmpty()) {
                        ToolParameters toolParams = convertToToolParameters(tool.getParameters());
                        log.debug("Tool {} converted ToolParameters: properties.size={}, required={}",
                                tool.getName(),
                                toolParams.properties() != null ? toolParams.properties().size() : 0,
                                toolParams.required());
                        builder.parameters(toolParams);
                    }
                }

                ToolSpecification spec = builder.build();
                log.debug("Built ToolSpecification: name={}, hasParameters={}",
                        spec.name(), spec.parameters() != null);
                specs.add(spec);
            } catch (Exception e) {
                log.error("Failed to convert tool {}: {}", tool.getName(), e.getMessage(), e);
                // 跳过失败的工具，继续处理其他工具
            }
        }

        return specs;
    }

    /**
     * 将参数 Map 转换为 LangChain4j 的 ToolParameters
     */
    @SuppressWarnings("unchecked")
    private ToolParameters convertToToolParameters(Map<String, Object> params) {
        Map<String, Map<String, Object>> properties = new HashMap<>();
        List<String> requiredList = new ArrayList<>();

        // 从 parameters 中提取 properties
        Object propertiesObj = params.get("properties");
        if (propertiesObj instanceof Map<?, ?>) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) propertiesObj).entrySet()) {
                String key = (String) entry.getKey();
                Object valueObj = entry.getValue();
                if (valueObj instanceof Map<?, ?>) {
                    Map<String, Object> value = new HashMap<>();
                    for (Map.Entry<?, ?> e : ((Map<?, ?>) valueObj).entrySet()) {
                        value.put((String) e.getKey(), e.getValue());
                    }
                    properties.put(key, value);
                }
            }
        }

        // 提取 required 字段
        Object required = params.get("required");
        if (required instanceof String[] requiredArr) {
            requiredList = List.of(requiredArr);
        } else if (required instanceof List<?> reqList) {
            for (Object r : reqList) {
                requiredList.add((String) r);
            }
        }

        return ToolParameters.builder()
                .properties(properties)
                .required(requiredList)
                .build();
    }
}
