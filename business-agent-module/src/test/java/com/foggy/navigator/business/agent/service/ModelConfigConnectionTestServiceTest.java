package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.form.ClientAppModelConfigForm;
import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggy.navigator.common.util.ProviderRouteRegistry;
import com.foggy.navigator.spi.config.LlmModelManager;
import com.foggy.navigator.spi.config.WorkerBackendConnectionTester;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class ModelConfigConnectionTestServiceTest {

    private final LlmModelManager llmModelManager = mock(LlmModelManager.class);
    private final WorkerBackendConnectionTester appServerTester = mock(WorkerBackendConnectionTester.class);
    private final ModelConfigConnectionTestService service;

    ModelConfigConnectionTestServiceTest() {
        when(appServerTester.getWorkerBackend())
                .thenReturn(ProviderRouteRegistry.BACKEND_OPENAI_CODEX_APP_SERVER);
        service = new ModelConfigConnectionTestService(llmModelManager, List.of(appServerTester));
    }

    @Test
    void appServerTestUsesSelectedWorkerAndGpt56Model() {
        ClientAppModelConfigForm form = new ClientAppModelConfigForm();
        form.setWorkerBackend("OPENAI_CODEX_APP_SERVER");
        form.setWorkerId("worker-1");
        form.setModelName("gpt-5.6-sol:ultra");
        when(appServerTester.testConnection("actor-1", "tenant-1", "worker-1", "gpt-5.6-sol:ultra"))
                .thenReturn("Codex App Server READY");

        assertEquals("Codex App Server READY", service.test("actor-1", "tenant-1", form));
    }

    @Test
    void appServerTestRequiresWorker() {
        ClientAppModelConfigForm form = new ClientAppModelConfigForm();
        form.setWorkerBackend("OPENAI_CODEX_APP_SERVER");
        form.setModelName("codex-latest");

        assertThrows(IllegalArgumentException.class, () -> service.test("actor-1", "tenant-1", form));
        verify(appServerTester, never()).testConnection(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void sdkTestRejectsUltraBeforeWorkerProbe() {
        WorkerBackendConnectionTester sdkTester = mock(WorkerBackendConnectionTester.class);
        when(sdkTester.getWorkerBackend()).thenReturn(ProviderRouteRegistry.BACKEND_OPENAI_CODEX);
        ModelConfigConnectionTestService sdkService =
                new ModelConfigConnectionTestService(llmModelManager, List.of(sdkTester));
        ClientAppModelConfigForm form = new ClientAppModelConfigForm();
        form.setWorkerBackend("OPENAI_CODEX");
        form.setWorkerId("worker-1");
        form.setModelName("codex-ultra");

        assertThrows(IllegalArgumentException.class, () -> sdkService.test("actor-1", "tenant-1", form));
        verify(sdkTester, never()).testConnection(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void savedAppServerTestUsesFirstAllowedWorker() {
        LlmModelConfigDTO model = new LlmModelConfigDTO();
        model.setId("model-1");
        model.setWorkerBackend("OPENAI_CODEX_APP_SERVER");
        model.setModelName("codex-latest");
        model.setAllowedWorkerIds(List.of("worker-allowed"));
        when(appServerTester.testConnection("actor-1", "tenant-1", "worker-allowed", "codex-latest"))
                .thenReturn("READY");

        assertEquals("READY", service.testSaved("actor-1", "tenant-1", model, null));
        verify(llmModelManager).getDecryptedApiKey("model-1");
    }

    @Test
    void genericTestStillRequiresManagedCredentials() {
        ClientAppModelConfigForm form = new ClientAppModelConfigForm();
        form.setWorkerBackend("LANGGRAPH_BIZ");
        form.setModelName("gpt-test");

        assertThrows(IllegalArgumentException.class, () -> service.test("actor-1", "tenant-1", form));
        verify(llmModelManager, never()).testConnection(anyString(), anyString(), anyString());
    }
}
