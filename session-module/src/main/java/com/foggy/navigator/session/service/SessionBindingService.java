package com.foggy.navigator.session.service;

import com.foggy.navigator.common.entity.SessionEntity;
import com.foggy.navigator.session.exception.SessionAgentBoundMismatchException;
import com.foggy.navigator.session.repository.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 会话 ↔ Agent 绑定管理。
 * <p>
 * 核心规则：
 * <ul>
 *   <li>新会话首次发任务时绑定 Agent（agentId + providerType）</li>
 *   <li>已绑定会话不允许切换到不同 Agent（跨 Agent 漂移）</li>
 *   <li>旧会话（agentId 已设但 providerType 为空）在首次访问时自动补填</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionBindingService {

    private final SessionRepository sessionRepository;

    /**
     * 获取已有绑定或创建新绑定。
     *
     * @param sessionId       平台会话 ID
     * @param agentId         本次请求的目标 Agent ID
     * @param providerType    Provider 类型（"claude-worker" / "codex-worker"）
     * @param bindingSource   绑定来源（"EXPLICIT_AGENT" / "LEGACY_MODEL_CONFIG" / "RESTORED"）
     * @return 实际绑定的 agentId
     * @throws SessionAgentBoundMismatchException 如果检测到跨 Agent 漂移
     */
    @Transactional
    public String getOrBind(String sessionId, String agentId, String providerType, String bindingSource) {
        SessionEntity session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null) {
            // 会话尚未创建（将由 SessionManager.createSession 创建），返回目标 agentId
            return agentId;
        }

        String existingAgentId = session.getAgentId();

        // Case 1: 已有完整绑定
        if (existingAgentId != null && !existingAgentId.isBlank()
                && session.getProviderType() != null) {
            if (!existingAgentId.equals(agentId)) {
                throw new SessionAgentBoundMismatchException(sessionId, existingAgentId, agentId);
            }
            return existingAgentId;
        }

        // Case 2: 旧会话 —— agentId 已设但 providerType 为空，补填
        if (existingAgentId != null && !existingAgentId.isBlank()) {
            if (!existingAgentId.equals(agentId)) {
                throw new SessionAgentBoundMismatchException(sessionId, existingAgentId, agentId);
            }
            session.setProviderType(providerType);
            session.setBindingSource("RESTORED");
            sessionRepository.save(session);
            log.info("Restored binding for session [{}]: agentId={}, providerType={}",
                    sessionId, agentId, providerType);
            return existingAgentId;
        }

        // Case 3: 全新绑定
        session.setAgentId(agentId);
        session.setProviderType(providerType);
        session.setBindingSource(bindingSource);
        sessionRepository.save(session);
        log.info("Bound session [{}] to agent [{}], provider={}, source={}",
                sessionId, agentId, providerType, bindingSource);
        return agentId;
    }

    /**
     * 仅校验绑定一致性，不做写入（用于读操作场景）
     */
    public void validateBinding(String sessionId, String requestedAgentId) {
        SessionEntity session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null) return;

        String existingAgentId = session.getAgentId();
        if (existingAgentId != null && !existingAgentId.isBlank()
                && !existingAgentId.equals(requestedAgentId)) {
            throw new SessionAgentBoundMismatchException(sessionId, existingAgentId, requestedAgentId);
        }
    }
}
