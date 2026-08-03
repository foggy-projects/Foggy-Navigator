package com.foggy.navigator.session.controller;

import com.foggy.navigator.common.dto.a2a.A2aAgentCard;
import com.foggy.navigator.common.dto.a2a.A2aMessage;
import com.foggy.navigator.common.dto.a2a.A2aPart;
import com.foggy.navigator.common.dto.a2a.A2aTask;
import com.foggy.navigator.common.dto.CurrentUser;
import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.entity.AgentConsultationEntity;
import com.foggy.navigator.common.form.SharedAskForm;
import com.foggy.navigator.session.agent.pipeline.AgentSubmitPipeline;
import com.foggy.navigator.session.agent.pipeline.AgentTaskSubmitResult;
import com.foggy.navigator.session.registry.UnifiedAgentResolver;
import com.foggy.navigator.session.repository.AgentConsultationRepository;
import com.foggy.navigator.session.service.ScopedSharedTaskCreateCommandAdapter;
import com.foggy.navigator.spi.agent.A2aAgent;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import com.foggy.navigator.spi.agent.AgentTaskSubmitRequest;
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

    private final UnifiedAgentResolver agentResolver;
    private final AgentConsultationRepository consultationRepository;
    private final AgentSubmitPipeline agentSubmitPipeline;
    private final ScopedSharedTaskCreateCommandAdapter scopedTaskCreateCommandAdapter;

    @PostMapping("/ask")
    public RX<A2aTask> ask(
            @RequestHeader("X-Sharing-Key") String sharingKey,
            @RequestHeader(value = "X-Navigator-Client-Request-Id", required = false)
            String clientRequestId,
            @RequestBody SharedAskForm form) {

        String question = form.getQuestion();
        if (question == null || question.isBlank()) {
            return RX.failA("question is required");
        }

        CurrentUser ambientUser = UserContext.getCurrentUser();
        UserContext.clear();
        try {
            return askWithSharedAuthority(
                    sharingKey, clientRequestId, form, question);
        } finally {
            if (ambientUser == null) {
                UserContext.clear();
            } else {
                UserContext.setCurrentUser(ambientUser);
            }
        }
    }

    private RX<A2aTask> askWithSharedAuthority(
            String sharingKey,
            String clientRequestId,
            SharedAskForm form,
            String question) {
        ScopedSharedTaskCreateCommandAdapter.SharedCommandScope scope;
        try {
            scope = scopedTaskCreateCommandAdapter.mintScope(
                    sharingKey, clientRequestId);
        } catch (IllegalArgumentException e) {
            return RX.failA(e.getMessage());
        }

        AgentResolveContext context = scope.newResolveContext();
        A2aAgent agent = agentResolver.resolveAgent(
                scope.agentId(), context)
                .orElse(null);
        if (agent == null) {
            return RX.failA("Shared agent not available");
        }
        A2aAgentCard card = agent.getAgentCard();
        String systemPrompt = form.getSystemPrompt();
        String firstMsg = form.getFirstMsg();

        A2aMessage message = A2aMessage.user(List.of(A2aPart.text(question)));
        String contextId = form.getContextId();
        if (contextId != null && !contextId.isBlank()) {
            message.setContextId(contextId);
        }
        message.setContextAlias(form.getContextAlias());

        Map<String, Object> metadata = new HashMap<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            metadata.put("systemPrompt", systemPrompt);
        }
        if (firstMsg != null && !firstMsg.isBlank()) {
            metadata.put("firstMsg", firstMsg);
        }
        message.setMetadata(metadata);

        long start = System.currentTimeMillis();
        AgentTaskSubmitRequest submitRequest = AgentTaskSubmitRequest.builder()
                .clientRequestId(scope.clientRequestId())
                .agentId(scope.agentId())
                .resolveContext(context)
                .message(message)
                .prompt(question)
                .contextId(contextId)
                .contextAlias(form.getContextAlias())
                .metadata(metadata)
                .build();
        AgentTaskSubmitResult submitResult;
        try {
            submitResult = scopedTaskCreateCommandAdapter.executeScoped(
                    scope,
                    submitRequest,
                    new ScopedSharedTaskCreateCommandAdapter.FreshParticipants() {
                        @Override
                        public void prepareFreshTask() {
                            // Shared ask has no pre-Provider auxiliary mutation.
                        }

                        @Override
                        public void completeFreshTask(DispatchTaskDTO freshTask) {
                            recordSharedConsultation(
                                    scope,
                                    card.getName(),
                                    question,
                                    freshTask,
                                    start,
                                    contextId);
                        }
                    },
                    () -> agentSubmitPipeline.submit(submitRequest));
        } catch (ScopedSharedTaskCreateCommandAdapter
                .SharedCommandAdmissionRejectedException rejection) {
            return RX.failA(rejection.getMessage());
        }
        A2aTask task = submitResult.getTask();

        if (task.getContextId() == null && contextId != null && !contextId.isBlank()) {
            task.setContextId(contextId);
        }

        return RX.ok(task);
    }

    private void recordSharedConsultation(
            ScopedSharedTaskCreateCommandAdapter.SharedCommandScope scope,
            String agentName,
            String question,
            DispatchTaskDTO task,
            long startedAtMs,
            String requestedContextId) {
        try {
            AgentConsultationEntity entity = new AgentConsultationEntity();
            entity.setId(UUID.randomUUID().toString());
            entity.setSessionId("shared-" + scope.sharingKeyId());
            entity.setUserId(scope.ownerUserId());
            entity.setTargetAgentId(scope.agentId());
            entity.setTargetAgentName(agentName);
            entity.setQuestion(question);
            entity.setDurationMs(Math.max(0L, System.currentTimeMillis() - startedAtMs));
            entity.setContextId(hasText(task.getContextId())
                    ? task.getContextId() : requestedContextId);
            entity.setSource("SHARED");
            entity.setSharingKeyId(scope.sharingKeyId());
            entity.setAnswer(task.getResultText());

            boolean failed = "FAILED".equals(task.getStatus());
            entity.setStatus(failed ? "FAILED" : "COMPLETED");

            consultationRepository.save(entity);
        } catch (Exception e) {
            log.warn("Failed to record shared consultation: {}", e.getMessage());
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
