package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.dto.UpstreamClientAppAdminPrincipal;
import com.foggy.navigator.business.agent.model.form.ClientAppModelConfigForm;
import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggy.navigator.common.enums.LlmModelCategory;
import com.foggy.navigator.common.enums.ModelAccessScope;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import com.foggy.navigator.common.form.LlmModelConfigForm;
import com.foggy.navigator.spi.config.LlmModelManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UpstreamAdminModelConfigServiceTest {

    private final LlmModelManager llmModelManager = mock(LlmModelManager.class);
    private final UpstreamAdminModelConfigService service = new UpstreamAdminModelConfigService(llmModelManager);

    @Test
    void create_savesUpstreamSystemOwnedModel() {
        UpstreamClientAppAdminPrincipal principal = principal();
        ClientAppModelConfigForm form = new ClientAppModelConfigForm();
        form.setName("Shared GPT");
        form.setBaseUrl("https://llm.example/v1");
        form.setModelName("gpt-test");
        form.setApiKey("secret");
        form.setEnvVars(Map.of(" NAVI_LLM_PROVIDER ", " openai "));

        when(llmModelManager.saveModelConfig(
                eq("tenant-1"),
                any(LlmModelConfigForm.class),
                eq(ResourceOwnerType.UPSTREAM_SYSTEM),
                eq("ups-1"),
                eq(ResourceOwnerType.UPSTREAM_SYSTEM),
                eq("ups-1"),
                eq("cred-1")))
                .thenReturn("model-1");
        when(llmModelManager.getModelConfig("model-1")).thenReturn(Optional.of(model("model-1", "ups-1")));

        LlmModelConfigDTO result = service.create("tenant-1", principal, form);

        assertEquals("model-1", result.getId());
        ArgumentCaptor<LlmModelConfigForm> formCaptor = ArgumentCaptor.forClass(LlmModelConfigForm.class);
        verify(llmModelManager).saveModelConfig(
                eq("tenant-1"),
                formCaptor.capture(),
                eq(ResourceOwnerType.UPSTREAM_SYSTEM),
                eq("ups-1"),
                eq(ResourceOwnerType.UPSTREAM_SYSTEM),
                eq("ups-1"),
                eq("cred-1"));
        LlmModelConfigForm savedForm = formCaptor.getValue();
        assertEquals("Shared GPT", savedForm.getName());
        assertEquals(LlmModelCategory.GENERAL, savedForm.getCategory());
        assertEquals("https://llm.example/v1", savedForm.getBaseUrl());
        assertEquals("gpt-test", savedForm.getModelName());
        assertEquals("secret", savedForm.getApiKey());
        assertEquals(ModelAccessScope.GLOBAL, savedForm.getScope());
        assertEquals(ClientAppModelConfigGrantService.LANGGRAPH_BIZ_BACKEND, savedForm.getWorkerBackend());
        assertEquals("openai", savedForm.getEnvVars().get("NAVI_LLM_PROVIDER"));
    }

    @Test
    void update_rejectsModelOwnedByAnotherUpstreamSystem() {
        ClientAppModelConfigForm form = new ClientAppModelConfigForm();
        form.setName("Changed");
        when(llmModelManager.getModelConfig("model-1")).thenReturn(Optional.of(model("model-1", "ups-2")));

        assertThrows(IllegalArgumentException.class,
                () -> service.update("tenant-1", principal(), "model-1", form));
        verify(llmModelManager, never()).updateModelConfig(anyString(), any());
    }

    @Test
    void list_returnsOnlyCurrentUpstreamSystemModels() {
        when(llmModelManager.listModelConfigs("tenant-1")).thenReturn(List.of(
                model("model-1", "ups-1"),
                model("model-2", "ups-2"),
                platformModel("model-3")));

        List<LlmModelConfigDTO> result = service.list("tenant-1", principal());

        assertEquals(1, result.size());
        assertEquals("model-1", result.get(0).getId());
    }

    private UpstreamClientAppAdminPrincipal principal() {
        return UpstreamClientAppAdminPrincipal.builder()
                .credentialId("cred-1")
                .upstreamSystemId("ups-1")
                .authorizedTenantIds(Set.of("tenant-1"))
                .build();
    }

    private LlmModelConfigDTO model(String id, String ownerId) {
        LlmModelConfigDTO dto = new LlmModelConfigDTO();
        dto.setId(id);
        dto.setTenantId("tenant-1");
        dto.setName("Model " + id);
        dto.setOwnerType(ResourceOwnerType.UPSTREAM_SYSTEM);
        dto.setOwnerId(ownerId);
        return dto;
    }

    private LlmModelConfigDTO platformModel(String id) {
        LlmModelConfigDTO dto = model(id, "platform");
        dto.setOwnerType(ResourceOwnerType.PLATFORM);
        return dto;
    }
}
