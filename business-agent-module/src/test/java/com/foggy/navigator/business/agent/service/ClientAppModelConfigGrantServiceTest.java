package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.entity.ClientAppEntity;
import com.foggy.navigator.business.agent.model.entity.ClientAppModelConfigGrantEntity;
import com.foggy.navigator.business.agent.model.form.GrantModelConfigForm;
import com.foggy.navigator.business.agent.repository.ClientAppModelConfigGrantRepository;
import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggy.navigator.spi.config.LlmModelManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClientAppModelConfigGrantServiceTest {

    private ClientAppModelConfigGrantRepository grantRepository;
    private ClientAppService clientAppService;
    private LlmModelManager llmModelManager;
    private ClientAppModelConfigGrantService service;

    @BeforeEach
    void setUp() {
        grantRepository = mock(ClientAppModelConfigGrantRepository.class);
        clientAppService = mock(ClientAppService.class);
        llmModelManager = mock(LlmModelManager.class);
        service = new ClientAppModelConfigGrantService(grantRepository, clientAppService, llmModelManager);

        when(clientAppService.requireClientApp("tenant-1", "capp-1")).thenReturn(clientApp());
        when(grantRepository.save(any())).thenAnswer(inv -> {
            ClientAppModelConfigGrantEntity entity = inv.getArgument(0);
            if (entity.getId() == null) {
                entity.setId(1L);
            }
            return entity;
        });
        when(grantRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(llmModelManager.getModelConfig("cfg-1")).thenReturn(Optional.of(model("cfg-1", "tenant-1", "LANGGRAPH_BIZ")));
        when(llmModelManager.getModelConfig("cfg-2")).thenReturn(Optional.of(model("cfg-2", "tenant-1", "LANGGRAPH_BIZ")));
    }

    @Test
    void grantModelConfig_rejects_null_form() {
        assertThrows(IllegalArgumentException.class,
                () -> service.grantModelConfig("tenant-1", "admin-1", "capp-1", null));
    }

    @Test
    void grantModelConfig_rejects_missing_model_config() {
        when(llmModelManager.getModelConfig("missing")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> service.grantModelConfig("tenant-1", "admin-1", "capp-1", grantForm("missing", false)));
    }

    @Test
    void grantModelConfig_rejects_cross_tenant_model_config() {
        when(llmModelManager.getModelConfig("cfg-foreign"))
                .thenReturn(Optional.of(model("cfg-foreign", "tenant-2", "LANGGRAPH_BIZ")));

        assertThrows(IllegalArgumentException.class,
                () -> service.grantModelConfig("tenant-1", "admin-1", "capp-1", grantForm("cfg-foreign", false)));
    }

    @Test
    void grantModelConfig_rejects_invalid_backend() {
        when(llmModelManager.getModelConfig("cfg-other"))
                .thenReturn(Optional.of(model("cfg-other", "tenant-1", "CLAUDE_CODE")));

        assertThrows(IllegalArgumentException.class,
                () -> service.grantModelConfig("tenant-1", "admin-1", "capp-1", grantForm("cfg-other", false)));
    }

    @Test
    void grantModelConfig_rejects_duplicate_grant() {
        when(grantRepository.findByClientAppIdAndModelConfigId("capp-1", "cfg-1"))
                .thenReturn(Optional.of(grant("cfg-1", true, ClientAppModelConfigGrantService.STATUS_ENABLED)));

        assertThrows(IllegalArgumentException.class,
                () -> service.grantModelConfig("tenant-1", "admin-1", "capp-1", grantForm("cfg-1", false)));
    }

    @Test
    void grantModelConfig_as_default_clears_existing_defaults() {
        ClientAppModelConfigGrantEntity oldDefault =
                grant("cfg-2", true, ClientAppModelConfigGrantService.STATUS_ENABLED);
        when(grantRepository.findByClientAppIdAndStatusAndIsDefaultTrueOrderByUpdatedAtDesc(
                "capp-1", ClientAppModelConfigGrantService.STATUS_ENABLED)).thenReturn(List.of(oldDefault));

        service.grantModelConfig("tenant-1", "admin-1", "capp-1", grantForm("cfg-1", true));

        assertFalse(oldDefault.getIsDefault());
        verify(grantRepository).saveAll(List.of(oldDefault));
    }

    @Test
    void updateStatus_disabling_default_clears_default_flag() {
        ClientAppModelConfigGrantEntity grant =
                grant("cfg-1", true, ClientAppModelConfigGrantService.STATUS_ENABLED);
        grant.setId(7L);
        when(grantRepository.findByIdAndClientAppId(7L, "capp-1")).thenReturn(Optional.of(grant));

        service.updateStatus("tenant-1", "capp-1", 7L, ClientAppModelConfigGrantService.STATUS_DISABLED);

        assertFalse(grant.getIsDefault());
        assertEquals(ClientAppModelConfigGrantService.STATUS_DISABLED, grant.getStatus());
    }

    @Test
    void setDefault_rejects_disabled_grant() {
        ClientAppModelConfigGrantEntity grant =
                grant("cfg-1", false, ClientAppModelConfigGrantService.STATUS_DISABLED);
        grant.setId(7L);
        when(grantRepository.findByIdAndClientAppId(7L, "capp-1")).thenReturn(Optional.of(grant));

        assertThrows(IllegalArgumentException.class,
                () -> service.setDefault("tenant-1", "capp-1", 7L));
    }

    @Test
    void resolveEffectiveModelConfigId_uses_requested_enabled_grant() {
        when(grantRepository.findByClientAppIdAndModelConfigIdAndStatus(
                "capp-1", "cfg-1", ClientAppModelConfigGrantService.STATUS_ENABLED))
                .thenReturn(Optional.of(grant("cfg-1", false, ClientAppModelConfigGrantService.STATUS_ENABLED)));

        String result = service.resolveEffectiveModelConfigId("tenant-1", "capp-1", "cfg-1");

        assertEquals("cfg-1", result);
    }

    @Test
    void resolveEffectiveModelConfigId_rejects_requested_not_granted_without_fallback() {
        when(grantRepository.findByClientAppIdAndModelConfigIdAndStatus(
                "capp-1", "cfg-1", ClientAppModelConfigGrantService.STATUS_ENABLED))
                .thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> service.resolveEffectiveModelConfigId("tenant-1", "capp-1", "cfg-1"));
    }

    @Test
    void resolveEffectiveModelConfigId_uses_default_when_requested_blank() {
        when(grantRepository.findByClientAppIdAndStatusAndIsDefaultTrueOrderByUpdatedAtDesc(
                "capp-1", ClientAppModelConfigGrantService.STATUS_ENABLED))
                .thenReturn(List.of(grant("cfg-1", true, ClientAppModelConfigGrantService.STATUS_ENABLED)));

        String result = service.resolveEffectiveModelConfigId("tenant-1", "capp-1", " ");

        assertEquals("cfg-1", result);
    }

    @Test
    void resolveEffectiveModelConfigId_rejects_missing_default() {
        when(grantRepository.findByClientAppIdAndStatusAndIsDefaultTrueOrderByUpdatedAtDesc(
                "capp-1", ClientAppModelConfigGrantService.STATUS_ENABLED)).thenReturn(List.of());

        assertThrows(IllegalArgumentException.class,
                () -> service.resolveEffectiveModelConfigId("tenant-1", "capp-1", null));
    }

    @Test
    void resolveEffectiveModelConfigId_handles_multiple_defaults_deterministically() {
        when(grantRepository.findByClientAppIdAndStatusAndIsDefaultTrueOrderByUpdatedAtDesc(
                "capp-1", ClientAppModelConfigGrantService.STATUS_ENABLED))
                .thenReturn(List.of(
                        grant("cfg-2", true, ClientAppModelConfigGrantService.STATUS_ENABLED),
                        grant("cfg-1", true, ClientAppModelConfigGrantService.STATUS_ENABLED)));

        String result = service.resolveEffectiveModelConfigId("tenant-1", "capp-1", null);

        assertEquals("cfg-2", result);
    }

    @Test
    void resolveEffectiveModelConfigId_revalidates_dirty_grant() {
        when(grantRepository.findByClientAppIdAndModelConfigIdAndStatus(
                "capp-1", "cfg-dirty", ClientAppModelConfigGrantService.STATUS_ENABLED))
                .thenReturn(Optional.of(grant("cfg-dirty", false, ClientAppModelConfigGrantService.STATUS_ENABLED)));
        when(llmModelManager.getModelConfig("cfg-dirty")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> service.resolveEffectiveModelConfigId("tenant-1", "capp-1", "cfg-dirty"));
    }

    private GrantModelConfigForm grantForm(String modelConfigId, boolean isDefault) {
        GrantModelConfigForm form = new GrantModelConfigForm();
        form.setModelConfigId(modelConfigId);
        form.setIsDefault(isDefault);
        return form;
    }

    private ClientAppModelConfigGrantEntity grant(String modelConfigId, boolean isDefault, String status) {
        ClientAppModelConfigGrantEntity entity = new ClientAppModelConfigGrantEntity();
        entity.setClientAppId("capp-1");
        entity.setTenantId("tenant-1");
        entity.setModelConfigId(modelConfigId);
        entity.setIsDefault(isDefault);
        entity.setStatus(status);
        entity.setGrantScope("APP");
        return entity;
    }

    private ClientAppEntity clientApp() {
        ClientAppEntity entity = new ClientAppEntity();
        entity.setClientAppId("capp-1");
        entity.setTenantId("tenant-1");
        entity.setStatus(ClientAppService.STATUS_ACTIVE);
        return entity;
    }

    private LlmModelConfigDTO model(String id, String tenantId, String workerBackend) {
        LlmModelConfigDTO dto = new LlmModelConfigDTO();
        dto.setId(id);
        dto.setTenantId(tenantId);
        dto.setName(id + "-name");
        dto.setWorkerBackend(workerBackend);
        return dto;
    }
}
