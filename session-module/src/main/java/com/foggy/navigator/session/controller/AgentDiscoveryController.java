package com.foggy.navigator.session.controller;

import com.foggy.navigator.common.annotation.RequireAuth;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.a2a.*;
import com.foggy.navigator.common.entity.AgentConsultationEntity;
import com.foggy.navigator.common.entity.SessionEntity;
import com.foggy.navigator.session.repository.AgentConsultationRepository;
import com.foggy.navigator.session.repository.SessionRepository;
import com.foggy.navigator.session.registry.UnifiedAgentResolver;
import com.foggy.navigator.spi.agent.A2aAgent;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.common.util.IdGenerator;
import com.foggyframework.core.ex.RX;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 统一 Agent 发现 API — 只读端点，聚合所有 Provider 管理的 Agent
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/agents")
@RequireAuth
@RequiredArgsConstructor
public class AgentDiscoveryController {

    private final UnifiedAgentResolver agentResolver;
    private final AgentConsultationRepository consultationRepository;
    private final SessionRepository sessionRepository;
    private final ObjectMapper objectMapper;

    @GetMapping
    public RX<List<A2aAgentCard>> listAgents(
            @RequestParam(required = false) String type) {
        AgentResolveContext context = buildContext();
        List<A2aAgentCard> cards = (type != null)
                ? agentResolver.listByProviderType(type, context)
                : agentResolver.listAgents(context);
        return RX.ok(cards);
    }

    @GetMapping("/{agentId}/card")
    public RX<A2aAgentCard> getAgentCard(@PathVariable String agentId) {
        return agentResolver.resolveAgent(agentId, buildContext())
                .map(a -> RX.ok(a.getAgentCard()))
                .orElse(RX.failA("Agent not found: " + agentId));
    }

    /**
     * 向 Agent 提问（异步模式）
     *
     * 立即返回 SUBMITTED 状态的 A2aTask（含 taskId），
     * 调用者通过 GET /{agentId}/tasks/{taskId} 轮询状态获取结果。
     */
    @PostMapping("/{agentId}/ask")
    public RX<A2aTask> askAgent(
            @PathVariable String agentId,
            @RequestBody Map<String, String> body) {
        String userId = UserContext.getCurrentUserId();
        String question = body.get("question");
        String sessionId = body.get("sessionId");
        String systemPrompt = body.get("systemPrompt");
        String firstMsg = body.get("firstMsg");
        if (question == null || question.isBlank()) {
            return RX.failA("question is required");
        }
        AgentResolveContext context = AgentResolveContext.builder()
                .userId(userId)
                .requestSource("UI")
                .modelConfigId(body.get("modelConfigId"))
                .build();
        A2aAgent agent = agentResolver.resolveAgent(agentId, context)
                .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + agentId));

        // contextId: 客户端传入则透传，否则平台生成
        String contextId = body.get("contextId");
        if (contextId == null || contextId.isBlank()) {
            contextId = IdGenerator.shortId();
        }

        String contextAlias = body.get("contextAlias");

        A2aMessage message = A2aMessage.user(List.of(A2aPart.text(question)));
        message.setContextId(contextId);
        message.setContextAlias(contextAlias);

        // 将 sessionId 通过 metadata 传递给 Agent（后台回调用于持久化消息）
        Map<String, Object> metadata = new HashMap<>();
        if (sessionId != null && !sessionId.isBlank()) {
            metadata.put("tracked", true);
            metadata.put("sessionId", sessionId);
            updateParticipatingAgents(sessionId, agentId);
        }
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            metadata.put("systemPrompt", systemPrompt);
        }
        if (firstMsg != null && !firstMsg.isBlank()) {
            metadata.put("firstMsg", firstMsg);
        }
        if (!metadata.isEmpty()) {
            message.setMetadata(metadata);
        }

        A2aTask task = agent.sendTask(message);  // ← 立即返回 SUBMITTED

        // 确保返回值包含 contextId
        if (task.getContextId() == null) {
            task.setContextId(contextId);
        }

        return RX.ok(task);
    }

    /**
     * 查询 A2A 任务状态（轮询端点）
     *
     * 返回任务当前状态，COMPLETED 时包含 artifacts（结果文本）。
     */
    @GetMapping("/{agentId}/tasks/{taskId}")
    public RX<A2aTask> getTaskStatus(
            @PathVariable String agentId,
            @PathVariable String taskId) {
        A2aAgent agent = agentResolver.resolveAgent(agentId, buildContext())
                .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + agentId));
        A2aTask task = agent.getTask(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        return RX.ok(task);
    }

    /**
     * 取消 A2A 任务
     */
    @PostMapping("/{agentId}/tasks/{taskId}/cancel")
    public RX<String> cancelTask(
            @PathVariable String agentId,
            @PathVariable String taskId) {
        A2aAgent agent = agentResolver.resolveAgent(agentId, buildContext())
                .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + agentId));
        agent.cancelTask(taskId);
        return RX.ok("Task cancel requested");
    }

    /** 查询某会话的所有 @agent 咨询记录 */
    @GetMapping("/consultations")
    public RX<List<AgentConsultationEntity>> listConsultations(@RequestParam String sessionId) {
        return RX.ok(consultationRepository.findBySessionIdOrderByCreatedAtAsc(sessionId));
    }

    private void recordConsultation(String sessionId, String userId, String agentId,
                                    String agentName, String question, A2aTask task,
                                    long durationMs, String contextId) {
        try {
            AgentConsultationEntity entity = new AgentConsultationEntity();
            entity.setId(UUID.randomUUID().toString());
            entity.setSessionId(sessionId);
            entity.setUserId(userId);
            entity.setTargetAgentId(agentId);
            entity.setTargetAgentName(agentName);
            entity.setQuestion(question);
            entity.setDurationMs(durationMs);
            entity.setContextId(contextId);

            // 从 task artifacts 提取 answer
            String answer = extractAnswer(task);
            entity.setAnswer(answer);

            boolean failed = task.getStatus() != null
                    && task.getStatus().getState() == A2aTaskState.FAILED;
            entity.setStatus(failed ? "FAILED" : "COMPLETED");

            consultationRepository.save(entity);
        } catch (Exception e) {
            log.warn("Failed to record agent consultation: {}", e.getMessage());
        }
    }

    private String extractAnswer(A2aTask task) {
        if (task.getArtifacts() == null) return null;
        for (A2aArtifact artifact : task.getArtifacts()) {
            if (artifact.getParts() == null) continue;
            for (A2aPart part : artifact.getParts()) {
                if ("text".equals(part.getType()) && part.getText() != null) {
                    return part.getText();
                }
            }
        }
        return null;
    }

    private void updateParticipatingAgents(String sessionId, String agentId) {
        try {
            SessionEntity session = sessionRepository.findById(sessionId).orElse(null);
            if (session == null) return;

            List<String> agentIds;
            String existing = session.getParticipatingAgentIds();
            if (existing != null && !existing.isBlank()) {
                agentIds = objectMapper.readValue(existing, new TypeReference<>() {});
            } else {
                agentIds = new ArrayList<>();
            }

            if (!agentIds.contains(agentId)) {
                agentIds.add(agentId);
                session.setParticipatingAgentIds(objectMapper.writeValueAsString(agentIds));
                sessionRepository.save(session);
            }
        } catch (Exception e) {
            log.warn("Failed to update participating agents: {}", e.getMessage());
        }
    }

    private AgentResolveContext buildContext() {
        return AgentResolveContext.builder()
                .userId(UserContext.getCurrentUserId())
                .requestSource("UI")
                .build();
    }
}
