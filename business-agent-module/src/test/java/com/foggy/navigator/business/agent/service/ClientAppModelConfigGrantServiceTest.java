package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.entity.ClientAppEntity;
import com.foggy.navigator.business.agent.model.entity.ClientAppModelConfigGrantEntity;
import com.foggy.navigator.business.agent.model.form.GrantModelConfigForm;
import com.foggy.navigator.business.agent.repository.ClientAppModelConfigGrantRepository;
import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggy.navigator.common.enums.LlmModelCategory;
import com.foggy.navigator.common.enums.ResourceOwnerType;
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
                .thenReturn(Optional.of(model("cfg-other", "tenant-1", "OTHER_BACKEND")));

        assertThrows(IllegalArgumentException.class,
                () -> service.grantModelConfig("tenant-1", "admin-1", "capp-1", grantForm("cfg-other", false)));
    }

    @Test
    void grantModelConfig_accepts_openAiCodex_backend() {
        when(llmModelManager.getModelConfig("cfg-codex"))
                .thenReturn(Optional.of(model("cfg-codex", "tenant-1", "OPENAI_CODEX")));

        service.grantModelConfig("tenant-1", "admin-1", "capp-1", grantForm("cfg-codex", true));

        verify(grantRepository).save(argThat(grant -> "cfg-codex".equals(grant.getModelConfigId())));
    }

    @Test
    void grantModelConfig_accepts_same_upstream_system_owner() {
        when(llmModelManager.getModelConfig("cfg-system"))
                .thenReturn(Optional.of(model(
                        "cfg-system",
                        "tenant-1",
                        "LANGGRAPH_BIZ",
                        LlmModelCategory.GENERAL,
                        ResourceOwnerType.UPSTREAM_SYSTEM,
                        "ups-1")));

        service.grantModelConfig("tenant-1", "admin-1", "capp-1", grantForm("cfg-system", false));

        verify(grantRepository).save(argThat(grant -> "cfg-system".equals(grant.getModelConfigId())));
    }

    @Test
    void grantModelConfig_rejects_foreign_upstream_system_owner() {
        when(llmModelManager.getModelConfig("cfg-foreign-system"))
                .thenReturn(Optional.of(model(
                        "cfg-foreign-system",
                        "tenant-1",
                        "LANGGRAPH_BIZ",
                        LlmModelCategory.GENERAL,
                        ResourceOwnerType.UPSTREAM_SYSTEM,
                        "ups-2")));

        assertThrows(IllegalArgumentException.class,
                () -> service.grantModelConfig("tenant-1", "admin-1", "capp-1",
                        grantForm("cfg-foreign-system", false)));
    }

    @Test
    void grantModelConfig_rejects_foreign_clientApp_owner() {
        when(llmModelManager.getModelConfig("cfg-foreign-client"))
                .thenReturn(Optional.of(model(
                        "cfg-foreign-client",
                        "tenant-1",
                        "LANGGRAPH_BIZ",
                        LlmModelCategory.GENERAL,
                        ResourceOwnerType.CLIENT_APP,
                        "capp-2")));

        assertThrows(IllegalArgumentException.class,
                () -> service.grantModelConfig("tenant-1", "admin-1", "capp-1",
                        grantForm("cfg-foreign-client", false)));
    }

    @Test
    void grantModelConfig_rejects_disabled_model() {
        LlmModelConfigDTO disabled = model("cfg-disabled", "tenant-1", "LANGGRAPH_BIZ");
        disabled.setEnabled(false);
        when(llmModelManager.getModelConfig("cfg-disabled")).thenReturn(Optional.of(disabled));

        assertThrows(IllegalArgumentException.class,
                () -> service.grantModelConfig("tenant-1", "admin-1", "capp-1",
                        grantForm("cfg-disabled", false)));
    }

    @Test
    void grantModelConfig_returns_existing_grant_when_already_granted() {
        ClientAppModelConfigGrantEntity existing =
                grant("cfg-1", true, ClientAppModelConfigGrantService.STATUS_ENABLED);
        when(grantRepository.findByClientAppIdAndModelConfigId("capp-1", "cfg-1"))
                .thenReturn(Optional.of(existing));

        var result = service.grantModelConfig("tenant-1", "admin-1", "capp-1", grantForm("cfg-1", false));

        assertEquals("cfg-1", result.getModelConfigId());
        assertTrue(result.getIsDefault());
        assertEquals(ClientAppModelConfigGrantService.STATUS_ENABLED, result.getStatus());
        verify(grantRepository, never()).save(any(ClientAppModelConfigGrantEntity.class));
    }

    @Test
    void grantModelConfig_reenables_existing_grant_and_sets_default() {
        ClientAppModelConfigGrantEntity existing =
                grant("cfg-1", false, ClientAppModelConfigGrantService.STATUS_DISABLED);
        ClientAppModelConfigGrantEntity oldDefault =
                grant("cfg-2", true, ClientAppModelConfigGrantService.STATUS_ENABLED);
        when(grantRepository.findByClientAppIdAndModelConfigId("capp-1", "cfg-1"))
                .thenReturn(Optional.of(existing));
        when(grantRepository.findByClientAppIdAndStatusAndIsDefaultTrueOrderByUpdatedAtDesc(
                "capp-1", ClientAppModelConfigGrantService.STATUS_ENABLED)).thenReturn(List.of(oldDefault));

        var result = service.grantModelConfig("tenant-1", "admin-1", "capp-1", grantForm("cfg-1", true));

        assertEquals("cfg-1", result.getModelConfigId());
        assertEquals(ClientAppModelConfigGrantService.STATUS_ENABLED, existing.getStatus());
        assertTrue(existing.getIsDefault());
        assertFalse(oldDefault.getIsDefault());
        verify(grantRepository).saveAll(List.of(oldDefault));
        verify(grantRepository).save(existing);
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

    @Test
    void grantModelConfig_as_default_only_clears_same_category_defaults() {
        ClientAppModelConfigGrantEntity textDefault =
                grant("cfg-general", true, ClientAppModelConfigGrantService.STATUS_ENABLED);
        ClientAppModelConfigGrantEntity oldVisionDefault =
                grant("cfg-vision-old", true, ClientAppModelConfigGrantService.STATUS_ENABLED);
        when(llmModelManager.getModelConfig("cfg-vision-new"))
                .thenReturn(Optional.of(model("cfg-vision-new", "tenant-1", "LANGGRAPH_BIZ", LlmModelCategory.VISION)));
        when(llmModelManager.getModelConfig("cfg-general"))
                .thenReturn(Optional.of(model("cfg-general", "tenant-1", "LANGGRAPH_BIZ", LlmModelCategory.GENERAL)));
        when(llmModelManager.getModelConfig("cfg-vision-old"))
                .thenReturn(Optional.of(model("cfg-vision-old", "tenant-1", "LANGGRAPH_BIZ", LlmModelCategory.VISION)));
        when(grantRepository.findByClientAppIdAndStatusAndIsDefaultTrueOrderByUpdatedAtDesc(
                "capp-1", ClientAppModelConfigGrantService.STATUS_ENABLED))
                .thenReturn(List.of(textDefault, oldVisionDefault));

        service.grantModelConfig("tenant-1", "admin-1", "capp-1", grantForm("cfg-vision-new", true));

        assertTrue(textDefault.getIsDefault());
        assertFalse(oldVisionDefault.getIsDefault());
        verify(grantRepository).saveAll(List.of(oldVisionDefault));
    }

    @Test
    void resolveEffectiveModelConfigId_uses_category_specific_default() {
        when(llmModelManager.getModelConfig("cfg-general"))
                .thenReturn(Optional.of(model("cfg-general", "tenant-1", "LANGGRAPH_BIZ", LlmModelCategory.GENERAL)));
        when(llmModelManager.getModelConfig("cfg-vision"))
                .thenReturn(Optional.of(model("cfg-vision", "tenant-1", "LANGGRAPH_BIZ", LlmModelCategory.VISION)));
        when(grantRepository.findByClientAppIdAndStatusAndIsDefaultTrueOrderByUpdatedAtDesc(
                "capp-1", ClientAppModelConfigGrantService.STATUS_ENABLED))
                .thenReturn(List.of(
                        grant("cfg-general", true, ClientAppModelConfigGrantService.STATUS_ENABLED),
                        grant("cfg-vision", true, ClientAppModelConfigGrantService.STATUS_ENABLED)));

        String result = service.resolveEffectiveModelConfigId("tenant-1", "capp-1", null, LlmModelCategory.VISION);

        assertEquals("cfg-vision", result);
    }

    @Test
    void resolveEffectiveModelConfigId_without_category_ignores_vision_default() {
        when(llmModelManager.getModelConfig("cfg-general"))
                .thenReturn(Optional.of(model("cfg-general", "tenant-1", "LANGGRAPH_BIZ", LlmModelCategory.GENERAL)));
        when(llmModelManager.getModelConfig("cfg-vision"))
                .thenReturn(Optional.of(model("cfg-vision", "tenant-1", "LANGGRAPH_BIZ", LlmModelCategory.VISION)));
        when(grantRepository.findByClientAppIdAndStatusAndIsDefaultTrueOrderByUpdatedAtDesc(
                "capp-1", ClientAppModelConfigGrantService.STATUS_ENABLED))
                .thenReturn(List.of(
                        grant("cfg-vision", true, ClientAppModelConfigGrantService.STATUS_ENABLED),
                        grant("cfg-general", true, ClientAppModelConfigGrantService.STATUS_ENABLED)));

        String result = service.resolveEffectiveModelConfigId("tenant-1", "capp-1", null);

        assertEquals("cfg-general", result);
    }

    @Test
    void resolveEffectiveModelConfigId_rejects_requested_category_mismatch() {
        when(llmModelManager.getModelConfig("cfg-general"))
                .thenReturn(Optional.of(model("cfg-general", "tenant-1", "LANGGRAPH_BIZ", LlmModelCategory.GENERAL)));
        when(grantRepository.findByClientAppIdAndModelConfigIdAndStatus(
                "capp-1", "cfg-general", ClientAppModelConfigGrantService.STATUS_ENABLED))
                .thenReturn(Optional.of(grant("cfg-general", false, ClientAppModelConfigGrantService.STATUS_ENABLED)));

        assertThrows(IllegalArgumentException.class,
                () -> service.resolveEffectiveModelConfigId("tenant-1", "capp-1", "cfg-general", LlmModelCategory.VISION));
    }

    @Test
    void tryResolveEffectiveModelConfigId_returns_resolved_value() {
        when(llmModelManager.getModelConfig("cfg-vision"))
                .thenReturn(Optional.of(model("cfg-vision", "tenant-1", "LANGGRAPH_BIZ", LlmModelCategory.VISION)));
        when(grantRepository.findByClientAppIdAndStatusAndIsDefaultTrueOrderByUpdatedAtDesc(
                "capp-1", ClientAppModelConfigGrantService.STATUS_ENABLED))
                .thenReturn(List.of(grant("cfg-vision", true, ClientAppModelConfigGrantService.STATUS_ENABLED)));

        Optional<String> result = service.tryResolveEffectiveModelConfigId(
                "tenant-1", "capp-1", null, LlmModelCategory.VISION);

        assertEquals(Optional.of("cfg-vision"), result);
    }

    @Test
    void tryResolveEffectiveModelConfigId_returns_empty_when_default_missing() {
        when(grantRepository.findByClientAppIdAndStatusAndIsDefaultTrueOrderByUpdatedAtDesc(
                "capp-1", ClientAppModelConfigGrantService.STATUS_ENABLED)).thenReturn(List.of());

        Optional<String> result = service.tryResolveEffectiveModelConfigId(
                "tenant-1", "capp-1", null, LlmModelCategory.VISION);

        assertTrue(result.isEmpty());
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
        entity.setUpstreamSystemId("ups-1");
        entity.setStatus(ClientAppService.STATUS_ACTIVE);
        return entity;
    }

    private LlmModelConfigDTO model(String id, String tenantId, String workerBackend) {
        return model(id, tenantId, workerBackend, null);
    }

    private LlmModelConfigDTO model(String id, String tenantId, String workerBackend, LlmModelCategory category) {
        return model(id, tenantId, workerBackend, category, ResourceOwnerType.PLATFORM, "platform");
    }

    private LlmModelConfigDTO model(String id,
                                    String tenantId,
                                    String workerBackend,
                                    LlmModelCategory category,
                                    ResourceOwnerType ownerType,
                                    String ownerId) {
        LlmModelConfigDTO dto = new LlmModelConfigDTO();
        dto.setId(id);
        dto.setTenantId(tenantId);
        dto.setName(id + "-name");
        dto.setWorkerBackend(workerBackend);
        dto.setCategory(category);
        dto.setOwnerType(ownerType);
        dto.setOwnerId(ownerId);
        dto.setEnabled(true);
        return dto;
    }
}
