package com.foggy.navigator.claude.worker.controller.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.business.agent.model.dto.BusinessAgentSessionDTO;
import com.foggy.navigator.business.agent.model.dto.ResolvedClientAppCredentialDTO;
import com.foggy.navigator.business.agent.service.BusinessAgentSessionService;
import com.foggy.navigator.business.agent.service.ClientAppRuntimeCredentialResolver;
import com.foggy.navigator.claude.worker.model.dto.OpenSessionSummaryDTO;
import com.foggy.navigator.claude.worker.model.dto.OpenSessionMessageDTO;
import com.foggy.navigator.claude.worker.model.form.OpenApiQueryForm;
import com.foggy.navigator.common.dto.a2a.A2aMessage;
import com.foggy.navigator.common.dto.a2a.A2aTask;
import com.foggy.navigator.common.dto.a2a.A2aTaskState;
import com.foggy.navigator.common.dto.a2a.A2aTaskStatus;
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
import static org.junit.jupiter.api.Assertions.assertSame;
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
                Map.class
        );
        method.setAccessible(true);
        OpenSessionSummaryDTO dto = (OpenSessionSummaryDTO) method.invoke(
                controller, entity, "agent-1", Map.of("session-1", "task-1"));

        assertEquals("ctx-1", dto.getContextId());
        assertEquals("task-1", dto.getLatestTaskId());
        assertEquals("tms-1", dto.getClientContext().get("upstreamConversationId"));
    }

    @Test
    void askAgent_topLevelAttachmentsOverrideMetadataAttachments() {
        UnifiedAgentResolver agentResolver = mock(UnifiedAgentResolver.class);
        ClientAppRuntimeCredentialResolver credentialResolver = mock(ClientAppRuntimeCredentialResolver.class);
        A2aAgent agent = mock(A2aAgent.class);
        OpenApiController controller = newController(agentResolver, credentialResolver);

        Map<String, Object> metadataAttachment = new LinkedHashMap<>();
        metadataAttachment.put("name", "old.png");
        metadataAttachment.put("url", "https://tms.example.com/old.png");
        Map<String, Object> topLevelAttachment = new LinkedHashMap<>();
        topLevelAttachment.put("name", "pod-photo.png");
        topLevelAttachment.put("url", "https://tms.example.com/pod-photo.png");
        List<Map<String, Object>> topLevelAttachments = List.of(topLevelAttachment);

        OpenApiQueryForm form = new OpenApiQueryForm();
        form.setMessage("结合附件分析表单");
        form.setMetadata(Map.of("attachments", List.of(metadataAttachment), "modelConfigId", "cfg-1"));
        form.setAttachments(topLevelAttachments);

        when(credentialResolver.resolveAccessTokenForSkill(
                nullable(String.class), nullable(String.class), eq("agent-1")))
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
        assertSame(topLevelAttachments, captor.getValue().getMetadata().get("attachments"));
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

        when(credentialResolver.resolveAccessTokenForSkill(
                nullable(String.class), nullable(String.class), eq("agent-1")))
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
        assertSame(metadataAttachments, captor.getValue().getMetadata().get("attachments"));
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

        when(credentialResolver.resolveAccessTokenForSkill(
                nullable(String.class), nullable(String.class), eq("agent-1")))
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
    void askAgent_rejectsContextIdWithoutUpstreamUserId() {
        UnifiedAgentResolver agentResolver = mock(UnifiedAgentResolver.class);
        ClientAppRuntimeCredentialResolver credentialResolver = mock(ClientAppRuntimeCredentialResolver.class);
        OpenApiController controller = newController(agentResolver, credentialResolver);

        OpenApiQueryForm form = new OpenApiQueryForm();
        form.setMessage("继续处理");
        form.setContextId("ctx-1");

        when(credentialResolver.resolveAccessTokenForSkill(
                nullable(String.class), nullable(String.class), eq("agent-1")))
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
        form.setContextId("ctx-1");

        when(request.getHeader("X-Upstream-User-Id")).thenReturn("upstream-b");
        when(credentialResolver.resolveAccessTokenForSkill(
                nullable(String.class), nullable(String.class), eq("agent-1")))
                .thenReturn(Optional.of(credential()));
        when(sessionService.getSession("tenant-1", "app-1", "upstream-b", "ctx-1"))
                .thenThrow(new IllegalArgumentException("business agent session not found: ctx-1"));

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
        form.setContextId("ctx-1");

        when(request.getHeader("X-Upstream-User-Id")).thenReturn("upstream-a");
        when(credentialResolver.resolveAccessTokenForSkill(
                nullable(String.class), nullable(String.class), eq("agent-1")))
                .thenReturn(Optional.of(credential()));
        when(sessionService.getSession("tenant-1", "app-1", "upstream-a", "ctx-1"))
                .thenReturn(new BusinessAgentSessionDTO());
        when(agentResolver.resolveAgent(eq("agent-1"), any())).thenReturn(Optional.of(agent));
        CodingAgentEntity agentEntity = new CodingAgentEntity();
        agentEntity.setAgentId("agent-1");
        agentEntity.setTenantId("tenant-1");
        agentEntity.setUserId("owner-1");
        when(codingAgentRepository.findByAgentId("agent-1")).thenReturn(Optional.of(agentEntity));
        when(sessionQueryService.resolveSessionId("ctx-1", "owner-1")).thenReturn(Optional.empty());
        when(agent.sendTask(any())).thenReturn(A2aTask.builder()
                .id("task-1")
                .contextId("ctx-1")
                .status(A2aTaskStatus.builder().state(A2aTaskState.SUBMITTED).build())
                .build());

        controller.askAgent("agent-1", form, request);

        var captor = org.mockito.ArgumentCaptor.forClass(A2aMessage.class);
        verify(agent).sendTask(captor.capture());
        assertEquals("ctx-1", captor.getValue().getContextId());
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
        return newController(agentResolver, credentialResolver, null);
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
        ObjectProvider<ClientAppRuntimeCredentialResolver> credentialProvider = mock(ObjectProvider.class);
        when(credentialProvider.getIfAvailable()).thenReturn(credentialResolver);
        ObjectProvider<BusinessAgentSessionService> sessionProvider = mock(ObjectProvider.class);
        when(sessionProvider.getIfAvailable()).thenReturn(sessionService);
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
                credentialProvider,
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                sessionProvider
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
