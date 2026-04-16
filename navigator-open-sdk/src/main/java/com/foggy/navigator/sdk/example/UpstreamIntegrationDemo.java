package com.foggy.navigator.sdk.example;

import com.foggy.navigator.sdk.NavigatorClient;
import com.foggy.navigator.sdk.model.*;

import java.time.Duration;
import java.util.List;

/**
 * 上游系统接入 Demo
 * <p>
 * 演示完整的首版接入流程：
 * 1. 配置 API Key 和 agentId
 * 2. 发起任务 (ask)
 * 3. 轮询任务状态 (getTask)
 * 4. 轮询任务新增消息 (getTaskMessages)
 * 5. 按 contextId 拉取完整消息回放 (getSessionMessages)
 *
 * <p>使用方式：
 * <pre>
 * // 设置环境变量后运行
 * export NAVIGATOR_BASE_URL=http://localhost:8112
 * export NAVIGATOR_API_KEY=sk-xxx
 * export NAVIGATOR_AGENT_ID=your-agent-id
 *
 * java -cp navigator-open-sdk.jar com.foggy.navigator.sdk.example.UpstreamIntegrationDemo
 * </pre>
 */
public class UpstreamIntegrationDemo {

    /** 轮询间隔 */
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(3);
    /** 最大等待时间 */
    private static final Duration TIMEOUT = Duration.ofMinutes(5);

    public static void main(String[] args) {
        // ── 1. 配置 ──
        String baseUrl = envOrDefault("NAVIGATOR_BASE_URL", "http://localhost:8112");
        String apiKey = requireEnv("NAVIGATOR_API_KEY");
        String agentId = requireEnv("NAVIGATOR_AGENT_ID");

        System.out.println("=== Foggy Navigator 上游接入 Demo ===");
        System.out.println("Server: " + baseUrl);
        System.out.println("Agent:  " + agentId);
        System.out.println();

        NavigatorClient client = NavigatorClient.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .timeout(Duration.ofSeconds(30))
                .build();

        // ── 2. 发起任务 ──
        System.out.println("[Step 1] 发起任务...");
        AgentTask task = client.agents().ask(agentId, "请用一句话介绍你自己。");
        System.out.println("  taskId:    " + task.getTaskId());
        System.out.println("  contextId: " + task.getContextId());
        System.out.println("  status:    " + task.getStatus());
        System.out.println();

        String taskId = task.getTaskId();
        String contextId = task.getContextId();

        // ── 3. 轮询任务状态 + 增量消息 ──
        System.out.println("[Step 2] 轮询任务状态与增量消息...");
        String cursor = null;
        int totalMessages = 0;
        long deadline = System.currentTimeMillis() + TIMEOUT.toMillis();

        while (System.currentTimeMillis() < deadline) {
            // 轮询任务状态
            AgentTask current = client.agents().getTask(agentId, taskId);
            System.out.println("  status: " + current.getStatus());

            // 轮询增量消息
            TaskMessagesPage msgPage = client.agents().getTaskMessages(agentId, taskId, 50, cursor);
            if (msgPage.getMessages() != null && !msgPage.getMessages().isEmpty()) {
                for (SessionMessage msg : msgPage.getMessages()) {
                    totalMessages++;
                    System.out.println("  [msg #" + totalMessages + "] "
                            + msg.getRole() + "/" + msg.getType()
                            + ": " + truncate(msg.getContent(), 80));
                }
                cursor = msgPage.getNextCursor();
            }

            // 终态则退出
            if (current.isTerminal()) {
                System.out.println("  任务完成: " + current.getStatus());
                if (current.getResult() != null) {
                    System.out.println("  结果: " + truncate(current.getResult(), 200));
                }
                if (current.getErrorMessage() != null) {
                    System.out.println("  错误: " + current.getErrorMessage());
                }
                break;
            }

            sleep(POLL_INTERVAL);
        }
        System.out.println("  共收到 " + totalMessages + " 条增量消息");
        System.out.println();

        // ── 4. 回放会话消息 ──
        System.out.println("[Step 3] 按 contextId 回放完整会话消息...");
        String sessionCursor = null;
        int replayCount = 0;
        do {
            SessionMessagesPage page = client.agents().getSessionMessages(
                    agentId, contextId, 50, sessionCursor);
            if (page.getMessages() != null) {
                for (SessionMessage msg : page.getMessages()) {
                    replayCount++;
                    System.out.println("  [" + replayCount + "] "
                            + msg.getRole() + "/" + msg.getType()
                            + " (task=" + msg.getTaskId() + ")"
                            + ": " + truncate(msg.getContent(), 80));
                }
                sessionCursor = page.getNextCursor();
                if (!page.isHasMore()) break;
            } else {
                break;
            }
        } while (true);
        System.out.println("  会话共 " + replayCount + " 条消息");
        System.out.println();

        // ── 5. 查看会话列表 ──
        System.out.println("[Step 4] 会话列表...");
        SessionListPage sessions = client.agents().listSessions(agentId, 5, null);
        if (sessions.getSessions() != null) {
            for (SessionSummary s : sessions.getSessions()) {
                System.out.println("  " + s.getContextId()
                        + " | " + s.getTitle()
                        + " | latest=" + s.getLatestTaskId()
                        + " | " + s.getUpdatedAt());
            }
        }
        System.out.println();

        System.out.println("=== Demo 完成 ===");
    }

    // ── 工具方法 ──

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            System.err.println("错误：需要设置环境变量 " + name);
            System.exit(1);
        }
        return value;
    }

    private static String envOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return (value != null && !value.isBlank()) ? value : defaultValue;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "(null)";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
