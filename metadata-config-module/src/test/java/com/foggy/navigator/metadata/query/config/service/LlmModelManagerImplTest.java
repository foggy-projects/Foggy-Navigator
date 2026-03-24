package com.foggy.navigator.metadata.query.config.service;

import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggy.navigator.common.entity.LlmModelConfigEntity;
import com.foggy.navigator.common.enums.LlmModelCategory;
import com.foggy.navigator.common.form.LlmModelConfigForm;
import com.foggy.navigator.common.security.CredentialEncryptor;
import com.foggy.navigator.metadata.query.config.repository.AgentModelOverrideRepository;
import com.foggy.navigator.metadata.query.config.repository.LlmModelConfigRepository;
import com.foggy.navigator.metadata.query.config.repository.ModelWorkerAccessRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.web.client.RestTemplateBuilder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LlmModelManagerImplTest {

    @Mock
    private LlmModelConfigRepository llmModelRepo;
    @Mock
    private AgentModelOverrideRepository overrideRepo;
    @Mock
    private ModelWorkerAccessRepository workerAccessRepo;
    @Mock
    private CredentialEncryptor credentialEncryptor;
    @Mock
    private RestTemplateBuilder restTemplateBuilder;

    private LlmModelManagerImpl service;

    @BeforeEach
    void setUp() {
        service = new LlmModelManagerImpl(
                llmModelRepo, overrideRepo, workerAccessRepo, credentialEncryptor, restTemplateBuilder);
    }

    @Test
    void updateModelConfig_clearsStoredApiKeyForCodexSubscriptionMode() {
        LlmModelConfigEntity entity = new LlmModelConfigEntity();
        entity.setId("cfg-1");
        entity.setTenantId("tenant-1");
        entity.setCategory(LlmModelCategory.CODING);
        entity.setWorkerBackend("OPENAI_CODEX");
        entity.setBaseUrl(null);
        entity.setApiKey("encrypted-old-key");
        entity.setModelName("gpt-5.4");

        LlmModelConfigForm form = new LlmModelConfigForm();
        form.setWorkerBackend("OPENAI_CODEX");
        form.setBaseUrl("");
        form.setModelName("gpt-5.4");

        when(llmModelRepo.findById("cfg-1")).thenReturn(Optional.of(entity));

        service.updateModelConfig("cfg-1", form);

        assertNull(entity.getApiKey());
        verify(llmModelRepo).save(entity);
        verifyNoInteractions(credentialEncryptor);
    }

    @Test
    void getModelConfig_hidesStaleApiKeyForCodexSubscriptionMode() {
        LlmModelConfigEntity entity = new LlmModelConfigEntity();
        entity.setId("cfg-1");
        entity.setTenantId("tenant-1");
        entity.setName("codex-subscription");
        entity.setCategory(LlmModelCategory.CODING);
        entity.setWorkerBackend("OPENAI_CODEX");
        entity.setBaseUrl(null);
        entity.setApiKey("encrypted-old-key");
        entity.setModelName("gpt-5.4");
        entity.setIsDefault(false);

        when(llmModelRepo.findById("cfg-1")).thenReturn(Optional.of(entity));

        LlmModelConfigDTO dto = service.getModelConfig("cfg-1").orElseThrow();

        assertFalse(Boolean.TRUE.equals(dto.getHasApiKey()));
    }
}
