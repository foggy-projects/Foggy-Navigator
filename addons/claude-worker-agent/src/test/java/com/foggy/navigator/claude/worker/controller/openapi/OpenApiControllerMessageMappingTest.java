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
import com.foggy.navigator.business.agent.service.worker.BusinessAgentWorkerTaskLaunchRequest;
import com.foggy.navigator.claude.worker.model.dto.OpenSessionSummaryDTO;
import com.foggy.navigator.claude.worker.model.dto.OpenSessionMessageDTO;
import com.foggy.navigator.claude.worker.model.dto.OpenTaskMessagesResponse;
import com.foggy.navigator.claude.worker.model.dto.OpenTaskDiagnosticsDTO;
import com.foggy.navigator.claude.worker.model.dto.OpenTaskEvidenceDTO;
import com.foggy.navigator.claude.worker.model.form.OpenApiQueryForm;
import com.foggy.navigator.common.dto.a2a.A2aMessage;
import com.foggy.navigator.common.dto.a2a.A2aTask;
import com.foggy.navigator.common.dto.a2a.A2aTaskState;
import com.foggy.navigator.common.dto.a2a.A2aTaskStatus;
import com.foggy.navigator.common.enums.LlmModelCategory;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import com.foggy.navigator.common.enums.WorkingDirectoryResolverType;
import com.foggy.navigator.common.enums.WorkspaceScope;
import com.foggy.navigator.common.entity.AgentConversationContextEntity;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.common.model.CodexConfig;
import com.foggy.navigator.claude.worker.model.entity.ClaudeWorkerEntity;
import com.foggy.navigator.claude.worker.repository.ClaudeWorkerRepository;
import com.foggy.navigator.claude.worker.repository.CodingAgentRepository;
import com.foggy.navigator.claude.worker.service.*;
import com.foggy.navigator.common.entity.SessionMessageEntity;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.repository.WorkingDirectoryRepository;
import com.foggy.navigator.session.registry.UnifiedAgentResolver;
import com.foggy.navigator.session.agent.pipeline.AgentSubmitPipeline;
import com.foggy.navigator.session.agent.pipeline.AgentTaskSubmitResult;
import com.foggy.navigator.session.service.OpenApiSessionQueryService;
import com.foggy.navigator.session.service.TaskDispatchFacade;
import com.foggy.navigator.spi.agent.A2aAgent;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import com.foggy.navigator.spi.agent.AgentTaskSubmitRequest;
import com.foggy.navigator.spi.claude.ClaudeWorkerFacade;
import com.foggy.navigator.business.agent.service.AccountContextFileService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

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
        assertEquals("final_marker", dto.getEventKind());
        assertTrue(dto.getTerminal());
        assertEquals("COMPLETED", dto.getTerminalStatus());
        assertEquals("task-1", dto.getTaskId());
    }

    @Test
    void taskCompletedMessageExposesStructuredOutputAtTopLevel() throws Exception {
        OpenApiController controller = newController();
        SessionMessageEntity entity = new SessionMessageEntity();
        entity.setId("msg-open-artifact");
        entity.setSessionId("session-1");
        entity.setTaskId("task-1");
        entity.setRole("ASSISTANT");
        entity.setContent("已生成打印模板草稿");
        entity.setMetadata("""
                {
                  "type": "TASK_COMPLETED",
                  "taskId": "task-1",
                  "structuredOutput": {
                    "type": "OPEN_ARTIFACT",
                    "label": "查看模板预览",
                    "artifact": {
                      "kind": "iframe",
                      "uri": "http://localhost:3199/tms/print-template-preview?templateId=tpl-1"
                    }
                  }
                }
                """);
        entity.setCreatedAt(LocalDateTime.now());

        OpenSessionMessageDTO dto = mapMessage(controller, entity);

        assertEquals("RESULT", dto.getType());
        assertEquals("final_marker", dto.getEventKind());
        assertEquals("COMPLETED", dto.getTerminalStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> structuredOutput = (Map<String, Object>) dto.getStructuredOutput();
        assertEquals("OPEN_ARTIFACT", structuredOutput.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> artifact = (Map<String, Object>) structuredOutput.get("artifact");
        assertEquals("iframe", artifact.get("kind"));
        assertNull(dto.getMetadata().get("taskId"));
        assertNotNull(dto.getMetadata().get("structuredOutput"));
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
        assertEquals("tool_call_summary", dto.getEventKind());
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
    void messageEventContractExposesProgressTypeAndEvidenceRefs() throws Exception {
        OpenApiController controller = newController();
        SessionMessageEntity entity = new SessionMessageEntity();
        entity.setId("msg-progress");
        entity.setSessionId("session-1");
        entity.setTaskId("task-1");
        entity.setRole("ASSISTANT");
        entity.setContent("Opening execution frame");
        entity.setMetadata("""
                {
                  "type": "STATE_SYNC",
                  "subtype": "skill_frame_open",
                  "taskId": "task-1",
                  "reportRefs": ["frame-report://worker-task-1/frame-1"],
                  "artifactRefs": [
                    {"path": "/home/sa/workspace/report.md?signature=secret", "summary": "report ready"}
                  ]
                }
                """);
        entity.setCreatedAt(LocalDateTime.now());

        OpenSessionMessageDTO dto = mapMessage(controller, entity, "RUNNING");

        assertEquals("STATE", dto.getType());
        assertEquals("progress", dto.getEventKind());
        assertEquals("skill_frame_open", dto.getProgressType());
        assertEquals("RUNNING", dto.getStatus());
        assertEquals(1, dto.getReportRefs().size());
        assertEquals("frame_report", dto.getReportRefs().get(0).getType());
        assertEquals("frame-1", dto.getReportRefs().get(0).getFrameId());
        assertEquals(1, dto.getArtifactRefs().size());
        assertEquals("/home/sa/workspace/report.md", dto.getArtifactRefs().get(0).getPath());
        assertFalse(dto.getArtifactRefs().get(0).getPath().contains("signature=secret"));
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
    void getTaskMessagesReturnsSyntheticErrorWhenFailedTaskHasNoPersistedMessages() {
        UnifiedAgentResolver agentResolver = mock(UnifiedAgentResolver.class);
        ClientAppRuntimeCredentialResolver credentialResolver = mock(ClientAppRuntimeCredentialResolver.class);
        OpenApiSessionQueryService sessionQueryService = mock(OpenApiSessionQueryService.class);
        A2aAgent agent = mock(A2aAgent.class);
        OpenApiController controller = newController(
                agentResolver,
                credentialResolver,
                null,
                mock(CodingAgentRepository.class),
                sessionQueryService);
        HttpServletRequest request = mock(HttpServletRequest.class);

        SessionTaskEntity task = new SessionTaskEntity();
        task.setTaskId("task-failed");
        task.setSessionId("session-1");
        task.setTenantId("tenant-1");
        task.setAgentId("agent-1");
        task.setWorkerId("worker-1");
        task.setProviderType("OPENAI_CODEX");
        task.setStatus("FAILED");
        task.setErrorMessage("Codex not configured for worker: worker-1");
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(task.getCreatedAt());

        when(credentialResolver.resolveAccessToken(nullable(String.class), nullable(String.class)))
                .thenReturn(Optional.of(credential()));
        when(agentResolver.resolveAgent(eq("agent-1"), any())).thenReturn(Optional.of(agent));
        when(sessionQueryService.findTask("task-failed")).thenReturn(Optional.of(task));
        when(sessionQueryService.resolveContextId("session-1")).thenReturn(Optional.of("ctx-1"));
        when(sessionQueryService.getTaskMessages("task-failed", null, 51)).thenReturn(List.of());

        OpenTaskMessagesResponse response = controller.getTaskMessages(
                "agent-1", "task-failed", null, 50, false, request).getData();

        assertEquals("FAILED", response.getStatus());
        assertTrue(response.isTerminal());
        assertEquals("FAILED", response.getTerminalStatus());
        assertEquals(1, response.getMessages().size());
        OpenSessionMessageDTO message = response.getMessages().get(0);
        assertEquals("task-error:task-failed", message.getMessageId());
        assertEquals("ERROR", message.getType());
        assertEquals("Codex not configured for worker: worker-1", message.getContent());
        assertEquals("task_state", message.getMetadata().get("source"));
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
    void askAgent_topLevelCodexBizRuntimeOptionsAreForwardedAndDirectoryIsValidated() {
        UnifiedAgentResolver agentResolver = mock(UnifiedAgentResolver.class);
        ClientAppRuntimeCredentialResolver credentialResolver = mock(ClientAppRuntimeCredentialResolver.class);
        A2AgentResourceResolver resourceResolver = mock(A2AgentResourceResolver.class);
        A2aAgent agent = mock(A2aAgent.class);
        OpenApiController controller = newController(
                agentResolver,
                credentialResolver,
                null,
                null,
                mock(CodingAgentRepository.class),
                mock(OpenApiSessionQueryService.class),
                defaultRouteService(),
                resourceResolver);
        HttpServletRequest request = mock(HttpServletRequest.class);

        A2AgentResourceResolver.ResolvedAgentResource agentResource =
                new A2AgentResourceResolver.ResolvedAgentResource(
                        "agent-1",
                        ResourceOwnerType.CLIENT_APP,
                        "app-1",
                        "app-1",
                        "agent-1",
                        "pool-1",
                        ResourceOwnerType.PLATFORM,
                        "tenant-1",
                        "WORKER_POOL:PLATFORM",
                        "OPENAI_CODEX",
                        "codex-worker-1",
                        ResourceOwnerType.CLIENT_APP,
                        "app-1",
                        "AGENT_WORKER_REF",
                        "model-default",
                        null,
                        "dir-default",
                        "AGENT:CLIENT_APP");
        when(resourceResolver.resolveRequiredAgent(
                eq("tenant-1"), eq("app-1"), eq("scenario-1.actor-1"), eq("agent-1")))
                .thenReturn(agentResource);
        when(resourceResolver.resolveRequiredModelForAgent(
                eq("tenant-1"),
                eq("app-1"),
                eq(agentResource),
                eq("model-default"),
                nullable(String.class),
                eq(LlmModelCategory.GENERAL)))
                .thenReturn(new A2AgentResourceResolver.ResolvedModelResource(
                        "model-default",
                        "model-default",
                        null,
                        LlmModelCategory.GENERAL,
                        "codex-latest",
                        "MODEL_CONFIG_DEFAULT",
                        "OPENAI_CODEX",
                        "AGENT_DEFAULT_MODEL:DEFAULT_MODEL_GRANT"));
        when(resourceResolver.resolveRequiredWorkspaceForAgent(
                eq("tenant-1"), eq("app-1"), eq("scenario-1.actor-1"), eq(agentResource), eq("dir-requested")))
                .thenReturn(new A2AgentResourceResolver.ResolvedWorkspaceResource(
                        "dir-requested",
                        "codex-worker-1",
                        WorkspaceScope.USER_PRIVATE,
                        WorkingDirectoryResolverType.MANAGED,
                        "/mnt/d/world-sim/scenario-1/actor-1",
                        List.of("/mnt/d/world-sim/scenario-1/actor-1"),
                        false,
                        null,
                        null,
                        null,
                        "WORKING_DIRECTORY:USER_PRIVATE"));

        OpenApiQueryForm form = new OpenApiQueryForm();
        form.setMessage("run actor task");
        form.setProviderType("codex-biz-worker");
        form.setDirectoryId("dir-requested");
        form.setPrivateAccountId("scenario-1.actor-1");
        form.setSandboxMode("workspace-write");
        form.setApprovalPolicy("never");
        form.setNetworkAccessEnabled(false);
        form.setWebSearchMode("disabled");

        when(request.getHeader("X-Upstream-User-Id")).thenReturn("scenario-1.actor-1");
        when(credentialResolver.resolveAccessToken(
                nullable(String.class), nullable(String.class)))
                .thenReturn(Optional.of(credential()));
        when(agentResolver.resolveAgent(eq("agent-1"), any())).thenReturn(Optional.of(agent));
        when(agent.sendTask(any())).thenReturn(A2aTask.builder()
                .id("task-1")
                .contextId("ctx-1")
                .status(A2aTaskStatus.builder().state(A2aTaskState.SUBMITTED).build())
                .build());

        controller.askAgent("agent-1", form, request);

        var captor = org.mockito.ArgumentCaptor.forClass(A2aMessage.class);
        verify(agent).sendTask(captor.capture());
        Map<String, Object> metadata = captor.getValue().getMetadata();
        assertEquals("codex-biz-worker", metadata.get("providerType"));
        assertEquals("dir-requested", metadata.get("directoryId"));
        assertEquals("/mnt/d/world-sim/scenario-1/actor-1", metadata.get("cwd"));
        assertEquals("scenario-1.actor-1", metadata.get("privateAccountId"));
        assertEquals("workspace-write", metadata.get("sandboxMode"));
        assertEquals("never", metadata.get("approvalPolicy"));
        assertEquals(false, metadata.get("networkAccessEnabled"));
        assertEquals("disabled", metadata.get("webSearchMode"));
        verify(resourceResolver).resolveRequiredWorkspaceForAgent(
                "tenant-1", "app-1", "scenario-1.actor-1", agentResource, "dir-requested");
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
    void askAgent_bindsBusinessSessionFromTaskMetadataWhenContextMappingIsDelayed() {
        UnifiedAgentResolver agentResolver = mock(UnifiedAgentResolver.class);
        ClientAppRuntimeCredentialResolver credentialResolver = mock(ClientAppRuntimeCredentialResolver.class);
        BusinessAgentSessionService sessionService = mock(BusinessAgentSessionService.class);
        OpenApiSessionQueryService sessionQueryService = mock(OpenApiSessionQueryService.class);
        A2aAgent agent = mock(A2aAgent.class);
        OpenApiController controller = newController(
                agentResolver,
                credentialResolver,
                sessionService,
                null,
                mock(CodingAgentRepository.class),
                sessionQueryService);
        HttpServletRequest request = mock(HttpServletRequest.class);

        OpenApiQueryForm form = new OpenApiQueryForm();
        form.setMessage("创建一个新会话");

        when(request.getHeader("X-Upstream-User-Id")).thenReturn("upstream-a");
        when(credentialResolver.resolveAccessToken(
                nullable(String.class), nullable(String.class)))
                .thenReturn(Optional.of(credential()));
        when(agentResolver.resolveAgent(eq("agent-1"), any())).thenReturn(Optional.of(agent));
        when(agent.sendTask(any())).thenReturn(A2aTask.builder()
                .id("task-1")
                .metadata(Map.of("sessionId", "session-1"))
                .status(A2aTaskStatus.builder().state(A2aTaskState.SUBMITTED).build())
                .build());

        var result = controller.askAgent("agent-1", form, request);

        assertNotNull(result.getData());
        assertTrue(result.getData().getContextId().matches("^bctx_\\d{8}_[0-9a-f]{2}_[A-Za-z0-9._-]+$"));
        verify(sessionService).bindOpenApiSession(
                eq("tenant-1"),
                eq("app-1"),
                eq("upstream-a"),
                argThat(contextId -> contextId != null
                        && contextId.matches("^bctx_\\d{8}_[0-9a-f]{2}_[A-Za-z0-9._-]+$")),
                eq("session-1"),
                eq("agent-1"),
                eq("task-1"),
                nullable(String.class));
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
        when(taskService.prepareOpenApiTaskScopedToken(
                eq("tenant-1"),
                eq("app-1"),
                eq("app-1"),
                eq("upstream-a"),
                eq("agent-1"),
                any(),
                nullable(String.class),
                any(BusinessAgentWorkerTaskLaunchRequest.class)))
                .thenReturn(preparedOpenApiToken("btt_open_api_1"));
        when(agentResolver.resolveAgent(eq("agent-1"), any())).thenReturn(Optional.of(agent));
        when(agent.sendTask(any())).thenReturn(A2aTask.builder()
                .id("lgt_visible_1")
                .contextId("ctx-1")
                .metadata(Map.of(
                        "sessionId", "worker_session_1",
                        "workerId", "preselected-worker"))
                .status(A2aTaskStatus.builder().state(A2aTaskState.SUBMITTED).build())
                .build());

        controller.askAgent("agent-1", form, request);

        var captor = org.mockito.ArgumentCaptor.forClass(A2aMessage.class);
        verify(agent).sendTask(captor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> runtimeContext = (Map<String, Object>) captor.getValue().getMetadata().get("runtimeContext");
        assertEquals("btt_open_api_1", runtimeContext.get("task_scoped_token"));
        assertEquals("preselected-worker", runtimeContext.get("worker_id"));
        assertEquals("bwl_test_01", runtimeContext.get("worker_lease_id"));
        assertFalse(runtimeContext.containsKey("skill_name"));
        verify(taskService).bindOpenApiTaskScopedTokenToWorkerTask(
                "tenant-1",
                "btt_open_api_1",
                "lgt_visible_1",
                "worker_session_1",
                "preselected-worker",
                "bwl_test_01");
    }

    @Test
    void askAgent_submitsNonBizProviderWithoutWorkerGatewayCapability() {
        UnifiedAgentResolver agentResolver = mock(UnifiedAgentResolver.class);
        ClientAppRuntimeCredentialResolver credentialResolver = mock(ClientAppRuntimeCredentialResolver.class);
        BusinessAgentTaskService taskService = mock(BusinessAgentTaskService.class);
        A2AgentResourceResolver resourceResolver = mock(A2AgentResourceResolver.class);
        A2aAgent agent = mock(A2aAgent.class);
        A2AgentResourceResolver.ResolvedAgentResource agentResource =
                new A2AgentResourceResolver.ResolvedAgentResource(
                        "agent-1",
                        ResourceOwnerType.CLIENT_APP,
                        "app-1",
                        "app-1",
                        "agent-1",
                        null,
                        null,
                        null,
                        null,
                        "CLAUDE_CODE",
                        null,
                        null,
                        null,
                        null,
                        "model-claude",
                        null,
                        null,
                        "AGENT:CLIENT_APP");
        A2AgentResourceResolver.ResolvedModelResource modelResource =
                new A2AgentResourceResolver.ResolvedModelResource(
                        "model-claude",
                        null,
                        null,
                        LlmModelCategory.GENERAL,
                        "claude-sonnet",
                        "MODEL_CONFIG_DEFAULT",
                        "CLAUDE_CODE",
                        "AGENT_DEFAULT_MODEL:DEFAULT_MODEL_GRANT");
        OpenApiController controller = newController(
                agentResolver,
                credentialResolver,
                null,
                taskService,
                mock(CodingAgentRepository.class),
                mock(OpenApiSessionQueryService.class),
                defaultRouteService(),
                resourceResolver);
        HttpServletRequest request = mock(HttpServletRequest.class);
        OpenApiQueryForm form = new OpenApiQueryForm();
        form.setMessage("提交 Claude 任务");

        when(request.getHeader("X-Upstream-User-Id")).thenReturn("upstream-a");
        when(credentialResolver.resolveAccessToken(
                nullable(String.class), nullable(String.class)))
                .thenReturn(Optional.of(credential()));
        when(resourceResolver.resolveRequiredAgent(
                "tenant-1", "app-1", "upstream-a", "agent-1"))
                .thenReturn(agentResource);
        when(resourceResolver.resolveRequiredModelForAgent(
                eq("tenant-1"),
                eq("app-1"),
                eq(agentResource),
                nullable(String.class),
                nullable(String.class),
                eq(LlmModelCategory.GENERAL)))
                .thenReturn(modelResource);
        when(taskService.prepareOpenApiTaskScopedToken(
                eq("tenant-1"),
                eq("app-1"),
                eq("app-1"),
                eq("upstream-a"),
                eq("agent-1"),
                any(),
                nullable(String.class),
                argThat(selection -> "CLAUDE_CODE".equals(selection.getWorkerBackend()))))
                .thenReturn(null);
        when(agentResolver.resolveAgent(eq("agent-1"), any())).thenReturn(Optional.of(agent));
        when(agent.sendTask(any())).thenReturn(A2aTask.builder()
                .id("claude-task-1")
                .status(A2aTaskStatus.builder().state(A2aTaskState.SUBMITTED).build())
                .build());

        var result = controller.askAgent("agent-1", form, request);

        assertNotNull(result.getData());
        var messageCaptor = org.mockito.ArgumentCaptor.forClass(A2aMessage.class);
        verify(agent).sendTask(messageCaptor.capture());
        assertFalse(messageCaptor.getValue().getMetadata().containsKey("runtimeContext"));
        verify(taskService, never()).bindOpenApiTaskScopedTokenToWorkerTask(
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void askAgent_revokesOpenApiBusinessRuntimeTokenWhenWorkerTaskBindingFails() {
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
        when(taskService.prepareOpenApiTaskScopedToken(
                eq("tenant-1"),
                eq("app-1"),
                eq("app-1"),
                eq("upstream-a"),
                eq("agent-1"),
                any(),
                nullable(String.class),
                any(BusinessAgentWorkerTaskLaunchRequest.class)))
                .thenReturn(preparedOpenApiToken("btt_bind_failure"));
        when(agentResolver.resolveAgent(eq("agent-1"), any())).thenReturn(Optional.of(agent));
        when(agent.sendTask(any())).thenReturn(A2aTask.builder()
                .id("lgt_bind_failure")
                .contextId("ctx-1")
                .metadata(Map.of(
                        "sessionId", "worker_session_1",
                        "workerId", "preselected-worker"))
                .status(A2aTaskStatus.builder().state(A2aTaskState.SUBMITTED).build())
                .build());
        doThrow(new IllegalStateException("worker task token binding rejected"))
                .when(taskService)
                .bindOpenApiTaskScopedTokenToWorkerTask(
                        "tenant-1",
                        "btt_bind_failure",
                        "lgt_bind_failure",
                        "worker_session_1",
                        "preselected-worker",
                        "bwl_test_01");

        var result = controller.askAgent("agent-1", form, request);

        assertNull(result.getData());
        assertTrue(result.getMsg().contains("worker task token binding rejected"));
        verify(taskService).revokeOpenApiTaskScopedToken(
                "tenant-1",
                "btt_bind_failure",
                "system",
                "open api task token binding failed");
    }

    @Test
    void askAgent_revokesOpenApiBusinessRuntimeTokenBeforeRethrowingUnexpectedSubmitFailure() {
        UnifiedAgentResolver agentResolver = mock(UnifiedAgentResolver.class);
        ClientAppRuntimeCredentialResolver credentialResolver = mock(ClientAppRuntimeCredentialResolver.class);
        BusinessAgentTaskService taskService = mock(BusinessAgentTaskService.class);
        A2aAgent agent = mock(A2aAgent.class);
        OpenApiController controller = newController(agentResolver, credentialResolver, null, taskService);
        HttpServletRequest request = mock(HttpServletRequest.class);

        OpenApiQueryForm form = new OpenApiQueryForm();
        form.setMessage("触发未知提交异常");

        when(request.getHeader("X-Upstream-User-Id")).thenReturn("upstream-a");
        when(credentialResolver.resolveAccessToken(
                nullable(String.class), nullable(String.class)))
                .thenReturn(Optional.of(credential()));
        when(taskService.prepareOpenApiTaskScopedToken(
                eq("tenant-1"),
                eq("app-1"),
                eq("app-1"),
                eq("upstream-a"),
                eq("agent-1"),
                any(),
                nullable(String.class),
                any(BusinessAgentWorkerTaskLaunchRequest.class)))
                .thenReturn(preparedOpenApiToken("btt_unexpected_submit_failure"));
        when(agentResolver.resolveAgent(eq("agent-1"), any())).thenReturn(Optional.of(agent));
        when(agent.sendTask(any())).thenThrow(new UnsupportedOperationException("unexpected submit failure"));

        UnsupportedOperationException failure = assertThrows(
                UnsupportedOperationException.class,
                () -> controller.askAgent("agent-1", form, request));

        assertEquals("unexpected submit failure", failure.getMessage());
        verify(taskService).revokeOpenApiTaskScopedToken(
                "tenant-1",
                "btt_unexpected_submit_failure",
                "system",
                "open api task submission failed");
    }

    @Test
    void askAgent_revokesOpenApiBusinessRuntimeTokenWhenSubmitReturnsTaskWithoutId() {
        UnifiedAgentResolver agentResolver = mock(UnifiedAgentResolver.class);
        ClientAppRuntimeCredentialResolver credentialResolver = mock(ClientAppRuntimeCredentialResolver.class);
        BusinessAgentTaskService taskService = mock(BusinessAgentTaskService.class);
        A2aAgent agent = mock(A2aAgent.class);
        OpenApiController controller = newController(agentResolver, credentialResolver, null, taskService);
        HttpServletRequest request = mock(HttpServletRequest.class);

        OpenApiQueryForm form = new OpenApiQueryForm();
        form.setMessage("返回缺少任务编号的任务");

        when(request.getHeader("X-Upstream-User-Id")).thenReturn("upstream-a");
        when(credentialResolver.resolveAccessToken(
                nullable(String.class), nullable(String.class)))
                .thenReturn(Optional.of(credential()));
        when(taskService.prepareOpenApiTaskScopedToken(
                eq("tenant-1"),
                eq("app-1"),
                eq("app-1"),
                eq("upstream-a"),
                eq("agent-1"),
                any(),
                nullable(String.class),
                any(BusinessAgentWorkerTaskLaunchRequest.class)))
                .thenReturn(preparedOpenApiToken("btt_missing_task_id"));
        when(agentResolver.resolveAgent(eq("agent-1"), any())).thenReturn(Optional.of(agent));
        when(agent.sendTask(any())).thenReturn(A2aTask.builder()
                .contextId("ctx-1")
                .status(A2aTaskStatus.builder().state(A2aTaskState.SUBMITTED).build())
                .build());

        var result = controller.askAgent("agent-1", form, request);

        assertNull(result.getData());
        assertEquals("open api task submission returned no task id", result.getMsg());
        verify(taskService).revokeOpenApiTaskScopedToken(
                "tenant-1",
                "btt_missing_task_id",
                "system",
                "open api task submission returned no task id");
        verify(taskService, never()).bindOpenApiTaskScopedTokenToWorkerTask(
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void askAgent_failsFastWhenLanggraphBizTaskHasNoDirectoryId() {
        UnifiedAgentResolver agentResolver = mock(UnifiedAgentResolver.class);
        ClientAppRuntimeCredentialResolver credentialResolver = mock(ClientAppRuntimeCredentialResolver.class);
        A2AgentResourceResolver resourceResolver = mock(A2AgentResourceResolver.class);
        A2aAgent agent = mock(A2aAgent.class);
        OpenApiController controller = newController(
                agentResolver,
                credentialResolver,
                null,
                null,
                mock(CodingAgentRepository.class),
                mock(OpenApiSessionQueryService.class),
                defaultRouteService(),
                resourceResolver);
        HttpServletRequest request = mock(HttpServletRequest.class);

        A2AgentResourceResolver.ResolvedAgentResource agentResource =
                new A2AgentResourceResolver.ResolvedAgentResource(
                        "agent-1",
                        ResourceOwnerType.CLIENT_APP,
                        "app-1",
                        "app-1",
                        "agent-1",
                        "pool-1",
                        ResourceOwnerType.PLATFORM,
                        "tenant-1",
                        "WORKER_POOL:PLATFORM",
                        "LANGGRAPH_BIZ",
                        null,
                        null,
                        null,
                        null,
                        "model-default",
                        null,
                        null,
                        "AGENT:CLIENT_APP");
        when(resourceResolver.resolveRequiredAgent(
                eq("tenant-1"), eq("app-1"), eq("upstream-a"), eq("agent-1")))
                .thenReturn(agentResource);
        when(resourceResolver.resolveRequiredModelForAgent(
                eq("tenant-1"),
                eq("app-1"),
                eq(agentResource),
                eq("model-default"),
                nullable(String.class),
                eq(LlmModelCategory.GENERAL)))
                .thenReturn(new A2AgentResourceResolver.ResolvedModelResource(
                        "model-default",
                        "model-default",
                        null,
                        LlmModelCategory.GENERAL,
                        "qwen-plus",
                        "MODEL_CONFIG_DEFAULT",
                        "LANGGRAPH_BIZ",
                        "AGENT_DEFAULT_MODEL:DEFAULT_MODEL_GRANT"));

        OpenApiQueryForm form = new OpenApiQueryForm();
        form.setMessage("run biz smoke");
        when(request.getHeader("X-Upstream-User-Id")).thenReturn("upstream-a");
        when(credentialResolver.resolveAccessToken(
                nullable(String.class), nullable(String.class)))
                .thenReturn(Optional.of(credential()));

        var result = controller.askAgent("agent-1", form, request);

        assertFalse(result.isOk());
        assertTrue(result.getMsg().contains("TASK_DIRECTORY_REQUIRED"));
        verify(resourceResolver, never()).resolveRequiredWorkspaceForAgent(
                any(String.class),
                any(String.class),
                nullable(String.class),
                any(A2AgentResourceResolver.ResolvedAgentResource.class),
                any(String.class));
        verify(agentResolver, never()).resolveAgent(any(), any());
        verify(agent, never()).sendTask(any());
    }

    @Test
    void askAgent_rejectsMissingUpstreamUserGrantBeforeSubmittingTask() {
        UnifiedAgentResolver agentResolver = mock(UnifiedAgentResolver.class);
        ClientAppRuntimeCredentialResolver credentialResolver = mock(ClientAppRuntimeCredentialResolver.class);
        BusinessAgentTaskService taskService = mock(BusinessAgentTaskService.class);
        A2aAgent agent = mock(A2aAgent.class);
        OpenApiController controller = newController(agentResolver, credentialResolver, null, taskService);
        HttpServletRequest request = mock(HttpServletRequest.class);

        OpenApiQueryForm form = new OpenApiQueryForm();
        form.setMessage("创建车辆并走审批");

        when(request.getHeader("X-Upstream-User-Id")).thenReturn("upstream-missing-grant");
        when(credentialResolver.resolveAccessToken(
                nullable(String.class), nullable(String.class)))
                .thenReturn(Optional.of(credential()));
        when(taskService.prepareOpenApiTaskScopedToken(
                eq("tenant-1"),
                eq("app-1"),
                eq("app-1"),
                eq("upstream-missing-grant"),
                eq("agent-1"),
                any(),
                nullable(String.class),
                any(BusinessAgentWorkerTaskLaunchRequest.class)))
                .thenThrow(new IllegalStateException("Upstream user is not granted access to this Client App"));
        when(agentResolver.resolveAgent(eq("agent-1"), any())).thenReturn(Optional.of(agent));

        RuntimeException error = assertThrows(
                RuntimeException.class,
                () -> controller.askAgent("agent-1", form, request));

        assertTrue(error.getMessage().contains("Upstream user is not granted access to this Client App"));
        verify(agent, never()).sendTask(any());
        verify(taskService, never()).bindOpenApiTaskScopedTokenToWorkerTask(
                any(), any(), any(), any(), any(), any());
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
        when(taskService.prepareOpenApiTaskScopedToken(
                eq("tenant-1"),
                eq("app-1"),
                eq("app-1"),
                eq("upstream-a"),
                eq("tms.navigator.agent"),
                any(),
                nullable(String.class),
                any(BusinessAgentWorkerTaskLaunchRequest.class)))
                .thenReturn(preparedOpenApiToken("btt_open_api_1"));
        when(agentResolver.resolveAgent(eq("root-agent"), any())).thenReturn(Optional.of(agent));
        when(agent.sendTask(any())).thenReturn(A2aTask.builder()
                .id("task-1")
                .contextId("ctx-1")
                .metadata(Map.of("workerId", "preselected-worker"))
                .status(A2aTaskStatus.builder().state(A2aTaskState.SUBMITTED).build())
                .build());

        controller.askAgent("root-agent", form, request);

        var captor = org.mockito.ArgumentCaptor.forClass(A2aMessage.class);
        verify(agent).sendTask(captor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> context = (Map<String, Object>) captor.getValue().getMetadata().get("context");
        assertEquals("root-agent", context.get("rootAgentId"));
        assertFalse(context.containsKey("businessSkillId"));
        assertFalse(context.containsKey("businessSkillName"));
        assertNull(captor.getValue().getMetadata().get("skill_name"));
        @SuppressWarnings("unchecked")
        Map<String, Object> runtimeContext = (Map<String, Object>) captor.getValue().getMetadata().get("runtimeContext");
        assertFalse(runtimeContext.containsKey("skill_name"));
        verify(credentialResolver, never()).resolveAccessTokenForSkill(
                nullable(String.class), nullable(String.class), eq("root-agent"));
    }

    @Test
    void askAgent_sanitizesOwnerAwareRuntimeContextFromUntrustedMetadata() {
        UnifiedAgentResolver agentResolver = mock(UnifiedAgentResolver.class);
        ClientAppRuntimeCredentialResolver credentialResolver = mock(ClientAppRuntimeCredentialResolver.class);
        BusinessAgentTaskService taskService = mock(BusinessAgentTaskService.class);
        A2aAgent agent = mock(A2aAgent.class);
        OpenApiController controller = newController(agentResolver, credentialResolver, null, taskService);
        HttpServletRequest request = mock(HttpServletRequest.class);

        OpenApiQueryForm form = new OpenApiQueryForm();
        form.setMessage("验证 owner-aware runtime");
        form.setMetadata(Map.of(
                "skill_name", "stale-direct-skill",
                "skillName", "staleDirectSkill",
                "skill_markdown", "# stale markdown",
                "runtimeContext", Map.of("task_scoped_token", "caller-token", "skill_name", "caller-skill"),
                "context", Map.of(
                        "traceId", "trace-1",
                        "clientAppId", "evil-app",
                        "upstreamUserId", "evil-user",
                        "accountId", "evil-account",
                        "account_id", "evil-account",
                        "businessSkillId", "evil-skill",
                        "businessSkillName", "evil-skill",
                        "skill_name", "evil-direct-skill",
                        "skill_markdown", "# evil markdown",
                        "task_scoped_token", "evil-token"
                )));

        when(request.getHeader("X-Upstream-User-Id")).thenReturn("upstream-a");
        when(credentialResolver.resolveAccessToken(
                nullable(String.class), nullable(String.class)))
                .thenReturn(Optional.of(credential()));
        when(taskService.prepareOpenApiTaskScopedToken(
                eq("tenant-1"),
                eq("app-1"),
                eq("app-1"),
                eq("upstream-a"),
                eq("agent-1"),
                any(),
                nullable(String.class),
                any(BusinessAgentWorkerTaskLaunchRequest.class)))
                .thenReturn(preparedOpenApiToken("btt_owner_runtime_1"));
        when(agentResolver.resolveAgent(eq("agent-1"), any())).thenReturn(Optional.of(agent));
        when(agent.sendTask(any())).thenReturn(A2aTask.builder()
                .id("task-1")
                .contextId("ctx-1")
                .metadata(Map.of("workerId", "preselected-worker"))
                .status(A2aTaskStatus.builder().state(A2aTaskState.SUBMITTED).build())
                .build());

        controller.askAgent("agent-1", form, request);

        var captor = org.mockito.ArgumentCaptor.forClass(A2aMessage.class);
        verify(agent).sendTask(captor.capture());
        Map<String, Object> metadata = captor.getValue().getMetadata();
        assertFalse(metadata.containsKey("skill_name"));
        assertFalse(metadata.containsKey("skillName"));
        assertFalse(metadata.containsKey("skill_markdown"));
        assertFalse(metadata.containsKey("runtime_context"));

        @SuppressWarnings("unchecked")
        Map<String, Object> runtimeContext = (Map<String, Object>) metadata.get("runtimeContext");
        assertEquals("btt_owner_runtime_1", runtimeContext.get("task_scoped_token"));
        assertFalse(runtimeContext.containsKey("skill_name"));

        @SuppressWarnings("unchecked")
        Map<String, Object> context = (Map<String, Object>) metadata.get("context");
        assertEquals("trace-1", context.get("traceId"));
        assertEquals("app-1", context.get("clientAppId"));
        assertEquals("upstream-a", context.get("upstreamUserId"));
        assertEquals("upstream-a", context.get("accountId"));
        assertEquals("upstream-a", context.get("account_id"));
        assertFalse(context.containsKey("businessSkillId"));
        assertFalse(context.containsKey("businessSkillName"));
        assertEquals("cred-1", context.get("credentialId"));
        assertEquals(true, context.get("auto_inject_app_public_skills"));
        assertFalse(context.containsKey("skill_name"));
        assertFalse(context.containsKey("skill_markdown"));
        assertFalse(context.containsKey("task_scoped_token"));
    }

    @Test
    void askAgent_injectsResolvedWorkspaceLaunchMetadataAndIgnoresCallerLaunchFields() {
        UnifiedAgentResolver agentResolver = mock(UnifiedAgentResolver.class);
        ClientAppRuntimeCredentialResolver credentialResolver = mock(ClientAppRuntimeCredentialResolver.class);
        A2AgentResourceResolver resourceResolver = mock(A2AgentResourceResolver.class);
        A2aAgent agent = mock(A2aAgent.class);
        OpenApiController controller = newController(
                agentResolver,
                credentialResolver,
                null,
                null,
                mock(CodingAgentRepository.class),
                mock(OpenApiSessionQueryService.class),
                defaultRouteService(),
                resourceResolver);
        HttpServletRequest request = mock(HttpServletRequest.class);

        A2AgentResourceResolver.ResolvedAgentResource agentResource =
                new A2AgentResourceResolver.ResolvedAgentResource(
                        "agent-1",
                        ResourceOwnerType.CLIENT_APP,
                        "app-1",
                        "app-1",
                        "agent-1",
                        "pool-1",
                        ResourceOwnerType.PLATFORM,
                        "tenant-1",
                        "WORKER_POOL:PLATFORM",
                        "OPENAI_CODEX",
                        "manifest-worker",
                        ResourceOwnerType.CLIENT_APP,
                        "app-1",
                        "AGENT_WORKER_REF",
                        "model-default",
                        null,
                        "dir-default",
                        "AGENT:CLIENT_APP");
        when(resourceResolver.resolveRequiredAgent(
                eq("tenant-1"), eq("app-1"), eq("upstream-a"), eq("agent-1")))
                .thenReturn(agentResource);
        when(resourceResolver.resolveRequiredModelForAgent(
                eq("tenant-1"),
                eq("app-1"),
                eq(agentResource),
                eq("model-default"),
                nullable(String.class),
                eq(LlmModelCategory.GENERAL)))
                .thenReturn(new A2AgentResourceResolver.ResolvedModelResource(
                        "model-default",
                        "model-default",
                        null,
                        LlmModelCategory.GENERAL,
                        "codex-latest",
                        "MODEL_CONFIG_DEFAULT",
                        "OPENAI_CODEX",
                        "AGENT_DEFAULT_MODEL:DEFAULT_MODEL_GRANT"));
        when(resourceResolver.resolveRequiredWorkspaceForAgent(
                eq("tenant-1"), eq("app-1"), eq("upstream-a"), eq(agentResource), eq("dir-default")))
                .thenReturn(new A2AgentResourceResolver.ResolvedWorkspaceResource(
                        "dir-default",
                        "physical-worker",
                        WorkspaceScope.USER_PRIVATE,
                        WorkingDirectoryResolverType.MANAGED,
                        "/home/sa/workspace/school",
                        List.of("/home/sa/workspace/school"),
                        false,
                        null,
                        null,
                        null,
                        "WORKING_DIRECTORY:USER_PRIVATE"));

        OpenApiQueryForm form = new OpenApiQueryForm();
        form.setMessage("run codex smoke");
        form.setMetadata(Map.of(
                "workerId", "caller-worker",
                "directoryId", "caller-dir",
                "cwd", "D:/caller",
                "traceId", "trace-1"));

        when(request.getHeader("X-Upstream-User-Id")).thenReturn("upstream-a");
        when(credentialResolver.resolveAccessToken(
                nullable(String.class), nullable(String.class)))
                .thenReturn(Optional.of(credential()));
        when(agentResolver.resolveAgent(eq("agent-1"), any())).thenReturn(Optional.of(agent));
        when(agent.sendTask(any())).thenReturn(A2aTask.builder()
                .id("task-1")
                .contextId("ctx-1")
                .status(A2aTaskStatus.builder().state(A2aTaskState.SUBMITTED).build())
                .build());

        controller.askAgent("agent-1", form, request);

        var captor = org.mockito.ArgumentCaptor.forClass(A2aMessage.class);
        verify(agent).sendTask(captor.capture());
        Map<String, Object> metadata = captor.getValue().getMetadata();
        assertEquals("physical-worker", metadata.get("workerId"));
        assertEquals("dir-default", metadata.get("directoryId"));
        assertEquals("/home/sa/workspace/school", metadata.get("cwd"));
        assertEquals("trace-1", metadata.get("traceId"));
        @SuppressWarnings("unchecked")
        Map<String, Object> runtimeContext = (Map<String, Object>) metadata.get("runtimeContext");
        @SuppressWarnings("unchecked")
        Map<String, Object> executionPolicy = (Map<String, Object>) runtimeContext.get("execution_policy");
        assertEquals("dir-default", executionPolicy.get("directory_id"));
        assertEquals("USER_PRIVATE", executionPolicy.get("workspace_scope"));
        assertEquals("MANAGED", executionPolicy.get("workspace_resolver_type"));
        assertEquals(false, executionPolicy.get("read_only"));
        assertEquals("/home/sa/workspace/school", executionPolicy.get("workdir"));
        assertEquals(List.of("/home/sa/workspace/school"), executionPolicy.get("allowed_dirs"));
        verify(resourceResolver).resolveRequiredWorkspaceForAgent(
                "tenant-1", "app-1", "upstream-a", agentResource, "dir-default");
    }

    @Test
    void askAgent_doesNotUseDirectoryWorkerAsLanggraphBizExecutionWorker() {
        UnifiedAgentResolver agentResolver = mock(UnifiedAgentResolver.class);
        ClientAppRuntimeCredentialResolver credentialResolver = mock(ClientAppRuntimeCredentialResolver.class);
        A2AgentResourceResolver resourceResolver = mock(A2AgentResourceResolver.class);
        A2aAgent agent = mock(A2aAgent.class);
        OpenApiController controller = newController(
                agentResolver,
                credentialResolver,
                null,
                null,
                mock(CodingAgentRepository.class),
                mock(OpenApiSessionQueryService.class),
                defaultRouteService(),
                resourceResolver);
        HttpServletRequest request = mock(HttpServletRequest.class);

        A2AgentResourceResolver.ResolvedAgentResource agentResource =
                new A2AgentResourceResolver.ResolvedAgentResource(
                        "agent-1",
                        ResourceOwnerType.CLIENT_APP,
                        "app-1",
                        "app-1",
                        "agent-1",
                        "pool-1",
                        ResourceOwnerType.PLATFORM,
                        "tenant-1",
                        "WORKER_POOL:PLATFORM",
                        "LANGGRAPH_BIZ",
                        null,
                        null,
                        null,
                        null,
                        "model-default",
                        null,
                        "dir-default",
                        "AGENT:CLIENT_APP");
        when(resourceResolver.resolveRequiredAgent(
                eq("tenant-1"), eq("app-1"), eq("upstream-a"), eq("agent-1")))
                .thenReturn(agentResource);
        when(resourceResolver.resolveRequiredModelForAgent(
                eq("tenant-1"),
                eq("app-1"),
                eq(agentResource),
                eq("model-default"),
                nullable(String.class),
                eq(LlmModelCategory.GENERAL)))
                .thenReturn(new A2AgentResourceResolver.ResolvedModelResource(
                        "model-default",
                        "model-default",
                        null,
                        LlmModelCategory.GENERAL,
                        "qwen-plus",
                        "MODEL_CONFIG_DEFAULT",
                        "LANGGRAPH_BIZ",
                        "AGENT_DEFAULT_MODEL:DEFAULT_MODEL_GRANT"));
        when(resourceResolver.resolveRequiredWorkspaceForAgent(
                eq("tenant-1"), eq("app-1"), eq("upstream-a"), eq(agentResource), eq("dir-default")))
                .thenReturn(new A2AgentResourceResolver.ResolvedWorkspaceResource(
                        "dir-default",
                        "directory-worker",
                        WorkspaceScope.USER_PRIVATE,
                        WorkingDirectoryResolverType.MANAGED,
                        "/home/sa/workspace/school",
                        List.of("/home/sa/workspace/school"),
                        false,
                        null,
                        null,
                        null,
                        "WORKING_DIRECTORY:USER_PRIVATE"));

        OpenApiQueryForm form = new OpenApiQueryForm();
        form.setMessage("run biz smoke");
        form.setMetadata(Map.of("workerId", "caller-worker"));

        when(request.getHeader("X-Upstream-User-Id")).thenReturn("upstream-a");
        when(credentialResolver.resolveAccessToken(
                nullable(String.class), nullable(String.class)))
                .thenReturn(Optional.of(credential()));
        when(agentResolver.resolveAgent(eq("agent-1"), any())).thenReturn(Optional.of(agent));
        when(agent.sendTask(any())).thenReturn(A2aTask.builder()
                .id("task-1")
                .contextId("ctx-1")
                .status(A2aTaskStatus.builder().state(A2aTaskState.SUBMITTED).build())
                .build());

        controller.askAgent("agent-1", form, request);

        var captor = org.mockito.ArgumentCaptor.forClass(A2aMessage.class);
        verify(agent).sendTask(captor.capture());
        Map<String, Object> metadata = captor.getValue().getMetadata();
        assertFalse(metadata.containsKey("workerId"));
        assertEquals("dir-default", metadata.get("directoryId"));
        assertEquals("/home/sa/workspace/school", metadata.get("cwd"));
    }

    @Test
    void askAgent_usesWorkerHostBizIdentityForLanggraphWhenAgentWorkerIsCodingWorker() {
        UnifiedAgentResolver agentResolver = mock(UnifiedAgentResolver.class);
        ClientAppRuntimeCredentialResolver credentialResolver = mock(ClientAppRuntimeCredentialResolver.class);
        A2AgentResourceResolver resourceResolver = mock(A2AgentResourceResolver.class);
        A2aAgent agent = mock(A2aAgent.class);
        OpenApiController controller = newController(
                agentResolver,
                credentialResolver,
                null,
                null,
                mock(CodingAgentRepository.class),
                mock(OpenApiSessionQueryService.class),
                defaultRouteService(),
                resourceResolver);
        HttpServletRequest request = mock(HttpServletRequest.class);

        A2AgentResourceResolver.ResolvedAgentResource agentResource =
                new A2AgentResourceResolver.ResolvedAgentResource(
                        "agent-1",
                        ResourceOwnerType.CLIENT_APP,
                        "app-1",
                        "app-1",
                        "school-sim.actor.pm.m2.v1",
                        null,
                        null,
                        null,
                        null,
                        "OPENAI_CODEX",
                        "2ca910a6",
                        ResourceOwnerType.PLATFORM,
                        "tenant-1",
                        "CLAUDE_WORKER:TENANT",
                        "model-default",
                        null,
                        "dir-default",
                        "AGENT:CLIENT_APP");
        when(resourceResolver.resolveRequiredAgent(
                eq("tenant-1"), eq("app-1"), eq("upstream-a"), eq("agent-1")))
                .thenReturn(agentResource);
        when(resourceResolver.resolveRequiredModelForAgent(
                eq("tenant-1"),
                eq("app-1"),
                eq(agentResource),
                eq("model-default"),
                nullable(String.class),
                eq(LlmModelCategory.GENERAL)))
                .thenReturn(new A2AgentResourceResolver.ResolvedModelResource(
                        "model-default",
                        "model-default",
                        null,
                        LlmModelCategory.GENERAL,
                        "gemini-3.5-flash-low",
                        "MODEL_CONFIG_DEFAULT",
                        "LANGGRAPH_BIZ",
                        "AGENT_DEFAULT_MODEL:DEFAULT_MODEL_GRANT"));
        when(resourceResolver.resolveRequiredWorkspaceForAgent(
                eq("tenant-1"), eq("app-1"), eq("upstream-a"), eq(agentResource), eq("dir-default")))
                .thenReturn(new A2AgentResourceResolver.ResolvedWorkspaceResource(
                        "dir-default",
                        "2ca910a6",
                        WorkspaceScope.USER_PRIVATE,
                        WorkingDirectoryResolverType.MANAGED,
                        "/home/navigator/school",
                        List.of("/home/navigator/school"),
                        false,
                        null,
                        null,
                        null,
                        "WORKING_DIRECTORY:USER_PRIVATE"));
        when(resourceResolver.resolveLatestHealthyBizWorkerIdentityId("tenant-1", "app-1"))
                .thenReturn(Optional.of("school-sim-wsl-biz"));

        OpenApiQueryForm form = new OpenApiQueryForm();
        form.setMessage("请使用 school-sim.actor.pm.m2.v1 技能，写入 smoke marker");

        when(request.getHeader("X-Upstream-User-Id")).thenReturn("upstream-a");
        when(credentialResolver.resolveAccessToken(
                nullable(String.class), nullable(String.class)))
                .thenReturn(Optional.of(credential()));
        when(agentResolver.resolveAgent(eq("agent-1"), any())).thenReturn(Optional.of(agent));
        when(agent.sendTask(any())).thenReturn(A2aTask.builder()
                .id("task-1")
                .contextId("ctx-1")
                .status(A2aTaskStatus.builder().state(A2aTaskState.SUBMITTED).build())
                .build());

        controller.askAgent("agent-1", form, request);

        var captor = org.mockito.ArgumentCaptor.forClass(A2aMessage.class);
        verify(agent).sendTask(captor.capture());
        Map<String, Object> metadata = captor.getValue().getMetadata();
        assertEquals("school-sim-wsl-biz", metadata.get("workerId"));
        assertEquals("BIZ_WORKER_IDENTITY", metadata.get("workerSource"));
        assertEquals("LANGGRAPH_BIZ", metadata.get("workerBackend"));
        assertEquals("dir-default", metadata.get("directoryId"));
        assertEquals("/home/navigator/school", metadata.get("cwd"));
    }

    @Test
    void askAgent_forwardsTopLevelExecutionPolicyWithoutDirectSkillName() {
        UnifiedAgentResolver agentResolver = mock(UnifiedAgentResolver.class);
        ClientAppRuntimeCredentialResolver credentialResolver = mock(ClientAppRuntimeCredentialResolver.class);
        A2aAgent agent = mock(A2aAgent.class);
        OpenApiController controller = newController(agentResolver, credentialResolver);

        OpenApiQueryForm form = new OpenApiQueryForm();
        form.setMessage("分析仓库");
        form.setWorkdir("/home/sa/workspace/app");
        form.setAllowedDirs(List.of("/home/sa/workspace"));
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
        assertFalse(context.containsKey("businessSkillId"));
        assertFalse(context.containsKey("businessSkillName"));
        @SuppressWarnings("unchecked")
        Map<String, Object> executionPolicy = (Map<String, Object>) context.get("execution_policy");
        assertEquals("/home/sa/workspace/app", executionPolicy.get("workdir"));
        assertEquals(List.of("/home/sa/workspace"), executionPolicy.get("allowed_dirs"));
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

        var result = controller.askAgent("agent-1", form, mock(HttpServletRequest.class));

        assertNull(result.getData());
        assertTrue(result.getMsg().contains("upstream user id is required"));
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

        var result = controller.askAgent("agent-1", form, request);

        assertNull(result.getData());
        assertTrue(result.getMsg().contains("business agent session not found"));
        verify(agentResolver, never()).resolveAgent(any(), any());
        verify(agent, never()).sendTask(any());
    }

    @Test
    void askAgent_recoversMissingBusinessSessionWhenNavigatorContextExists() {
        UnifiedAgentResolver agentResolver = mock(UnifiedAgentResolver.class);
        ClientAppRuntimeCredentialResolver credentialResolver = mock(ClientAppRuntimeCredentialResolver.class);
        BusinessAgentSessionService sessionService = mock(BusinessAgentSessionService.class);
        BusinessAgentTaskService taskService = mock(BusinessAgentTaskService.class);
        CodingAgentRepository codingAgentRepository = mock(CodingAgentRepository.class);
        OpenApiSessionQueryService sessionQueryService = mock(OpenApiSessionQueryService.class);
        A2aAgent agent = mock(A2aAgent.class);
        OpenApiController controller = newController(
                agentResolver,
                credentialResolver,
                sessionService,
                taskService,
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
                .thenThrow(new IllegalArgumentException("business agent session not found: " + STANDARD_CONTEXT_ID));
        CodingAgentEntity agentEntity = new CodingAgentEntity();
        agentEntity.setAgentId("agent-1");
        agentEntity.setTenantId("tenant-1");
        agentEntity.setUserId("owner-1");
        when(codingAgentRepository.findByAgentIdAndTenantId("agent-1", "tenant-1"))
                .thenReturn(Optional.of(agentEntity));
        AgentConversationContextEntity contextEntity = new AgentConversationContextEntity();
        contextEntity.setContextId(STANDARD_CONTEXT_ID);
        contextEntity.setUserId("owner-1");
        contextEntity.setTargetAgentId("agent-1");
        contextEntity.setNavigatorSessionId("session-1");
        when(sessionQueryService.findContextForUser(STANDARD_CONTEXT_ID, "owner-1"))
                .thenReturn(Optional.of(contextEntity));
        when(taskService.hasOpenApiTaskScopedTokenForContext(
                "tenant-1", "app-1", "upstream-a", STANDARD_CONTEXT_ID))
                .thenReturn(true);
        when(agentResolver.resolveAgent(eq("agent-1"), any())).thenReturn(Optional.of(agent));
        when(agent.sendTask(any())).thenReturn(A2aTask.builder()
                .id("task-2")
                .contextId(STANDARD_CONTEXT_ID)
                .metadata(Map.of("sessionId", "session-1"))
                .status(A2aTaskStatus.builder().state(A2aTaskState.SUBMITTED).build())
                .build());

        var result = controller.askAgent("agent-1", form, request);

        assertNotNull(result.getData());
        assertEquals(STANDARD_CONTEXT_ID, result.getData().getContextId());
        verify(agent).sendTask(any());
        verify(sessionService).bindOpenApiSession(
                "tenant-1",
                "app-1",
                "upstream-a",
                STANDARD_CONTEXT_ID,
                "session-1",
                "agent-1",
                "task-2",
                null);
    }

    @Test
    void askAgent_rejectsMissingBusinessSessionWhenNavigatorContextBelongsToDifferentUpstreamUser() {
        UnifiedAgentResolver agentResolver = mock(UnifiedAgentResolver.class);
        ClientAppRuntimeCredentialResolver credentialResolver = mock(ClientAppRuntimeCredentialResolver.class);
        BusinessAgentSessionService sessionService = mock(BusinessAgentSessionService.class);
        BusinessAgentTaskService taskService = mock(BusinessAgentTaskService.class);
        CodingAgentRepository codingAgentRepository = mock(CodingAgentRepository.class);
        OpenApiSessionQueryService sessionQueryService = mock(OpenApiSessionQueryService.class);
        A2aAgent agent = mock(A2aAgent.class);
        OpenApiController controller = newController(
                agentResolver,
                credentialResolver,
                sessionService,
                taskService,
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
        CodingAgentEntity agentEntity = new CodingAgentEntity();
        agentEntity.setAgentId("agent-1");
        agentEntity.setTenantId("tenant-1");
        agentEntity.setUserId("owner-1");
        when(codingAgentRepository.findByAgentIdAndTenantId("agent-1", "tenant-1"))
                .thenReturn(Optional.of(agentEntity));
        AgentConversationContextEntity contextEntity = new AgentConversationContextEntity();
        contextEntity.setContextId(STANDARD_CONTEXT_ID);
        contextEntity.setUserId("owner-1");
        contextEntity.setTargetAgentId("agent-1");
        contextEntity.setNavigatorSessionId("session-1");
        when(sessionQueryService.findContextForUser(STANDARD_CONTEXT_ID, "owner-1"))
                .thenReturn(Optional.of(contextEntity));
        when(taskService.hasOpenApiTaskScopedTokenForContext(
                "tenant-1", "app-1", "upstream-b", STANDARD_CONTEXT_ID))
                .thenReturn(false);

        var result = controller.askAgent("agent-1", form, request);

        assertNull(result.getData());
        assertTrue(result.getMsg().contains("business agent session not found"));
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
    void askAgent_projectsAgentOwnerUserIntoSubmitResolveContextForContextContinuation() {
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
        form.setMessage("continue actor task");
        form.setContextId(STANDARD_CONTEXT_ID);
        form.setPrivateAccountId("scenario-1.actor-1");

        when(request.getHeader("X-Upstream-User-Id")).thenReturn("scenario-1.actor-1");
        when(credentialResolver.resolveAccessToken(
                nullable(String.class), nullable(String.class)))
                .thenReturn(Optional.of(credential()));
        when(sessionService.getSession("tenant-1", "app-1", "scenario-1.actor-1", STANDARD_CONTEXT_ID))
                .thenReturn(new BusinessAgentSessionDTO());
        CodingAgentEntity agentEntity = new CodingAgentEntity();
        agentEntity.setAgentId("agent-1");
        agentEntity.setTenantId("tenant-1");
        agentEntity.setUserId("owner-1");
        when(codingAgentRepository.findByAgentIdAndTenantId("agent-1", "tenant-1"))
                .thenReturn(Optional.of(agentEntity));
        when(sessionQueryService.resolveSessionId(STANDARD_CONTEXT_ID, "owner-1")).thenReturn(Optional.empty());
        when(agentResolver.resolveAgent(eq("agent-1"), argThat(ctx ->
                ctx != null
                        && "owner-1".equals(ctx.getUserId())
                        && "tenant-1".equals(ctx.getTenantId())
                        && "OPEN_API".equals(ctx.getRequestSource()))))
                .thenReturn(Optional.of(agent));
        when(agent.sendTask(any())).thenReturn(A2aTask.builder()
                .id("task-1")
                .contextId(STANDARD_CONTEXT_ID)
                .status(A2aTaskStatus.builder().state(A2aTaskState.SUBMITTED).build())
                .build());

        controller.askAgent("agent-1", form, request);

        verify(agentResolver, atLeastOnce()).resolveAgent(eq("agent-1"), argThat(ctx ->
                ctx != null
                        && "owner-1".equals(ctx.getUserId())
                        && "tenant-1".equals(ctx.getTenantId())
                        && "OPEN_API".equals(ctx.getRequestSource())));
        var captor = org.mockito.ArgumentCaptor.forClass(A2aMessage.class);
        verify(agent).sendTask(captor.capture());
        Map<String, Object> metadata = captor.getValue().getMetadata();
        assertEquals("scenario-1.actor-1", metadata.get("privateAccountId"));
        assertNull(metadata.get("providerType"));
    }

    @Test
    void askAgent_returnsRxFailureWhenSubmitRejectsBusyContext() {
        UnifiedAgentResolver agentResolver = mock(UnifiedAgentResolver.class);
        ClientAppRuntimeCredentialResolver credentialResolver = mock(ClientAppRuntimeCredentialResolver.class);
        BusinessAgentSessionService sessionService = mock(BusinessAgentSessionService.class);
        BusinessAgentTaskService taskService = mock(BusinessAgentTaskService.class);
        CodingAgentRepository codingAgentRepository = mock(CodingAgentRepository.class);
        OpenApiSessionQueryService sessionQueryService = mock(OpenApiSessionQueryService.class);
        A2aAgent agent = mock(A2aAgent.class);
        OpenApiController controller = newController(
                agentResolver,
                credentialResolver,
                sessionService,
                taskService,
                codingAgentRepository,
                sessionQueryService);
        HttpServletRequest request = mock(HttpServletRequest.class);

        OpenApiQueryForm form = new OpenApiQueryForm();
        form.setMessage("立即继续");
        form.setContextId(STANDARD_CONTEXT_ID);

        when(request.getHeader("X-Upstream-User-Id")).thenReturn("upstream-a");
        when(credentialResolver.resolveAccessToken(
                nullable(String.class), nullable(String.class)))
                .thenReturn(Optional.of(credential()));
        when(sessionService.getSession("tenant-1", "app-1", "upstream-a", STANDARD_CONTEXT_ID))
                .thenReturn(new BusinessAgentSessionDTO());
        when(taskService.prepareOpenApiTaskScopedToken(
                eq("tenant-1"),
                eq("app-1"),
                eq("app-1"),
                eq("upstream-a"),
                eq("agent-1"),
                eq(STANDARD_CONTEXT_ID),
                nullable(String.class),
                any(BusinessAgentWorkerTaskLaunchRequest.class)))
                .thenReturn(preparedOpenApiToken("btt_submit_failure"));
        CodingAgentEntity agentEntity = new CodingAgentEntity();
        agentEntity.setAgentId("agent-1");
        agentEntity.setTenantId("tenant-1");
        agentEntity.setUserId("owner-1");
        when(codingAgentRepository.findByAgentIdAndTenantId("agent-1", "tenant-1"))
                .thenReturn(Optional.of(agentEntity));
        when(sessionQueryService.resolveSessionId(STANDARD_CONTEXT_ID, "owner-1"))
                .thenReturn(Optional.of("session-1"));
        when(agentResolver.resolveAgent(eq("agent-1"), any())).thenReturn(Optional.of(agent));
        when(agent.sendTask(any())).thenThrow(new IllegalStateException(
                "CONTEXT_RUNTIME_BUSY: contextId " + STANDARD_CONTEXT_ID
                        + " already has active task lgt_45e01f2e4dfd42e9"));
        doThrow(new IllegalStateException("revocation storage failure"))
                .when(taskService)
                .revokeOpenApiTaskScopedToken(
                        "tenant-1",
                        "btt_submit_failure",
                        "system",
                        "open api task submission failed");

        var result = controller.askAgent("agent-1", form, request);

        assertNull(result.getData());
        assertTrue(result.getMsg().contains("CONTEXT_RUNTIME_BUSY"));
        assertTrue(result.getMsg().contains("lgt_45e01f2e4dfd42e9"));
        verify(sessionService, never()).bindOpenApiSession(
                any(), any(), any(), any(), any(), any(), any(), any());
        verify(taskService).revokeOpenApiTaskScopedToken(
                "tenant-1",
                "btt_submit_failure",
                "system",
                "open api task submission failed");
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

    @Test
    void getTaskDiagnosticsReturnsFactSnapshotForOwnedTask() {
        UnifiedAgentResolver agentResolver = mock(UnifiedAgentResolver.class);
        ClientAppRuntimeCredentialResolver credentialResolver = mock(ClientAppRuntimeCredentialResolver.class);
        OpenApiSessionQueryService sessionQueryService = mock(OpenApiSessionQueryService.class);
        A2aAgent agent = mock(A2aAgent.class);
        OpenApiController controller = newController(
                agentResolver,
                credentialResolver,
                null,
                mock(CodingAgentRepository.class),
                sessionQueryService);
        HttpServletRequest request = mock(HttpServletRequest.class);

        LocalDateTime createdAt = LocalDateTime.of(2026, 5, 27, 9, 0);
        LocalDateTime startedAt = LocalDateTime.of(2026, 5, 27, 9, 1);
        LocalDateTime latestMessageAt = LocalDateTime.of(2026, 5, 27, 9, 3);
        SessionTaskEntity task = openApiTask("task-1", "tenant-1", "agent-1", "RUNNING");
        task.setCreatedAt(createdAt);
        task.setUpdatedAt(LocalDateTime.of(2026, 5, 27, 9, 2));
        task.setLastAliveAt(LocalDateTime.of(2026, 5, 27, 9, 2, 30));
        task.setLastAckedSeq(7);
        task.setProviderTaskId("worker-task-1");
        task.setWorkerId("worker-1");
        task.setModelConfigId("model-1");
        task.setTaskStateJson("""
                {
                  "workerStartedAt": "2026-05-27T09:01:00",
                  "workerBackend": "claude-worker",
                  "modelConfigSource": "agent_default",
                  "originalTaskId": "task-original",
                  "recoveryCorrelationKey": "corr-1",
                  "attemptNumber": 2,
                  "idempotencyKey": "Bearer abcdefgh123456"
                }
                """);
        SessionMessageEntity latestMessage = message(
                "msg-latest", "session-1", "ASSISTANT", "working", "{\"type\":\"TEXT\"}");
        latestMessage.setCreatedAt(latestMessageAt);

        when(credentialResolver.resolveAccessToken(nullable(String.class), nullable(String.class)))
                .thenReturn(Optional.of(credential()));
        when(agentResolver.resolveAgent(eq("agent-1"), any())).thenReturn(Optional.of(agent));
        when(sessionQueryService.findTask("task-1")).thenReturn(Optional.of(task));
        when(sessionQueryService.resolveContextId("session-1")).thenReturn(Optional.of("ctx-1"));
        when(sessionQueryService.findLatestTaskMessage("task-1")).thenReturn(Optional.of(latestMessage));
        when(sessionQueryService.countTaskMessages("task-1")).thenReturn(2L);

        OpenTaskDiagnosticsDTO diagnostics = controller.getTaskDiagnostics("agent-1", "task-1", request).getData();

        assertEquals("task-1", diagnostics.getTaskId());
        assertEquals("agent-1", diagnostics.getAgentId());
        assertEquals("ctx-1", diagnostics.getContextId());
        assertEquals("RUNNING", diagnostics.getStatus());
        assertEquals(false, diagnostics.getTerminal());
        assertNull(diagnostics.getTerminalStatus());
        assertEquals(createdAt, diagnostics.getSubmittedAt());
        assertEquals(startedAt, diagnostics.getWorkerStartedAt());
        assertEquals(latestMessageAt, diagnostics.getLastObservedAt());
        assertEquals(2L, diagnostics.getMessagesCount());
        assertEquals("worker-task-1", diagnostics.getWorkerTaskId());
        assertEquals(7L, diagnostics.getLastAckedSeq());
        assertEquals("model-1", diagnostics.getModelConfigId());
        assertEquals("agent_default", diagnostics.getModelConfigSource());
        assertEquals("claude-worker", diagnostics.getWorkerBackend());
        assertEquals("worker-1", diagnostics.getSafeWorkerRef());
        assertNotNull(diagnostics.getCancelCapability());
        assertEquals(false, diagnostics.getCancelCapability().getCancelSupported());
        assertEquals("admin_only", diagnostics.getCancelCapability().getCancelMode());
        assertNotNull(diagnostics.getCorrelation());
        assertEquals("task-original", diagnostics.getCorrelation().getOriginalTaskId());
        assertEquals("corr-1", diagnostics.getCorrelation().getRecoveryCorrelationKey());
        assertEquals(2, diagnostics.getCorrelation().getAttemptNumber());
        assertFalse(diagnostics.getCorrelation().getIdempotencyKey().contains("abcdefgh123456"));
    }

    @Test
    void getTaskDiagnosticsReturnsSubmittedFactsWhenTaskNotPickedUp() {
        UnifiedAgentResolver agentResolver = mock(UnifiedAgentResolver.class);
        ClientAppRuntimeCredentialResolver credentialResolver = mock(ClientAppRuntimeCredentialResolver.class);
        OpenApiSessionQueryService sessionQueryService = mock(OpenApiSessionQueryService.class);
        A2aAgent agent = mock(A2aAgent.class);
        OpenApiController controller = newController(
                agentResolver,
                credentialResolver,
                null,
                mock(CodingAgentRepository.class),
                sessionQueryService);
        HttpServletRequest request = mock(HttpServletRequest.class);

        LocalDateTime submittedAt = LocalDateTime.of(2026, 5, 27, 9, 0);
        SessionTaskEntity task = openApiTask("task-1", "tenant-1", "agent-1", "SUBMITTED");
        task.setCreatedAt(submittedAt);
        task.setUpdatedAt(submittedAt);
        task.setProviderTaskId(null);
        task.setWorkerId(null);
        task.setTaskStateJson("{}");

        when(credentialResolver.resolveAccessToken(nullable(String.class), nullable(String.class)))
                .thenReturn(Optional.of(credential()));
        when(agentResolver.resolveAgent(eq("agent-1"), any())).thenReturn(Optional.of(agent));
        when(sessionQueryService.findTask("task-1")).thenReturn(Optional.of(task));
        when(sessionQueryService.resolveContextId("session-1")).thenReturn(Optional.of("ctx-1"));
        when(sessionQueryService.findLatestTaskMessage("task-1")).thenReturn(Optional.empty());
        when(sessionQueryService.countTaskMessages("task-1")).thenReturn(0L);

        OpenTaskDiagnosticsDTO diagnostics = controller.getTaskDiagnostics("agent-1", "task-1", request).getData();

        assertEquals("SUBMITTED", diagnostics.getStatus());
        assertEquals(false, diagnostics.getTerminal());
        assertEquals(submittedAt, diagnostics.getSubmittedAt());
        assertNull(diagnostics.getWorkerStartedAt());
        assertEquals(submittedAt, diagnostics.getLastObservedAt());
        assertEquals(0L, diagnostics.getMessagesCount());
        assertNull(diagnostics.getWorkerTaskId());
        assertNull(diagnostics.getSafeWorkerRef());
        assertEquals("admin_only", diagnostics.getCancelCapability().getCancelMode());
        assertTrue(diagnostics.getCancelCapability().getBackendLimitations()
                .contains("runtime_client_app_cancel_not_exposed"));
    }

    @Test
    void getTaskEvidenceReturnsSanitizedSummariesAndRefs() {
        UnifiedAgentResolver agentResolver = mock(UnifiedAgentResolver.class);
        ClientAppRuntimeCredentialResolver credentialResolver = mock(ClientAppRuntimeCredentialResolver.class);
        OpenApiSessionQueryService sessionQueryService = mock(OpenApiSessionQueryService.class);
        A2aAgent agent = mock(A2aAgent.class);
        OpenApiController controller = newController(
                agentResolver,
                credentialResolver,
                null,
                mock(CodingAgentRepository.class),
                sessionQueryService);
        HttpServletRequest request = mock(HttpServletRequest.class);

        SessionTaskEntity task = openApiTask("task-1", "tenant-1", "agent-1", "COMPLETED");
        task.setResultText("done api_key=sk-secret-token");
        task.setTaskStateJson("""
                {
                  "structuredOutput": {"status":"ok","token":"sk-secret-token"},
                  "reportRefs": ["frame-report://lgt_1/frm_2"],
                  "artifactRefs": [{"path":"/home/sa/workspace/report.md?signature=secret","summary":"final report"}]
                }
                """);

        when(credentialResolver.resolveAccessToken(nullable(String.class), nullable(String.class)))
                .thenReturn(Optional.of(credential()));
        when(agentResolver.resolveAgent(eq("agent-1"), any())).thenReturn(Optional.of(agent));
        when(sessionQueryService.findTask("task-1")).thenReturn(Optional.of(task));
        when(sessionQueryService.resolveContextId("session-1")).thenReturn(Optional.of("ctx-1"));
        when(sessionQueryService.getLatestTaskMessages("task-1", 200)).thenReturn(List.of(
                message("msg-1", "session-1", "ASSISTANT", "ignored",
                        "{\"type\":\"TEXT\",\"artifactRefs\":[\"/home/sa/workspace/log.txt?token=secret\"]}")));

        OpenTaskEvidenceDTO evidence = controller.getTaskEvidence("agent-1", "task-1", request).getData();

        assertEquals("COMPLETED", evidence.getStatus());
        assertEquals(true, evidence.getTerminal());
        assertEquals("COMPLETED", evidence.getTerminalStatus());
        assertEquals(true, evidence.getFinalAnswer().getAvailable());
        assertFalse(evidence.getFinalAnswer().getSummary().contains("sk-secret-token"));
        assertEquals("task_result", evidence.getFinalAnswer().getSource());
        assertEquals(true, evidence.getStructuredOutput().getAvailable());
        @SuppressWarnings("unchecked")
        Map<String, Object> structured = (Map<String, Object>) evidence.getStructuredOutput().getValue();
        assertEquals("ok", structured.get("status"));
        assertFalse(String.valueOf(structured.get("token")).contains("sk-secret-token"));
        assertEquals(1, evidence.getReportRefs().size());
        assertEquals("frame_report", evidence.getReportRefs().get(0).getType());
        assertEquals("frm_2", evidence.getReportRefs().get(0).getFrameId());
        assertEquals(2, evidence.getArtifactRefs().size());
        assertEquals("/home/sa/workspace/report.md", evidence.getArtifactRefs().get(0).getPath());
        assertEquals("/home/sa/workspace/log.txt", evidence.getArtifactRefs().get(1).getPath());
    }

    @Test
    void getTaskEvidenceLiftsOpenArtifactFromFinalJsonMessage() {
        UnifiedAgentResolver agentResolver = mock(UnifiedAgentResolver.class);
        ClientAppRuntimeCredentialResolver credentialResolver = mock(ClientAppRuntimeCredentialResolver.class);
        OpenApiSessionQueryService sessionQueryService = mock(OpenApiSessionQueryService.class);
        A2aAgent agent = mock(A2aAgent.class);
        OpenApiController controller = newController(
                agentResolver,
                credentialResolver,
                null,
                mock(CodingAgentRepository.class),
                sessionQueryService);
        HttpServletRequest request = mock(HttpServletRequest.class);

        SessionTaskEntity task = openApiTask("task-1", "tenant-1", "agent-1", "COMPLETED");
        task.setTaskStateJson("{}");

        when(credentialResolver.resolveAccessToken(nullable(String.class), nullable(String.class)))
                .thenReturn(Optional.of(credential()));
        when(agentResolver.resolveAgent(eq("agent-1"), any())).thenReturn(Optional.of(agent));
        when(sessionQueryService.findTask("task-1")).thenReturn(Optional.of(task));
        when(sessionQueryService.resolveContextId("session-1")).thenReturn(Optional.of("ctx-1"));
        when(sessionQueryService.getLatestTaskMessages("task-1", 200)).thenReturn(List.of(
                message("msg-1", "session-1", "ASSISTANT", """
                        {
                          "marker": "codex-biz-smoke-20260630",
                          "functionId": "submit_skill_result",
                          "status": "SUCCESS",
                          "structured_output.type": "OPEN_ARTIFACT",
                          "structured_output.label": "Open result",
                          "structured_output.artifact.kind": "iframe",
                          "structured_output.artifact.uri": "https://tms.example.com/report?token=secret",
                          "structured_output.context.businessDomain": "tms"
                        }
                        """, "{\"type\":\"TEXT\"}")));

        OpenTaskEvidenceDTO evidence = controller.getTaskEvidence("agent-1", "task-1", request).getData();

        assertEquals(true, evidence.getStructuredOutput().getAvailable());
        assertEquals("message_content", evidence.getStructuredOutput().getSource());
        @SuppressWarnings("unchecked")
        Map<String, Object> structured = (Map<String, Object>) evidence.getStructuredOutput().getValue();
        assertEquals("OPEN_ARTIFACT", structured.get("type"));
        assertEquals("Open result", structured.get("label"));
        @SuppressWarnings("unchecked")
        Map<String, Object> artifact = (Map<String, Object>) structured.get("artifact");
        assertEquals("iframe", artifact.get("kind"));
        assertFalse(String.valueOf(artifact.get("uri")).contains("secret"));
        @SuppressWarnings("unchecked")
        Map<String, Object> context = (Map<String, Object>) structured.get("context");
        assertEquals("tms", context.get("businessDomain"));
    }

    @Test
    void getTaskDiagnosticsRejectsTaskOwnedByAnotherAgent() {
        UnifiedAgentResolver agentResolver = mock(UnifiedAgentResolver.class);
        ClientAppRuntimeCredentialResolver credentialResolver = mock(ClientAppRuntimeCredentialResolver.class);
        OpenApiSessionQueryService sessionQueryService = mock(OpenApiSessionQueryService.class);
        A2aAgent agent = mock(A2aAgent.class);
        OpenApiController controller = newController(
                agentResolver,
                credentialResolver,
                null,
                mock(CodingAgentRepository.class),
                sessionQueryService);

        when(credentialResolver.resolveAccessToken(nullable(String.class), nullable(String.class)))
                .thenReturn(Optional.of(credential()));
        when(agentResolver.resolveAgent(eq("agent-1"), any())).thenReturn(Optional.of(agent));
        when(sessionQueryService.findTask("task-1"))
                .thenReturn(Optional.of(openApiTask("task-1", "tenant-1", "other-agent", "RUNNING")));

        RuntimeException error = assertThrows(
                RuntimeException.class,
                () -> controller.getTaskDiagnostics("agent-1", "task-1", mock(HttpServletRequest.class)));

        assertTrue(error.getMessage().contains("Task not found: task-1"));
    }

    @Test
    void workerBackendFromProviderType_usesSharedRouteAliasesAndPreservesUnknownFallback() throws Exception {
        OpenApiController controller = newController();
        Method method = OpenApiController.class.getDeclaredMethod("workerBackendFromProviderType", String.class);
        method.setAccessible(true);

        assertEquals("OPENAI_CODEX", method.invoke(controller, "codex-biz-worker"));
        assertEquals("OPENAI_CODEX_APP_SERVER",
                method.invoke(controller, "codex-app-server-worker"));
        assertEquals("GEMINI_CLI", method.invoke(controller, "gemini"));
        assertEquals("CUSTOM-PROVIDER", method.invoke(controller, "custom-provider"));
    }

    @Test
    void appServerLaunchUsesWorkspaceWorkerAndClassifiesOpaqueFailureAsRuntime() throws Exception {
        OpenApiController controller = newController();
        A2AgentResourceResolver.ResolvedAgentResource agentResource =
                new A2AgentResourceResolver.ResolvedAgentResource(
                        "agent-app", ResourceOwnerType.CLIENT_APP, "app-1", "app-1",
                        "agent-app", null, null, null, null,
                        "OPENAI_CODEX_APP_SERVER", "agent-worker",
                        ResourceOwnerType.CLIENT_APP, "app-1", "AGENT_WORKER_REF",
                        "model-app", "codex-terra:ultra", "dir-app", "AGENT:CLIENT_APP");
        A2AgentResourceResolver.ResolvedModelResource modelResource =
                new A2AgentResourceResolver.ResolvedModelResource(
                        "model-app", "model-app", "codex-terra:ultra",
                        LlmModelCategory.GENERAL, "codex-terra:ultra",
                        "REQUESTED_MODEL", "OPENAI_CODEX_APP_SERVER", "MODEL_CONFIG_GRANT");
        A2AgentResourceResolver.ResolvedWorkspaceResource workspaceResource =
                new A2AgentResourceResolver.ResolvedWorkspaceResource(
                        "dir-app", "workspace-worker", WorkspaceScope.USER_PRIVATE,
                        WorkingDirectoryResolverType.MANAGED, "/workspace/app",
                        List.of("/workspace/app"), false, null, null, null,
                        "WORKING_DIRECTORY:USER_PRIVATE");

        Method launchMethod = OpenApiController.class.getDeclaredMethod(
                "resolveOwnerAwareLaunchWorker",
                String.class, String.class, A2AgentResourceResolver.class,
                A2AgentResourceResolver.ResolvedAgentResource.class,
                A2AgentResourceResolver.ResolvedModelResource.class,
                A2AgentResourceResolver.ResolvedWorkspaceResource.class);
        launchMethod.setAccessible(true);
        Object launchWorker = launchMethod.invoke(
                controller, "tenant-1", "app-1", mock(A2AgentResourceResolver.class),
                agentResource, modelResource, workspaceResource);
        Method workerIdAccessor = launchWorker.getClass().getDeclaredMethod("workerId");
        workerIdAccessor.setAccessible(true);
        assertEquals("workspace-worker", workerIdAccessor.invoke(launchWorker));

        Method failureStageMethod = OpenApiController.class.getDeclaredMethod(
                "inferFailureStageFromText",
                String.class, String.class, String.class, String.class);
        failureStageMethod.setAccessible(true);
        assertEquals("RUNTIME", failureStageMethod.invoke(
                controller, "FAILED", "codex-app-server-worker", null, "opaque failure"));
        assertEquals("RUNTIME", failureStageMethod.invoke(
                controller, "FAILED", null, "OPENAI_CODEX_APP_SERVER", "opaque failure"));
    }

    @Test
    void codexWorkerHostConfigUsesDirectPhysicalWorkerSelectionInsteadOfPoolMembership() throws Exception {
        OpenApiController controller = newController();
        Field workerRepositoryField = OpenApiController.class.getDeclaredField("workerRepository");
        workerRepositoryField.setAccessible(true);
        ClaudeWorkerRepository workerRepository = (ClaudeWorkerRepository) workerRepositoryField.get(controller);
        ClaudeWorkerEntity worker = new ClaudeWorkerEntity();
        worker.setWorkerId("workspace-worker");
        worker.setCodexConfig(CodexConfig.builder()
                .baseUrl("http://127.0.0.1:3151")
                .model("gpt-5.5")
                .build());
        when(workerRepository.findByWorkerId("workspace-worker")).thenReturn(Optional.of(worker));

        A2AgentResourceResolver.ResolvedAgentResource agentResource =
                new A2AgentResourceResolver.ResolvedAgentResource(
                        "agent-codex", ResourceOwnerType.CLIENT_APP, "app-1", "app-1",
                        "agent-codex", "pool-codex", ResourceOwnerType.UPSTREAM_SYSTEM, "sim-1",
                        "WORKER_POOL:UPSTREAM_SYSTEM", "OPENAI_CODEX", null,
                        null, null, null, "model-codex", "gpt-5.5", "dir-codex", "AGENT:CLIENT_APP");
        A2AgentResourceResolver.ResolvedModelResource modelResource =
                new A2AgentResourceResolver.ResolvedModelResource(
                        "model-codex", "model-codex", null, LlmModelCategory.GENERAL, "gpt-5.5",
                        "MODEL_CONFIG_DEFAULT", "OPENAI_CODEX", "MODEL_CONFIG_GRANT");
        A2AgentResourceResolver.ResolvedWorkspaceResource workspaceResource =
                new A2AgentResourceResolver.ResolvedWorkspaceResource(
                        "dir-codex", "workspace-worker", WorkspaceScope.USER_PRIVATE,
                        WorkingDirectoryResolverType.MANAGED, "/workspace/codex", List.of("/workspace/codex"),
                        false, null, null, null, "WORKING_DIRECTORY:USER_PRIVATE");

        Method resolveMethod = OpenApiController.class.getDeclaredMethod(
                "resolveOwnerAwareLaunchWorker",
                String.class, String.class, A2AgentResourceResolver.class,
                A2AgentResourceResolver.ResolvedAgentResource.class,
                A2AgentResourceResolver.ResolvedModelResource.class,
                A2AgentResourceResolver.ResolvedWorkspaceResource.class);
        resolveMethod.setAccessible(true);
        Object launchWorker = resolveMethod.invoke(
                controller, "tenant-1", "app-1", mock(A2AgentResourceResolver.class),
                agentResource, modelResource, workspaceResource);
        Method workerIdAccessor = launchWorker.getClass().getDeclaredMethod("workerId");
        Method workerSourceAccessor = launchWorker.getClass().getDeclaredMethod("workerSource");
        workerIdAccessor.setAccessible(true);
        workerSourceAccessor.setAccessible(true);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("workerId", workerIdAccessor.invoke(launchWorker));
        metadata.put("workerSource", workerSourceAccessor.invoke(launchWorker));

        Method selectionMethod = OpenApiController.class.getDeclaredMethod(
                "buildOpenApiWorkerSelectionRequest",
                String.class, String.class, String.class, String.class, String.class, String.class, String.class,
                A2AgentResourceResolver.ResolvedAgentResource.class,
                A2AgentResourceResolver.ResolvedModelResource.class,
                A2AgentResourceResolver.ResolvedWorkspaceResource.class,
                Map.class);
        selectionMethod.setAccessible(true);
        BusinessAgentWorkerTaskLaunchRequest request = (BusinessAgentWorkerTaskLaunchRequest) selectionMethod.invoke(
                controller, "tenant-1", "app-1", "app-1", "upstream-a", "agent-codex", "skill-codex", "ctx-1",
                agentResource, modelResource, workspaceResource, metadata);

        assertEquals("workspace-worker", request.getPhysicalWorkerId());
        assertEquals("workspace-worker", request.getWorkerPoolId());
        assertNull(request.getWorkerPoolOwnerType());
        assertNull(request.getWorkerPoolOwnerId());
        assertEquals("CLAUDE_WORKER_CODEX_CONFIG", metadata.get("workerSource"));
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

    private SessionTaskEntity openApiTask(String taskId, String tenantId, String agentId, String status) {
        SessionTaskEntity task = new SessionTaskEntity();
        task.setTaskId(taskId);
        task.setSessionId("session-1");
        task.setTenantId(tenantId);
        task.setAgentId(agentId);
        task.setUserId("owner-1");
        task.setStatus(status);
        task.setProviderType("CLAUDE_WORKER");
        task.setCreatedAt(LocalDateTime.of(2026, 5, 27, 9, 0));
        task.setUpdatedAt(LocalDateTime.of(2026, 5, 27, 9, 0));
        return task;
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
        return newController(
                agentResolver,
                credentialResolver,
                sessionService,
                taskService,
                codingAgentRepository,
                sessionQueryService,
                defaultRouteService());
    }

    private OpenApiAgentRouteService defaultRouteService() {
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
        return routeService;
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
        return newController(
                agentResolver,
                credentialResolver,
                sessionService,
                taskService,
                codingAgentRepository,
                sessionQueryService,
                routeService,
                null);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private OpenApiController newController(
            UnifiedAgentResolver agentResolver,
            ClientAppRuntimeCredentialResolver credentialResolver,
            BusinessAgentSessionService sessionService,
            BusinessAgentTaskService taskService,
            CodingAgentRepository codingAgentRepository,
            OpenApiSessionQueryService sessionQueryService,
            OpenApiAgentRouteService routeService,
            A2AgentResourceResolver resourceResolverOverride) {
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
        CodingAgentEntity defaultAgentEntity = new CodingAgentEntity();
        defaultAgentEntity.setAgentId("agent-1");
        defaultAgentEntity.setTenantId("tenant-1");
        defaultAgentEntity.setUserId("owner-1");
        lenient().when(codingAgentRepository.findByAgentIdAndTenantId(any(String.class), any(String.class)))
                .thenReturn(Optional.of(defaultAgentEntity));
        A2AgentResourceResolver resourceResolver = resourceResolverOverride != null
                ? resourceResolverOverride
                : defaultResourceResolver();
        ObjectProvider<A2AgentResourceResolver> resourceResolverProvider = mock(ObjectProvider.class);
        when(resourceResolverProvider.getIfAvailable()).thenReturn(resourceResolver);
        TaskDispatchFacade taskDispatchFacade = defaultTaskDispatchFacade(agentResolver);
        return new OpenApiController(
                mock(OpenApiProvisioningService.class),
                mock(ClaudeWorkerService.class),
                mock(ClaudeTaskService.class),
                mock(WorkingDirectoryService.class),
                mock(ClaudeWorkerFacade.class),
                mock(ClaudeWorkerRepository.class),
                codingAgentRepository,
                mock(WorkingDirectoryRepository.class),
                mock(WorkerHealthChecker.class),
                agentResolver,
                taskDispatchFacade,
                defaultAgentSubmitPipeline(taskDispatchFacade),
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

    private TaskDispatchFacade defaultTaskDispatchFacade(UnifiedAgentResolver agentResolver) {
        TaskDispatchFacade facade = mock(TaskDispatchFacade.class);
        when(facade.submitTask(any(AgentTaskSubmitRequest.class))).thenAnswer(invocation -> {
            AgentTaskSubmitRequest submitRequest = invocation.getArgument(0, AgentTaskSubmitRequest.class);
            AgentResolveContext context = submitRequest.getResolveContext() != null
                    ? submitRequest.getResolveContext()
                    : AgentResolveContext.builder().requestSource("TEST").build();
            A2aAgent agent = agentResolver.resolveAgent(submitRequest.getAgentId(), context)
                    .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + submitRequest.getAgentId()));
            A2aMessage message = submitRequest.getMessage();
            A2aTask task = agent.sendTask(message);
            if (task != null && task.getContextId() == null && message != null) {
                task.setContextId(message.getContextId());
            }
            return task;
        });
        return facade;
    }

    private AgentSubmitPipeline defaultAgentSubmitPipeline(TaskDispatchFacade taskDispatchFacade) {
        return request -> AgentTaskSubmitResult.of(taskDispatchFacade.submitTask(request));
    }

    private A2AgentResourceResolver defaultResourceResolver() {
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
                            null,
                            null,
                            null,
                            null,
                            "model-default",
                            null,
                            "dir-default",
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
        when(resourceResolver.resolveRequiredWorkspaceForAgent(
                any(String.class),
                any(String.class),
                nullable(String.class),
                any(A2AgentResourceResolver.ResolvedAgentResource.class),
                eq("dir-default")))
                .thenReturn(new A2AgentResourceResolver.ResolvedWorkspaceResource(
                        "dir-default",
                        "physical-worker-default",
                        WorkspaceScope.USER_PRIVATE,
                        WorkingDirectoryResolverType.MANAGED,
                        "/home/sa/workspace/default",
                        List.of("/home/sa/workspace/default"),
                        false,
                        null,
                        null,
                        null,
                        "WORKING_DIRECTORY:USER_PRIVATE"));
        return resourceResolver;
    }

    private BusinessAgentTaskService.PreparedOpenApiTaskScopedToken preparedOpenApiToken(
            String plainToken) {
        return new BusinessAgentTaskService.PreparedOpenApiTaskScopedToken(
                plainToken,
                "tst_test_01",
                "preselected-worker",
                "bwl_test_01",
                "pool-1",
                "LANGGRAPH_BIZ");
    }

    private ResolvedClientAppCredentialDTO credential() {
        return ResolvedClientAppCredentialDTO.builder()
                .credentialId("cred-1")
                .tenantId("tenant-1")
                .clientAppId("app-1")
                .build();
    }
}
