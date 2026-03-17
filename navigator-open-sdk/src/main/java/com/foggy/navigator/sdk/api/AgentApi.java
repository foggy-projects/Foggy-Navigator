package com.foggy.navigator.sdk.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.foggy.navigator.sdk.exception.NavigatorApiException;
import com.foggy.navigator.sdk.internal.HttpHelper;
import com.foggy.navigator.sdk.model.AgentCard;
import com.foggy.navigator.sdk.model.AgentTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
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
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("question", question);
        if (contextId != null) body.put("contextId", contextId);
        if (maxTurns != null) body.put("maxTurns", maxTurns);
        return http.post("/api/v1/open/agents/" + agentId + "/ask",
                body, new TypeReference<>() {});
    }

    /**
     * 轮询任务状态
     */
    public AgentTask getTask(String agentId, String taskId) {
        return http.get("/api/v1/open/agents/" + agentId + "/tasks/" + taskId,
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
}
