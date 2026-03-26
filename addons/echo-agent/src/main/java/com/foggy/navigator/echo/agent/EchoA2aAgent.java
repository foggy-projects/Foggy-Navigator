package com.foggy.navigator.echo.agent;

import com.foggy.navigator.common.dto.a2a.*;
import com.foggy.navigator.spi.agent.A2aAgent;

import java.time.Instant;
import java.util.*;

/**
 * 模拟 Agent —— 直接 echo 回用户的 prompt，不需要真实 Worker。
 * <p>
 * 用途：
 * <ul>
 *   <li>验证统一任务分发框架的 Agent 接入流程</li>
 *   <li>前端开发调试（不需要启动 Worker）</li>
 *   <li>集成测试桩</li>
 * </ul>
 */
public class EchoA2aAgent implements A2aAgent {

    private final String agentId;
    private final String agentName;

    /** 内存中的任务存储 */
    private final Map<String, A2aTask> taskStore = new LinkedHashMap<>();

    public EchoA2aAgent(String agentId, String agentName) {
        this.agentId = agentId;
        this.agentName = agentName;
    }

    @Override
    public A2aAgentCard getAgentCard() {
        return A2aAgentCard.builder()
                .id(agentId)
                .name(agentName)
                .description("Echo Agent — mirrors your prompt back as the result (for testing)")
                .version("1.0.0")
                .skills(List.of(
                        A2aAgentSkill.builder()
                                .id("echo")
                                .name("Echo")
                                .description("Echo back user input")
                                .tags(List.of("echo", "test", "debug"))
                                .build()
                ))
                .build();
    }

    @Override
    public A2aTask sendTask(A2aMessage message) {
        String taskId = "echo-" + UUID.randomUUID().toString().substring(0, 8);

        // 提取 prompt
        String prompt = message.getParts() != null
                ? message.getParts().stream()
                    .filter(p -> "text".equals(p.getType()) && p.getText() != null)
                    .map(A2aPart::getText)
                    .findFirst().orElse("(no text)")
                : "(no parts)";

        // 直接返回 COMPLETED（echo 不需要异步执行）
        A2aTask task = A2aTask.builder()
                .id(taskId)
                .contextId(message.getContextId())
                .status(A2aTaskStatus.builder()
                        .state(A2aTaskState.COMPLETED)
                        .description("Echo completed")
                        .timestamp(Instant.now())
                        .build())
                .artifacts(List.of(
                        A2aArtifact.builder()
                                .artifactId("echo-result")
                                .name("Echo Result")
                                .parts(List.of(A2aPart.text("[Echo Agent] " + prompt)))
                                .build()
                ))
                .metadata(Map.of(
                        "agentId", agentId,
                        "providerType", "echo-agent"
                ))
                .build();

        taskStore.put(taskId, task);
        return task;
    }

    @Override
    public Optional<A2aTask> getTask(String taskId) {
        return Optional.ofNullable(taskStore.get(taskId));
    }

    @Override
    public void cancelTask(String taskId) {
        A2aTask task = taskStore.get(taskId);
        if (task != null) {
            task.setStatus(A2aTaskStatus.builder()
                    .state(A2aTaskState.CANCELED)
                    .description("Cancelled by user")
                    .timestamp(Instant.now())
                    .build());
        }
    }

    @Override
    public boolean isAvailable() {
        return true;
    }
}
