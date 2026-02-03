package com.foggy.navigator.tutor.tool;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Tutor Agent 工具定义
 * 提供给 LLM 调用的工具集
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TutorToolDefinitions {

    private final CodingAgentToolExecutor toolExecutor;

    /**
     * 当前用户的认证 Token（需要在每次调用前设置）
     */
    private final ThreadLocal<String> currentAuthToken = new ThreadLocal<>();

    /**
     * 设置当前请求的认证 Token
     */
    public void setAuthToken(String token) {
        currentAuthToken.set(token);
    }

    /**
     * 清除当前请求的认证 Token
     */
    public void clearAuthToken() {
        currentAuthToken.remove();
    }

    private String getAuthToken() {
        String token = currentAuthToken.get();
        if (token == null || token.isEmpty()) {
            throw new IllegalStateException("Auth token not set");
        }
        return token;
    }

    @Tool(name = "list_git_credentials", value = "列出用户配置的所有 Git 凭证（如 GitLab、GitHub 等）")
    public String listGitCredentials() {
        log.info("Tool called: list_git_credentials");
        String token = getAuthToken();  // 在 try 块外调用，让认证异常传播
        try {
            List<Map<String, Object>> credentials = toolExecutor.listGitCredentials(token);
            if (credentials.isEmpty()) {
                return "当前没有配置任何 Git 凭证。您可以先添加一个 Git 凭证（如 GitLab、GitHub）来管理代码仓库。";
            }
            StringBuilder sb = new StringBuilder("已配置的 Git 凭证：\n");
            for (Map<String, Object> cred : credentials) {
                sb.append("- ID: ").append(cred.get("id"))
                  .append(", 名称: ").append(cred.get("name"))
                  .append(", 类型: ").append(cred.get("type"))
                  .append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("list_git_credentials failed", e);
            return "获取 Git 凭证列表失败：" + e.getMessage();
        }
    }

    @Tool(name = "list_git_projects", value = "列出指定 Git 凭证下的所有项目/仓库")
    public String listGitProjects(
            @P("Git 凭证 ID") String credentialId) {
        log.info("Tool called: list_git_projects, credentialId={}", credentialId);
        String token = getAuthToken();  // 在 try 块外调用，让认证异常传播
        try {
            List<Map<String, Object>> projects = toolExecutor.listGitProjects(credentialId, token);
            if (projects.isEmpty()) {
                return "该凭证下没有找到任何项目。请检查凭证配置或权限设置。";
            }
            StringBuilder sb = new StringBuilder("项目列表：\n");
            for (Map<String, Object> proj : projects) {
                sb.append("- ID: ").append(proj.get("id"))
                  .append(", 名称: ").append(proj.get("name"))
                  .append(", 路径: ").append(proj.get("path_with_namespace"))
                  .append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("list_git_projects failed", e);
            return "获取项目列表失败：" + e.getMessage();
        }
    }

    @Tool(name = "list_git_branches", value = "列出指定项目的所有分支")
    public String listGitBranches(
            @P("Git 凭证 ID") String credentialId,
            @P("项目 ID") String projectId) {
        log.info("Tool called: list_git_branches, credentialId={}, projectId={}", credentialId, projectId);
        String token = getAuthToken();  // 在 try 块外调用，让认证异常传播
        try {
            List<Map<String, Object>> branches = toolExecutor.listGitBranches(credentialId, projectId, token);
            if (branches.isEmpty()) {
                return "该项目没有找到任何分支。";
            }
            StringBuilder sb = new StringBuilder("分支列表：\n");
            for (Map<String, Object> branch : branches) {
                sb.append("- ").append(branch.get("name"));
                if (Boolean.TRUE.equals(branch.get("default"))) {
                    sb.append(" (默认)");
                }
                sb.append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("list_git_branches failed", e);
            return "获取分支列表失败：" + e.getMessage();
        }
    }

    @Tool(name = "create_coding_conversation", value = "创建一个新的编码会话，用于执行代码编写任务")
    public String createCodingConversation(
            @P("会话标题") String title,
            @P("Git 凭证 ID") String credentialId,
            @P("项目 ID") String projectId,
            @P("分支名称") String branch) {
        log.info("Tool called: create_coding_conversation, title={}", title);
        String token = getAuthToken();  // 在 try 块外调用，让认证异常传播
        try {
            Map<String, Object> request = Map.of(
                "title", title,
                "credentialId", credentialId,
                "projectId", projectId,
                "branch", branch
            );
            Map<String, Object> result = toolExecutor.createCodingConversation(request, token);
            return "会话创建成功！\n会话 ID: " + result.get("id") + "\n状态: " + result.get("status");
        } catch (Exception e) {
            log.error("create_coding_conversation failed", e);
            return "创建会话失败：" + e.getMessage();
        }
    }

    @Tool(name = "send_coding_message", value = "向编码会话发送消息/指令")
    public String sendCodingMessage(
            @P("会话 ID") String conversationId,
            @P("消息内容") String message) {
        log.info("Tool called: send_coding_message, conversationId={}", conversationId);
        String token = getAuthToken();  // 在 try 块外调用，让认证异常传播
        try {
            Map<String, Object> request = Map.of("content", message);
            Map<String, Object> result = toolExecutor.sendCodingMessage(conversationId, request, token);
            String response = (String) result.getOrDefault("content", "消息已发送");
            return "消息已发送。\n响应: " + response;
        } catch (Exception e) {
            log.error("send_coding_message failed", e);
            return "发送消息失败：" + e.getMessage();
        }
    }

    @Tool(name = "get_conversation_status", value = "查询编码会话的当前状态和进度")
    public String getConversationStatus(
            @P("会话 ID") String conversationId) {
        log.info("Tool called: get_conversation_status, conversationId={}", conversationId);
        String token = getAuthToken();  // 在 try 块外调用，让认证异常传播
        try {
            Map<String, Object> result = toolExecutor.getConversationStatus(conversationId, token);
            StringBuilder sb = new StringBuilder("会话状态：\n");
            sb.append("- ID: ").append(result.get("id")).append("\n");
            sb.append("- 标题: ").append(result.get("title")).append("\n");
            sb.append("- 状态: ").append(result.get("status")).append("\n");
            if (result.containsKey("messageCount")) {
                sb.append("- 消息数: ").append(result.get("messageCount")).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("get_conversation_status failed", e);
            return "查询会话状态失败：" + e.getMessage();
        }
    }
}
