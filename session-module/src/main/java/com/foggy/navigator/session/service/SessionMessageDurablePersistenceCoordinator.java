package com.foggy.navigator.session.service;

import com.foggy.navigator.agent.framework.protocol.AgentMessage;
import com.foggy.navigator.agent.framework.session.Message;
import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.session.service.payload.SessionMessagePayloadRoutingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Coordinates descriptor and session-message persistence in one database
 * transaction. The payload file is deliberately written first: a database
 * rollback leaves at most an idempotently reusable orphan, while a database
 * failure still propagates to durable SSE relays and therefore prevents ACK.
 */
@Service
@RequiredArgsConstructor
public class SessionMessageDurablePersistenceCoordinator {

    private final SessionMessagePayloadRoutingService payloadRoutingService;
    private final AgentMessageSessionMessageMapper messageMapper;
    private final SessionManager sessionManager;

    @Transactional
    public void persist(AgentMessage message) {
        payloadRoutingService.prepareForDurablePersistence(message);
        Message sessionMessage = messageMapper.toSessionMessage(message);
        sessionManager.addMessage(message.getSessionId(), sessionMessage);
    }
}
