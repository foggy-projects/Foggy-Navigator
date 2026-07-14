package com.foggy.navigator.session.controller;

import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.CurrentUser;
import com.foggy.navigator.common.entity.AgentTaskEntity;
import com.foggy.navigator.session.repository.AgentTaskRepository;
import com.foggy.navigator.session.service.SessionTaskResourceAccessService;
import com.foggyframework.core.ex.RX;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentTaskControllerTest {

    private static final String USER_ID = "user-1";
    private static final String TENANT_ID = "tenant-1";

    @Mock
    private AgentTaskRepository agentTaskRepository;
    @Mock
    private SessionTaskResourceAccessService resourceAccessService;

    private AgentTaskController controller;

    @BeforeEach
    void setUp() {
        controller = new AgentTaskController(agentTaskRepository, resourceAccessService);
        UserContext.setCurrentUser(CurrentUser.builder()
                .userId(USER_ID)
                .tenantId(TENANT_ID)
                .build());
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void listTasksBySession_authorizesParentBeforeReadingChildren() {
        AgentTaskEntity task = new AgentTaskEntity();
        task.setTaskId("agent-task-1");
        task.setParentSessionId("session-1");
        when(agentTaskRepository.findByParentSessionIdAndUserId("session-1", USER_ID))
                .thenReturn(List.of(task));

        RX<List<Map<String, Object>>> result = controller.listTasksBySession("session-1");

        assertEquals("agent-task-1", result.getData().get(0).get("taskId"));
        InOrder ordered = inOrder(resourceAccessService, agentTaskRepository);
        ordered.verify(resourceAccessService)
                .requireOwnedSession("session-1", USER_ID, TENANT_ID);
        ordered.verify(agentTaskRepository)
                .findByParentSessionIdAndUserId("session-1", USER_ID);
    }

    @Test
    void listTasksBySession_rejectsUnownedParentBeforeRepositoryQuery() {
        doThrow(new SecurityException("session resource is not owned by current user"))
                .when(resourceAccessService)
                .requireOwnedSession("session-other", USER_ID, TENANT_ID);

        assertThrows(SecurityException.class,
                () -> controller.listTasksBySession("session-other"));

        verifyNoInteractions(agentTaskRepository);
    }
}
