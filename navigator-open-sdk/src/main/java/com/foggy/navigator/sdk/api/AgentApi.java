package com.foggy.navigator.sdk.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.foggy.navigator.sdk.exception.NavigatorApiException;
import com.foggy.navigator.sdk.internal.HttpHelper;
import com.foggy.navigator.sdk.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 交互 API（A2A 协议）
 *
 * <pre>
 * // 发送任务
 * AgentTask task = client.agents().ask("agent-id", "帮我写一个 hello world");
 *
 * // 轮询到完成
 * AgentTask result = client.agents().pollUntilDone("agent-id", task.getTaskId(),
 *     Duration.ofMinutes(5));
 *
 * // 多轮对话
 * AgentTask task2 = client.agents().ask("agent-id", "加个单元测试",
 *     result.getContextId(), null);
 * </pre>
 */
public class AgentApi {

    private static final Logger log = LoggerFactory.getLogger(AgentApi.class);

    private final HttpHelper http;

    public AgentApi(HttpHelper http) {
        this.http = http;
    }

    /**
     * 列出所有 Agent
     */
    public List<AgentCard> list() {
        return http.get("/api/v1/open/agents", new TypeReference<>() {});
    }

    /**
     * 获取 Agent Card 详情
     */
    public AgentCard get(String agentId) {
        return http.get("/api/v1/open/agents/" + agentId, new TypeReference<>() {});
    }

    /**
     * 发送任务（简单模式）
     *
     * @param agentId  Agent ID
     * @param question 提示词/问题
     * @return 提交后的任务（状态为 SUBMITTED）
     */
    public AgentTask ask(String agentId, String question) {
        return ask(agentId, question, null, null);
    }

    /**
     * 发送任务（完整模式）
     *
     * @param agentId   Agent ID
     * @param question  提示词/问题
     * @param contextId 多轮会话 ID（首次传 null 自动生成，后续复用前次返回的 contextId）
     * @param maxTurns  最大交互轮数（null 使用默认值 3）
     * @return 提交后的任务（状态为 SUBMITTED）
     */
    public AgentTask ask(String agentId, String question, String contextId, Integer maxTurns) {
        return ask(agentId, question, contextId, maxTurns, null, null);
    }

    /**
     * 提交 Agent 任务，支持原生 systemPrompt 和首轮 firstMsg。
     */
    public AgentTask ask(String agentId, String question, String contextId, Integer maxTurns,
                         String systemPrompt, String firstMsg) {
        Map<String, Object> body = buildAskBody(question, contextId, maxTurns, systemPrompt, firstMsg, null, null);
        return http.post("/api/v1/open/agents/" + agentId + "/ask",
                body, new TypeReference<>() {});
    }

    public AgentTask ask(String agentId, String question, String contextId, Integer maxTurns,
                         String systemPrompt, String firstMsg,
                         Map<String, Object> clientContext) {
        Map<String, Object> body = buildAskBody(question, contextId, maxTurns,
                systemPrompt, firstMsg, clientContext, null);
        return http.post("/api/v1/open/agents/" + agentId + "/ask",
                body, new TypeReference<>() {});
    }

    public AgentTask askWithClientAppAccessToken(
            String agentId,
            String question,
            String contextId,
            Integer maxTurns,
            String clientAppKey,
            String clientAppAccessToken,
            String upstreamUserId) {
        return askWithClientAppAccessToken(agentId, question, contextId, maxTurns,
                null, null, clientAppKey, clientAppAccessToken, upstreamUserId);
    }

    public AgentTask askWithClientAppAccessToken(
            String agentId,
            String question,
            String contextId,
            Integer maxTurns,
            Map<String, Object> clientContext,
            String clientAppKey,
            String clientAppAccessToken,
            String upstreamUserId) {
        return askWithClientAppAccessToken(agentId, question, contextId, maxTurns,
                clientContext, null, clientAppKey, clientAppAccessToken, upstreamUserId);
    }

    public AgentTask askWithClientAppAccessToken(
            String agentId,
            String question,
            String contextId,
            Integer maxTurns,
            Map<String, Object> clientContext,
            String modelConfigId,
            String clientAppKey,
            String clientAppAccessToken,
            String upstreamUserId) {
        Map<String, Object> body = buildAskBody(question, contextId, maxTurns, null, null, clientContext, modelConfigId);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Client-App-Key", clientAppKey);
        headers.put("X-Client-App-Access-Token", clientAppAccessToken);
        headers.put("X-Upstream-User-Id", upstreamUserId);
        return http.post("/api/v1/open/agents/" + agentId + "/ask",
                body, headers, new TypeReference<>() {});
    }

