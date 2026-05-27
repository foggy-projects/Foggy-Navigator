package com.foggy.navigator.session.controller;

import com.foggy.navigator.common.dto.a2a.A2aAgentCard;
import com.foggy.navigator.common.dto.a2a.A2aArtifact;
import com.foggy.navigator.common.dto.a2a.A2aMessage;
import com.foggy.navigator.common.dto.a2a.A2aPart;
import com.foggy.navigator.common.dto.a2a.A2aTask;
import com.foggy.navigator.common.dto.a2a.A2aTaskState;
import com.foggy.navigator.common.entity.AgentConsultationEntity;
import com.foggy.navigator.common.entity.SharingKeyEntity;
import com.foggy.navigator.common.form.SharedAskForm;
import com.foggy.navigator.session.agent.TaskSubmittingA2aAgentDecorator;
import com.foggy.navigator.session.agent.pipeline.AgentSubmitPipeline;
import com.foggy.navigator.session.registry.UnifiedAgentResolver;
import com.foggy.navigator.session.repository.AgentConsultationRepository;
import com.foggy.navigator.session.service.SharingKeyService;
import com.foggy.navigator.spi.agent.A2aAgent;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import com.foggy.navigator.spi.agent.AgentTaskSubmitRequest;
import com.foggy.navigator.spi.agent.TaskSubmittingA2aAgent;
import com.foggyframework.core.ex.RX;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final UnifiedAgentResolver agentResolver;
    private final AgentConsultationRepository consultationRepository;
    private final AgentSubmitPipeline agentSubmitPipeline;

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
            sharingKeyService.checkOperation(keyEntity, "ask");
        } catch (IllegalArgumentException e) {
            return RX.failA(e.getMessage());
        }

        AgentResolveContext context = buildSharedContext(keyEntity);
        A2aAgent agent = agentResolver.resolveAgent(
                keyEntity.getAgentId(), context)
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

        A2aMessage message = A2aMessage.user(List.of(A2aPart.text(question)));
        String contextId = form.getContextId();
        if (contextId != null && !contextId.isBlank()) {
            message.setContextId(contextId);
        }
        message.setContextAlias(form.getContextAlias());

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("maxTurns", keyEntity.getMaxTurns());
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            metadata.put("systemPrompt", systemPrompt);
        }
        if (firstMsg != null && !firstMsg.isBlank()) {
            metadata.put("firstMsg", firstMsg);
        }
        message.setMetadata(metadata);

        long start = System.currentTimeMillis();
        TaskSubmittingA2aAgent submittingAgent = new TaskSubmittingA2aAgentDecorator(
                agent, agentSubmitPipeline, keyEntity.getAgentId(), context);
        A2aTask task = submittingAgent.submitTask(AgentTaskSubmitRequest.builder()
                .agentId(keyEntity.getAgentId())
                .resolveContext(context)
                .message(message)
                .prompt(question)
                .maxTurns(keyEntity.getMaxTurns())
                .contextId(contextId)
                .contextAlias(form.getContextAlias())
                .metadata(metadata)
                .build());
        long durationMs = System.currentTimeMillis() - start;

        if (task.getContextId() == null && contextId != null && !contextId.isBlank()) {
            task.setContextId(contextId);
        }

        recordSharedConsultation(keyEntity, card.getName(), question, task, durationMs, task.getContextId());
        return RX.ok(task);
    }

    private AgentResolveContext buildSharedContext(SharingKeyEntity keyEntity) {
        return AgentResolveContext.builder()
                .userId(keyEntity.getOwnerUserId())
                .requestSource("SHARED_API")
                .build();
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
