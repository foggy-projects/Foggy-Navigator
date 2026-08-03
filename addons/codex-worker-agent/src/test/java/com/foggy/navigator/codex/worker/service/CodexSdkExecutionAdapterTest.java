package com.foggy.navigator.codex.worker.service;

import com.foggy.navigator.codex.worker.client.CodexWorkerClient;
import com.foggy.navigator.codex.worker.client.CodexWorkerClientFactory;
import com.foggy.navigator.codex.worker.model.CodexRuntimeBinding;
import com.foggy.navigator.codex.worker.model.CodexRuntimeType;
import com.foggy.navigator.common.model.CodexConfig;
import com.foggy.navigator.spi.worker.WorkerManagementFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CodexSdkExecutionAdapterTest {

    private WorkerManagementFacade workerManagementFacade;
    private CodexWorkerClientFactory clientFactory;
    private CodexWorkerClient client;
    private CodexSdkExecutionAdapter adapter;

    @BeforeEach
    void setUp() {
        workerManagementFacade = mock(WorkerManagementFacade.class);
        clientFactory = mock(CodexWorkerClientFactory.class);
        client = mock(CodexWorkerClient.class);
        adapter = new CodexSdkExecutionAdapter(workerManagementFacade, clientFactory);
    }

    @Test
    void hasOnlyTheFrozenConfigurationAndClientFactoryDependencies() {
        assertEquals(List.of(WorkerManagementFacade.class, CodexWorkerClientFactory.class),
                List.of(CodexSdkExecutionAdapter.class.getDeclaredConstructors()[0]
                        .getParameterTypes()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"codex-worker", "codex-biz-worker"})
    void bindsOnlyExactSdkProviderIdentityThroughFullThreeArgumentClientFactory(
            String providerType) {
        CodexRuntimeBinding runtime = CodexRuntimeBinding.legacySdk("worker-1");
        when(workerManagementFacade.getCodexConfig("worker-1"))
                .thenReturn(CodexConfig.builder()
                        .baseUrl("http://localhost:3051")
                        .authToken("worker-token")
                        .build());
        when(clientFactory.getOrCreate(
                "worker-1:codex", "http://localhost:3051", "worker-token"))
                .thenReturn(client);

        CodexSdkExecutionAdapter.SdkExecution execution =
                adapter.bind(providerType, "worker-1", runtime);

        assertEquals(providerType, execution.providerType());
        assertEquals("worker-1", execution.workerId());
        assertSame(runtime, execution.runtime());
        assertSame(client, adapter.client(execution));
        verify(workerManagementFacade).getCodexConfig("worker-1");
        verify(clientFactory).getOrCreate(
                "worker-1:codex", "http://localhost:3051", "worker-token");
    }

    @Test
    void rejectsUnknownAppServerAndNormalizedProviderAliasesBeforeConfiguration() {
        CodexRuntimeBinding runtime = CodexRuntimeBinding.legacySdk("worker-1");

        assertThrows(IllegalArgumentException.class,
                () -> adapter.bind(null, "worker-1", runtime));
        for (String providerType : List.of(
                "codex-app-server-worker", "CODEX-WORKER", " codex-worker ", "unknown")) {
            assertThrows(IllegalArgumentException.class,
                    () -> adapter.bind(providerType, "worker-1", runtime));
        }

        verifyNoInteractions(workerManagementFacade, clientFactory);
    }

    @Test
    void rejectsNonSdkOrInexactLegacyAffinityBeforeConfiguration() {
        CodexRuntimeBinding appServer = CodexRuntimeBinding.builder()
                .runtimeId("app-main")
                .runtimeType(CodexRuntimeType.APP_SERVER)
                .workerId("worker-1")
                .build();
        CodexRuntimeBinding wrongRuntimeId = CodexRuntimeBinding.builder()
                .runtimeId("legacy-sdk:worker-1:other")
                .runtimeType(CodexRuntimeType.SDK_EXEC)
                .workerId("worker-1")
                .build();
        CodexRuntimeBinding wrongWorker = CodexRuntimeBinding.builder()
                .runtimeId("legacy-sdk:worker-1")
                .runtimeType(CodexRuntimeType.SDK_EXEC)
                .workerId("worker-2")
                .build();

        assertThrows(IllegalStateException.class,
                () -> adapter.bind("codex-worker", "worker-1", null));
        for (CodexRuntimeBinding runtime : List.of(appServer, wrongRuntimeId, wrongWorker)) {
            assertThrows(IllegalStateException.class,
                    () -> adapter.bind("codex-worker", "worker-1", runtime));
        }

        verifyNoInteractions(workerManagementFacade, clientFactory);
    }

    @Test
    void missingOrBlankConfigurationFailsBeforeClientOrProviderEffect() {
        when(workerManagementFacade.getCodexConfig("worker-1"))
                .thenReturn(null)
                .thenReturn(CodexConfig.builder().baseUrl("  ").build());
        CodexRuntimeBinding runtime = CodexRuntimeBinding.legacySdk("worker-1");

        assertThrows(IllegalStateException.class,
                () -> adapter.bind("codex-worker", "worker-1", runtime));
        assertThrows(IllegalStateException.class,
                () -> adapter.bind("codex-biz-worker", "worker-1", runtime));

        verify(workerManagementFacade, times(2)).getCodexConfig("worker-1");
        verifyNoInteractions(clientFactory, client);
    }

    @Test
    void forwardsTheCompleteRequestMapByTheSameReferenceWithoutMutation() {
        CodexSdkExecutionAdapter.SdkExecution execution = bindSdk();
        Map<String, Object> businessContext = new LinkedHashMap<>();
        businessContext.put("task_scoped_token", "token-1");
        Map<String, Object> outputSchema = new LinkedHashMap<>();
        outputSchema.put("type", "object");
        List<String> directories = List.of("/workspace/shared");
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("codex_home_key", "tenant/sim/actor-1");
        requestBody.put("business_runtime_context", businessContext);
        requestBody.put("sandbox_mode", "workspace-write");
        requestBody.put("approval_policy", "never");
        requestBody.put("network_access_enabled", false);
        requestBody.put("web_search_mode", "disabled");
        requestBody.put("output_schema", outputSchema);
        requestBody.put("additional_directories", directories);
        Flux<ServerSentEvent<String>> stream = Flux.never();
        when(client.streamQuery(same(requestBody))).thenReturn(stream);

        Flux<ServerSentEvent<String>> returned =
                adapter.streamQuery(execution, requestBody);

        assertSame(stream, returned);
        assertSame(businessContext, requestBody.get("business_runtime_context"));
        assertSame(outputSchema, requestBody.get("output_schema"));
        assertSame(directories, requestBody.get("additional_directories"));
        verify(client).streamQuery(same(requestBody));
    }

    @Test
    void forwardsReconnectWorkerTaskAckAndSettlementCallbackUnchanged() {
        CodexSdkExecutionAdapter.SdkExecution execution = bindSdk();
        Runnable connectionSettled = mock(Runnable.class);
        Flux<ServerSentEvent<String>> manual = Flux.never();
        Flux<ServerSentEvent<String>> automatic = Flux.never();
        when(client.subscribeToTask("worker-task-9", 37)).thenReturn(manual);
        when(client.subscribeToTask("worker-task-10", 41, connectionSettled))
                .thenReturn(automatic);

        assertSame(manual, adapter.subscribe(execution, "worker-task-9", 37));
        assertSame(automatic, adapter.subscribe(
                execution, "worker-task-10", 41, connectionSettled));

        verify(client).subscribeToTask("worker-task-9", 37);
        verify(client).subscribeToTask("worker-task-10", 41, connectionSettled);
        verifyNoInteractions(connectionSettled);
    }

    private CodexSdkExecutionAdapter.SdkExecution bindSdk() {
        when(workerManagementFacade.getCodexConfig("worker-1"))
                .thenReturn(CodexConfig.builder()
                        .baseUrl("http://localhost:3051")
                        .authToken("worker-token")
                        .build());
        when(clientFactory.getOrCreate(
                "worker-1:codex", "http://localhost:3051", "worker-token"))
                .thenReturn(client);
        return adapter.bind(
                "codex-worker", "worker-1",
                CodexRuntimeBinding.legacySdk("worker-1"));
    }
}
