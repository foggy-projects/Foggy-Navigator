package com.foggy.navigator.metadata.query.config.service;

import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggy.navigator.common.entity.LlmModelConfigEntity;
import com.foggy.navigator.common.enums.ModelAccessScope;
import com.foggy.navigator.common.enums.LlmModelCategory;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import com.foggy.navigator.common.form.LlmModelConfigForm;
import com.foggy.navigator.common.form.LlmModelConfigOwnerRepairForm;
import com.foggy.navigator.common.security.CredentialEncryptor;
import com.foggy.navigator.common.util.ProviderRouteRegistry;
import com.foggy.navigator.metadata.query.config.repository.AgentModelOverrideRepository;
import com.foggy.navigator.metadata.query.config.repository.LlmModelConfigRepository;
import com.foggy.navigator.metadata.query.config.repository.ModelWorkerAccessRepository;
import com.foggy.navigator.spi.config.WorkerBackendConnectionTester;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.web.client.RestTemplateBuilder;

import java.util.List;
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
                llmModelRepo, overrideRepo, workerAccessRepo, credentialEncryptor,
                restTemplateBuilder, java.util.List.of());
    }

    @Test
    void saveModelConfigRejectsUltraForSdkBackendBeforePersistence() {
        LlmModelConfigForm form = new LlmModelConfigForm();
        form.setName("SDK Ultra");
        form.setCategory(LlmModelCategory.CODING);
        form.setModelName("codex-latest:ultra");
        form.setAvailableModels(List.of("codex-latest:max"));
        form.setWorkerBackend(ProviderRouteRegistry.BACKEND_OPENAI_CODEX);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.saveModelConfig(
                        "tenant-1", form, ResourceOwnerType.PLATFORM, "platform",
                        ResourceOwnerType.PLATFORM, "admin-1", null));

        assertTrue(error.getMessage().startsWith("CODEX_ULTRA_APP_SERVER_REQUIRED"));
        verify(llmModelRepo, never()).save(any());
    }

    @Test
    void updateModelConfigRejectsUnsupportedAppServerUltraCatalog() {
        LlmModelConfigEntity entity = model(
                "app-ultra", ProviderRouteRegistry.BACKEND_OPENAI_CODEX_APP_SERVER,
                ModelAccessScope.GLOBAL);
        when(llmModelRepo.findById("app-ultra")).thenReturn(Optional.of(entity));
        LlmModelConfigForm form = new LlmModelConfigForm();
        form.setModelName("codex-latest:max");
        form.setAvailableModels(List.of("codex-luna:ultra"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.updateModelConfig("app-ultra", form));

        assertTrue(error.getMessage().startsWith("CODEX_APP_SERVER_MODEL_UNSUPPORTED"));
        verify(llmModelRepo, never()).save(any());
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
    void updateModelConfig_clearsStoredApiKeyWhenRequested() {
        LlmModelConfigEntity entity = new LlmModelConfigEntity();
        entity.setId("cfg-clear-key");
        entity.setTenantId("tenant-1");
        entity.setCategory(LlmModelCategory.CODING);
        entity.setWorkerBackend("OPENAI_CODEX");
        entity.setBaseUrl("https://api.openai.example/v1");
        entity.setApiKey("encrypted-old-key");
        entity.setModelName("gpt-5.4");

        LlmModelConfigForm form = new LlmModelConfigForm();
        form.setClearApiKey(true);

        when(llmModelRepo.findById("cfg-clear-key")).thenReturn(Optional.of(entity));

        service.updateModelConfig("cfg-clear-key", form);

        assertNull(entity.getApiKey());
        assertEquals("https://api.openai.example/v1", entity.getBaseUrl());
        verify(llmModelRepo).save(entity);
        verifyNoInteractions(credentialEncryptor);
    }

    @Test
    void updateModelConfig_clearsStoredApiKeyForNormalizedSubscriptionBackend() {
        LlmModelConfigEntity entity = new LlmModelConfigEntity();
        entity.setId("cfg-normalized");
        entity.setTenantId("tenant-1");
        entity.setCategory(LlmModelCategory.CODING);
        entity.setWorkerBackend("OPENAI_CODEX");
        entity.setBaseUrl(null);
        entity.setApiKey("encrypted-old-key");
        entity.setModelName("gpt-5.4");

        LlmModelConfigForm form = new LlmModelConfigForm();
        form.setWorkerBackend("openai-codex");
        form.setBaseUrl("");
        form.setModelName("gpt-5.4");

        when(llmModelRepo.findById("cfg-normalized")).thenReturn(Optional.of(entity));

        service.updateModelConfig("cfg-normalized", form);

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

    @Test
    void updateModelConfig_persistsRuntimeBudgetFields() {
        LlmModelConfigEntity entity = new LlmModelConfigEntity();
        entity.setId("cfg-budget");
        entity.setTenantId("tenant-1");
        entity.setCategory(LlmModelCategory.GENERAL);
        entity.setWorkerBackend("LANGGRAPH_BIZ");
        entity.setBaseUrl("https://llm.example/v1");
        entity.setModelName("qwen3.5-plus");

        LlmModelConfigForm form = new LlmModelConfigForm();
        form.setRuntimeBudgetPresetKey(" generic.128k ");
        form.setRuntimeBudgetOverrideJson(" {\"maxOutputTokens\":6144} ");

        when(llmModelRepo.findById("cfg-budget")).thenReturn(Optional.of(entity));

        service.updateModelConfig("cfg-budget", form);

        assertEquals("generic.128k", entity.getRuntimeBudgetPresetKey());
        assertEquals("{\"maxOutputTokens\":6144}", entity.getRuntimeBudgetOverrideJson());
        verify(llmModelRepo).save(entity);
    }

    @Test
    void getModelConfig_exposesRuntimeBudgetFields() {
        LlmModelConfigEntity entity = new LlmModelConfigEntity();
        entity.setId("cfg-budget");
        entity.setTenantId("tenant-1");
        entity.setName("biz model");
        entity.setCategory(LlmModelCategory.GENERAL);
        entity.setWorkerBackend("LANGGRAPH_BIZ");
        entity.setBaseUrl("https://llm.example/v1");
        entity.setApiKey("encrypted-key");
        entity.setModelName("qwen3.5-plus");
        entity.setIsDefault(false);
        entity.setRuntimeBudgetPresetKey("generic.128k");
        entity.setRuntimeBudgetOverrideJson("{\"maxOutputTokens\":6144}");

        when(llmModelRepo.findById("cfg-budget")).thenReturn(Optional.of(entity));

        LlmModelConfigDTO dto = service.getModelConfig("cfg-budget").orElseThrow();

        assertEquals("generic.128k", dto.getRuntimeBudgetPresetKey());
        assertEquals("{\"maxOutputTokens\":6144}", dto.getRuntimeBudgetOverrideJson());
    }

    @Test
    void repairModelConfigOwnerUpdatesOnlyOwnershipMetadata() {
        LlmModelConfigEntity entity = new LlmModelConfigEntity();
        entity.setId("cfg-tenant-138");
        entity.setTenantId("nav_tms_138");
        entity.setName("legacy model");
        entity.setCategory(LlmModelCategory.CODING);
        entity.setBaseUrl("https://llm.example/v1");
        entity.setApiKey("encrypted-key");
        entity.setModelName("qwen-coder");
        entity.setOwnerType(ResourceOwnerType.CLIENT_APP);
        entity.setOwnerId("old-client");
        entity.setEnabled(false);

        LlmModelConfigOwnerRepairForm form = new LlmModelConfigOwnerRepairForm();
        form.setTenantId("nav_tms_138");
        form.setOwnerType(ResourceOwnerType.UPSTREAM_SYSTEM);
        form.setOwnerId("TMS");
        form.setEnabled(true);

        when(llmModelRepo.findById("cfg-tenant-138")).thenReturn(Optional.of(entity));
        when(llmModelRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.repairModelConfigOwner("cfg-tenant-138", form);

        assertEquals("cfg-tenant-138", result.getModelConfigId());
        assertEquals("nav_tms_138", result.getTenantId());
        assertEquals(ResourceOwnerType.CLIENT_APP, result.getPreviousOwnerType());
        assertEquals("old-client", result.getPreviousOwnerId());
        assertEquals(ResourceOwnerType.UPSTREAM_SYSTEM, result.getOwnerType());
        assertEquals("TMS", result.getOwnerId());
        assertFalse(result.getPreviousEnabled());
        assertTrue(result.getEnabled());
        verify(llmModelRepo).save(argThat(saved ->
                saved.getOwnerType() == ResourceOwnerType.UPSTREAM_SYSTEM
                        && "TMS".equals(saved.getOwnerId())
                        && Boolean.TRUE.equals(saved.getEnabled())
                        && "https://llm.example/v1".equals(saved.getBaseUrl())
                        && "encrypted-key".equals(saved.getApiKey())
                        && "qwen-coder".equals(saved.getModelName())));
    }

    @Test
    void repairModelConfigOwnerRejectsTenantMismatch() {
        LlmModelConfigEntity entity = new LlmModelConfigEntity();
        entity.setId("cfg-tenant-138");
        entity.setTenantId("nav_tms_138");

        LlmModelConfigOwnerRepairForm form = new LlmModelConfigOwnerRepairForm();
        form.setTenantId("nav_tms_110");
        form.setOwnerType(ResourceOwnerType.UPSTREAM_SYSTEM);
        form.setOwnerId("TMS");

        when(llmModelRepo.findById("cfg-tenant-138")).thenReturn(Optional.of(entity));

        assertThrows(IllegalArgumentException.class,
                () -> service.repairModelConfigOwner("cfg-tenant-138", form));
        verify(llmModelRepo, never()).save(any());
    }

    @Test
    void repairModelConfigOwnerNormalizesPlatformOwner() {
        LlmModelConfigEntity entity = new LlmModelConfigEntity();
        entity.setId("cfg-platform");
        entity.setTenantId("nav_tms_138");
        entity.setOwnerType(ResourceOwnerType.CLIENT_APP);
        entity.setOwnerId("client-1");

        LlmModelConfigOwnerRepairForm form = new LlmModelConfigOwnerRepairForm();
        form.setOwnerType(ResourceOwnerType.PLATFORM);

        when(llmModelRepo.findById("cfg-platform")).thenReturn(Optional.of(entity));
        when(llmModelRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.repairModelConfigOwner("cfg-platform", form);

        assertEquals(ResourceOwnerType.PLATFORM, result.getOwnerType());
        assertEquals("platform", result.getOwnerId());
        verify(llmModelRepo).save(argThat(saved ->
                saved.getOwnerType() == ResourceOwnerType.PLATFORM
                        && "platform".equals(saved.getOwnerId())));
    }

    @Test
    void listModelConfigsForWorkerFiltersSdkAndAppServerByIndependentConfigurationSupport() {
        WorkerBackendConnectionTester sdkTester = mock(WorkerBackendConnectionTester.class);
        WorkerBackendConnectionTester appServerTester = mock(WorkerBackendConnectionTester.class);
        when(sdkTester.getWorkerBackend()).thenReturn(ProviderRouteRegistry.BACKEND_OPENAI_CODEX);
        when(appServerTester.getWorkerBackend())
                .thenReturn(ProviderRouteRegistry.BACKEND_OPENAI_CODEX_APP_SERVER);
        when(sdkTester.supportsWorkerConfiguration("worker-1", "gpt-5.6-codex"))
                .thenReturn(true);
        when(appServerTester.supportsWorkerConfiguration("worker-1", "gpt-5.6-codex"))
                .thenReturn(false);
        service = serviceWith(List.of(sdkTester, appServerTester));

        LlmModelConfigEntity sdk = model(
                "sdk", ProviderRouteRegistry.BACKEND_OPENAI_CODEX, ModelAccessScope.GLOBAL);
        LlmModelConfigEntity appServer = model(
                "app", ProviderRouteRegistry.BACKEND_OPENAI_CODEX_APP_SERVER, ModelAccessScope.GLOBAL);
        when(llmModelRepo.findByTenantIdOrderBySortOrderAscCreatedAtAsc("tenant-1"))
                .thenReturn(List.of(sdk, appServer));

        List<LlmModelConfigDTO> result = service.listModelConfigsForWorker("tenant-1", "worker-1");

        assertEquals(List.of("sdk"), result.stream().map(LlmModelConfigDTO::getId).toList());
        verify(sdkTester).supportsWorkerConfiguration("worker-1", "gpt-5.6-codex");
        verify(appServerTester).supportsWorkerConfiguration("worker-1", "gpt-5.6-codex");
    }

    @Test
    void listKeepsSupportedAppServerConfigWhileExecutionRemainsUnavailable() {
        WorkerBackendConnectionTester appServerTester = mock(WorkerBackendConnectionTester.class);
        when(appServerTester.getWorkerBackend())
                .thenReturn(ProviderRouteRegistry.BACKEND_OPENAI_CODEX_APP_SERVER);
        when(appServerTester.supportsWorkerConfiguration("worker-1", "gpt-5.6-codex"))
                .thenReturn(true);
        when(appServerTester.supportsWorker("worker-1", "gpt-5.6-codex"))
                .thenReturn(false);
        service = serviceWith(List.of(appServerTester));
        LlmModelConfigEntity appServer = model(
                "app", ProviderRouteRegistry.BACKEND_OPENAI_CODEX_APP_SERVER,
                ModelAccessScope.GLOBAL);
        when(llmModelRepo.findByTenantIdOrderBySortOrderAscCreatedAtAsc("tenant-1"))
                .thenReturn(List.of(appServer));
        when(llmModelRepo.findById("app")).thenReturn(Optional.of(appServer));

        List<LlmModelConfigDTO> result =
                service.listModelConfigsForWorker("tenant-1", "worker-1");

        assertEquals(List.of("app"), result.stream().map(LlmModelConfigDTO::getId).toList());
        assertThrows(IllegalArgumentException.class,
                () -> service.validateModelAccessForWorker("app", "worker-1"));
        verify(appServerTester).supportsWorkerConfiguration("worker-1", "gpt-5.6-codex");
        verify(appServerTester).supportsWorker("worker-1", "gpt-5.6-codex");
    }

    @Test
    void listModelConfigsForWorkerFiltersUnsupportedAppServerVariantsAndKeepsBaseModel() {
        WorkerBackendConnectionTester appServerTester = mock(WorkerBackendConnectionTester.class);
        when(appServerTester.getWorkerBackend())
                .thenReturn(ProviderRouteRegistry.BACKEND_OPENAI_CODEX_APP_SERVER);
        when(appServerTester.supportsWorkerConfiguration("worker-1", "codex-terra:high"))
                .thenReturn(true);
        when(appServerTester.supportsWorkerConfiguration("worker-1", "codex-terra:ultra"))
                .thenReturn(false);
        service = serviceWith(List.of(appServerTester));

        LlmModelConfigEntity appConfig = model(
                "app", ProviderRouteRegistry.BACKEND_OPENAI_CODEX_APP_SERVER,
                ModelAccessScope.GLOBAL);
        appConfig.setModelName("codex-terra:high");
        appConfig.setAvailableModels("[\"codex-terra:high\",\"codex-terra:ultra\"]");
        when(llmModelRepo.findByTenantIdOrderBySortOrderAscCreatedAtAsc("tenant-1"))
                .thenReturn(List.of(appConfig));

        List<LlmModelConfigDTO> result = service.listModelConfigsForWorker(
                "tenant-1", "worker-1");

        assertEquals(1, result.size());
        assertEquals(List.of("codex-terra:high"), result.get(0).getAvailableModels());
        verify(appServerTester).supportsWorkerConfiguration("worker-1", "codex-terra:high");
        verify(appServerTester).supportsWorkerConfiguration("worker-1", "codex-terra:ultra");
    }

    @Test
    void validateModelAccessForWorkerRejectsAppServerModelWithoutEndpointCapability() {
        WorkerBackendConnectionTester appServerTester = mock(WorkerBackendConnectionTester.class);
        when(appServerTester.getWorkerBackend())
                .thenReturn(ProviderRouteRegistry.BACKEND_OPENAI_CODEX_APP_SERVER);
        when(appServerTester.supportsWorker("worker-1", "gpt-5.6-codex")).thenReturn(false);
        service = serviceWith(List.of(appServerTester));
        when(llmModelRepo.findById("app"))
                .thenReturn(Optional.of(model(
                        "app", ProviderRouteRegistry.BACKEND_OPENAI_CODEX_APP_SERVER,
                        ModelAccessScope.GLOBAL)));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.validateModelAccessForWorker("app", "worker-1"));

        assertEquals("WORKER_BACKEND_CAPABILITY_MISSING: Worker worker-1 does not provide "
                + ProviderRouteRegistry.BACKEND_OPENAI_CODEX_APP_SERVER, error.getMessage());
    }

    @Test
    void validateModelAccessForWorkerChecksSelectedAgentModelInsteadOfConfigBaseModel() {
        WorkerBackendConnectionTester appServerTester = mock(WorkerBackendConnectionTester.class);
        when(appServerTester.getWorkerBackend())
                .thenReturn(ProviderRouteRegistry.BACKEND_OPENAI_CODEX_APP_SERVER);
        when(appServerTester.supportsWorker("worker-1", "codex-terra:ultra")).thenReturn(false);
        service = serviceWith(List.of(appServerTester));
        LlmModelConfigEntity appConfig = model(
                "app", ProviderRouteRegistry.BACKEND_OPENAI_CODEX_APP_SERVER,
                ModelAccessScope.GLOBAL);
        appConfig.setModelName("codex-terra:high");
        when(llmModelRepo.findById("app")).thenReturn(Optional.of(appConfig));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.validateModelAccessForWorker(
                        "app", "worker-1", "codex-terra:ultra"));

        assertTrue(error.getMessage().startsWith("WORKER_BACKEND_CAPABILITY_MISSING"));
        verify(appServerTester).supportsWorker("worker-1", "codex-terra:ultra");
        verify(appServerTester, never()).supportsWorker("worker-1", "codex-terra:high");
    }

    @Test
    void restrictedModelAssignmentRejectsWorkerFromDifferentCodexBackend() {
        WorkerBackendConnectionTester sdkTester = mock(WorkerBackendConnectionTester.class);
        WorkerBackendConnectionTester appServerTester = mock(WorkerBackendConnectionTester.class);
        when(sdkTester.getWorkerBackend()).thenReturn(ProviderRouteRegistry.BACKEND_OPENAI_CODEX);
        when(appServerTester.getWorkerBackend())
                .thenReturn(ProviderRouteRegistry.BACKEND_OPENAI_CODEX_APP_SERVER);
        when(appServerTester.supportsWorkerConfiguration("sdk-only-worker", "codex-terra:ultra"))
                .thenReturn(false);
        service = serviceWith(List.of(sdkTester, appServerTester));
        when(llmModelRepo.findByTenantIdOrderBySortOrderAscCreatedAtAsc("tenant-1"))
                .thenReturn(List.of());

        LlmModelConfigForm form = new LlmModelConfigForm();
        form.setName("App Server");
        form.setCategory(LlmModelCategory.CODING);
        form.setModelName("codex-terra:ultra");
        form.setWorkerBackend(ProviderRouteRegistry.BACKEND_OPENAI_CODEX_APP_SERVER);
        form.setScope(ModelAccessScope.RESTRICTED);
        form.setAllowedWorkerIds(List.of("sdk-only-worker"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.saveModelConfig(
                        "tenant-1", form, ResourceOwnerType.PLATFORM, "platform",
                        ResourceOwnerType.PLATFORM, "user-1", null));

        assertTrue(error.getMessage().startsWith("WORKER_BACKEND_CAPABILITY_MISSING"));
        verify(workerAccessRepo, never()).save(any());
        verify(sdkTester, never())
                .supportsWorkerConfiguration("sdk-only-worker", "codex-terra:ultra");
    }

    @Test
    void capabilityMatchingCanonicalizesBackendAlias() {
        WorkerBackendConnectionTester appServerTester = mock(WorkerBackendConnectionTester.class);
        when(appServerTester.getWorkerBackend())
                .thenReturn(ProviderRouteRegistry.BACKEND_OPENAI_CODEX_APP_SERVER);
        when(appServerTester.supportsWorker("worker-1", "gpt-5.6-codex")).thenReturn(false);
        service = serviceWith(List.of(appServerTester));
        when(llmModelRepo.findById("app-alias"))
                .thenReturn(Optional.of(model(
                        "app-alias", "openai-codex-app-server", ModelAccessScope.GLOBAL)));

        assertThrows(IllegalArgumentException.class,
                () -> service.validateModelAccessForWorker("app-alias", "worker-1"));

        verify(appServerTester).supportsWorker("worker-1", "gpt-5.6-codex");
    }

    @Test
    void codexBackendsFailClosedWhenTheirCapabilityTesterIsUnavailable() {
        when(llmModelRepo.findById("app"))
                .thenReturn(Optional.of(model(
                        "app", ProviderRouteRegistry.BACKEND_OPENAI_CODEX_APP_SERVER,
                        ModelAccessScope.GLOBAL)));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.validateModelAccessForWorker("app", "worker-1"));

        assertTrue(error.getMessage().startsWith("WORKER_BACKEND_CAPABILITY_MISSING"));
    }

    private LlmModelManagerImpl serviceWith(List<WorkerBackendConnectionTester> testers) {
        return new LlmModelManagerImpl(
                llmModelRepo, overrideRepo, workerAccessRepo, credentialEncryptor,
                restTemplateBuilder, testers);
    }

    private LlmModelConfigEntity model(String id, String backend, ModelAccessScope scope) {
        LlmModelConfigEntity entity = new LlmModelConfigEntity();
        entity.setId(id);
        entity.setTenantId("tenant-1");
        entity.setName(id);
        entity.setCategory(LlmModelCategory.CODING);
        entity.setWorkerBackend(backend);
        entity.setModelName("gpt-5.6-codex");
        entity.setScope(scope);
        entity.setEnabled(true);
        entity.setIsDefault(false);
        return entity;
    }
}
