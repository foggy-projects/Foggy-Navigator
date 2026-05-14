package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.dto.ClientAppModelConfigGrantDTO;
import com.foggy.navigator.business.agent.model.dto.E2eModelConfigEnsureResultDTO;
import com.foggy.navigator.business.agent.model.entity.ClientAppEntity;
import com.foggy.navigator.business.agent.model.form.EnsureE2eModelConfigForm;
import com.foggy.navigator.business.agent.model.form.GrantModelConfigForm;
import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggy.navigator.common.enums.LlmModelCategory;
import com.foggy.navigator.common.form.LlmModelConfigForm;
import com.foggy.navigator.spi.config.LlmModelManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class E2eModelConfigEnsureServiceTest {

    private ClientAppService clientAppService;
    private ClientAppModelConfigGrantService grantService;
    private LlmModelManager llmModelManager;
    private E2eModelConfigEnsureService service;

    @BeforeEach
    void setUp() {
        clientAppService = mock(ClientAppService.class);
        grantService = mock(ClientAppModelConfigGrantService.class);
        llmModelManager = mock(LlmModelManager.class);
        service = new E2eModelConfigEnsureService(clientAppService, grantService, llmModelManager);

        when(clientAppService.requireActiveClientApp("tenant-1", "capp-1")).thenReturn(clientApp());
        when(llmModelManager.saveModelConfig(eq("tenant-1"), any())).thenReturn("cfg-e2e");
        when(grantService.listGrants("tenant-1", "capp-1")).thenReturn(List.of());
        when(grantService.grantModelConfig(eq("tenant-1"), eq("admin-1"), eq("capp-1"), any()))
                .thenReturn(grant(7L, "cfg-e2e", true, ClientAppModelConfigGrantService.STATUS_ENABLED));
    }

    @Test
    void ensure_creates_clientApp_specific_model_and_default_grant() {
        E2eModelConfigEnsureResultDTO result = service.ensure(
                "tenant-1", "admin-1", "capp-1", form("http://localhost:8200/", true));

        ArgumentCaptor<LlmModelConfigForm> modelCaptor = ArgumentCaptor.forClass(LlmModelConfigForm.class);
        verify(llmModelManager).saveModelConfig(eq("tenant-1"), modelCaptor.capture());
        LlmModelConfigForm modelForm = modelCaptor.getValue();
        assertEquals("Navigator E2E Test Model - capp-1", modelForm.getName());
        assertEquals("http://localhost:8200/v1", modelForm.getBaseUrl());
        assertEquals("navigator-e2e-scripted", modelForm.getModelName());
        assertEquals("navigator-e2e-test-key", modelForm.getApiKey());
        assertEquals("LANGGRAPH_BIZ", modelForm.getWorkerBackend());
        assertEquals(LlmModelCategory.GENERAL, modelForm.getCategory());
        assertEquals("openai", modelForm.getEnvVars().get("NAVI_LLM_PROVIDER"));
        assertFalse(modelForm.getIsDefault());

        ArgumentCaptor<GrantModelConfigForm> grantCaptor = ArgumentCaptor.forClass(GrantModelConfigForm.class);
        verify(grantService).grantModelConfig(eq("tenant-1"), eq("admin-1"), eq("capp-1"), grantCaptor.capture());
        assertEquals("cfg-e2e", grantCaptor.getValue().getModelConfigId());
        assertTrue(grantCaptor.getValue().getIsDefault());

        assertEquals("cfg-e2e", result.getModelConfigId());
        assertTrue(result.isModelCreated());
        assertTrue(result.isGrantCreated());
        assertTrue(result.getIsDefault());
    }

    @Test
    void ensure_reuses_existing_model_and_existing_grant() {
        when(llmModelManager.listModelConfigs("tenant-1")).thenReturn(List.of(model("cfg-existing", "http://localhost:8200/v1")));
        when(grantService.listGrants("tenant-1", "capp-1"))
                .thenReturn(List.of(grant(8L, "cfg-existing", true, ClientAppModelConfigGrantService.STATUS_ENABLED)));

        E2eModelConfigEnsureResultDTO result = service.ensure(
                "tenant-1", "admin-1", "capp-1", form("http://localhost:8200", true));

        verify(llmModelManager, never()).saveModelConfig(anyString(), any());
        verify(llmModelManager, never()).updateModelConfig(anyString(), any());
        verify(grantService, never()).grantModelConfig(anyString(), anyString(), anyString(), any());
        assertEquals("cfg-existing", result.getModelConfigId());
        assertFalse(result.isModelCreated());
        assertFalse(result.isModelUpdated());
        assertFalse(result.isGrantCreated());
    }

    @Test
    void ensure_accepts_mockBaseUrl_already_endingWithOpenAiV1() {
        E2eModelConfigEnsureResultDTO result = service.ensure(
                "tenant-1", "admin-1", "capp-1", form("http://localhost:8200/v1/", true));

        ArgumentCaptor<LlmModelConfigForm> modelCaptor = ArgumentCaptor.forClass(LlmModelConfigForm.class);
        verify(llmModelManager).saveModelConfig(eq("tenant-1"), modelCaptor.capture());
        assertEquals("http://localhost:8200/v1", modelCaptor.getValue().getBaseUrl());
        assertEquals("http://localhost:8200/v1", result.getMockBaseUrl());
    }

    @Test
    void ensure_updates_existing_clientApp_model_when_mockUrl_missingOpenAiV1Path() {
        when(llmModelManager.listModelConfigs("tenant-1")).thenReturn(List.of(model("cfg-existing", "http://localhost:8200")));
        when(grantService.listGrants("tenant-1", "capp-1"))
                .thenReturn(List.of(grant(8L, "cfg-existing", true, ClientAppModelConfigGrantService.STATUS_ENABLED)));

        E2eModelConfigEnsureResultDTO result = service.ensure(
                "tenant-1", "admin-1", "capp-1", form("http://localhost:8200", true));

        ArgumentCaptor<LlmModelConfigForm> modelCaptor = ArgumentCaptor.forClass(LlmModelConfigForm.class);
        verify(llmModelManager).updateModelConfig(eq("cfg-existing"), modelCaptor.capture());
        assertEquals("http://localhost:8200/v1", modelCaptor.getValue().getBaseUrl());
        assertEquals("http://localhost:8200/v1", result.getMockBaseUrl());
        assertTrue(result.isModelUpdated());
    }

    @Test
    void ensure_updates_existing_clientApp_model_when_mockUrl_changes() {
        when(llmModelManager.listModelConfigs("tenant-1")).thenReturn(List.of(model("cfg-existing", "http://old:8200/v1")));
        when(grantService.listGrants("tenant-1", "capp-1"))
                .thenReturn(List.of(grant(8L, "cfg-existing", false, ClientAppModelConfigGrantService.STATUS_ENABLED)));
        when(grantService.setDefault("tenant-1", "capp-1", 8L))
                .thenReturn(grant(8L, "cfg-existing", true, ClientAppModelConfigGrantService.STATUS_ENABLED));

        E2eModelConfigEnsureResultDTO result = service.ensure(
                "tenant-1", "admin-1", "capp-1", form("http://localhost:8200", true));

        verify(llmModelManager).updateModelConfig(eq("cfg-existing"), any());
        verify(grantService).setDefault("tenant-1", "capp-1", 8L);
        assertEquals("cfg-existing", result.getModelConfigId());
        assertTrue(result.isModelUpdated());
        assertTrue(result.getIsDefault());
    }

    @Test
    void ensure_rejects_unsupported_standard() {
        EnsureE2eModelConfigForm form = form("http://localhost:8200", true);
        form.setStandard("other");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.ensure("tenant-1", "admin-1", "capp-1", form));
        assertTrue(error.getMessage().contains("unsupported e2e standard"));
    }

    private EnsureE2eModelConfigForm form(String mockBaseUrl, boolean setDefault) {
        EnsureE2eModelConfigForm form = new EnsureE2eModelConfigForm();
        form.setStandard("biz-worker");
        form.setMockBaseUrl(mockBaseUrl);
        form.setSetDefault(setDefault);
        return form;
    }

    private LlmModelConfigDTO model(String id, String baseUrl) {
        LlmModelConfigDTO dto = new LlmModelConfigDTO();
        dto.setId(id);
        dto.setTenantId("tenant-1");
        dto.setName("Navigator E2E Test Model - capp-1");
        dto.setBaseUrl(baseUrl);
        dto.setModelName("navigator-e2e-scripted");
        dto.setWorkerBackend("LANGGRAPH_BIZ");
        dto.setCategory(LlmModelCategory.GENERAL);
        dto.setIsDefault(false);
        dto.setHasApiKey(true);
        return dto;
    }

    private ClientAppModelConfigGrantDTO grant(Long id, String modelConfigId, boolean isDefault, String status) {
        ClientAppModelConfigGrantDTO dto = new ClientAppModelConfigGrantDTO();
        dto.setId(id);
        dto.setTenantId("tenant-1");
        dto.setClientAppId("capp-1");
        dto.setModelConfigId(modelConfigId);
        dto.setStatus(status);
        dto.setIsDefault(isDefault);
        return dto;
    }

    private ClientAppEntity clientApp() {
        ClientAppEntity entity = new ClientAppEntity();
        entity.setClientAppId("capp-1");
        entity.setTenantId("tenant-1");
        entity.setStatus(ClientAppService.STATUS_ACTIVE);
        return entity;
    }
}
