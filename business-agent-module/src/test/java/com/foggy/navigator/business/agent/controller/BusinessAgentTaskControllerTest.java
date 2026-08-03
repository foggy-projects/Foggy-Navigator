package com.foggy.navigator.business.agent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.business.agent.model.dto.BusinessAgentTaskDTO;
import com.foggy.navigator.business.agent.model.dto.CreatedBusinessAgentTaskDTO;
import com.foggy.navigator.business.agent.model.form.CreateBusinessAgentTaskForm;
import com.foggy.navigator.business.agent.service.BusinessAgentTaskCreateCommandFacade;
import com.foggy.navigator.business.agent.service.BusinessAgentTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BusinessAgentTaskControllerTest {

    private static final String TENANT_ID = "tenant-001";
    private static final String USER_ID = "user-001";
    private static final String CLIENT_APP_ID = "app-001";
    private static final String SESSION_ID = "biz-session-001";
    private static final String CONTEXT_ID = "bctx_20260520_ab_ctx_001";
    private static final String UPSTREAM_USER_ID = "upstream-001";
    private static final String AGENT_ID = "agent-001";
    private static final String SKILL_ID = "skill-001";
    private static final String WORKER_POOL_ID = "pool-001";
    private static final String CLIENT_REQUEST_ID =
            "550e8400-e29b-41d4-a716-446655440000";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private BusinessAgentTaskService taskService;
    private BusinessAgentTaskCreateCommandFacade taskCreateCommandFacade;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        taskService = mock(BusinessAgentTaskService.class);
        taskCreateCommandFacade = mock(BusinessAgentTaskCreateCommandFacade.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new BusinessAgentTaskController(
                        taskService, taskCreateCommandFacade))
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
        createdTask.setAgentId(AGENT_ID);
        createdTask.setSkillId(SKILL_ID);
        createdTask.setWorkerPoolId(WORKER_POOL_ID);
        createdTask.setStatus("RUNNING");
        createdTask.setTaskScopedToken("token-001");

        when(taskCreateCommandFacade.createTask(
                eq(CLIENT_REQUEST_ID), org.mockito.ArgumentMatchers.any()))
                .thenReturn(createdTask);

        Map<String, Object> requestBody = requestBody();

        mockMvc.perform(post("/api/v1/business-agent/tasks")
                        .requestAttr("tenantId", TENANT_ID)
                        .requestAttr("userId", USER_ID)
                        .header("X-Navigator-Client-Request-Id", CLIENT_REQUEST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value("biz-task-001"))
                .andExpect(jsonPath("$.sessionId").value(SESSION_ID))
                .andExpect(jsonPath("$.contextId").value(CONTEXT_ID))
                .andExpect(jsonPath("$.taskScopedToken").value("token-001"));

        ArgumentCaptor<CreateBusinessAgentTaskForm> formCaptor =
                ArgumentCaptor.forClass(CreateBusinessAgentTaskForm.class);
        verify(taskCreateCommandFacade).createTask(
                eq(CLIENT_REQUEST_ID), formCaptor.capture());
        verify(taskService, never()).createTask(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());

        CreateBusinessAgentTaskForm capturedForm = formCaptor.getValue();
        assertEquals(CLIENT_APP_ID, capturedForm.getClientAppId());
        assertEquals(SESSION_ID, capturedForm.getSessionId());
        assertEquals(CONTEXT_ID, capturedForm.getContextId());
        assertEquals(UPSTREAM_USER_ID, capturedForm.getUpstreamUserId());
        assertEquals(AGENT_ID, capturedForm.getAgentId());
        assertEquals("{\"screen\":\"tickets\"}", capturedForm.getClientContextJson());
    }

    @Test
    void createTask_forwardsAbsentAndBlankRequestIdsWithoutControllerInterpretation()
            throws Exception {
        CreatedBusinessAgentTaskDTO createdTask = new CreatedBusinessAgentTaskDTO();
        createdTask.setTaskId("biz-task-001");
        when(taskCreateCommandFacade.createTask(
                isNull(), org.mockito.ArgumentMatchers.any())).thenReturn(createdTask);
        when(taskCreateCommandFacade.createTask(
                eq("   "), org.mockito.ArgumentMatchers.any())).thenReturn(createdTask);

        mockMvc.perform(post("/api/v1/business-agent/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestBody())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value("biz-task-001"));
        mockMvc.perform(post("/api/v1/business-agent/tasks")
                        .header("X-Navigator-Client-Request-Id", "   ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestBody())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value("biz-task-001"));

        verify(taskCreateCommandFacade).createTask(
                isNull(), org.mockito.ArgumentMatchers.any());
        verify(taskCreateCommandFacade).createTask(
                eq("   "), org.mockito.ArgumentMatchers.any());
        verify(taskService, never()).createTask(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void readEndpointsRemainOnTheExistingReadService() throws Exception {
        BusinessAgentTaskDTO task = new BusinessAgentTaskDTO();
        task.setTaskId("biz-task-001");
        task.setSessionId(SESSION_ID);
        when(taskService.getTask(TENANT_ID, "biz-task-001")).thenReturn(task);
        when(taskService.listTasksBySession(TENANT_ID, SESSION_ID)).thenReturn(List.of(task));

        mockMvc.perform(get("/api/v1/business-agent/tasks/biz-task-001")
                        .requestAttr("tenantId", TENANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value("biz-task-001"));
        mockMvc.perform(get("/api/v1/business-agent/sessions/{sessionId}/tasks", SESSION_ID)
                        .requestAttr("tenantId", TENANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].taskId").value("biz-task-001"));

        verify(taskService).getTask(TENANT_ID, "biz-task-001");
        verify(taskService).listTasksBySession(TENANT_ID, SESSION_ID);
        verifyNoInteractions(taskCreateCommandFacade);
    }

    private Map<String, Object> requestBody() {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("clientAppId", CLIENT_APP_ID);
        requestBody.put("sessionId", SESSION_ID);
        requestBody.put("contextId", CONTEXT_ID);
        requestBody.put("upstreamUserId", UPSTREAM_USER_ID);
        requestBody.put("agentId", AGENT_ID);
        requestBody.put("clientContextJson", "{\"screen\":\"tickets\"}");
        return requestBody;
    }
}
