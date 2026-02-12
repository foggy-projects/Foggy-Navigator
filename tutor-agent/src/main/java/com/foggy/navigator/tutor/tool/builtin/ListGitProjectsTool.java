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
 * 列出指定 Git 凭证下的所有项目/仓库
 */
@Slf4j
@Component
public class ListGitProjectsTool implements BuiltInTool {

    private final CodingAgentFacade codingAgentFacade;

    public ListGitProjectsTool(@Nullable CodingAgentFacade codingAgentFacade) {
        this.codingAgentFacade = codingAgentFacade;
    }

    @Override
    public String getName() {
        return "list_git_projects";
    }

    @Override
    public String getDescription() {
        return "列出指定 Git 凭证下的所有项目/仓库";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> credentialId = new LinkedHashMap<>();
        credentialId.put("type", "string");
        credentialId.put("description", "Git 凭证 ID");

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("credentialId", credentialId));
        schema.put("required", new String[]{"credentialId"});
        return schema;
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request) {
        if (codingAgentFacade == null) {
            log.warn("CodingAgentFacade not available, tool disabled");
            return ToolExecutionResult.error("编程 Agent 模块未启用");
        }
        String credentialId = (String) request.getParameters().get("credentialId");
        log.info("Executing list_git_projects for userId={}, credentialId={}", request.getUserId(), credentialId);
        try {
            List<Map<String, Object>> projects = codingAgentFacade.listGitProjects(request.getUserId(), credentialId);
            if (projects.isEmpty()) {
                return ToolExecutionResult.success("该凭证下没有找到任何项目。请检查凭证配置或权限设置。");
            }
            StringBuilder sb = new StringBuilder("项目列表：\n");
            for (Map<String, Object> proj : projects) {
                sb.append("- ID: ").append(proj.get("id"))
                  .append(", 名称: ").append(proj.get("name"))
                  .append(", 路径: ").append(proj.get("path_with_namespace"))
                  .append("\n");
            }
            return ToolExecutionResult.success(sb.toString());
        } catch (Exception e) {
            log.error("list_git_projects failed", e);
            return ToolExecutionResult.error("获取项目列表失败：" + e.getMessage());
        }
    }
}
