package com.foggy.navigator.tutor.tool.builtin;

import com.foggy.navigator.agent.framework.tool.BuiltInTool;
import com.foggy.navigator.agent.framework.tool.ToolExecutionRequest;
import com.foggy.navigator.agent.framework.tool.ToolExecutionResult;
import com.foggy.navigator.spi.coding.CodingAgentFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 向编码会话发送消息/指令
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SendCodingMessageTool implements BuiltInTool {

    private final CodingAgentFacade codingAgentFacade;

    @Override
    public String getName() {
        return "send_coding_message";
    }

    @Override
    public String getDescription() {
        return "向编码会话发送消息/指令";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> conversationId = new LinkedHashMap<>();
        conversationId.put("type", "string");
        conversationId.put("description", "会话 ID");

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "string");
        message.put("description", "消息内容");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("conversationId", conversationId);
        properties.put("message", message);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", new String[]{"conversationId", "message"});
        return schema;
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request) {
        Map<String, Object> params = request.getParameters();
        String conversationId = (String) params.get("conversationId");
        log.info("Executing send_coding_message for conversationId={}", conversationId);
        try {
            Map<String, Object> result = codingAgentFacade.sendMessage(
                    request.getUserId(), conversationId, params);
            String content = (String) result.getOrDefault("content", "消息已发送");
            return ToolExecutionResult.success("消息已发送。\n响应: " + content);
        } catch (Exception e) {
            log.error("send_coding_message failed", e);
            return ToolExecutionResult.error("发送消息失败：" + e.getMessage());
        }
    }
}
