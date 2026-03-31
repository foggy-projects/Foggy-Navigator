package com.foggy.navigator.session.controller;

import com.foggy.navigator.agent.framework.session.SessionCreateRequest;
import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.common.dto.a2a.A2aAgentCard;
import com.foggy.navigator.common.dto.a2a.A2aArtifact;
import com.foggy.navigator.common.dto.a2a.A2aMessage;
import com.foggy.navigator.common.dto.a2a.A2aPart;
import com.foggy.navigator.common.dto.a2a.A2aTask;
import com.foggy.navigator.common.dto.a2a.A2aTaskState;
import com.foggy.navigator.common.entity.AgentConsultationEntity;
import com.foggy.navigator.common.entity.SharingKeyEntity;
import com.foggy.navigator.common.form.SharedAskForm;
import com.foggy.navigator.common.util.IdGenerator;
import com.foggy.navigator.session.registry.DefaultA2aAgentRegistry;
import com.foggy.navigator.session.repository.AgentConsultationRepository;
import com.foggy.navigator.session.service.SharingKeyService;
import com.foggy.navigator.spi.agent.A2aAgent;
import com.foggy.navigator.spi.agent.AgentContextStore;
import com.foggyframework.core.ex.RX;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 外部共享调用端点，通过 Sharing Key 调用 Agent。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/shared")
@RequiredArgsConstructor
public class SharedAskController {

    private final SharingKeyService sharingKeyService;
    private final DefaultA2aAgentRegistry registry;
    private final AgentConsultationRepository consultationRepository;
    private final SessionManager sessionManager;
    @Nullable
    private final AgentContextStore contextStore;

    @PostMapping("/ask")
    public RX<A2aTask> ask(
            @RequestHeader("X-Sharing-Key") String sharingKey,
            @RequestBody SharedAskForm form) {

        String question = form.getQuestion();
        if (question == null || question.isBlank()) {
            return RX.failA("question is required");
        }

        SharingKeyEntity keyEntity;
        try {
            keyEntity = sharingKeyService.validateAndConsume(sharingKey);
        } catch (IllegalArgumentException e) {
            return RX.failA(e.getMessage());
        }

        A2aAgent agent = registry.resolveAgent(
                keyEntity.getAgentId(), keyEntity.getOwnerUserId())
                .orElse(null);
        if (agent == null) {
            return RX.failA("Shared agent not available");
        }

        A2aAgentCard card = agent.getAgentCard();
        String systemPrompt = form.getSystemPrompt();
        if (systemPrompt == null || systemPrompt.isBlank()) {
            systemPrompt = keyEntity.getSystemPrompt();
        }
        String firstMsg = form.getFirstMsg();

        String contextId = form.getContextId();
        if (contextId == null || contextId.isBlank()) {
            contextId = IdGenerator.shortId();
        }

        String navigatorSessionId = ensureNavigatorSession(keyEntity, card.getName(), contextId);

        A2aMessage message = A2aMessage.user(List.of(A2aPart.text(question)));
        message.setContextId(contextId);
        message.setContextAlias(form.getContextAlias());

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("maxTurns", keyEntity.getMaxTurns());
        if (navigatorSessionId != null) {
            metadata.put("tracked", true);
            metadata.put("sessionId", navigatorSessionId);
        }
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            metadata.put("systemPrompt", systemPrompt);
        }
        if (firstMsg != null && !firstMsg.isBlank()) {
            metadata.put("firstMsg", firstMsg);
        }
        message.setMetadata(metadata);

        long start = System.currentTimeMillis();
        A2aTask task = agent.sendTask(message);
        long durationMs = System.currentTimeMillis() - start;

        if (task.getContextId() == null) {
            task.setContextId(contextId);
        }

        recordSharedConsultation(keyEntity, card.getName(), question, task, durationMs, contextId);
        return RX.ok(task);
    }

    private void recordSharedConsultation(SharingKeyEntity keyEntity, String agentName,
                                          String question, A2aTask task,
                                          long durationMs, String contextId) {
        try {
            AgentConsultationEntity entity = new AgentConsultationEntity();
            entity.setId(UUID.randomUUID().toString());
            entity.setSessionId("shared-" + keyEntity.getId());
            entity.setUserId(keyEntity.getOwnerUserId());
            entity.setTargetAgentId(keyEntity.getAgentId());
            entity.setTargetAgentName(agentName);
            entity.setQuestion(question);
            entity.setDurationMs(durationMs);
            entity.setContextId(contextId);
            entity.setSource("SHARED");
            entity.setSharingKeyId(keyEntity.getId());

            String answer = extractAnswer(task);
            entity.setAnswer(answer);

            boolean failed = task.getStatus() != null
                    && task.getStatus().getState() == A2aTaskState.FAILED;
            entity.setStatus(failed ? "FAILED" : "COMPLETED");

            consultationRepository.save(entity);
        } catch (Exception e) {
            log.warn("Failed to record shared consultation: {}", e.getMessage());
        }
    }

    private String ensureNavigatorSession(SharingKeyEntity keyEntity, String agentName, String contextId) {
        try {
            String storeKey = "shared-nav:" + keyEntity.getId() + ":" + contextId;
            if (contextStore != null) {
                Optional<String> existing = contextStore.findSessionRef(
                        storeKey, keyEntity.getOwnerUserId(), 720);
                if (existing.isPresent()) {
                    if (sessionManager.getSession(existing.get()) != null) {
                        return existing.get();
                    }
                    log.warn("Shared navigator session {} was deleted, recreating", existing.get());
                }
            }

            String label = keyEntity.getLabel() != null ? keyEntity.getLabel() : agentName;
            String sessionId = sessionManager.createSession(SessionCreateRequest.builder()
                    .userId(keyEntity.getOwnerUserId())
                    .agentId("claude-worker")
                    .taskName("Shared: " + label)
                    .build());

            if (contextStore != null) {
                contextStore.saveSessionRef(storeKey, "shared-nav",
                        sessionId, keyEntity.getOwnerUserId(), keyEntity.getAgentId());
            }

            log.info("Created navigator session for shared call: sessionId={}, keyId={}, contextId={}",
                    sessionId, keyEntity.getId(), contextId);
            return sessionId;
        } catch (Exception e) {
            log.warn("Failed to create navigator session for shared call: {}", e.getMessage());
            return null;
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
}
