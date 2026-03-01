package com.foggy.navigator.session.controller;

import com.foggy.navigator.common.annotation.RequireAuth;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.a2a.*;
import com.foggy.navigator.common.entity.AgentConsultationEntity;
import com.foggy.navigator.common.entity.SessionEntity;
import com.foggy.navigator.session.registry.DefaultA2aAgentRegistry;
import com.foggy.navigator.session.repository.AgentConsultationRepository;
import com.foggy.navigator.session.repository.SessionRepository;
import com.foggy.navigator.spi.agent.A2aAgent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    private final DefaultA2aAgentRegistry registry;
    private final AgentConsultationRepository consultationRepository;
    private final SessionRepository sessionRepository;
    private final ObjectMapper objectMapper;

    @GetMapping
    public RX<List<A2aAgentCard>> listAgents(
            @RequestParam(required = false) String type) {
        String userId = UserContext.getCurrentUserId();
        List<A2aAgentCard> cards = (type != null)
                ? registry.listByProviderType(type, userId)
                : registry.listAgents(userId);
        return RX.ok(cards);
    }

    @GetMapping("/{agentId}/card")
    public RX<A2aAgentCard> getAgentCard(@PathVariable String agentId) {
        String userId = UserContext.getCurrentUserId();
        return registry.resolveAgent(agentId, userId)
                .map(a -> RX.ok(a.getAgentCard()))
                .orElse(RX.failA("Agent not found: " + agentId));
    }

    @PostMapping("/{agentId}/ask")
    public RX<A2aTask> askAgent(
            @PathVariable String agentId,
            @RequestBody Map<String, String> body) {
        String userId = UserContext.getCurrentUserId();
        String question = body.get("question");
        String sessionId = body.get("sessionId");
        if (question == null || question.isBlank()) {
            return RX.failA("question is required");
        }
        A2aAgent agent = registry.resolveAgent(agentId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + agentId));

        long start = System.currentTimeMillis();
        A2aMessage message = A2aMessage.user(List.of(A2aPart.text(question)));
        A2aTask task = agent.sendTask(message);
        long durationMs = System.currentTimeMillis() - start;

        // 记录 consultation
        if (sessionId != null && !sessionId.isBlank()) {
            A2aAgentCard card = agent.getAgentCard();
            recordConsultation(sessionId, userId, agentId, card.getName(), question, task, durationMs);
            updateParticipatingAgents(sessionId, agentId);
        }

        return RX.ok(task);
    }

    /** 查询某会话的所有 @agent 咨询记录 */
    @GetMapping("/consultations")
    public RX<List<AgentConsultationEntity>> listConsultations(@RequestParam String sessionId) {
        return RX.ok(consultationRepository.findBySessionIdOrderByCreatedAtAsc(sessionId));
    }

    private void recordConsultation(String sessionId, String userId, String agentId,
                                    String agentName, String question, A2aTask task, long durationMs) {
        try {
            AgentConsultationEntity entity = new AgentConsultationEntity();
            entity.setId(UUID.randomUUID().toString());
            entity.setSessionId(sessionId);
            entity.setUserId(userId);
            entity.setTargetAgentId(agentId);
            entity.setTargetAgentName(agentName);
            entity.setQuestion(question);
            entity.setDurationMs(durationMs);

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
}