    public AgentReadiness verifyReadinessWithClientAppAccessToken(
            String agentId,
            String upstreamUserId,
            String modelConfigId,
            String clientAppKey,
            String clientAppAccessToken) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("upstreamUserId", upstreamUserId);
        if (modelConfigId != null && !modelConfigId.isBlank()) {
            body.put("modelConfigId", modelConfigId);
        }
        body.put("context", Map.of("skillId", agentId));
        return http.post("/api/v1/open/agents/" + agentId + "/preflight",
                body,
                clientAppHeaders(clientAppKey, clientAppAccessToken, null),
                new TypeReference<>() {});
    }

    public SkillArtifactTree getSkillArtifactTreeWithClientAppAccessToken(
            String skillId,
            String clientAppKey,
            String clientAppAccessToken) {
        return http.get("/api/v1/open/skills/" + skillId + "/files/tree",
                clientAppHeaders(clientAppKey, clientAppAccessToken, null),
                new TypeReference<>() {});
    }

    public SkillArtifactSlice readSkillArtifactSliceWithClientAppAccessToken(
            String skillId,
            String path,
            int startLine,
            int startColumn,
            int maxChars,
            String clientAppKey,
            String clientAppAccessToken) {
        String query = "?path=" + encode(path)
                + "&startLine=" + startLine
                + "&startColumn=" + startColumn
                + "&maxChars=" + maxChars;
        return http.get("/api/v1/open/skills/" + skillId + "/files/slice" + query,
                clientAppHeaders(clientAppKey, clientAppAccessToken, null),
                new TypeReference<>() {});
    }

    public com.foggy.navigator.sdk.model.businessagent.SkillBundleDTO syncMyAccountSkillBundleWithClientAppAccessToken(
            com.foggy.navigator.sdk.model.businessagent.SyncAccountSkillBundleForm form,
            String clientAppKey,
            String clientAppAccessToken,
            String upstreamUserId) {
        return http.post("/api/v1/open/accounts/me/skill-bundles/sync",
                form,
                clientAppHeaders(clientAppKey, clientAppAccessToken, upstreamUserId),
                new TypeReference<>() {});
    }

    public com.foggy.navigator.sdk.model.businessagent.AccountContextFileTreeDTO listAccountContextFilesWithClientAppAccessToken(
            String clientAppKey,
            String clientAppAccessToken,
            String upstreamUserId) {
        return http.get("/api/v1/open/accounts/me/context-files",
                clientAppHeaders(clientAppKey, clientAppAccessToken, upstreamUserId),
                new TypeReference<>() {});
    }

    public com.foggy.navigator.sdk.model.businessagent.AccountContextFileDTO readAccountContextFileWithClientAppAccessToken(
            String fileName,
            String clientAppKey,
            String clientAppAccessToken,
            String upstreamUserId) {
        return http.get("/api/v1/open/accounts/me/context-files/" + encode(fileName),
                clientAppHeaders(clientAppKey, clientAppAccessToken, upstreamUserId),
                new TypeReference<>() {});
    }

    public com.foggy.navigator.sdk.model.businessagent.AccountContextFileDTO writeAccountPolicyWithClientAppAccessToken(
            com.foggy.navigator.sdk.model.businessagent.AccountContextFileWriteForm form,
            String clientAppKey,
            String clientAppAccessToken,
            String upstreamUserId) {
        return http.put("/api/v1/open/accounts/me/context-files/ACCOUNT_POLICY.md",
                form,
                clientAppHeaders(clientAppKey, clientAppAccessToken, upstreamUserId),
                new TypeReference<>() {});
    }

    private Map<String, String> clientAppHeaders(
            String clientAppKey,
            String clientAppAccessToken,
            String upstreamUserId) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Client-App-Key", clientAppKey);
        headers.put("X-Client-App-Access-Token", clientAppAccessToken);
        if (upstreamUserId != null && !upstreamUserId.isBlank()) {
            headers.put("X-Upstream-User-Id", upstreamUserId);
        }
        return headers;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private Map<String, Object> buildAskBody(String question, String contextId, Integer maxTurns,
                                             String systemPrompt, String firstMsg,
                                             Map<String, Object> clientContext,
                                             String modelConfigId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", question);    // 合同字段
        body.put("question", question);   // 兼容字段
        if (contextId != null) body.put("contextId", contextId);
        if (maxTurns != null) body.put("maxTurns", maxTurns);
        if (systemPrompt != null) body.put("systemPrompt", systemPrompt);
        if (firstMsg != null) body.put("firstMsg", firstMsg);
        if (clientContext != null && !clientContext.isEmpty()) body.put("clientContext", clientContext);
        if (modelConfigId != null && !modelConfigId.isBlank()) {
            body.put("modelConfigId", modelConfigId);
            body.put("metadata", Map.of("modelConfigId", modelConfigId));
        }
        return body;
    }

    /**
     * 轮询任务状态
     */
    public AgentTask getTask(String agentId, String taskId) {
        return http.get("/api/v1/open/agents/" + agentId + "/tasks/" + taskId,
                new TypeReference<>() {});
    }

    public AgentTask getTaskWithClientAppAccessToken(
            String agentId,
            String taskId,
            String clientAppKey,
            String clientAppAccessToken,
            String upstreamUserId) {
        return http.get("/api/v1/open/agents/" + agentId + "/tasks/" + taskId,
                clientAppHeaders(clientAppKey, clientAppAccessToken, upstreamUserId),
                new TypeReference<>() {});
    }

    /**
     * 取消任务
     */
    public void cancelTask(String agentId, String taskId) {
        http.post("/api/v1/open/agents/" + agentId + "/tasks/" + taskId + "/cancel",
                null, new TypeReference<String>() {});
    }

    /**
     * 列出活跃任务
     */
    public List<AgentTask> listTasks(String agentId) {
        return http.get("/api/v1/open/agents/" + agentId + "/tasks",
                new TypeReference<>() {});
    }

    /**
     * 轮询直到任务完成（阻塞方法）
     * <p>
     * 每 2 秒轮询一次，直到任务进入终态（COMPLETED / FAILED / CANCELED）或超时。
     *
     * @param agentId Agent ID
     * @param taskId  任务 ID
     * @param timeout 最大等待时间
     * @return 终态任务
     * @throws NavigatorApiException 超时或 API 异常
     */
    public AgentTask pollUntilDone(String agentId, String taskId, Duration timeout) {
        return pollUntilDone(agentId, taskId, timeout, Duration.ofSeconds(2));
    }

    /**
     * 轮询直到任务完成（可配置轮询间隔）
     */
    public AgentTask pollUntilDone(String agentId, String taskId,
                                    Duration timeout, Duration pollInterval) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        long intervalMs = pollInterval.toMillis();

        while (System.currentTimeMillis() < deadline) {
            AgentTask task = getTask(agentId, taskId);
            if (task.isTerminal()) {
                return task;
            }
            log.debug("Task {} status: {}, polling again in {}ms", taskId, task.getStatus(), intervalMs);
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new NavigatorApiException("Polling interrupted", e);
            }
        }
        throw new NavigatorApiException("Task " + taskId + " did not complete within " + timeout);
    }

    /**
     * 便捷方法：发送任务并等待完成
     *
     * @param agentId  Agent ID
     * @param question 提示词
     * @param timeout  最大等待时间
     * @return 完成的任务（含结果）
     */
    public AgentTask askAndWait(String agentId, String question, Duration timeout) {
        AgentTask submitted = ask(agentId, question);
        return pollUntilDone(agentId, submitted.getTaskId(), timeout);
    }

    /**
     * 便捷方法：多轮对话发送并等待
     */
    public AgentTask askAndWait(String agentId, String question,
                                String contextId, Duration timeout) {
        AgentTask submitted = ask(agentId, question, contextId, null);
        return pollUntilDone(agentId, submitted.getTaskId(), timeout);
    }

    // ===== 会话上下文列表 =====

    /**
     * 获取会话上下文列表
     */
    public SessionListPage listSessions(String agentId) {
        return listSessions(agentId, 20, null);
    }

    /**
     * 获取会话上下文列表（分页）
     */
    public SessionListPage listSessions(String agentId, int limit, String cursor) {
        StringBuilder path = new StringBuilder("/api/v1/open/agents/" + agentId + "/sessions?limit=" + limit);
        if (cursor != null) path.append("&cursor=").append(cursor);
        return http.get(path.toString(), new TypeReference<>() {});
    }

    public SessionListPage listSessionsWithClientAppAccessToken(
            String agentId,
            int limit,
            String cursor,
            String clientAppKey,
            String clientAppAccessToken,
            String upstreamUserId) {
        StringBuilder path = new StringBuilder("/api/v1/open/agents/" + agentId + "/sessions?limit=" + limit);
        if (cursor != null) path.append("&cursor=").append(cursor);
        return http.get(path.toString(), clientAppHeaders(clientAppKey, clientAppAccessToken, upstreamUserId),
                new TypeReference<>() {});
    }

    public SessionListPage listBusinessAgentSessionsWithClientAppAccessToken(
            int limit,
            String cursor,
            String clientAppKey,
            String clientAppAccessToken,
            String upstreamUserId) {
        StringBuilder path = new StringBuilder("/api/v1/open/business-agent/sessions?limit=" + limit);
        if (cursor != null) path.append("&cursor=").append(encode(cursor));
        return http.get(path.toString(), clientAppHeaders(clientAppKey, clientAppAccessToken, upstreamUserId),
                new TypeReference<>() {});
    }

    // ===== 会话消息 =====

    /**
     * 获取指定会话上下文的消息列表
     */
    public SessionMessagesPage getSessionMessages(String agentId, String contextId) {
        return getSessionMessages(agentId, contextId, 50, null);
    }

    /**
     * 获取指定会话上下文的消息列表（分页）
     */
    public SessionMessagesPage getSessionMessages(String agentId, String contextId,
                                                   int limit, String cursor) {
        StringBuilder path = new StringBuilder(
                "/api/v1/open/agents/" + agentId + "/sessions/" + contextId + "/messages?limit=" + limit);
        if (cursor != null) path.append("&cursor=").append(cursor);
        return http.get(path.toString(), new TypeReference<>() {});
    }

    public SessionMessagesPage getSessionMessagesWithClientAppAccessToken(
            String agentId,
            String contextId,
            int limit,
            String cursor,
            String clientAppKey,
            String clientAppAccessToken,
            String upstreamUserId) {
        StringBuilder path = new StringBuilder(
                "/api/v1/open/agents/" + agentId + "/sessions/" + contextId + "/messages?limit=" + limit);
        if (cursor != null) path.append("&cursor=").append(cursor);
        return http.get(path.toString(), clientAppHeaders(clientAppKey, clientAppAccessToken, upstreamUserId),
                new TypeReference<>() {});
    }

    public SessionMessagesPage getBusinessAgentSessionMessagesWithClientAppAccessToken(
            String contextId,
            int limit,
            String cursor,
            String clientAppKey,
            String clientAppAccessToken,
            String upstreamUserId) {
        StringBuilder path = new StringBuilder(
                "/api/v1/open/business-agent/sessions/" + encode(contextId) + "/messages?limit=" + limit);
        if (cursor != null) path.append("&cursor=").append(encode(cursor));
        return http.get(path.toString(), clientAppHeaders(clientAppKey, clientAppAccessToken, upstreamUserId),
                new TypeReference<>() {});
    }

    // ===== 任务增量消息 =====

    /**
     * 轮询任务执行中的新增消息
     */
    public TaskMessagesPage getTaskMessages(String agentId, String taskId) {
        return getTaskMessages(agentId, taskId, 50, null);
    }

    /**
     * 轮询任务执行中的新增消息（分页）
     */
    public TaskMessagesPage getTaskMessages(String agentId, String taskId,
                                             int limit, String cursor) {
        StringBuilder path = new StringBuilder(
                "/api/v1/open/agents/" + agentId + "/tasks/" + taskId + "/messages?limit=" + limit);
        if (cursor != null) path.append("&cursor=").append(cursor);
        return http.get(path.toString(), new TypeReference<>() {});
    }

    public TaskMessagesPage getTaskMessagesWithClientAppAccessToken(
            String agentId,
            String taskId,
            int limit,
            String cursor,
            String clientAppKey,
            String clientAppAccessToken,
            String upstreamUserId) {
        StringBuilder path = new StringBuilder(
                "/api/v1/open/agents/" + agentId + "/tasks/" + taskId + "/messages?limit=" + limit);
        if (cursor != null) path.append("&cursor=").append(cursor);
        return http.get(path.toString(), clientAppHeaders(clientAppKey, clientAppAccessToken, upstreamUserId),
                new TypeReference<>() {});
    }

    /**
     * 轮询任务消息直到任务完成（便捷方法）
     * <p>
     * 持续轮询新增消息，直到任务进入终态。返回收集到的所有消息。
     *
     * @param agentId      Agent ID
     * @param taskId       任务 ID
     * @param timeout      最大等待时间
     * @param pollInterval 轮询间隔
     * @return 任务执行过程中的所有消息
     */
    public List<SessionMessage> pollTaskMessages(String agentId, String taskId,
                                                  Duration timeout, Duration pollInterval) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        long intervalMs = pollInterval.toMillis();
        String cursor = null;
        List<SessionMessage> allMessages = new ArrayList<>();

        while (System.currentTimeMillis() < deadline) {
            // 拉取新增消息
            TaskMessagesPage page = getTaskMessages(agentId, taskId, 50, cursor);
            if (page.getMessages() != null && !page.getMessages().isEmpty()) {
                allMessages.addAll(page.getMessages());
                cursor = page.getNextCursor();
            }

            // 检查任务是否终态
            AgentTask task = getTask(agentId, taskId);
            if (task.isTerminal()) {
                // 终态后最后拉一次遗漏消息
                TaskMessagesPage lastPage = getTaskMessages(agentId, taskId, 50, cursor);
                if (lastPage.getMessages() != null && !lastPage.getMessages().isEmpty()) {
                    allMessages.addAll(lastPage.getMessages());
                }
                break;
            }

            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new NavigatorApiException("Polling interrupted", e);
            }
        }
        return allMessages;
    }
}
