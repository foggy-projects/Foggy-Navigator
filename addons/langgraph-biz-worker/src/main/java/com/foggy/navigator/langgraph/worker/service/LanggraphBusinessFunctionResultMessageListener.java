package com.foggy.navigator.langgraph.worker.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.agent.framework.protocol.AgentMessage;
import com.foggy.navigator.agent.framework.protocol.MessageType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * Reconciles LangGraph task terminal status from deterministic business function result messages.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LanggraphBusinessFunctionResultMessageListener {

    private static final String SUBTYPE_BUSINESS_FUNCTION_RESULT_MESSAGE = "business_function_result_message";

    private final LanggraphTaskService taskService;
    private final ObjectMapper objectMapper;

    @EventListener
    public void handleAgentMessage(AgentMessage message) {
        if (message == null || message.getType() != MessageType.TEXT_COMPLETE) {
            return;
        }
        if (!(message.getPayload() instanceof Map<?, ?> payload)) {
            return;
        }
        if (!SUBTYPE_BUSINESS_FUNCTION_RESULT_MESSAGE.equals(asText(payload.get("subtype")))) {
            return;
        }

        String workerTaskId = asText(payload.get("workerTaskId"));
        if (!StringUtils.hasText(workerTaskId)) {
            log.debug("Skip langgraph task terminal reconciliation because workerTaskId is absent: messageTaskId={}",
                    message.getTaskId());
            return;
        }

        String executionStatus = asText(payload.get("executionStatus"));
        String status = asText(payload.get("status"));
        String content = firstText(asText(payload.get("content")), asText(payload.get("errorMessage")));

        if (isSuccess(executionStatus, status)) {
            taskService.completeTask(
                    workerTaskId,
                    StringUtils.hasText(content) ? content : "业务函数执行完成。",
                    serializePayload(payload),
                    null);
            return;
        }

        if (isFailure(executionStatus, status)) {
            taskService.failTask(
                    workerTaskId,
                    StringUtils.hasText(content) ? content : "业务函数执行失败。");
        }
    }

    private boolean isSuccess(String executionStatus, String status) {
        return "COMPLETED".equalsIgnoreCase(executionStatus) || "SUCCESS".equalsIgnoreCase(status);
    }

    private boolean isFailure(String executionStatus, String status) {
        return "FAILED".equalsIgnoreCase(executionStatus) || "FAILED".equalsIgnoreCase(status);
    }

    private String serializePayload(Map<?, ?> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize business function result payload for langgraph task reconciliation", e);
            return null;
        }
    }

    private String asText(Object value) {
        return value instanceof String text ? text : null;
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }
}
