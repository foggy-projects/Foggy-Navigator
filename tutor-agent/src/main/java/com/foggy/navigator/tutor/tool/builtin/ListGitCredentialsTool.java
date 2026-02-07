package com.foggy.navigator.tutor.tool.builtin;

import com.foggy.navigator.agent.framework.tool.BuiltInTool;
import com.foggy.navigator.agent.framework.tool.ToolExecutionRequest;
import com.foggy.navigator.agent.framework.tool.ToolExecutionResult;
import com.foggy.navigator.spi.coding.CodingAgentFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 列出用户配置的所有 Git 凭证
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ListGitCredentialsTool implements BuiltInTool {

    private final CodingAgentFacade codingAgentFacade;

    @Override
    public String getName() {
        return "list_git_credentials";
    }

    @Override
    public String getDescription() {
        return "列出用户配置的所有 Git 凭证（如 GitLab、GitHub 等）";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of());
        return schema;
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request) {
        log.info("Executing list_git_credentials for userId={}", request.getUserId());
        try {
            List<Map<String, Object>> credentials = codingAgentFacade.listGitCredentials(request.getUserId());
            if (credentials.isEmpty()) {
                return ToolExecutionResult.success("当前没有配置任何 Git 凭证。您可以先添加一个 Git 凭证（如 GitLab、GitHub）来管理代码仓库。");
            }
            StringBuilder sb = new StringBuilder("已配置的 Git 凭证：\n");
            for (Map<String, Object> cred : credentials) {
                sb.append("- ID: ").append(cred.get("id"))
                  .append(", 名称: ").append(cred.get("name"))
                  .append(", 类型: ").append(cred.get("type"))
                  .append("\n");
            }
            return ToolExecutionResult.success(sb.toString());
        } catch (Exception e) {
            log.error("list_git_credentials failed", e);
            return ToolExecutionResult.error("获取 Git 凭证列表失败：" + e.getMessage());
        }
    }
}
