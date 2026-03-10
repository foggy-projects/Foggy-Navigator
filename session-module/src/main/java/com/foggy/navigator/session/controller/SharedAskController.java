package com.foggy.navigator.session.controller;

import com.foggy.navigator.common.dto.a2a.*;
import com.foggy.navigator.common.entity.AgentConsultationEntity;
import com.foggy.navigator.common.entity.SharingKeyEntity;
import com.foggy.navigator.common.form.SharedAskForm;
import com.foggy.navigator.common.util.IdGenerator;
import com.foggy.navigator.session.registry.DefaultA2aAgentRegistry;
import com.foggy.navigator.session.repository.AgentConsultationRepository;
import com.foggy.navigator.session.service.SharingKeyService;
import com.foggy.navigator.spi.agent.A2aAgent;
import com.foggyframework.core.ex.RX;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 外部共享调用端点 — 通过 Sharing Key 调用 Agent（A2A 标准接口）
 * <p>
 * 不加 @RequireAuth，外部用户无需登录，通过 X-Sharing-Key 验证身份。
 * 返回标准 A2aTask，与 AgentDiscoveryController.askAgent() 格式一致。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/shared")
@RequiredArgsConstructor
public class SharedAskController {

    private final SharingKeyService sharingKeyService;
    private final DefaultA2aAgentRegistry registry;
    private final AgentConsultationRepository consultationRepository;

    /**
     * 外部调用端点 — 走 A2aAgent 标准接口，返回 A2aTask
     */
    @PostMapping("/ask")
    public RX<A2aTask> ask(
            @RequestHeader("X-Sharing-Key") String sharingKey,
            @RequestBody SharedAskForm form) {

        String question = form.getQuestion();
        if (question == null || question.isBlank()) {
            return RX.failA("question is required");
        }

        // 1. 验证 Sharing Key（含限额检查）
        SharingKeyEntity keyEntity;
        try {
            keyEntity = sharingKeyService.validateAndConsume(sharingKey);
        } catch (IllegalArgumentException e) {
            return RX.failA(e.getMessage());
        }

        // 2. 用 ownerUserId 解析 Agent（关键：跨用户 Agent 访问）
        A2aAgent agent = registry.resolveAgent(
                keyEntity.getAgentId(), keyEntity.getOwnerUserId())
                .orElse(null);
        if (agent == null) {
            return RX.failA("Shared agent not available");
        }

        // 3. 组装 prompt（注入 systemPrompt 约束）
        String prompt = question;
        if (keyEntity.getSystemPrompt() != null && !keyEntity.getSystemPrompt().isBlank()) {
            prompt = keyEntity.getSystemPrompt() + "\n\n---\n用户提问：" + prompt;
        }

        // 4. 构建 A2aMessage（标准 A2A 协议）
        String contextId = form.getContextId();
        if (contextId == null || contextId.isBlank()) {
            contextId = IdGenerator.shortId();
        }
        A2aMessage message = A2aMessage.user(List.of(A2aPart.text(prompt)));
        message.setContextId(contextId);
        message.setMetadata(Map.of("maxTurns", keyEntity.getMaxTurns()));

        // 5. 调用 Agent（同步阻塞，与 AgentDiscoveryController 完全一致）
        long start = System.currentTimeMillis();
        A2aTask task = agent.sendTask(message);
        long durationMs = System.currentTimeMillis() - start;

        if (task.getContextId() == null) {
            task.setContextId(contextId);
        }

        // 6. 记录咨询日志（source=SHARED）
        A2aAgentCard card = agent.getAgentCard();
        recordSharedConsultation(keyEntity, card.getName(), question, task, durationMs, contextId);

        // 7. 返回标准 A2aTask（与内部端点格式一致）
        return RX.ok(task);
    }

    /**
     * 记录共享调用咨询日志 — source=SHARED
     */
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
