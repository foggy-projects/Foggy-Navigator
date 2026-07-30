package com.foggy.navigator.codex.worker.spi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.codex.worker.client.CodexWorkerClientFactory;
import com.foggy.navigator.codex.worker.service.CodexTaskService;
import com.foggy.navigator.spi.worker.WorkerManagementFacade;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class CodexWorkerFacadeRuntimeClosureProviderTest {

    @Test
    void exactCodexBizProviderSupportsTypedClosureAndCompletionReadiness() {
        CodexWorkerFacadeImpl provider = new CodexWorkerFacadeImpl(
                mock(WorkerManagementFacade.class),
                mock(CodexWorkerClientFactory.class),
                mock(CodexTaskService.class),
                new ObjectMapper());

        assertTrue(provider.supports(CodexTaskService.CODEX_BIZ_PROVIDER_TYPE));
        assertTrue(provider.supportsCompletionReadiness(
                CodexTaskService.CODEX_BIZ_PROVIDER_TYPE));
    }
}
