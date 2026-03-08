package com.foggy.navigator.tutor.tool.builtin;

import com.foggy.navigator.agent.framework.tool.BuiltInTool;
import com.foggy.navigator.agent.framework.tool.ToolExecutionRequest;
import com.foggy.navigator.agent.framework.tool.ToolExecutionResult;
import com.foggy.navigator.spi.coding.CodingAgentFacade;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 列出指定项目的所有分支
 */
@Slf4j
@Component
public class ListGitBranchesTool implements BuiltInTool {

    private final CodingAgentFacade codingAgentFacade;

    public ListGitBranchesTool(@Nullable CodingAgentFacade codingAgentFacade) {
        this.codingAgentFacade = codingAgentFacade;
    }

    @Override
    public String getName() {
        return "list_git_branches";
    }

    @Override
    public String getDescription() {
        return "列出指定项目的所有分支";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> credentialId = new LinkedHashMap<>();
        credentialId.put("type", "string");
        credentialId.put("description", "Git 凭证 ID");

        Map<String, Object> projectId = new LinkedHashMap<>();
        projectId.put("type", "string");
        projectId.put("description", "项目 ID");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("credentialId", credentialId);
        properties.put("projectId", projectId);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", new String[]{"credentialId", "projectId"});
        return schema;
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request) {
        if (codingAgentFacade == null) {
            log.warn("CodingAgentFacade not available, tool disabled");
            return ToolExecutionResult.error("编程 Agent 模块未启用");
        }
        String credentialId = (String) request.getParameters().get("credentialId");
        String projectId = (String) request.getParameters().get("projectId");
        log.info("Executing list_git_branches for userId={}, credentialId={}, projectId={}",
                request.getUserId(), credentialId, projectId);
        try {
            List<Map<String, Object>> branches = codingAgentFacade.listGitBranches(
                    request.getUserId(), credentialId, projectId);
            if (branches.isEmpty()) {
                return ToolExecutionResult.success("该项目没有找到任何分支。");
            }
            StringBuilder sb = new StringBuilder("分支列表：\n");
            for (Map<String, Object> branch : branches) {
                sb.append("- ").append(branch.get("name"));
                if (Boolean.TRUE.equals(branch.get("default"))) {
                    sb.append(" (默认)");
                }
                sb.append("\n");
            }
            return ToolExecutionResult.success(sb.toString());
        } catch (Exception e) {
            log.error("list_git_branches failed", e);
            return ToolExecutionResult.error("获取分支列表失败：" + e.getMessage());
        }
    }
}
