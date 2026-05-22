package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.dto.ClientAppModelConfigGrantDTO;
import com.foggy.navigator.business.agent.model.entity.ClientAppEntity;
import com.foggy.navigator.business.agent.model.entity.ClientAppModelConfigGrantEntity;
import com.foggy.navigator.business.agent.model.form.ClientAppModelConfigForm;
import com.foggy.navigator.business.agent.model.form.GrantModelConfigForm;
import com.foggy.navigator.business.agent.model.form.RotateModelConfigKeyForm;
import com.foggy.navigator.business.agent.repository.ClientAppModelConfigGrantRepository;
import com.foggy.navigator.common.form.LlmModelConfigForm;
import com.foggy.navigator.spi.config.LlmModelManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClientAppOwnedModelConfigServiceTest {

    private ClientAppService clientAppService;
    private ClientAppModelConfigGrantService grantService;
    private ClientAppModelConfigGrantRepository grantRepository;
    private LlmModelManager llmModelManager;
    private ClientAppOwnedModelConfigService service;

    @BeforeEach
    void setUp() {
        clientAppService = mock(ClientAppService.class);
        grantService = mock(ClientAppModelConfigGrantService.class);
        grantRepository = mock(ClientAppModelConfigGrantRepository.class);
        llmModelManager = mock(LlmModelManager.class);
        service = new ClientAppOwnedModelConfigService(clientAppService, grantService, grantRepository, llmModelManager);

        when(clientAppService.requireClientApp("tenant-1", "capp-1")).thenReturn(clientApp());
        when(llmModelManager.saveModelConfig(anyString(), any())).thenReturn("cfg-owned");
        when(grantService.grantModelConfig(anyString(), anyString(), anyString(), any())).thenReturn(grantDto("cfg-owned"));
    }

    @Test
    void create_saves_langgraph_model_and_owned_grant() {
        ClientAppModelConfigGrantDTO dto = service.create("tenant-1", "actor-1", "capp-1", createForm());

        ArgumentCaptor<LlmModelConfigForm> modelCaptor = ArgumentCaptor.forClass(LlmModelConfigForm.class);
        verify(llmModelManager).saveModelConfig(eq("tenant-1"), modelCaptor.capture());
        LlmModelConfigForm saved = modelCaptor.getValue();
        assertEquals("Upstream GPT", saved.getName());
        assertEquals("https://llm.example/v1", saved.getBaseUrl());
        assertEquals("gpt-test", saved.getModelName());
        assertEquals("secret-key", saved.getApiKey());
        assertEquals(ClientAppModelConfigGrantService.LANGGRAPH_BIZ_BACKEND, saved.getWorkerBackend());
        assertFalse(Boolean.TRUE.equals(saved.getIsDefault()));
        assertEquals("generic.128k", saved.getRuntimeBudgetPresetKey());
        assertEquals("{\"maxOutputTokens\":6144}", saved.getRuntimeBudgetOverrideJson());

        ArgumentCaptor<GrantModelConfigForm> grantCaptor = ArgumentCaptor.forClass(GrantModelConfigForm.class);
        verify(grantService).grantModelConfig(eq("tenant-1"), eq("actor-1"), eq("capp-1"), grantCaptor.capture());
        assertEquals("cfg-owned", grantCaptor.getValue().getModelConfigId());
        assertTrue(grantCaptor.getValue().getIsDefault());
        assertEquals(ClientAppOwnedModelConfigService.GRANT_SCOPE_CLIENT_APP_OWNED, grantCaptor.getValue().getGrantScope());
        assertEquals("cfg-owned", dto.getModelConfigId());
    }

    @Test
    void update_rejects_shared_grant() {
        ClientAppModelConfigGrantEntity grant = ownedGrant("cfg-shared");
        grant.setGrantScope("CLIENT_APP");
        when(grantRepository.findByClientAppIdAndModelConfigId("capp-1", "cfg-shared"))
                .thenReturn(Optional.of(grant));

        assertThrows(IllegalArgumentException.class,
                () -> service.update("tenant-1", "capp-1", "cfg-shared", createForm()));
        verify(llmModelManager, never()).updateModelConfig(anyString(), any());
    }

    @Test
    void rotateKey_updates_only_owned_model_key() {
        ClientAppModelConfigGrantEntity grant = ownedGrant("cfg-owned");
        when(grantRepository.findByClientAppIdAndModelConfigId("capp-1", "cfg-owned"))
                .thenReturn(Optional.of(grant));
        RotateModelConfigKeyForm form = new RotateModelConfigKeyForm();
        form.setApiKey("new-secret");

        ClientAppModelConfigGrantDTO dto = service.rotateKey("tenant-1", "capp-1", "cfg-owned", form);

        ArgumentCaptor<LlmModelConfigForm> captor = ArgumentCaptor.forClass(LlmModelConfigForm.class);
        verify(llmModelManager).updateModelConfig(eq("cfg-owned"), captor.capture());
        assertEquals("new-secret", captor.getValue().getApiKey());
        assertNull(captor.getValue().getBaseUrl());
        assertEquals("cfg-owned", dto.getModelConfigId());
    }

    private ClientAppModelConfigForm createForm() {
        ClientAppModelConfigForm form = new ClientAppModelConfigForm();
        form.setName("Upstream GPT");
        form.setBaseUrl("https://llm.example/v1");
        form.setModelName("gpt-test");
        form.setApiKey("secret-key");
        form.setSetDefault(true);
        form.setRuntimeBudgetPresetKey("generic.128k");
        form.setRuntimeBudgetOverrideJson("{\"maxOutputTokens\":6144}");
        return form;
    }

    private ClientAppModelConfigGrantEntity ownedGrant(String modelConfigId) {
        ClientAppModelConfigGrantEntity grant = new ClientAppModelConfigGrantEntity();
        grant.setId(9L);
        grant.setTenantId("tenant-1");
        grant.setClientAppId("capp-1");
        grant.setModelConfigId(modelConfigId);
        grant.setStatus(ClientAppModelConfigGrantService.STATUS_ENABLED);
        grant.setIsDefault(false);
        grant.setGrantScope(ClientAppOwnedModelConfigService.GRANT_SCOPE_CLIENT_APP_OWNED);
        return grant;
    }

    private ClientAppModelConfigGrantDTO grantDto(String modelConfigId) {
        ClientAppModelConfigGrantDTO dto = new ClientAppModelConfigGrantDTO();
        dto.setClientAppId("capp-1");
        dto.setModelConfigId(modelConfigId);
        dto.setStatus(ClientAppModelConfigGrantService.STATUS_ENABLED);
        dto.setIsDefault(true);
        dto.setGrantScope(ClientAppOwnedModelConfigService.GRANT_SCOPE_CLIENT_APP_OWNED);
        return dto;
    }

    private ClientAppEntity clientApp() {
        ClientAppEntity entity = new ClientAppEntity();
        entity.setTenantId("tenant-1");
        entity.setClientAppId("capp-1");
        entity.setStatus(ClientAppService.STATUS_ACTIVE);
        return entity;
    }
}
