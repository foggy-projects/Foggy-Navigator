package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.dto.BusinessTaskScopedTokenDTO;
import com.foggy.navigator.business.agent.model.dto.BusinessFunctionDTO;
import com.foggy.navigator.business.agent.model.dto.BusinessFunctionRuntimeContextDTO;
import com.foggy.navigator.business.agent.model.dto.BusinessFunctionSummaryDTO;
import com.foggy.navigator.business.agent.model.dto.BusinessFunctionVersionDTO;
import com.foggy.navigator.business.agent.model.dto.WorkerGatewayFunctionListDTO;
import com.foggy.navigator.business.agent.model.dto.WorkerGatewayFunctionSchemaDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkerGatewayServiceTest {

    @Mock
    private BusinessAgentTaskService taskService;
    @Mock
    private BusinessFunctionAuthorizationService authorizationService;
    @Mock
    private BusinessFunctionRegistryService functionRegistryService;
    @Mock
    private SkillRegistryService skillRegistryService;
    @Mock
    private ClientAppUserGrantService userGrantService;
    @Mock
    private BusinessFunctionSuspensionService suspensionService;
    @Mock
    private com.foggy.navigator.business.agent.service.adapter.BusinessFunctionAdapterInvoker adapterInvoker;
    @Mock
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    @Mock
    private BusinessFunctionRuntimeAuditService auditService;

    @InjectMocks
    private WorkerGatewayService workerGatewayService;

    private BusinessTaskScopedTokenDTO tokenDTO;

    @BeforeEach
    void setUp() {
        tokenDTO = new BusinessTaskScopedTokenDTO();
        tokenDTO.setTenantId("tenant1");
        tokenDTO.setClientAppId("app1");
        tokenDTO.setUpstreamUserId("user1");
        tokenDTO.setSkillId("skill1");
        tokenDTO.setTaskId("task1");
        tokenDTO.setSessionId("session1");
        tokenDTO.setWorkerPoolId("pool1");
    }

    @Test
    void listFunctions_success_filters_by_token_skill_and_app() {
        when(taskService.resolveTaskScopedToken("valid_token")).thenReturn(tokenDTO);

        BusinessFunctionSummaryDTO f1 = new BusinessFunctionSummaryDTO();
        f1.setFunctionId("f1");
        f1.setDomain("domain1");
        f1.setRiskLevel("readonly");

        BusinessFunctionSummaryDTO f2 = new BusinessFunctionSummaryDTO();
        f2.setFunctionId("f2");
        f2.setDomain("domain2");
        f2.setRiskLevel("state_change");

        when(functionRegistryService.listClientAppVisibleFunctionSummaries("tenant1", "app1"))
                .thenReturn(Arrays.asList(f1, f2));

        // Let's say skill1 only has access to f1
        doNothing().when(skillRegistryService).checkSkillFunctionAccess("tenant1", "skill1", "f1");
        doThrow(new IllegalStateException("Not allowlisted")).when(skillRegistryService).checkSkillFunctionAccess("tenant1", "skill1", "f2");

        WorkerGatewayFunctionListDTO result = workerGatewayService.listBusinessFunctions("valid_token", null, null);

        assertNotNull(result);
        assertEquals(1, result.getFunctions().size());
        assertEquals("f1", result.getFunctions().get(0).getFunctionId());

        verify(userGrantService).checkUpstreamUserAccess("tenant1", "app1", "user1");
        verify(skillRegistryService).checkClientAppSkillAccess("tenant1", "app1", "skill1");
    }

    @Test
    void listFunctions_rejects_invalid_or_expired_token() {
        when(taskService.resolveTaskScopedToken("invalid_token")).thenThrow(new IllegalArgumentException("invalid token"));

        assertThrows(IllegalArgumentException.class, () ->
            workerGatewayService.listBusinessFunctions("invalid_token", null, null)
        );
        verifyNoInteractions(functionRegistryService, skillRegistryService, userGrantService);
    }

    @Test
    void listFunctions_rejects_token_without_upstreamUserId() {
        tokenDTO.setUpstreamUserId(null);
        when(taskService.resolveTaskScopedToken("valid_token")).thenReturn(tokenDTO);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
            workerGatewayService.listBusinessFunctions("valid_token", null, null)
        );
        assertEquals("token missing upstreamUserId", ex.getMessage());
    }

    @Test
    void listFunctions_rejects_token_without_skillId() {
        tokenDTO.setSkillId("");
        when(taskService.resolveTaskScopedToken("valid_token")).thenReturn(tokenDTO);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
            workerGatewayService.listBusinessFunctions("valid_token", null, null)
        );
        assertEquals("token missing skillId", ex.getMessage());
    }

    @Test
    void listFunctions_rejects_disabled_upstream_user_grant() {
        when(taskService.resolveTaskScopedToken("valid_token")).thenReturn(tokenDTO);
        doThrow(new IllegalStateException("grant disabled"))
            .when(userGrantService).checkUpstreamUserAccess("tenant1", "app1", "user1");

        assertThrows(IllegalStateException.class, () ->
            workerGatewayService.listBusinessFunctions("valid_token", null, null)
        );
    }

    @Test
    void listFunctions_rejects_disabled_client_app_skill_grant() {
        when(taskService.resolveTaskScopedToken("valid_token")).thenReturn(tokenDTO);
        doThrow(new IllegalStateException("skill grant disabled"))
            .when(skillRegistryService).checkClientAppSkillAccess("tenant1", "app1", "skill1");

        assertThrows(IllegalStateException.class, () ->
            workerGatewayService.listBusinessFunctions("valid_token", null, null)
        );
    }

    @Test
    void listFunctions_does_not_expose_unallowlisted_function() {
        // Handled within listFunctions_success_filters_by_token_skill_and_app,
        // which verifies f2 is filtered out due to checkSkillFunctionAccess throwing Exception.
        listFunctions_success_filters_by_token_skill_and_app();
    }

    @Test
    void getSchema_success() {
        when(taskService.resolveTaskScopedToken("valid_token")).thenReturn(tokenDTO);

        BusinessFunctionRuntimeContextDTO context = new BusinessFunctionRuntimeContextDTO();

        BusinessFunctionDTO functionDTO = new BusinessFunctionDTO();
        functionDTO.setFunctionId("f1");
        functionDTO.setRiskLevel("readonly");
        functionDTO.setApprovalRequired(false);
        context.setFunction(functionDTO);

        BusinessFunctionVersionDTO versionDTO = new BusinessFunctionVersionDTO();
        versionDTO.setVersion("v1");
        versionDTO.setInputSchemaJson("{\"type\":\"object\"}");
        versionDTO.setOutputSchemaJson("{\"type\":\"object\"}");
        versionDTO.setLlmVisibleSummary("llm summary");
        versionDTO.setSchemaVisibleSummary("schema summary");
        // Ensure adapterConfigJson is not present or ignored

        context.setVersionData(versionDTO);

        when(authorizationService.resolveExecutableBusinessFunction(
                "tenant1", "app1", "user1", "skill1", "f1", "v1"
        )).thenReturn(context);

        WorkerGatewayFunctionSchemaDTO schema = workerGatewayService.getBusinessFunctionSchema("valid_token", "f1", "v1");

        assertNotNull(schema);
        assertEquals("f1", schema.getFunctionId());
        assertEquals("v1", schema.getVersion());
        assertEquals("{\"type\":\"object\"}", schema.getInputSchemaJson());
        assertEquals("readonly", schema.getRiskLevel());
        assertFalse(schema.getApprovalRequired());
    }

    @Test
    void getSchema_rejects_missing_function_grant() {
        when(taskService.resolveTaskScopedToken("valid_token")).thenReturn(tokenDTO);

        when(authorizationService.resolveExecutableBusinessFunction(
                "tenant1", "app1", "user1", "skill1", "f1", "v1"
        )).thenThrow(new IllegalArgumentException("grant not found"));

        assertThrows(IllegalArgumentException.class, () ->
            workerGatewayService.getBusinessFunctionSchema("valid_token", "f1", "v1")
        );
    }

    @Test
    void getSchema_does_not_expose_adapterConfig_or_manifest() {
        // Verify via reflection that the DTO class does not even have these fields to prevent accidental leakage
        boolean hasAdapterConfig = Arrays.stream(WorkerGatewayFunctionSchemaDTO.class.getDeclaredFields())
                .anyMatch(f -> f.getName().equals("adapterConfigJson"));
        boolean hasManifest = Arrays.stream(WorkerGatewayFunctionSchemaDTO.class.getDeclaredFields())
                .anyMatch(f -> f.getName().equals("manifestJson"));

        assertFalse(hasAdapterConfig, "WorkerGatewayFunctionSchemaDTO must not expose adapterConfigJson");
        assertFalse(hasManifest, "WorkerGatewayFunctionSchemaDTO must not expose manifestJson");
    }

    @Test
    void invoke_success_approval_required_returns_suspended() {
        when(taskService.resolveTaskScopedToken("valid_token")).thenReturn(tokenDTO);

        BusinessFunctionRuntimeContextDTO context = new BusinessFunctionRuntimeContextDTO();
        BusinessFunctionDTO functionDTO = new BusinessFunctionDTO();
        functionDTO.setFunctionId("f1");
        functionDTO.setApprovalRequired(true);
        context.setFunction(functionDTO);

        BusinessFunctionVersionDTO versionDTO = new BusinessFunctionVersionDTO();
        versionDTO.setVersion("v1");
        context.setVersionData(versionDTO);

        when(authorizationService.resolveExecutableBusinessFunction(
                "tenant1", "app1", "user1", "skill1", "f1", "v1"
        )).thenReturn(context);

        com.foggy.navigator.business.agent.model.entity.BusinessFunctionSuspensionEntity mockSuspension = new com.foggy.navigator.business.agent.model.entity.BusinessFunctionSuspensionEntity();
        mockSuspension.setSuspendId("sus_123");
        when(suspensionService.createSuspension(tokenDTO, "f1", "v1", "{}", null)).thenReturn(mockSuspension);

        com.foggy.navigator.business.agent.model.form.WorkerGatewayInvokeForm form = new com.foggy.navigator.business.agent.model.form.WorkerGatewayInvokeForm();
        form.setVersion("v1");
        form.setInputJson("{}");

        com.foggy.navigator.business.agent.model.dto.WorkerGatewayInvokeResponseDTO response = workerGatewayService.invokeBusinessFunction("valid_token", "f1", form);

        assertNotNull(response);
        assertEquals("f1", response.getFunctionId());
        assertEquals("v1", response.getVersion());
        assertTrue(response.getApprovalRequired());
        assertEquals(com.foggy.navigator.business.agent.model.dto.WorkerGatewayInvokeResponseDTO.STATUS_SUSPENDED, response.getStatus());
        assertEquals("sus_123", response.getSuspendId());

        verify(suspensionService).createSuspension(tokenDTO, "f1", "v1", "{}", null);
        verifyNoInteractions(adapterInvoker);
    }

    @Test
    void invokeBusinessFunction_nonApproval_echoAdapter_success() {
        when(taskService.resolveTaskScopedToken("valid_token")).thenReturn(tokenDTO);

        BusinessFunctionRuntimeContextDTO context = new BusinessFunctionRuntimeContextDTO();
        BusinessFunctionDTO functionDTO = new BusinessFunctionDTO();
        functionDTO.setFunctionId("f1");
        functionDTO.setApprovalRequired(false);
        context.setFunction(functionDTO);

        BusinessFunctionVersionDTO versionDTO = new BusinessFunctionVersionDTO();
        versionDTO.setVersion("v1");
        context.setVersionData(versionDTO);

        when(authorizationService.resolveExecutableBusinessFunction(
                "tenant1", "app1", "user1", "skill1", "f1", "v1"
        )).thenReturn(context);

        com.foggy.navigator.business.agent.service.adapter.BusinessFunctionAdapterResult adapterResult =
            com.foggy.navigator.business.agent.service.adapter.BusinessFunctionAdapterResult.success("{\"output\":\"data\"}");
        when(adapterInvoker.invoke(context, "{}")).thenReturn(adapterResult);

        com.foggy.navigator.business.agent.model.form.WorkerGatewayInvokeForm form = new com.foggy.navigator.business.agent.model.form.WorkerGatewayInvokeForm();
        form.setVersion("v1");
        form.setInputJson("{}");

        com.foggy.navigator.business.agent.model.dto.WorkerGatewayInvokeResponseDTO response = workerGatewayService.invokeBusinessFunction("valid_token", "f1", form);

        assertNotNull(response);
        assertEquals("f1", response.getFunctionId());
        assertEquals("v1", response.getVersion());
        assertFalse(response.getApprovalRequired());
        assertEquals(com.foggy.navigator.business.agent.model.dto.WorkerGatewayInvokeResponseDTO.STATUS_SUCCESS, response.getStatus());
        assertEquals("{\"output\":\"data\"}", response.getOutputJson());
        assertNull(response.getSuspendId());
    }

    @Test
    void invokeBusinessFunction_nonApproval_unsupportedAdapter_rejected() {
        when(taskService.resolveTaskScopedToken("valid_token")).thenReturn(tokenDTO);

        BusinessFunctionRuntimeContextDTO context = new BusinessFunctionRuntimeContextDTO();
        BusinessFunctionDTO functionDTO = new BusinessFunctionDTO();
        functionDTO.setFunctionId("f1");
        functionDTO.setApprovalRequired(false);
        context.setFunction(functionDTO);

        BusinessFunctionVersionDTO versionDTO = new BusinessFunctionVersionDTO();
        versionDTO.setVersion("v1");
        context.setVersionData(versionDTO);

        when(authorizationService.resolveExecutableBusinessFunction(
                "tenant1", "app1", "user1", "skill1", "f1", "v1"
        )).thenReturn(context);

        when(adapterInvoker.invoke(context, "{}")).thenThrow(new IllegalArgumentException("Unsupported adapter type"));

        com.foggy.navigator.business.agent.model.form.WorkerGatewayInvokeForm form = new com.foggy.navigator.business.agent.model.form.WorkerGatewayInvokeForm();
        form.setVersion("v1");
        form.setInputJson("{}");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
            workerGatewayService.invokeBusinessFunction("valid_token", "f1", form)
        );
        assertEquals("Unsupported adapter type", ex.getMessage());
    }

    @Test
    void invokeBusinessFunction_nonApproval_adapterErrorResult_rejected() {
        when(taskService.resolveTaskScopedToken("valid_token")).thenReturn(tokenDTO);

        BusinessFunctionRuntimeContextDTO context = new BusinessFunctionRuntimeContextDTO();
        BusinessFunctionDTO functionDTO = new BusinessFunctionDTO();
        functionDTO.setFunctionId("f1");
        functionDTO.setApprovalRequired(false);
        context.setFunction(functionDTO);

        BusinessFunctionVersionDTO versionDTO = new BusinessFunctionVersionDTO();
        versionDTO.setVersion("v1");
        context.setVersionData(versionDTO);

        when(authorizationService.resolveExecutableBusinessFunction(
                "tenant1", "app1", "user1", "skill1", "f1", "v1"
        )).thenReturn(context);

        when(adapterInvoker.invoke(context, "{}")).thenReturn(
                com.foggy.navigator.business.agent.service.adapter.BusinessFunctionAdapterResult.error("adapter failed")
        );

        com.foggy.navigator.business.agent.model.form.WorkerGatewayInvokeForm form = new com.foggy.navigator.business.agent.model.form.WorkerGatewayInvokeForm();
        form.setVersion("v1");
        form.setInputJson("{}");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                workerGatewayService.invokeBusinessFunction("valid_token", "f1", form)
        );
        assertEquals("adapter failed", ex.getMessage());
    }

    @Test
    void invoke_rejects_invalid_token() {
        when(taskService.resolveTaskScopedToken("invalid_token")).thenThrow(new IllegalArgumentException("invalid token"));

        com.foggy.navigator.business.agent.model.form.WorkerGatewayInvokeForm form = new com.foggy.navigator.business.agent.model.form.WorkerGatewayInvokeForm();
        form.setVersion("v1");
        form.setInputJson("{}");

        assertThrows(IllegalArgumentException.class, () ->
            workerGatewayService.invokeBusinessFunction("invalid_token", "f1", form)
        );
    }

    @Test
    void invoke_rejects_incomplete_token() {
        tokenDTO.setTaskId(null);
        when(taskService.resolveTaskScopedToken("valid_token")).thenReturn(tokenDTO);

        com.foggy.navigator.business.agent.model.form.WorkerGatewayInvokeForm form = new com.foggy.navigator.business.agent.model.form.WorkerGatewayInvokeForm();
        form.setVersion("v1");
        form.setInputJson("{}");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
            workerGatewayService.invokeBusinessFunction("valid_token", "f1", form)
        );
        assertEquals("token missing taskId", ex.getMessage());
    }

    @Test
    void invoke_rejects_unauthorized_function() {
        when(taskService.resolveTaskScopedToken("valid_token")).thenReturn(tokenDTO);

        when(authorizationService.resolveExecutableBusinessFunction(
                "tenant1", "app1", "user1", "skill1", "f1", "v1"
        )).thenThrow(new IllegalArgumentException("grant not found"));

        com.foggy.navigator.business.agent.model.form.WorkerGatewayInvokeForm form = new com.foggy.navigator.business.agent.model.form.WorkerGatewayInvokeForm();
        form.setVersion("v1");
        form.setInputJson("{}");

        assertThrows(IllegalArgumentException.class, () ->
            workerGatewayService.invokeBusinessFunction("valid_token", "f1", form)
        );
    }

    @Test
    void invoke_response_does_not_expose_adapterConfig_or_manifest() {
        boolean hasAdapterConfig = Arrays.stream(com.foggy.navigator.business.agent.model.dto.WorkerGatewayInvokeResponseDTO.class.getDeclaredFields())
                .anyMatch(f -> f.getName().equals("adapterConfigJson"));
        boolean hasManifest = Arrays.stream(com.foggy.navigator.business.agent.model.dto.WorkerGatewayInvokeResponseDTO.class.getDeclaredFields())
                .anyMatch(f -> f.getName().equals("manifestJson"));

        assertFalse(hasAdapterConfig, "WorkerGatewayInvokeResponseDTO must not expose adapterConfigJson");
        assertFalse(hasManifest, "WorkerGatewayInvokeResponseDTO must not expose manifestJson");
    }

    @Test
    void invokeBusinessFunction_withStructuredInput_success() throws Exception {
        when(taskService.resolveTaskScopedToken("valid_token")).thenReturn(tokenDTO);

        BusinessFunctionRuntimeContextDTO context = new BusinessFunctionRuntimeContextDTO();
        BusinessFunctionDTO functionDTO = new BusinessFunctionDTO();
        functionDTO.setFunctionId("f1");
        functionDTO.setApprovalRequired(true);
        context.setFunction(functionDTO);

        BusinessFunctionVersionDTO versionDTO = new BusinessFunctionVersionDTO();
        versionDTO.setVersion("v1");
        context.setVersionData(versionDTO);

        when(authorizationService.resolveExecutableBusinessFunction(
                "tenant1", "app1", "user1", "skill1", "f1", "v1"
        )).thenReturn(context);

        com.foggy.navigator.business.agent.model.entity.BusinessFunctionSuspensionEntity suspension = new com.foggy.navigator.business.agent.model.entity.BusinessFunctionSuspensionEntity();
        suspension.setSuspendId("sus_123");

        when(objectMapper.writeValueAsString(any())).thenReturn("{\"key\":\"value\"}");
        when(suspensionService.createSuspension(eq(tokenDTO), eq("f1"), eq("v1"), eq("{\"key\":\"value\"}"), eq("idem1")))
                .thenReturn(suspension);

        com.foggy.navigator.business.agent.model.form.WorkerGatewayInvokeForm form = new com.foggy.navigator.business.agent.model.form.WorkerGatewayInvokeForm();
        form.setVersion("v1");
        form.setIdempotencyKey("idem1");
        // Structured input
        form.setInput(java.util.Collections.singletonMap("key", "value"));

        com.foggy.navigator.business.agent.model.dto.WorkerGatewayInvokeResponseDTO response = workerGatewayService.invokeBusinessFunction("valid_token", "f1", form);

        assertEquals("sus_123", response.getSuspendId());
        assertEquals(com.foggy.navigator.business.agent.model.dto.WorkerGatewayInvokeResponseDTO.STATUS_SUSPENDED, response.getStatus());
        verify(objectMapper).writeValueAsString(form.getInput());
    }

    @Test
    void invokeBusinessFunction_withStructuredInput_serialization_fails() throws Exception {
        when(taskService.resolveTaskScopedToken("valid_token")).thenReturn(tokenDTO);

        BusinessFunctionRuntimeContextDTO context = new BusinessFunctionRuntimeContextDTO();
        BusinessFunctionDTO functionDTO = new BusinessFunctionDTO();
        functionDTO.setFunctionId("f1");
        functionDTO.setApprovalRequired(true);
        context.setFunction(functionDTO);

        BusinessFunctionVersionDTO versionDTO = new BusinessFunctionVersionDTO();
        versionDTO.setVersion("v1");
        context.setVersionData(versionDTO);

        when(authorizationService.resolveExecutableBusinessFunction(
                "tenant1", "app1", "user1", "skill1", "f1", "v1"
        )).thenReturn(context);

        when(objectMapper.writeValueAsString(any())).thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("mock error"){});

        com.foggy.navigator.business.agent.model.form.WorkerGatewayInvokeForm form = new com.foggy.navigator.business.agent.model.form.WorkerGatewayInvokeForm();
        form.setVersion("v1");
        form.setIdempotencyKey("idem1");
        form.setInput(java.util.Collections.singletonMap("key", "value"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
            workerGatewayService.invokeBusinessFunction("valid_token", "f1", form)
        );
        assertTrue(ex.getMessage().contains("serialization failed"));
    }

    @Test
    void reportToolMessage_success() {
        when(taskService.resolveTaskScopedToken("valid_token")).thenReturn(tokenDTO);

        com.foggy.navigator.business.agent.model.form.WorkerGatewayToolMessageForm form = new com.foggy.navigator.business.agent.model.form.WorkerGatewayToolMessageForm();
        form.setToolName("invoke_business_function");
        form.setFunctionId("f1");
        form.setStatus("APPROVAL_WAIT");
        form.setSuspendId("sus_123");

        com.foggy.navigator.business.agent.model.dto.WorkerGatewayToolMessageResponseDTO response = workerGatewayService.reportToolMessage("valid_token", form);

        assertTrue(response.isAccepted());
        assertEquals("Tool message accepted", response.getMessage());
    }

    @Test
    void reportToolMessage_rejectsIncompleteToken() {
        tokenDTO.setTaskId(null);
        when(taskService.resolveTaskScopedToken("valid_token")).thenReturn(tokenDTO);

        com.foggy.navigator.business.agent.model.form.WorkerGatewayToolMessageForm form = new com.foggy.navigator.business.agent.model.form.WorkerGatewayToolMessageForm();
        form.setToolName("invoke_business_function");
        form.setStatus("SUCCESS");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
            workerGatewayService.reportToolMessage("valid_token", form)
        );
        assertEquals("token missing taskId", ex.getMessage());
    }
}
