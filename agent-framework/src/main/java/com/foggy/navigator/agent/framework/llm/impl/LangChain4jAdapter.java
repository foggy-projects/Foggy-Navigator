package com.foggy.navigator.agent.framework.llm.impl;

import com.foggy.navigator.agent.framework.llm.*;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * LangChain4j实现的LLM适配器
 */
@Slf4j
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
        try {
            StreamingChatLanguageModel streamingModel = buildStreamingChatModel(request);
            List<ChatMessage> messages = convertMessages(request);
            StringBuilder fullContent = new StringBuilder();
            CountDownLatch latch = new CountDownLatch(1);

            streamingModel.generate(messages, new StreamingResponseHandler<>() {
                @Override
                public void onNext(String token) {
                    fullContent.append(token);
                    handler.onText(token);
                }

                @Override
                public void onComplete(Response<AiMessage> response) {
                    LlmResponse llmResponse = LlmResponse.builder()
                            .content(fullContent.toString())
                            .inputTokens(response.tokenUsage() != null ? response.tokenUsage().inputTokenCount() : 0)
                            .outputTokens(response.tokenUsage() != null ? response.tokenUsage().outputTokenCount() : 0)
                            .finishReason(response.finishReason() != null ? response.finishReason().name() : null)
                            .build();
                    handler.onComplete(llmResponse);
                    latch.countDown();
                }

                @Override
                public void onError(Throwable error) {
                    handler.onError(error);
                    latch.countDown();
                }
            });

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
}
