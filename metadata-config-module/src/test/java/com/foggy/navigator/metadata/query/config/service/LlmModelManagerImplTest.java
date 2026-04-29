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

    @Test
    void updateModelConfig_clearsStoredApiKeyForClaudeSubscriptionMode() {
        LlmModelConfigEntity entity = new LlmModelConfigEntity();
        entity.setId("cfg-2");
        entity.setTenantId("tenant-1");
        entity.setCategory(LlmModelCategory.CODING);
        entity.setWorkerBackend("CLAUDE_CODE");
        entity.setBaseUrl(null);
        entity.setApiKey("encrypted-old-key");
        entity.setModelName("opus");

        LlmModelConfigForm form = new LlmModelConfigForm();
        form.setWorkerBackend("CLAUDE_CODE");
        form.setBaseUrl("");
        form.setModelName("opus");

        when(llmModelRepo.findById("cfg-2")).thenReturn(Optional.of(entity));

        service.updateModelConfig("cfg-2", form);

        assertNull(entity.getApiKey());
        verify(llmModelRepo).save(entity);
        verifyNoInteractions(credentialEncryptor);
    }

    @Test
    void getModelConfig_hidesStaleApiKeyForClaudeSubscriptionMode() {
        LlmModelConfigEntity entity = new LlmModelConfigEntity();
        entity.setId("cfg-2");
        entity.setTenantId("tenant-1");
        entity.setName("claude-subscription");
        entity.setCategory(LlmModelCategory.CODING);
        entity.setWorkerBackend("CLAUDE_CODE");
        entity.setBaseUrl(null);
        entity.setApiKey("encrypted-old-key");
        entity.setModelName("opus");
        entity.setIsDefault(false);

        when(llmModelRepo.findById("cfg-2")).thenReturn(Optional.of(entity));

        LlmModelConfigDTO dto = service.getModelConfig("cfg-2").orElseThrow();

        assertFalse(Boolean.TRUE.equals(dto.getHasApiKey()));
    }

    @Test
    void getDecryptedApiKey_returnsNullForClaudeSubscriptionMode() {
        LlmModelConfigEntity entity = new LlmModelConfigEntity();
        entity.setId("cfg-3");
        entity.setTenantId("tenant-1");
        entity.setWorkerBackend("CLAUDE_CODE");
        entity.setBaseUrl(null);
        entity.setApiKey("encrypted-old-key");
        entity.setModelName("sonnet");

        when(llmModelRepo.findById("cfg-3")).thenReturn(Optional.of(entity));

        assertNull(service.getDecryptedApiKey("cfg-3"));
        verifyNoInteractions(credentialEncryptor);
    }

    @Test
    void updateModelConfig_clearsStoredApiKeyForLangGraphSubscriptionMode() {
        LlmModelConfigEntity entity = new LlmModelConfigEntity();
        entity.setId("cfg-langgraph");
        entity.setTenantId("tenant-1");
        entity.setCategory(LlmModelCategory.CODING);
        entity.setWorkerBackend("LANGGRAPH_BIZ");
        entity.setBaseUrl(null);
        entity.setApiKey("encrypted-old-key");
        entity.setModelName("biz-default");

        LlmModelConfigForm form = new LlmModelConfigForm();
        form.setWorkerBackend("LANGGRAPH_BIZ");
        form.setBaseUrl("");
        form.setModelName("biz-default");

        when(llmModelRepo.findById("cfg-langgraph")).thenReturn(Optional.of(entity));

        service.updateModelConfig("cfg-langgraph", form);

        assertNull(entity.getApiKey());
        verify(llmModelRepo).save(entity);
        verifyNoInteractions(credentialEncryptor);
    }
}
