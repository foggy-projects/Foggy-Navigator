package com.foggy.navigator.business.agent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.business.agent.model.dto.CreatedBusinessAgentTaskDTO;
import com.foggy.navigator.business.agent.model.form.CreateBusinessAgentTaskForm;
import com.foggy.navigator.business.agent.service.BusinessAgentTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BusinessAgentTaskControllerTest {

    private static final String TENANT_ID = "tenant-001";
    private static final String USER_ID = "user-001";
    private static final String CLIENT_APP_ID = "app-001";
    private static final String SESSION_ID = "biz-session-001";
    private static final String CONTEXT_ID = "ctx-001";
    private static final String UPSTREAM_USER_ID = "upstream-001";
    private static final String SKILL_ID = "skill-001";
    private static final String WORKER_POOL_ID = "pool-001";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private BusinessAgentTaskService taskService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        taskService = mock(BusinessAgentTaskService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new BusinessAgentTaskController(taskService))
                .build();
    }

    @Test
    void createTask_preservesContextIdAcrossHttpBoundary() throws Exception {
        CreatedBusinessAgentTaskDTO createdTask = new CreatedBusinessAgentTaskDTO();
        createdTask.setTaskId("biz-task-001");
        createdTask.setTenantId(TENANT_ID);
        createdTask.setClientAppId(CLIENT_APP_ID);
        createdTask.setSessionId(SESSION_ID);
        createdTask.setContextId(CONTEXT_ID);
        createdTask.setUpstreamUserId(UPSTREAM_USER_ID);
        createdTask.setSkillId(SKILL_ID);
        createdTask.setWorkerPoolId(WORKER_POOL_ID);
        createdTask.setStatus("RUNNING");
        createdTask.setTaskScopedToken("token-001");

        when(taskService.createTask(eq(TENANT_ID), eq(USER_ID), org.mockito.ArgumentMatchers.any()))
                .thenReturn(createdTask);

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("clientAppId", CLIENT_APP_ID);
        requestBody.put("sessionId", SESSION_ID);
        requestBody.put("contextId", CONTEXT_ID);
        requestBody.put("upstreamUserId", UPSTREAM_USER_ID);
        requestBody.put("skillId", SKILL_ID);
        requestBody.put("workerPoolId", WORKER_POOL_ID);
        requestBody.put("clientContextJson", "{\"screen\":\"tickets\"}");

        mockMvc.perform(post("/api/v1/business-agent/tasks")
                        .requestAttr("tenantId", TENANT_ID)
                        .requestAttr("userId", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value("biz-task-001"))
                .andExpect(jsonPath("$.sessionId").value(SESSION_ID))
                .andExpect(jsonPath("$.contextId").value(CONTEXT_ID))
                .andExpect(jsonPath("$.taskScopedToken").value("token-001"));

        ArgumentCaptor<CreateBusinessAgentTaskForm> formCaptor =
                ArgumentCaptor.forClass(CreateBusinessAgentTaskForm.class);
        verify(taskService).createTask(eq(TENANT_ID), eq(USER_ID), formCaptor.capture());

        CreateBusinessAgentTaskForm capturedForm = formCaptor.getValue();
        assertEquals(CLIENT_APP_ID, capturedForm.getClientAppId());
        assertEquals(SESSION_ID, capturedForm.getSessionId());
        assertEquals(CONTEXT_ID, capturedForm.getContextId());
        assertEquals(UPSTREAM_USER_ID, capturedForm.getUpstreamUserId());
        assertEquals(SKILL_ID, capturedForm.getSkillId());
        assertEquals(WORKER_POOL_ID, capturedForm.getWorkerPoolId());
        assertEquals("{\"screen\":\"tickets\"}", capturedForm.getClientContextJson());
    }
}
