package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.common.entity.SessionEntity;
import com.foggy.navigator.common.repository.SessionEntityRepository;
import com.foggy.navigator.spi.claude.ConversationStateQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ConversationStateQuery SPI 实现
 * 通过 SessionEntity 查询会话交互状态
 */
@Component
@RequiredArgsConstructor
public class ConversationStateQueryImpl implements ConversationStateQuery {

    private final SessionEntityRepository sessionRepository;

    @Override
    public List<String> findSessionIdsByStates(List<String> states) {
        if (states == null || states.isEmpty()) {
            return List.of();
        }
        return sessionRepository.findSessionIdsByStates(states);
    }

    @Override
    public Map<String, List<String>> findSessionIdsByStatesGroupByUser(List<String> states) {
        if (states == null || states.isEmpty()) {
            return Map.of();
        }
        List<SessionEntity> sessions = sessionRepository.findByInteractionStateIn(states);
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (SessionEntity session : sessions) {
            result.computeIfAbsent(session.getUserId(), k -> new ArrayList<>()).add(session.getId());
        }
        return result;
    }
}
