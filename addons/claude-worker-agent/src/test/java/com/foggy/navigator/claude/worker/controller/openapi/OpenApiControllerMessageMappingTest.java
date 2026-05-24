package com.foggy.navigator.claude.worker.controller.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.business.agent.model.dto.BusinessAgentSessionDTO;
import com.foggy.navigator.business.agent.model.dto.ResolvedClientAppCredentialDTO;
import com.foggy.navigator.business.agent.service.BusinessAgentFrameReportService;
import com.foggy.navigator.business.agent.service.BusinessAgentSessionService;
import com.foggy.navigator.business.agent.service.BusinessAgentTaskService;
import com.foggy.navigator.business.agent.service.ClientAppControlCredentialService;
import com.foggy.navigator.business.agent.service.ClientAppRuntimeCredentialResolver;
import com.foggy.navigator.business.agent.service.A2AgentResourceResolver;
import com.foggy.navigator.claude.worker.model.dto.OpenSessionSummaryDTO;
import com.foggy.navigator.claude.worker.model.dto.OpenSessionMessageDTO;
import com.foggy.navigator.claude.worker.model.form.OpenApiQueryForm;
import com.foggy.navigator.common.dto.a2a.A2aMessage;
import com.foggy.navigator.common.dto.a2a.A2aTask;
import com.foggy.navigator.common.dto.a2a.A2aTaskState;
import com.foggy.navigator.common.dto.a2a.A2aTaskStatus;
import com.foggy.navigator.common.enums.LlmModelCategory;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import com.foggy.navigator.common.entity.AgentConversationContextEntity;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.claude.worker.repository.ClaudeWorkerRepository;
import com.foggy.navigator.claude.worker.repository.CodingAgentRepository;
import com.foggy.navigator.claude.worker.service.*;
import com.foggy.navigator.common.entity.SessionMessageEntity;
import com.foggy.navigator.common.repository.WorkingDirectoryRepository;
import com.foggy.navigator.session.registry.UnifiedAgentResolver;
import com.foggy.navigator.session.service.OpenApiSessionQueryService;
import com.foggy.navigator.session.service.TaskDispatchFacade;
import com.foggy.navigator.spi.agent.A2aAgent;
import com.foggy.navigator.spi.claude.ClaudeWorkerFacade;
import com.foggy.navigator.business.agent.service.AccountContextFileService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenApiControllerMessageMappingTest {

    private static final String STANDARD_CONTEXT_ID = "bctx_20260520_ab_ctx_1";

    @Test
    void taskCompletedMessageIsMarkedAsTerminalResult() throws Exception {
        OpenApiController controller = newController();
        SessionMessageEntity entity = new SessionMessageEntity();
        entity.setId("msg-1");
        entity.setSessionId("session-1");
        entity.setTaskId("task-1");
        entity.setRole("ASSISTANT");
        entity.setContent("done");
        entity.setMetadata("{\"type\":\"TASK_COMPLETED\",\"taskId\":\"task-1\"}");
        entity.setCreatedAt(LocalDateTime.now());

        OpenSessionMessageDTO dto = mapMessage(controller, entity);

        assertEquals("RESULT", dto.getType());
        assertTrue(dto.getTerminal());
        assertEquals("COMPLETED", dto.getTerminalStatus());
        assertEquals("task-1", dto.getTaskId());
    }

    @Test
    void toolCallMessageIsNotTerminal() throws Exception {
        OpenApiController controller = newController();
        SessionMessageEntity entity = new SessionMessageEntity();
        entity.setId("msg-2");
        entity.setSessionId("session-1");
        entity.setTaskId("task-1");
        entity.setRole("ASSISTANT");
        entity.setContent("tms.dataset.listModels");
        entity.setMetadata("{\"type\":\"TOOL_CALL_START\",\"taskId\":\"task-1\"}");
        entity.setCreatedAt(LocalDateTime.now());

        OpenSessionMessageDTO dto = mapMessage(controller, entity);

        assertEquals("TOOL_CALL", dto.getType());
        assertEquals(false, dto.getTerminal());
    }

    @Test
    void messageCarriesOwningTaskStatusWithoutChangingMessageTerminalFlag() throws Exception {
        OpenApiController controller = newController();
        SessionMessageEntity entity = new SessionMessageEntity();
        entity.setId("msg-status");
        entity.setSessionId("session-1");
        entity.setTaskId("task-1");
        entity.setRole("ASSISTANT");
        entity.setContent("处理中间过程");
        entity.setMetadata("{\"type\":\"TEXT_COMPLETE\",\"taskId\":\"task-1\"}");
        entity.setCreatedAt(LocalDateTime.now());

        OpenSessionMessageDTO dto = mapMessage(controller, entity, "COMPLETED");

        assertEquals("TEXT", dto.getType());
        assertEquals("COMPLETED", dto.getStatus());
        assertEquals(false, dto.getTerminal());
        assertNull(dto.getTerminalStatus());
    }


    @Test
    void userMessageExposesAttachmentsAsTopLevelField() throws Exception {
        OpenApiController controller = newController();
        SessionMessageEntity entity = new SessionMessageEntity();
        entity.setId("msg-attachments");
        entity.setSessionId("session-1");
        entity.setTaskId("task-1");
        entity.setRole("USER");
        entity.setContent("请创建带附件的工单");
        entity.setMetadata("""
                {
                  "type": "USER",
                  "taskId": "task-1",
                  "attachments": [
                    {"id": "att-1", "name": "smoke-a.png", "mimeType": "image/png"},
                    {"id": "att-2", "name": "smoke-b.png", "mimeType": "image/png"}
                  ]
                }
                """);
        entity.setCreatedAt(LocalDateTime.now());

        OpenSessionMessageDTO dto = mapMessage(controller, entity);

        assertEquals("USER", dto.getType());
        assertEquals(2, dto.getAttachments().size());
        assertEquals("smoke-a.png", dto.getAttachments().get(0).get("name"));
        assertEquals("smoke-b.png", dto.getAttachments().get(1).get("name"));
        assertNull(dto.getMetadata().get("taskId"));
    }

    @Test
    void terminalStatusCanBeDerivedFromCompletedTaskStatus() throws Exception {
        OpenApiController controller = newController();

        assertEquals("COMPLETED", terminalStatusFromTaskStatus(controller, "COMPLETED"));
        assertEquals("FAILED", terminalStatusFromTaskStatus(controller, "FAILED"));
        assertEquals("CANCELLED", terminalStatusFromTaskStatus(controller, "CANCELLED"));
        assertNull(terminalStatusFromTaskStatus(controller, "RUNNING"));
    }

    @Test
    void sessionSummaryIncludesClientContext() throws Exception {
        OpenApiController controller = newController();
        AgentConversationContextEntity entity = new AgentConversationContextEntity();
        entity.setContextId("ctx-1");
        entity.setContextAlias("alias-1");
        entity.setNavigatorSessionId("session-1");
        entity.setClientContextJson("{\"upstreamConversationId\":\"tms-1\"}");
        entity.setCreatedAt(LocalDateTime.now());
        entity.setLastAccessedAt(LocalDateTime.now());

        Method method = OpenApiController.class.getDeclaredMethod(
                "toSessionSummary",
                AgentConversationContextEntity.class,
                String.class,
                Map.class,
                Map.class
        );
        method.setAccessible(true);
        OpenSessionSummaryDTO dto = (OpenSessionSummaryDTO) method.invoke(
                controller, entity, "agent-1", Map.of("session-1", "task-1"), Map.of("session-1", "first prompt"));

        assertEquals("ctx-1", dto.getContextId());
        assertEquals("alias-1", dto.getTitle());
        assertEquals("task-1", dto.getLatestTaskId());
        assertEquals("tms-1", dto.getClientContext().get("upstreamConversationId"));
    }

    @Test
    void sessionSummaryUsesFirstUserMessageAsDefaultTitle() throws Exception {
        OpenApiController controller = newController();
        AgentConversationContextEntity entity = new AgentConversationContextEntity();
        entity.setContextId("ctx-1");
        entity.setNavigatorSessionId("session-1");
        entity.setCreatedAt(LocalDateTime.now());
        entity.setLastAccessedAt(LocalDateTime.now());

        Method method = OpenApiController.class.getDeclaredMethod(
                "toSessionSummary",
                AgentConversationContextEntity.class,
                String.class,
                Map.class,
                Map.class
        );
        method.setAccessible(true);
        OpenSessionSummaryDTO dto = (OpenSessionSummaryDTO) method.invoke(
                controller,
                entity,
                "agent-1",
                Map.of(),
                Map.of("session-1", "你可以帮我提交工单吗"));

        assertEquals("你可以帮我提交工单吗", dto.getTitle());
    }

    @Test
    void askAgent_topLevelAttachmentsOverrideMetadataAttachmentsAndDedupes() {
        UnifiedAgentResolver agentResolver = mock(UnifiedAgentResolver.class);
        ClientAppRuntimeCredentialResolver credentialResolver = mock(ClientAppRuntimeCredentialResolver.class);
        A2aAgent agent = mock(A2aAgent.class);
        OpenApiController controller = newController(agentResolver, credentialResolver);

        Map<String, Object> metadataAttachment = new LinkedHashMap<>();
        metadataAttachment.put("id", "att-1");
        metadataAttachment.put("name", "old.png");
        metadataAttachment.put("url", "https://tms.example.com/old.png");
        Map<String, Object> metadataOnlyAttachment = new LinkedHashMap<>();
        metadataOnlyAttachment.put("id", "att-2");
        metadataOnlyAttachment.put("name", "metadata-only.png");
        metadataOnlyAttachment.put("url", "https://tms.example.com/metadata-only.png");
        Map<String, Object> topLevelAttachment = new LinkedHashMap<>();
        topLevelAttachment.put("id", "att-1");
        topLevelAttachment.put("name", "pod-photo.png");
        topLevelAttachment.put("url", "https://tms.example.com/pod-photo.png");
        Map<String, Object> topLevelOnlyAttachment = new LinkedHashMap<>();
        topLevelOnlyAttachment.put("id", "att-3");
        topLevelOnlyAttachment.put("name", "top-level-only.png");
        topLevelOnlyAttachment.put("url", "https://tms.example.com/top-level-only.png");
        List<Map<String, Object>> topLevelAttachments = List.of(topLevelAttachment, topLevelOnlyAttachment);

        OpenApiQueryForm form = new OpenApiQueryForm();
        form.setMessage("结合附件分析表单");
        form.setMetadata(Map.of(
                "attachments",
                List.of(metadataAttachment, metadataOnlyAttachment),
                "modelConfigId",
                "cfg-1"));
        form.setAttachments(topLevelAttachments);

        when(credentialResolver.resolveAccessToken(
                nullable(String.class), nullable(String.class)))
                .thenReturn(Optional.of(credential()));
        when(agentResolver.resolveAgent(eq("agent-1"), any())).thenReturn(Optional.of(agent));
        when(agent.sendTask(any())).thenReturn(A2aTask.builder()
                .id("task-1")
                .contextId("ctx-1")
                .status(A2aTaskStatus.builder().state(A2aTaskState.SUBMITTED).build())
                .build());

        controller.askAgent("agent-1", form, mock(HttpServletRequest.class));

        var captor = org.mockito.ArgumentCaptor.forClass(A2aMessage.class);
        verify(agent).sendTask(captor.capture());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> attachments =
                (List<Map<String, Object>>) captor.getValue().getMetadata().get("attachments");
        assertEquals(List.of(topLevelAttachment, topLevelOnlyAttachment, metadataOnlyAttachment), attachments);
    }

    @Test
    void askAgent_metadataAttachmentsRemainWhenTopLevelAttachmentsAbsent() {
        UnifiedAgentResolver agentResolver = mock(UnifiedAgentResolver.class);
        ClientAppRuntimeCredentialResolver credentialResolver = mock(ClientAppRuntimeCredentialResolver.class);
        A2aAgent agent = mock(A2aAgent.class);
        OpenApiController controller = newController(agentResolver, credentialResolver);

        Map<String, Object> metadataAttachment = new LinkedHashMap<>();
        metadataAttachment.put("name", "metadata-only.png");
        metadataAttachment.put("url", "https://tms.example.com/metadata-only.png");
        List<Map<String, Object>> metadataAttachments = List.of(metadataAttachment);

        OpenApiQueryForm form = new OpenApiQueryForm();
        form.setMessage("结合附件分析表单");
        form.setMetadata(Map.of("attachments", metadataAttachments));

        when(credentialResolver.resolveAccessToken(
                nullable(String.class), nullable(String.class)))
                .thenReturn(Optional.of(credential()));
        when(agentResolver.resolveAgent(eq("agent-1"), any())).thenReturn(Optional.of(agent));
        when(agent.sendTask(any())).thenReturn(A2aTask.builder()
                .id("task-1")
                .contextId("ctx-1")
                .status(A2aTaskStatus.builder().state(A2aTaskState.SUBMITTED).build())
                .build());

        controller.askAgent("agent-1", form, mock(HttpServletRequest.class));

        var captor = org.mockito.ArgumentCaptor.forClass(A2aMessage.class);
        verify(agent).sendTask(captor.capture());
        assertEquals(metadataAttachments, captor.getValue().getMetadata().get("attachments"));
    }

    @Test
    void askAgent_topLevelModelConfigIdOverridesMetadataAndIsForwarded() {
        UnifiedAgentResolver agentResolver = mock(UnifiedAgentResolver.class);
        ClientAppRuntimeCredentialResolver credentialResolver = mock(ClientAppRuntimeCredentialResolver.class);
        A2aAgent agent = mock(A2aAgent.class);
        OpenApiController controller = newController(agentResolver, credentialResolver);

        OpenApiQueryForm form = new OpenApiQueryForm();
        form.setMessage("run deterministic test");
        form.setModelConfigId("cfg-top-level");
        form.setMetadata(Map.of("modelConfigId", "cfg-metadata"));

        when(credentialResolver.resolveAccessToken(
                nullable(String.class), nullable(String.class)))
                .thenReturn(Optional.of(credential()));
        when(agentResolver.resolveAgent(eq("agent-1"), argThat(ctx ->
                "cfg-top-level".equals(ctx.getModelConfigId())))).thenReturn(Optional.of(agent));
        when(agent.sendTask(any())).thenReturn(A2aTask.builder()
                .id("task-1")
                .contextId("ctx-1")
                .status(A2aTaskStatus.builder().state(A2aTaskState.SUBMITTED).build())
                .build());

        controller.askAgent("agent-1", form, mock(HttpServletRequest.class));

        var captor = org.mockito.ArgumentCaptor.forClass(A2aMessage.class);
        verify(agent).sendTask(captor.capture());
        assertEquals("cfg-top-level", captor.getValue().getMetadata().get("modelConfigId"));
    }

    @Test
    void askAgent_generatesStandardBusinessContextIdWhenContextIdIsOmitted() {
        UnifiedAgentResolver agentResolver = mock(UnifiedAgentResolver.class);
        ClientAppRuntimeCredentialResolver credentialResolver = mock(ClientAppRuntimeCredentialResolver.class);
        A2aAgent agent = mock(A2aAgent.class);
        OpenApiController controller = newController(agentResolver, credentialResolver);

        OpenApiQueryForm form = new OpenApiQueryForm();
        form.setMessage("创建一个新会话");

        when(credentialResolver.resolveAccessToken(
                nullable(String.class), nullable(String.class)))
                .thenReturn(Optional.of(credential()));
        when(agentResolver.resolveAgent(eq("agent-1"), any())).thenReturn(Optional.of(agent));
        when(agent.sendTask(any())).thenReturn(A2aTask.builder()
                .id("task-1")
                .status(A2aTaskStatus.builder().state(A2aTaskState.SUBMITTED).build())
                .build());

        var result = controller.askAgent("agent-1", form, mock(HttpServletRequest.class));

        var captor = org.mockito.ArgumentCaptor.forClass(A2aMessage.class);
        verify(agent).sendTask(captor.capture());
        String generatedContextId = captor.getValue().getContextId();
        assertTrue(generatedContextId.matches("^bctx_\\d{8}_[0-9a-f]{2}_[A-Za-z0-9._-]+$"));
        assertEquals(generatedContextId, result.getData().getContextId());
    }

    @Test
    void askAgent_bindsOpenApiBusinessRuntimeTokenToVisibleWorkerTask() {
        UnifiedAgentResolver agentResolver = mock(UnifiedAgentResolver.class);
        ClientAppRuntimeCredentialResolver credentialResolver = mock(ClientAppRuntimeCredentialResolver.class);
        BusinessAgentTaskService taskService = mock(BusinessAgentTaskService.class);
        A2aAgent agent = mock(A2aAgent.class);
        OpenApiController controller = newController(agentResolver, credentialResolver, null, taskService);
        HttpServletRequest request = mock(HttpServletRequest.class);

        OpenApiQueryForm form = new OpenApiQueryForm();
        form.setMessage("创建车辆并走审批");

        when(request.getHeader("X-Upstream-User-Id")).thenReturn("upstream-a");
        when(credentialResolver.resolveAccessToken(
                nullable(String.class), nullable(String.class)))
                .thenReturn(Optional.of(credential()));
        when(taskService.issueOpenApiTaskScopedToken(
                eq("tenant-1"),
                eq("app-1"),
                eq("app-1"),
                eq("upstream-a"),
                eq("agent-1"),
                any(),
                nullable(String.class)))
                .thenReturn("btt_open_api_1");
        when(agentResolver.resolveAgent(eq("agent-1"), any())).thenReturn(Optional.of(agent));
        when(agent.sendTask(any())).thenReturn(A2aTask.builder()
                .id("lgt_visible_1")
                .contextId("ctx-1")
                .metadata(Map.of("sessionId", "worker_session_1"))
                .status(A2aTaskStatus.builder().state(A2aTaskState.SUBMITTED).build())
                .build());

        controller.askAgent("agent-1", form, request);

        var captor = org.mockito.ArgumentCaptor.forClass(A2aMessage.class);
        verify(agent).sendTask(captor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> runtimeContext = (Map<String, Object>) captor.getValue().getMetadata().get("runtimeContext");
        assertEquals("btt_open_api_1", runtimeContext.get("task_scoped_token"));
        assertEquals("agent-1", runtimeContext.get("skill_name"));
        verify(taskService).bindOpenApiTaskScopedTokenToWorkerTask(
                "tenant-1",
                "btt_open_api_1",
                "lgt_visible_1",
                "worker_session_1");
    }

    @Test
    void askAgent_usesRootAgentRouteAndDerivedSkillForBusinessRuntime() {
        UnifiedAgentResolver agentResolver = mock(UnifiedAgentResolver.class);
        ClientAppRuntimeCredentialResolver credentialResolver = mock(ClientAppRuntimeCredentialResolver.class);
        BusinessAgentTaskService taskService = mock(BusinessAgentTaskService.class);
        OpenApiAgentRouteService routeService = mock(OpenApiAgentRouteService.class);
        A2aAgent agent = mock(A2aAgent.class);
        OpenApiController controller = newController(
                agentResolver,
                credentialResolver,
                null,
                taskService,
                mock(CodingAgentRepository.class),
                mock(OpenApiSessionQueryService.class),
                routeService);
        HttpServletRequest request = mock(HttpServletRequest.class);

        OpenApiQueryForm form = new OpenApiQueryForm();
        form.setMessage("查询派车状态");

        when(request.getHeader("X-Upstream-User-Id")).thenReturn("upstream-a");
        when(credentialResolver.resolveAccessToken(
                nullable(String.class), nullable(String.class)))
                .thenReturn(Optional.of(credential()));
        when(routeService.resolve(eq("root-agent"), any(ResolvedClientAppCredentialDTO.class)))
                .thenReturn(new OpenApiAgentRouteService.ResolvedOpenApiAgentRoute(
                        "root-agent",
                        "tms.navigator.agent",
                        "app-1",
                        true,
                        false));
        when(taskService.issueOpenApiTaskScopedToken(
                eq("tenant-1"),
                eq("app-1"),
                eq("app-1"),
                eq("upstream-a"),
                eq("tms.navigator.agent"),
                any(),
                nullable(String.class)))
                .thenReturn("btt_open_api_1");
        when(agentResolver.resolveAgent(eq("root-agent"), any())).thenReturn(Optional.of(agent));
        when(agent.sendTask(any())).thenReturn(A2aTask.builder()
                .id("task-1")
                .contextId("ctx-1")
                .status(A2aTaskStatus.builder().state(A2aTaskState.SUBMITTED).build())
                .build());

        controller.askAgent("root-agent", form, request);

        var captor = org.mockito.ArgumentCaptor.forClass(A2aMessage.class);
        verify(agent).sendTask(captor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> context = (Map<String, Object>) captor.getValue().getMetadata().get("context");
        assertEquals("root-agent", context.get("rootAgentId"));
        assertEquals("tms.navigator.agent", context.get("businessSkillId"));
        assertEquals("tms.navigator.agent", context.get("businessSkillName"));
        assertNull(captor.getValue().getMetadata().get("skill_name"));
        @SuppressWarnings("unchecked")
        Map<String, Object> runtimeContext = (Map<String, Object>) captor.getValue().getMetadata().get("runtimeContext");
        assertEquals("tms.navigator.agent", runtimeContext.get("skill_name"));
        verify(credentialResolver, never()).resolveAccessTokenForSkill(
                nullable(String.class), nullable(String.class), eq("root-agent"));
    }

    @Test
    void askAgent_forwardsTopLevelExecutionPolicyWithoutDirectSkillName() {
        UnifiedAgentResolver agentResolver = mock(UnifiedAgentResolver.class);
        ClientAppRuntimeCredentialResolver credentialResolver = mock(ClientAppRuntimeCredentialResolver.class);
        A2aAgent agent = mock(A2aAgent.class);
        OpenApiController controller = newController(agentResolver, credentialResolver);

        OpenApiQueryForm form = new OpenApiQueryForm();
        form.setMessage("分析仓库");
        form.setWorkdir("D:/workspace/app");
        form.setAllowedDirs(List.of("D:/workspace"));
        form.setAllowedTools(List.of("read_file", "invoke_business_function"));
        form.setMetadata(Map.of("context", Map.of("traceId", "trace-1")));

        when(credentialResolver.resolveAccessToken(
                nullable(String.class), nullable(String.class)))
                .thenReturn(Optional.of(credential()));
        when(agentResolver.resolveAgent(eq("agent-1"), any())).thenReturn(Optional.of(agent));
        when(agent.sendTask(any())).thenReturn(A2aTask.builder()
                .id("task-1")
                .contextId("ctx-1")
                .status(A2aTaskStatus.builder().state(A2aTaskState.SUBMITTED).build())
                .build());

        controller.askAgent("agent-1", form, mock(HttpServletRequest.class));

        var captor = org.mockito.ArgumentCaptor.forClass(A2aMessage.class);
        verify(agent).sendTask(captor.capture());
        Map<String, Object> metadata = captor.getValue().getMetadata();
        assertNull(metadata.get("skill_name"));
        @SuppressWarnings("unchecked")
        Map<String, Object> context = (Map<String, Object>) metadata.get("context");
        assertEquals("trace-1", context.get("traceId"));
        assertEquals("agent-1", context.get("businessSkillId"));
        assertEquals("agent-1", context.get("businessSkillName"));
        @SuppressWarnings("unchecked")
        Map<String, Object> executionPolicy = (Map<String, Object>) context.get("execution_policy");
        assertEquals("D:/workspace/app", executionPolicy.get("workdir"));
        assertEquals(List.of("D:/workspace"), executionPolicy.get("allowed_dirs"));
        assertEquals(List.of("read_file", "invoke_business_function"), executionPolicy.get("allowed_tools"));
    }

    @Test
    void askAgent_rejectsContextIdWithoutUpstreamUserId() {
        UnifiedAgentResolver agentResolver = mock(UnifiedAgentResolver.class);
        ClientAppRuntimeCredentialResolver credentialResolver = mock(ClientAppRuntimeCredentialResolver.class);
        OpenApiController controller = newController(agentResolver, credentialResolver);

        OpenApiQueryForm form = new OpenApiQueryForm();
        form.setMessage("继续处理");
        form.setContextId("ctx-1");

        when(credentialResolver.resolveAccessToken(
                nullable(String.class), nullable(String.class)))
                .thenReturn(Optional.of(credential()));

        RuntimeException error = assertThrows(
                RuntimeException.class,
                () -> controller.askAgent("agent-1", form, mock(HttpServletRequest.class)));

        assertTrue(error.getMessage().contains("upstream user id is required"));
        verify(agentResolver, never()).resolveAgent(any(), any());
    }

    @Test
    void askAgent_rejectsContextIdOwnedByAnotherUpstreamUserBeforeSend() {
        UnifiedAgentResolver agentResolver = mock(UnifiedAgentResolver.class);
        ClientAppRuntimeCredentialResolver credentialResolver = mock(ClientAppRuntimeCredentialResolver.class);
        BusinessAgentSessionService sessionService = mock(BusinessAgentSessionService.class);
        CodingAgentRepository codingAgentRepository = mock(CodingAgentRepository.class);
        OpenApiSessionQueryService sessionQueryService = mock(OpenApiSessionQueryService.class);
        A2aAgent agent = mock(A2aAgent.class);
        OpenApiController controller = newController(
                agentResolver,
                credentialResolver,
                sessionService,
                codingAgentRepository,
                sessionQueryService);
        HttpServletRequest request = mock(HttpServletRequest.class);

        OpenApiQueryForm form = new OpenApiQueryForm();
        form.setMessage("继续处理");
        form.setContextId(STANDARD_CONTEXT_ID);

        when(request.getHeader("X-Upstream-User-Id")).thenReturn("upstream-b");
        when(credentialResolver.resolveAccessToken(
                nullable(String.class), nullable(String.class)))
                .thenReturn(Optional.of(credential()));
        when(sessionService.getSession("tenant-1", "app-1", "upstream-b", STANDARD_CONTEXT_ID))
                .thenThrow(new IllegalArgumentException("business agent session not found: " + STANDARD_CONTEXT_ID));

        RuntimeException error = assertThrows(
                RuntimeException.class,
                () -> controller.askAgent("agent-1", form, request));

        assertTrue(error.getMessage().contains("business agent session not found"));
        verify(agentResolver, never()).resolveAgent(any(), any());
        verify(agent, never()).sendTask(any());
    }

    @Test
    void askAgent_allowsContextIdOwnedByCurrentUpstreamUser() {
        UnifiedAgentResolver agentResolver = mock(UnifiedAgentResolver.class);
        ClientAppRuntimeCredentialResolver credentialResolver = mock(ClientAppRuntimeCredentialResolver.class);
        BusinessAgentSessionService sessionService = mock(BusinessAgentSessionService.class);
        CodingAgentRepository codingAgentRepository = mock(CodingAgentRepository.class);
        OpenApiSessionQueryService sessionQueryService = mock(OpenApiSessionQueryService.class);
        A2aAgent agent = mock(A2aAgent.class);
        OpenApiController controller = newController(
                agentResolver,
                credentialResolver,
                sessionService,
                codingAgentRepository,
                sessionQueryService);
        HttpServletRequest request = mock(HttpServletRequest.class);

        OpenApiQueryForm form = new OpenApiQueryForm();
        form.setMessage("继续处理");
        form.setContextId(STANDARD_CONTEXT_ID);

        when(request.getHeader("X-Upstream-User-Id")).thenReturn("upstream-a");
        when(credentialResolver.resolveAccessToken(
                nullable(String.class), nullable(String.class)))
                .thenReturn(Optional.of(credential()));
        when(sessionService.getSession("tenant-1", "app-1", "upstream-a", STANDARD_CONTEXT_ID))
                .thenReturn(new BusinessAgentSessionDTO());
        when(agentResolver.resolveAgent(eq("agent-1"), any())).thenReturn(Optional.of(agent));
        CodingAgentEntity agentEntity = new CodingAgentEntity();
        agentEntity.setAgentId("agent-1");
        agentEntity.setTenantId("tenant-1");
        agentEntity.setUserId("owner-1");
        when(codingAgentRepository.findByAgentIdAndTenantId("agent-1", "tenant-1")).thenReturn(Optional.of(agentEntity));
        when(sessionQueryService.resolveSessionId(STANDARD_CONTEXT_ID, "owner-1")).thenReturn(Optional.empty());
        when(agent.sendTask(any())).thenReturn(A2aTask.builder()
                .id("task-1")
                .contextId(STANDARD_CONTEXT_ID)
                .status(A2aTaskStatus.builder().state(A2aTaskState.SUBMITTED).build())
                .build());

        controller.askAgent("agent-1", form, request);

        var captor = org.mockito.ArgumentCaptor.forClass(A2aMessage.class);
        verify(agent).sendTask(captor.capture());
        verify(codingAgentRepository).findByAgentIdAndTenantId("agent-1", "tenant-1");
        verify(codingAgentRepository, never()).findByAgentId("agent-1");
        assertEquals(STANDARD_CONTEXT_ID, captor.getValue().getContextId());
    }

    @Test
    void getSessionMessages_hidesInternalRuntimeMessagesByDefault() {
        UnifiedAgentResolver agentResolver = mock(UnifiedAgentResolver.class);
        ClientAppRuntimeCredentialResolver credentialResolver = mock(ClientAppRuntimeCredentialResolver.class);
        CodingAgentRepository codingAgentRepository = mock(CodingAgentRepository.class);
        OpenApiSessionQueryService sessionQueryService = mock(OpenApiSessionQueryService.class);
        A2aAgent agent = mock(A2aAgent.class);
        OpenApiController controller = newController(
                agentResolver,
                credentialResolver,
                null,
                codingAgentRepository,
                sessionQueryService);
        HttpServletRequest request = mock(HttpServletRequest.class);

        when(credentialResolver.resolveAccessToken(
                nullable(String.class), nullable(String.class)))
                .thenReturn(Optional.of(credential()));
        when(agentResolver.resolveAgent(eq("agent-1"), any())).thenReturn(Optional.of(agent));
        CodingAgentEntity agentEntity = new CodingAgentEntity();
        agentEntity.setAgentId("agent-1");
        agentEntity.setTenantId("tenant-1");
        agentEntity.setUserId("owner-1");
        when(codingAgentRepository.findByAgentIdAndTenantId("agent-1", "tenant-1"))
                .thenReturn(Optional.of(agentEntity));
        when(sessionQueryService.resolveSessionId("ctx-1", "owner-1")).thenReturn(Optional.of("session-1"));
        when(sessionQueryService.getSessionMessages("session-1", null, 20)).thenReturn(List.of(
                userMessage("msg_user", "session-1", "hi"),
                message("msg_tool_call", "session-1", "assistant", "submit_skill_result",
                        "{\"type\":\"TOOL_CALL_START\",\"toolName\":\"submit_skill_result\"}"),
                message("msg_tool_result", "session-1", "tool", "{\"ok\":true}",
                        "{\"type\":\"TOOL_CALL_RESULT\"}"),
                message("msg_root_state", "session-1", "assistant", "Opening conversation root frame",
                        "{\"type\":\"STATE_SYNC\",\"subtype\":\"skill_frame_open\",\"content\":\"Opening conversation root frame\"}"),
                message("msg_result", "session-1", "assistant", "你好",
                        "{\"type\":\"TASK_COMPLETED\"}")));
        when(sessionQueryService.batchFindTaskStatuses(any()))
                .thenReturn(Map.of("task-1", "COMPLETED"));

        var result = controller.getSessionMessages("agent-1", "ctx-1", null, 20, false, request);

        assertEquals(List.of("msg_user", "msg_result"), result.getData().getMessages().stream()
                .map(OpenSessionMessageDTO::getMessageId)
                .toList());
        assertEquals(List.of("COMPLETED", "COMPLETED"), result.getData().getMessages().stream()
                .map(OpenSessionMessageDTO::getStatus)
                .toList());
    }

    private OpenSessionMessageDTO mapMessage(OpenApiController controller, SessionMessageEntity entity)
            throws Exception {
        Method method = OpenApiController.class.getDeclaredMethod(
                "toOpenSessionMessageDTO",
                SessionMessageEntity.class,
                String.class
        );
        method.setAccessible(true);
        return (OpenSessionMessageDTO) method.invoke(controller, entity, "ctx-1");
    }

    private OpenSessionMessageDTO mapMessage(OpenApiController controller, SessionMessageEntity entity, String status)
            throws Exception {
        Method method = OpenApiController.class.getDeclaredMethod(
                "toOpenSessionMessageDTO",
                SessionMessageEntity.class,
                String.class,
                String.class
        );
        method.setAccessible(true);
        return (OpenSessionMessageDTO) method.invoke(controller, entity, "ctx-1", status);
    }

    private SessionMessageEntity userMessage(String id, String sessionId, String content) {
        return message(id, sessionId, "USER", content, "{\"type\":\"USER\"}");
    }

    private SessionMessageEntity message(
            String id,
            String sessionId,
            String role,
            String content,
            String metadata) {
        SessionMessageEntity entity = new SessionMessageEntity();
        entity.setId(id);
        entity.setSessionId(sessionId);
        entity.setTaskId("task-1");
        entity.setRole(role);
        entity.setContent(content);
        entity.setMetadata(metadata);
        entity.setCreatedAt(LocalDateTime.now());
        return entity;
    }

    private String terminalStatusFromTaskStatus(OpenApiController controller, String status) throws Exception {
        Method method = OpenApiController.class.getDeclaredMethod("terminalStatusFromTaskStatus", String.class);
        method.setAccessible(true);
        return (String) method.invoke(controller, status);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private OpenApiController newController() {
        return newController(mock(UnifiedAgentResolver.class), null);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private OpenApiController newController(
            UnifiedAgentResolver agentResolver,
            ClientAppRuntimeCredentialResolver credentialResolver) {
        return newController(agentResolver, credentialResolver, null, null);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private OpenApiController newController(
            UnifiedAgentResolver agentResolver,
            ClientAppRuntimeCredentialResolver credentialResolver,
            BusinessAgentSessionService sessionService,
            BusinessAgentTaskService taskService) {
        return newController(
                agentResolver,
                credentialResolver,
                sessionService,
                taskService,
                mock(CodingAgentRepository.class),
                mock(OpenApiSessionQueryService.class));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private OpenApiController newController(
            UnifiedAgentResolver agentResolver,
            ClientAppRuntimeCredentialResolver credentialResolver,
            BusinessAgentSessionService sessionService) {
        return newController(
                agentResolver,
                credentialResolver,
                sessionService,
                null,
                mock(CodingAgentRepository.class),
                mock(OpenApiSessionQueryService.class));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private OpenApiController newController(
            UnifiedAgentResolver agentResolver,
            ClientAppRuntimeCredentialResolver credentialResolver,
            BusinessAgentSessionService sessionService,
            CodingAgentRepository codingAgentRepository,
            OpenApiSessionQueryService sessionQueryService) {
        return newController(agentResolver, credentialResolver, sessionService, null, codingAgentRepository, sessionQueryService);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private OpenApiController newController(
            UnifiedAgentResolver agentResolver,
            ClientAppRuntimeCredentialResolver credentialResolver,
            BusinessAgentSessionService sessionService,
            BusinessAgentTaskService taskService,
            CodingAgentRepository codingAgentRepository,
            OpenApiSessionQueryService sessionQueryService) {
        OpenApiAgentRouteService routeService = mock(OpenApiAgentRouteService.class);
        when(routeService.resolve(any(String.class), any(ResolvedClientAppCredentialDTO.class)))
                .thenAnswer(invocation -> {
                    String routeAgentId = invocation.getArgument(0);
                    ResolvedClientAppCredentialDTO credential = invocation.getArgument(1);
                    return new OpenApiAgentRouteService.ResolvedOpenApiAgentRoute(
                            routeAgentId,
                            routeAgentId,
                            credential.getClientAppId(),
                            false,
                            true);
                });
        return newController(
                agentResolver,
                credentialResolver,
                sessionService,
                taskService,
                codingAgentRepository,
                sessionQueryService,
                routeService);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private OpenApiController newController(
            UnifiedAgentResolver agentResolver,
            ClientAppRuntimeCredentialResolver credentialResolver,
            BusinessAgentSessionService sessionService,
            BusinessAgentTaskService taskService,
            CodingAgentRepository codingAgentRepository,
            OpenApiSessionQueryService sessionQueryService,
            OpenApiAgentRouteService routeService) {
        ObjectProvider<ClientAppRuntimeCredentialResolver> credentialProvider = mock(ObjectProvider.class);
        when(credentialProvider.getIfAvailable()).thenReturn(credentialResolver);
        ObjectProvider<BusinessAgentTaskService> taskProvider = mock(ObjectProvider.class);
        when(taskProvider.getIfAvailable()).thenReturn(taskService);
        ObjectProvider<BusinessAgentSessionService> sessionProvider = mock(ObjectProvider.class);
        when(sessionProvider.getIfAvailable()).thenReturn(sessionService);
        ObjectProvider<BusinessAgentFrameReportService> frameReportProvider = mock(ObjectProvider.class);
        when(frameReportProvider.getIfAvailable()).thenReturn(null);
        ObjectProvider<ClientAppControlCredentialService> controlCredentialProvider = mock(ObjectProvider.class);
        when(controlCredentialProvider.getIfAvailable()).thenReturn(null);
        A2AgentResourceResolver resourceResolver = mock(A2AgentResourceResolver.class);
        when(resourceResolver.resolveRequiredAgent(
                any(String.class),
                any(String.class),
                nullable(String.class),
                any(String.class)))
                .thenAnswer(invocation -> {
                    String agentId = invocation.getArgument(3, String.class);
                    return new A2AgentResourceResolver.ResolvedAgentResource(
                            agentId,
                            ResourceOwnerType.CLIENT_APP,
                            "app-1",
                            "app-1",
                            agentId,
                            "pool-1",
                            ResourceOwnerType.PLATFORM,
                            "tenant-1",
                            "WORKER_POOL:PLATFORM",
                            "LANGGRAPH_BIZ",
                            "model-default",
                            null,
                            null,
                            "AGENT:CLIENT_APP");
                });
        when(resourceResolver.resolveRequiredModelForAgent(
                any(String.class),
                any(String.class),
                any(A2AgentResourceResolver.ResolvedAgentResource.class),
                nullable(String.class),
                nullable(String.class),
                eq(LlmModelCategory.GENERAL)))
                .thenAnswer(invocation -> {
                    String requestedModelConfigId = invocation.getArgument(3, String.class);
                    String requestedModelVariant = invocation.getArgument(4, String.class);
                    String modelConfigId = requestedModelConfigId != null && !requestedModelConfigId.isBlank()
                            ? requestedModelConfigId
                            : "model-default";
                    String modelName = requestedModelVariant != null && !requestedModelVariant.isBlank()
                            ? requestedModelVariant
                            : "qwen-plus";
                    return new A2AgentResourceResolver.ResolvedModelResource(
                            modelConfigId,
                            requestedModelConfigId,
                            requestedModelVariant,
                            LlmModelCategory.GENERAL,
                            modelName,
                            requestedModelVariant != null && !requestedModelVariant.isBlank()
                                    ? "REQUESTED_MODEL_VARIANT"
                                    : "MODEL_CONFIG_DEFAULT",
                            "LANGGRAPH_BIZ",
                            requestedModelConfigId != null && !requestedModelConfigId.isBlank()
                                    ? "AGENT_MODEL_BINDING:REQUESTED_MODEL_GRANT"
                                    : "AGENT_DEFAULT_MODEL:DEFAULT_MODEL_GRANT");
                });
        ObjectProvider<A2AgentResourceResolver> resourceResolverProvider = mock(ObjectProvider.class);
        when(resourceResolverProvider.getIfAvailable()).thenReturn(resourceResolver);
        return new OpenApiController(
                mock(OpenApiProvisioningService.class),
                mock(ClaudeWorkerService.class),
                mock(WorkingDirectoryService.class),
                mock(ClaudeWorkerFacade.class),
                mock(ClaudeWorkerRepository.class),
                codingAgentRepository,
                mock(WorkingDirectoryRepository.class),
                mock(WorkerHealthChecker.class),
                agentResolver,
                mock(TaskDispatchFacade.class),
                mock(TaskStateReconciler.class),
                sessionQueryService,
                new ObjectMapper(),
                routeService,
                credentialProvider,
                taskProvider,
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                sessionProvider,
                frameReportProvider,
                controlCredentialProvider,
                resourceResolverProvider
        );
    }

    private ResolvedClientAppCredentialDTO credential() {
        return ResolvedClientAppCredentialDTO.builder()
                .credentialId("cred-1")
                .tenantId("tenant-1")
                .clientAppId("app-1")
                .build();
    }
}
