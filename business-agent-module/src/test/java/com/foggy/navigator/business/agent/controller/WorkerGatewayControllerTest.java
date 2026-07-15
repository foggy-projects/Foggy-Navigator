package com.foggy.navigator.business.agent.controller;

import com.foggy.navigator.business.agent.model.dto.BusinessTaskScopedTokenDTO;
import com.foggy.navigator.business.agent.model.dto.WorkerGatewayFunctionListDTO;
import com.foggy.navigator.business.agent.model.dto.WorkerGatewayFunctionSchemaDTO;
import com.foggy.navigator.business.agent.model.dto.WorkerGatewayInvokeResponseDTO;
import com.foggy.navigator.business.agent.model.dto.WorkerGatewayToolMessageResponseDTO;
import com.foggy.navigator.business.agent.model.form.WorkerGatewayInvokeForm;
import com.foggy.navigator.business.agent.model.form.WorkerGatewayToolMessageForm;
import com.foggy.navigator.business.agent.service.WorkerGatewayRequestAuthorizationService;
import com.foggy.navigator.business.agent.service.WorkerGatewayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class WorkerGatewayControllerTest {

    private static final String TASK_TOKEN = "task-token";
    private static final String WORKER_ID = "worker-1";
    private static final String CREDENTIAL = "worker-credential";
    private static final String LEASE_ID = "lease-1";

    @Mock
    private WorkerGatewayService workerGatewayService;
    @Mock
    private WorkerGatewayRequestAuthorizationService requestAuthorizationService;

    private WorkerGatewayController controller;
    private MockMvc mockMvc;
    private BusinessTaskScopedTokenDTO token;

    @BeforeEach
    void setUp() {
        controller = new WorkerGatewayController(
                workerGatewayService, requestAuthorizationService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        token = new BusinessTaskScopedTokenDTO();
        when(requestAuthorizationService.authorize(
                TASK_TOKEN, WORKER_ID, CREDENTIAL, LEASE_ID, null)).thenReturn(token);
    }

    @Test
    void listRouteUsesStrictHeadersAndDtoServiceOverload() throws Exception {
        when(workerGatewayService.listBusinessFunctions(token, "orders", "LOW"))
                .thenReturn(new WorkerGatewayFunctionListDTO());

        mockMvc.perform(withStrictHeaders(get("/internal/worker-gateway/v1/business-functions"))
                        .queryParam("domain", "orders")
                        .queryParam("riskLevel", "LOW"))
                .andExpect(status().isOk());

        verifyStrictAuthorization();
        verify(workerGatewayService).listBusinessFunctions(token, "orders", "LOW");
        verify(workerGatewayService, never()).listBusinessFunctions(
                anyString(), any(), any());
    }

    @Test
    void schemaRouteUsesStrictHeadersAndDtoServiceOverload() throws Exception {
        when(workerGatewayService.getBusinessFunctionSchema(token, "f1", "v1"))
                .thenReturn(new WorkerGatewayFunctionSchemaDTO());

        mockMvc.perform(withStrictHeaders(get(
                        "/internal/worker-gateway/v1/business-functions/f1/schema"))
                        .queryParam("version", "v1"))
                .andExpect(status().isOk());

        verifyStrictAuthorization();
        verify(workerGatewayService).getBusinessFunctionSchema(token, "f1", "v1");
        verify(workerGatewayService, never()).getBusinessFunctionSchema(
                anyString(), anyString(), any());
    }

    @Test
    void invokeRouteAuthenticatesBeforeDtoServiceOverload() throws Exception {
        when(workerGatewayService.invokeBusinessFunction(eq(token), eq("f1"), any()))
                .thenReturn(new WorkerGatewayInvokeResponseDTO());

        mockMvc.perform(withStrictHeaders(post(
                        "/internal/worker-gateway/v1/business-functions/f1/invoke"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inputJson\":\"{}\"}"))
                .andExpect(status().isOk());

        verifyStrictAuthorization();
        verify(workerGatewayService).invokeBusinessFunction(eq(token), eq("f1"), any());
        verify(workerGatewayService, never()).invokeBusinessFunction(
                anyString(), anyString(), any());
    }

    @Test
    void toolMessageRouteAuthenticatesBeforeDtoServiceOverload() throws Exception {
        when(workerGatewayService.reportToolMessage(eq(token), any()))
                .thenReturn(WorkerGatewayToolMessageResponseDTO.accepted());

        mockMvc.perform(withStrictHeaders(post("/internal/worker-gateway/v1/tool-messages"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toolName\":\"invoke_business_function\",\"status\":\"SUCCESS\"}"))
                .andExpect(status().isOk());

        verifyStrictAuthorization();
        verify(workerGatewayService).reportToolMessage(eq(token), any());
        verify(workerGatewayService, never()).reportToolMessage(anyString(), any());
    }

    @Test
    void authorizationFailureStopsAllFourRoutesBeforeAnyGatewaySideEffect() {
        reset(requestAuthorizationService);
        when(requestAuthorizationService.authorize(
                TASK_TOKEN, WORKER_ID, CREDENTIAL, LEASE_ID, null))
                .thenThrow(new SecurityException("invalid worker credential"));

        assertThrows(SecurityException.class, () -> controller.listBusinessFunctions(
                TASK_TOKEN,
                WORKER_ID,
                CREDENTIAL,
                LEASE_ID,
                null,
                null,
                null));
        assertThrows(SecurityException.class, () -> controller.getBusinessFunctionSchema(
                TASK_TOKEN,
                WORKER_ID,
                CREDENTIAL,
                LEASE_ID,
                null,
                "f1",
                "v1"));
        assertThrows(SecurityException.class, () -> controller.invokeBusinessFunction(
                TASK_TOKEN,
                WORKER_ID,
                CREDENTIAL,
                LEASE_ID,
                null,
                "f1",
                new WorkerGatewayInvokeForm()));
        assertThrows(SecurityException.class, () -> controller.reportToolMessage(
                TASK_TOKEN,
                WORKER_ID,
                CREDENTIAL,
                LEASE_ID,
                null,
                new WorkerGatewayToolMessageForm()));

        verifyNoInteractions(workerGatewayService);
        verify(requestAuthorizationService, times(4)).authorize(
                TASK_TOKEN, WORKER_ID, CREDENTIAL, LEASE_ID, null);
    }

    @Test
    void legacyWorkerIdHeaderIsExplicitlyRejectedBeforeGatewayService() {
        reset(requestAuthorizationService);
        when(requestAuthorizationService.authorize(
                TASK_TOKEN, null, null, null, "legacy-worker"))
                .thenThrow(new SecurityException("worker credential is required"));

        assertThrows(SecurityException.class, () -> controller.listBusinessFunctions(
                TASK_TOKEN,
                null,
                null,
                null,
                "legacy-worker",
                null,
                null));

        verify(requestAuthorizationService).authorize(
                TASK_TOKEN, null, null, null, "legacy-worker");
        verifyNoInteractions(workerGatewayService);
    }

    private <T extends org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder> T
    withStrictHeaders(T request) {
        request.header("X-Task-Scoped-Token", TASK_TOKEN)
                .header(WorkerGatewayRequestAuthorizationService.HEADER_WORKER_ID, WORKER_ID)
                .header(WorkerGatewayRequestAuthorizationService.HEADER_WORKER_CREDENTIAL, CREDENTIAL)
                .header(WorkerGatewayRequestAuthorizationService.HEADER_WORKER_LEASE_ID, LEASE_ID);
        return request;
    }

    private void verifyStrictAuthorization() {
        verify(requestAuthorizationService).authorize(
                TASK_TOKEN, WORKER_ID, CREDENTIAL, LEASE_ID, null);
    }
}
