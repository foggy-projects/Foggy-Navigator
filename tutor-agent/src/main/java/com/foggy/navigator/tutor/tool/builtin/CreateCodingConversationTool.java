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
 * 创建一个新的编码会话
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CreateCodingConversationTool implements BuiltInTool {

    private final CodingAgentFacade codingAgentFacade;

    @Override
    public String getName() {
        return "create_coding_conversation";
    }

    @Override
    public String getDescription() {
        return "创建一个新的编码会话，用于执行代码编写任务";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> title = new LinkedHashMap<>();
        title.put("type", "string");
        title.put("description", "会话标题/任务描述");

        Map<String, Object> credentialId = new LinkedHashMap<>();
        credentialId.put("type", "string");
        credentialId.put("description", "Git 凭证 ID");

        Map<String, Object> projectId = new LinkedHashMap<>();
        projectId.put("type", "string");
        projectId.put("description", "项目 ID");

        Map<String, Object> branch = new LinkedHashMap<>();
        branch.put("type", "string");
        branch.put("description", "分支名称");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("title", title);
        properties.put("credentialId", credentialId);
        properties.put("projectId", projectId);
        properties.put("branch", branch);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", new String[]{"title", "credentialId", "projectId", "branch"});
        return schema;
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request) {
        Map<String, Object> params = request.getParameters();
        log.info("Executing create_coding_conversation for userId={}, title={}",
                request.getUserId(), params.get("title"));
        try {
            Map<String, Object> result = codingAgentFacade.createConversation(request.getUserId(), params);
            return ToolExecutionResult.success(
                    "会话创建成功！\n会话 ID: " + result.get("id") + "\n状态: " + result.get("status"));
        } catch (Exception e) {
            log.error("create_coding_conversation failed", e);
            return ToolExecutionResult.error("创建会话失败：" + e.getMessage());
        }
    }
}
