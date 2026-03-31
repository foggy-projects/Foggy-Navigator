package com.foggy.navigator.session.controller;

import com.foggy.navigator.agent.framework.session.SessionCreateRequest;
import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.common.dto.a2a.*;
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
import org.springframework.web.bind.annotation.*;

import java.util.*;

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
    private final SessionManager sessionManager;
    @Nullable
    private final AgentContextStore contextStore;

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

        // 3. 获取 Agent Card（后续多处需要 agentName）
        A2aAgentCard card = agent.getAgentCard();

        // 4. 组装 prompt（注入 systemPrompt 约束）
        String prompt = question;
        if (keyEntity.getSystemPrompt() != null && !keyEntity.getSystemPrompt().isBlank()) {
            prompt = keyEntity.getSystemPrompt() + "\n\n---\n用户提问：" + prompt;
        }

        // 5. 构建 A2aMessage（标准 A2A 协议）
        String contextId = form.getContextId();
        if (contextId == null || contextId.isBlank()) {
            contextId = IdGenerator.shortId();
        }

        // 5.1 创建/复用 Navigator Session（使共享调用出现在 Worker 页面历史会话中）
        String navigatorSessionId = ensureNavigatorSession(keyEntity, card.getName(), contextId);

        A2aMessage message = A2aMessage.user(List.of(A2aPart.text(prompt)));
        message.setContextId(contextId);
        message.setContextAlias(form.getContextAlias());
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("maxTurns", keyEntity.getMaxTurns());
        if (navigatorSessionId != null) {
            metadata.put("tracked", true);
            metadata.put("sessionId", navigatorSessionId);
        }
        message.setMetadata(metadata);

        // 5. 调用 Agent（同步阻塞，与 AgentDiscoveryController 完全一致）
        long start = System.currentTimeMillis();
        A2aTask task = agent.sendTask(message);
        long durationMs = System.currentTimeMillis() - start;

        if (task.getContextId() == null) {
            task.setContextId(contextId);
        }

        // 8. 记录咨询日志（source=SHARED）
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

    /**
     * 为共享调用创建或复用 Navigator Session，使其出现在 Worker 页面的历史会话列表中。
     * <p>
     * 复用策略：同一 SharingKey + contextId 共用同一 Session（通过 AgentContextStore 映射）。
     */
    private String ensureNavigatorSession(SharingKeyEntity keyEntity, String agentName, String contextId) {
        try {
            // 用 AgentContextStore 存储 contextId → navigatorSessionId 映射
            String storeKey = "shared-nav:" + keyEntity.getId() + ":" + contextId;
            if (contextStore != null) {
                Optional<String> existing = contextStore.findSessionRef(
                        storeKey, keyEntity.getOwnerUserId(), 720); // 30天 TTL
                if (existing.isPresent()) {
                    // 验证 session 仍然存在
                    if (sessionManager.getSession(existing.get()) != null) {
                        return existing.get();
                    }
                    log.warn("Shared navigator session {} was deleted, recreating", existing.get());
                }
            }

            // 创建新 Session（agentId="claude-worker" 使其出现在 Worker 页面）
            String label = keyEntity.getLabel() != null ? keyEntity.getLabel() : agentName;
            String sessionId = sessionManager.createSession(SessionCreateRequest.builder()
                    .userId(keyEntity.getOwnerUserId())
                    .agentId("claude-worker")
                    .taskName("Shared: " + label)
                    .build());

            // 保存映射
            if (contextStore != null) {
                contextStore.saveSessionRef(storeKey, "shared-nav",
                        sessionId, keyEntity.getOwnerUserId(), keyEntity.getAgentId());
            }

            log.info("Created navigator session for shared call: sessionId={}, keyId={}, contextId={}",
                    sessionId, keyEntity.getId(), contextId);
            return sessionId;
        } catch (Exception e) {
            log.warn("Failed to create navigator session for shared call: {}", e.getMessage());
            return null; // 降级：不创建 session，仍可正常执行查询
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
