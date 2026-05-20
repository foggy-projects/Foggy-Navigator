package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.dto.BusinessAgentSessionDTO;
import com.foggy.navigator.business.agent.model.dto.BusinessAgentSessionListDTO;
import com.foggy.navigator.business.agent.model.dto.BusinessAgentSessionMessagesDTO;
import com.foggy.navigator.business.agent.model.entity.BusinessAgentSessionEntity;
import com.foggy.navigator.business.agent.model.entity.BusinessAgentTaskEntity;
import com.foggy.navigator.business.agent.repository.BusinessAgentSessionMessageRepository;
import com.foggy.navigator.business.agent.repository.BusinessAgentSessionRepository;
import com.foggy.navigator.common.entity.SessionMessageEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BusinessAgentSessionServiceTest {

    @Mock
    private BusinessAgentSessionRepository sessionRepository;
    @Mock
    private BusinessAgentSessionMessageRepository messageRepository;
    @Mock
    private ClientAppUserGrantService userGrantService;

    private BusinessAgentSessionService service;

    @BeforeEach
    void setUp() {
        service = new BusinessAgentSessionService(sessionRepository, messageRepository, userGrantService);
        lenient().when(sessionRepository.save(any(BusinessAgentSessionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void bindTask_createsBusinessSessionScopedByClientAppAndUpstreamUser() {
        BusinessAgentTaskEntity task = task();
        when(sessionRepository.findByTenantIdAndClientAppIdAndUpstreamUserIdAndSessionId(
                "tenant_01", "app_01", "upstream_01", "session_01"))
                .thenReturn(Optional.empty());

        BusinessAgentSessionDTO result = service.bindTask(task, null, "{\"screen\":\"orders\"}");

        assertNotNull(result.getContextId());
        assertTrue(result.getContextId().matches("bctx_\\d{8}_[0-9a-f]{2}_[0-9a-f]{32}"));
        assertEquals("tenant_01", result.getTenantId());
        assertEquals("app_01", result.getClientAppId());
        assertEquals("upstream_01", result.getUpstreamUserId());
        assertEquals("upstream_01", result.getAccountId());
        assertEquals("skill_01", result.getSkillId());
        assertEquals("bt_01", result.getLatestTaskId());
        assertEquals(BusinessAgentSessionService.STATUS_ACTIVE, result.getStatus());

        ArgumentCaptor<BusinessAgentSessionEntity> captor = ArgumentCaptor.forClass(BusinessAgentSessionEntity.class);
        verify(sessionRepository).save(captor.capture());
        assertEquals("session_01", captor.getValue().getSessionId());
    }

    @Test
    void bindTask_rejectsContextMismatchForSameSession() {
        BusinessAgentSessionEntity existing = session("ctx_existing", "session_01");
        when(sessionRepository.findByTenantIdAndClientAppIdAndUpstreamUserIdAndSessionId(
                "tenant_01", "app_01", "upstream_01", "session_01"))
                .thenReturn(Optional.of(existing));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.bindTask(task(), "ctx_other", null));
        assertTrue(ex.getMessage().contains("context mismatch"));
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void listSessions_checksUpstreamGrantAndReturnsCursorPage() {
        when(sessionRepository.findByTenantIdAndClientAppIdAndUpstreamUserIdOrderByLastAccessedAtDesc(
                eq("tenant_01"), eq("app_01"), eq("upstream_01"), any(Pageable.class)))
                .thenReturn(List.of(session("ctx_1", "session_1"), session("ctx_2", "session_2")));

        BusinessAgentSessionListDTO result = service.listSessions(
                "tenant_01", "app_01", "upstream_01", null, 1);

        verify(userGrantService).checkUpstreamUserAccess("tenant_01", "app_01", "upstream_01");
        assertEquals(1, result.getSessions().size());
        assertEquals("ctx_1", result.getNextCursor());
        assertTrue(result.isHasMore());
    }

    @Test
    void getMessages_resolvesSessionByScopedContext() {
        BusinessAgentSessionEntity session = session("ctx_1", "session_1");
        when(sessionRepository.findByTenantIdAndClientAppIdAndUpstreamUserIdAndContextId(
                "tenant_01", "app_01", "upstream_01", "ctx_1"))
                .thenReturn(Optional.of(session));
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(eq("session_1"), any(Pageable.class)))
                .thenReturn(List.of(message("msg_1", "session_1"), message("msg_2", "session_1")));

        BusinessAgentSessionMessagesDTO result = service.getMessages(
                "tenant_01", "app_01", "upstream_01", "ctx_1", null, 1);

        verify(userGrantService).checkUpstreamUserAccess("tenant_01", "app_01", "upstream_01");
        assertEquals("ctx_1", result.getContextId());
        assertEquals("session_1", result.getSessionId());
        assertEquals(1, result.getMessages().size());
        assertEquals("msg_1", result.getNextCursor());
        assertTrue(result.isHasMore());
    }

    private BusinessAgentTaskEntity task() {
        BusinessAgentTaskEntity task = new BusinessAgentTaskEntity();
        task.setTaskId("bt_01");
        task.setTenantId("tenant_01");
        task.setClientAppId("app_01");
        task.setUpstreamUserId("upstream_01");
        task.setSessionId("session_01");
        task.setSkillId("skill_01");
        return task;
    }

    private BusinessAgentSessionEntity session(String contextId, String sessionId) {
        BusinessAgentSessionEntity entity = new BusinessAgentSessionEntity();
        entity.setTenantId("tenant_01");
        entity.setClientAppId("app_01");
        entity.setUpstreamUserId("upstream_01");
        entity.setAccountId("upstream_01");
        entity.setContextId(contextId);
        entity.setSessionId(sessionId);
        entity.setSkillId("skill_01");
        entity.setStatus(BusinessAgentSessionService.STATUS_ACTIVE);
        entity.setLastAccessedAt(LocalDateTime.now());
        return entity;
    }

    private SessionMessageEntity message(String id, String sessionId) {
        SessionMessageEntity entity = new SessionMessageEntity();
        entity.setId(id);
        entity.setSessionId(sessionId);
        entity.setTaskId("task_01");
        entity.setRole("assistant");
        entity.setContent("ok");
        entity.setCreatedAt(LocalDateTime.now());
        return entity;
    }
}
