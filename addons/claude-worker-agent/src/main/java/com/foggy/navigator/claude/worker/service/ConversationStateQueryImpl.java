package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.claude.worker.model.entity.ConversationConfigEntity;
import com.foggy.navigator.claude.worker.repository.ConversationConfigRepository;
import com.foggy.navigator.spi.claude.ConversationStateQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ConversationStateQuery SPI 实现
 * 通过 ConversationConfigRepository 查询会话交互状态
 */
@Component
@RequiredArgsConstructor
public class ConversationStateQueryImpl implements ConversationStateQuery {

    private final ConversationConfigRepository configRepository;

    @Override
    public List<String> findSessionIdsByStates(List<String> states) {
        if (states == null || states.isEmpty()) {
            return List.of();
        }
        return configRepository.findSessionIdsByStates(states);
    }

    @Override
    public Map<String, List<String>> findSessionIdsByStatesGroupByUser(List<String> states) {
        if (states == null || states.isEmpty()) {
            return Map.of();
        }
        List<ConversationConfigEntity> configs = configRepository.findByInteractionStates(states);
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (ConversationConfigEntity c : configs) {
            result.computeIfAbsent(c.getUserId(), k -> new ArrayList<>()).add(c.getSessionId());
        }
        return result;
    }
}
