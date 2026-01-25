package com.foggy.navigator.agent.llm.impl;

import com.foggy.navigator.agent.llm.*;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * LangChain4j实现的LLM适配器
 */
@Component
public class LangChain4jAdapter implements LlmAdapter {

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
        // MVP阶段：使用同步调用模拟流式
        try {
            LlmResponse response = chat(request);
            handler.onText(response.getContent());
            handler.onComplete(response);
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
}
