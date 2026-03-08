package com.foggy.navigator.coding.agent.api.service;

import com.foggy.navigator.coding.agent.api.model.entity.ConversationEntity;
import com.foggy.navigator.coding.agent.api.repository.ConversationRepository;
import com.foggy.navigator.common.service.CodingConversationLookup;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Coding 会话信息查询服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CodingConversationLookupImpl implements CodingConversationLookup {

    private final ConversationRepository conversationRepository;
    private final ConversationService conversationService;

    @Override
    public Map<String, Object> getConversationInfo(String sessionId) {
        return conversationRepository.findBySessionId(sessionId)
                .map(this::toInfoMap)
                .orElse(null);
    }

    @Override
    public Map<String, Map<String, Object>> getConversationInfoBatch(List<String> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return Map.of();
        }

        List<ConversationEntity> conversations = conversationRepository.findBySessionIdIn(sessionIds);

        return conversations.stream()
                .filter(c -> c.getSessionId() != null)
                .collect(Collectors.toMap(
                        ConversationEntity::getSessionId,
                        this::toInfoMap
                ));
    }

    private Map<String, Object> toInfoMap(ConversationEntity entity) {
        Map<String, Object> info = new HashMap<>();
        info.put("conversationId", entity.getConversationId());
        info.put("sandboxStatus", entity.getStatus() != null ? entity.getStatus().name() : null);
        info.put("gitRepoUrl", entity.getGitRepoUrl());
        info.put("workingBranch", entity.getWorkingBranch());
        info.put("sandboxId", entity.getSandboxId());
        return info;
    }

    @Override
    public boolean deleteConversationBySessionId(String sessionId) {
        return conversationRepository.findBySessionId(sessionId)
                .map(entity -> {
                    try {
                        // 使用 deleteConversationOnly（不删除 Session，因为由 session-module 删除）
                        conversationService.deleteConversationOnly(entity.getConversationId());
                        log.info("Deleted conversation by sessionId: sessionId={}, conversationId={}",
                                sessionId, entity.getConversationId());
                        return true;
                    } catch (Exception e) {
                        log.error("Failed to delete conversation by sessionId: sessionId={}, error={}",
                                sessionId, e.getMessage(), e);
                        return false;
                    }
                })
                .orElse(true); // 没有关联的 Conversation，视为成功
    }
}
