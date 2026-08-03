package com.foggy.navigator.codex.worker.service;

import com.foggy.navigator.codex.worker.client.CodexWorkerClient;
import com.foggy.navigator.codex.worker.client.CodexWorkerClientFactory;
import com.foggy.navigator.codex.worker.model.CodexRuntimeBinding;
import com.foggy.navigator.codex.worker.model.CodexRuntimeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CodexAppServerExecutionAdapterTest {

    private static final CodexAppServerRuntimeAffinityAdapter.DurableAffinity AFFINITY =
            new CodexAppServerRuntimeAffinityAdapter.DurableAffinity(
                    "codex-app-server-worker",
                    "app-main",
                    7,
                    "APP_SERVER",
                    "worker-1",
                    "instance-a",
                    19L);

    private CodexRuntimeRegistryService runtimeRegistryService;
    private CodexWorkerClientFactory clientFactory;
    private CodexAppServerRuntimeAffinityAdapter runtimeAffinityAdapter;
    private CodexAppServerAcceptanceService acceptanceService;
    private CodexTaskRuntimeStateService runtimeStateService;
    private CodexWorkerClient client;
    private CodexAppServerExecutionAdapter adapter;

    @BeforeEach
    void setUp() {
        runtimeRegistryService = mock(CodexRuntimeRegistryService.class);
        clientFactory = mock(CodexWorkerClientFactory.class);
        runtimeAffinityAdapter = new CodexAppServerRuntimeAffinityAdapter(
                runtimeRegistryService, clientFactory);
        acceptanceService = mock(CodexAppServerAcceptanceService.class);
        runtimeStateService = mock(CodexTaskRuntimeStateService.class);
        client = mock(CodexWorkerClient.class);
        adapter = new CodexAppServerExecutionAdapter(
                runtimeAffinityAdapter, acceptanceService, runtimeStateService);

        CodexRuntimeBinding binding = CodexRuntimeBinding.builder()
                .runtimeId("app-main")
                .runtimeRevision(7)
                .runtimeType(CodexRuntimeType.APP_SERVER)
                .workerId("worker-1")
                .instanceId("instance-a")
                .routingEpoch(20L)
                .endpointUrl("http://127.0.0.1:3062")
                .authToken("runtime-token")
                .build();
        when(runtimeRegistryService.resolveBoundRuntime(
                "app-main", 7, "worker-1", "instance-a"))
                .thenReturn(binding);
        when(clientFactory.getOrCreate(
                "runtime:app-main:7",
                "http://127.0.0.1:3062",
                "runtime-token",
                "instance-a"))
                .thenReturn(client);
    }

    @Test
    void hasOnlyTheFrozenThreeDependenciesAndANonForgeableHandle() {
        assertEquals(List.of(
                        CodexAppServerRuntimeAffinityAdapter.class,
                        CodexAppServerAcceptanceService.class,
                        CodexTaskRuntimeStateService.class),
                List.of(CodexAppServerExecutionAdapter.class
                        .getDeclaredConstructors()[0].getParameterTypes()));
        assertTrue(Arrays.stream(CodexAppServerExecutionAdapter.AppServerExecution.class
                        .getDeclaredConstructors())
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers())));
        assertFalse(Arrays.stream(CodexAppServerExecutionAdapter.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("subscribe")
                        || method.getName().equals("status"))
                .flatMap(method -> Arrays.stream(method.getParameterTypes()))
                .anyMatch(String.class::equals));
    }

    @Test
    void bindsTheExactSevenFieldAffinityAndA1ClientWithoutFallback() {
        CodexAppServerExecutionAdapter.AppServerExecution execution =
                adapter.bind("task-1", "worker-task-9", AFFINITY);

        assertEquals("task-1", execution.navigatorTaskId());
        assertEquals("worker-task-9", execution.workerTaskId());
        assertSame(AFFINITY, execution.affinity());
        assertSame(client, adapter.client(execution));
        verify(runtimeRegistryService).resolveBoundRuntime(
                "app-main", 7, "worker-1", "instance-a");
        verify(clientFactory).getOrCreate(
                "runtime:app-main:7",
                "http://127.0.0.1:3062",
                "runtime-token",
                "instance-a");
    }

    @Test
    void incompleteTaskOrAffinityFailsClosedBeforeAnyProviderEffect() {
        assertThrows(IllegalStateException.class,
                () -> adapter.bind(" ", null, AFFINITY));
        assertThrows(IllegalStateException.class,
                () -> adapter.bind("task-1", " ", AFFINITY));
        assertThrows(CodexRuntimeUnavailableException.class,
                () -> adapter.bind("task-1", null,
                        new CodexAppServerRuntimeAffinityAdapter.DurableAffinity(
                                "codex-app-server-worker", "app-main", 7,
                                "APP_SERVER", "worker-1", null, 19L)));

        verifyNoInteractions(acceptanceService, runtimeStateService, client);
        verify(runtimeRegistryService, never()).resolveBoundRuntime(any(), any(), any(), any());
        verifyNoInteractions(clientFactory);
    }

    @Test
    void initialAcceptanceUsesNavigatorTaskKeyAndPersistsBeforeSubscription() {
        Map<String, Object> request = Map.of("prompt", "hello");
        Flux<ServerSentEvent<String>> stream = Flux.never();
        when(acceptanceService.accept(client, "task-1", request))
                .thenReturn("task-1");
        when(runtimeStateService.markSubscribed("task-1")).thenReturn(true);
        when(client.subscribeToTask("task-1", 0)).thenReturn(stream);
        CodexAppServerExecutionAdapter.AppServerExecution execution =
                adapter.bind("task-1", null, AFFINITY);

        CodexAppServerExecutionAdapter.AppServerExecution accepted =
                adapter.acceptInitial(execution, request);
        Flux<ServerSentEvent<String>> returned = adapter.subscribe(accepted, 0);

        assertEquals("task-1", accepted.workerTaskId());
        assertSame(stream, returned);
        InOrder order = inOrder(runtimeStateService, acceptanceService, client);
        order.verify(runtimeStateService).prepareAcceptance("task-1", request);
        order.verify(acceptanceService).accept(client, "task-1", request);
        order.verify(runtimeStateService).markSubscribed("task-1");
        order.verify(client).subscribeToTask("task-1", 0);
    }

    @Test
    void mismatchedAcceptanceCannotCreateAHandleForAnotherProviderTask() {
        Map<String, Object> request = Map.of("prompt", "hello");
        when(acceptanceService.accept(client, "task-1", request))
                .thenReturn("other-task");

        assertThrows(IllegalStateException.class,
                () -> adapter.acceptInitial(adapter.bind("task-1", null, AFFINITY), request));

        verify(client, never()).subscribeToTask(any(), anyInt());
    }

    @Test
    void automaticRecoveryLoadsOnlyTheSameTaskEnvelopeAndUsesOnePolicyCall() {
        Map<String, Object> request = Map.of("prompt", "recovered");
        when(runtimeStateService.loadPreparedRequest("task-1")).thenReturn(request);
        when(acceptanceService.acceptForRecoveryAttempt(client, "task-1", request))
                .thenReturn("task-1");

        adapter.recoverAcceptance(adapter.bind("task-1", null, AFFINITY), true);

        verify(runtimeStateService).loadPreparedRequest("task-1");
        verify(acceptanceService, times(1))
                .acceptForRecoveryAttempt(client, "task-1", request);
        verify(acceptanceService, never()).accept(any(), any(), any());
    }

    @Test
    void manualRecoveryUsesTheExistingNormalAcceptancePolicy() {
        Map<String, Object> request = Map.of("prompt", "recovered");
        when(runtimeStateService.loadPreparedRequest("task-1")).thenReturn(request);
        when(acceptanceService.accept(client, "task-1", request))
                .thenReturn("task-1");

        adapter.recoverAcceptance(adapter.bind("task-1", null, AFFINITY), false);

        verify(runtimeStateService).loadPreparedRequest("task-1");
        verify(acceptanceService).accept(client, "task-1", request);
        verify(acceptanceService, never())
                .acceptForRecoveryAttempt(any(), any(), any());
    }

    @Test
    void continuationWithPersistedWorkerTaskNeverRecreatesOrCrossResumes() {
        CodexAppServerExecutionAdapter.AppServerExecution execution =
                adapter.bind("task-1", "worker-task-9", AFFINITY);
        Map<String, Object> request = Map.of("prompt", "other");

        assertThrows(IllegalStateException.class,
                () -> adapter.acceptInitial(execution, request));
        assertThrows(IllegalStateException.class,
                () -> adapter.recoverAcceptance(execution, true));

        verifyNoInteractions(acceptanceService, runtimeStateService, client);
    }

    @Test
    void subscriptionDenialOccursBeforeTheProviderSubscribeCall() {
        when(runtimeStateService.markSubscribed("task-1")).thenReturn(false);
        CodexAppServerExecutionAdapter.AppServerExecution execution =
                adapter.bind("task-1", "worker-task-9", AFFINITY);

        assertThrows(CodexAppServerExecutionAdapter.SubscriptionDeniedException.class,
                () -> adapter.subscribe(execution, 13));

        verify(runtimeStateService).markSubscribed("task-1");
        verify(client, never()).subscribeToTask(any(), anyInt());
    }

    @Test
    void statusUsesOnlyTheHandleWorkerTaskAndReturnsRawSafeCompletedObservation() {
        Map<String, Object> pending = new LinkedHashMap<>();
        pending.put("request_id", "request-1");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("task_id", "worker-task-9");
        body.put("status", "terminal");
        body.put("outcome", "completed");
        body.put("thread_id", "thread-9");
        body.put("model", "gpt-5.6-sol");
        body.put("error_code", "IGNORED_FOR_COMPLETION");
        body.put("pending_interaction", pending);
        body.put("result", "must-not-cross-status-boundary");
        when(client.getTaskStatus("worker-task-9")).thenReturn(Mono.just(body));

        CodexAppServerExecutionAdapter.RemoteTaskStatus status = adapter.status(
                adapter.bind("task-1", "worker-task-9", AFFINITY));

        assertEquals("terminal", status.status());
        assertEquals("completed", status.outcome());
        assertEquals("thread-9", status.threadId());
        assertEquals("gpt-5.6-sol", status.model());
        assertEquals("IGNORED_FOR_COMPLETION", status.errorCode());
        assertEquals(Map.of("request_id", "request-1"), status.pendingInteraction());
        assertThrows(UnsupportedOperationException.class,
                () -> status.pendingInteraction().put("other", "value"));
        assertThrows(NoSuchMethodException.class,
                () -> status.getClass().getDeclaredMethod("isTerminal"));
        verify(client).getTaskStatus("worker-task-9");
    }

    @Test
    void statusRejectsAResponseForAnotherProviderTask() {
        when(client.getTaskStatus("worker-task-9")).thenReturn(Mono.just(Map.of(
                "task_id", "other-task", "status", "running")));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> adapter.status(adapter.bind(
                        "task-1", "worker-task-9", AFFINITY)));

        assertEquals("CODEX_RUNTIME_STATUS_TASK_MISMATCH", error.getMessage());
    }

    @Test
    void nullStatusResponseRemainsAnUnavailableRawObservation() {
        when(client.getTaskStatus("worker-task-9")).thenReturn(Mono.empty());

        assertNull(adapter.status(adapter.bind(
                "task-1", "worker-task-9", AFFINITY)));
    }

    @Test
    void unknownAcceptanceIsMarkedAndTheOriginalExceptionIsRethrown() {
        Map<String, Object> request = Map.of("prompt", "hello");
        CodexAppServerAcceptanceService.UnknownException unknown =
                newUnknownException();
        doThrow(unknown).when(acceptanceService)
                .accept(client, "task-1", request);

        CodexAppServerAcceptanceService.UnknownException thrown = assertThrows(
                CodexAppServerAcceptanceService.UnknownException.class,
                () -> adapter.acceptInitial(
                        adapter.bind("task-1", null, AFFINITY), request));

        assertSame(unknown, thrown);
        verify(runtimeStateService).markAcceptanceUnknown("task-1");
    }

    @Test
    void markAcceptanceUnknownFailureCannotReplaceTheOriginalUnknown() {
        Map<String, Object> request = Map.of("prompt", "hello");
        CodexAppServerAcceptanceService.UnknownException unknown =
                newUnknownException();
        IllegalStateException stateError =
                new IllegalStateException("runtime state unavailable");
        doThrow(unknown).when(acceptanceService)
                .accept(client, "task-1", request);
        doThrow(stateError).when(runtimeStateService)
                .markAcceptanceUnknown("task-1");

        CodexAppServerAcceptanceService.UnknownException thrown = assertThrows(
                CodexAppServerAcceptanceService.UnknownException.class,
                () -> adapter.acceptInitial(
                        adapter.bind("task-1", null, AFFINITY), request));

        assertSame(unknown, thrown);
        assertEquals(List.of(stateError), List.of(thrown.getSuppressed()));
    }

    @Test
    void existingRejectedAndCancelledExceptionsRemainUnchanged() {
        Map<String, Object> request = Map.of("prompt", "hello");
        CodexAppServerAcceptanceService.RejectedException rejected =
                mock(CodexAppServerAcceptanceService.RejectedException.class);
        doThrow(rejected).when(acceptanceService)
                .accept(client, "task-1", request);

        assertSame(rejected, assertThrows(
                CodexAppServerAcceptanceService.RejectedException.class,
                () -> adapter.acceptInitial(
                        adapter.bind("task-1", null, AFFINITY), request)));

        CodexTaskRuntimeStateService.AcceptanceCancelledException cancelled =
                mock(CodexTaskRuntimeStateService.AcceptanceCancelledException.class);
        doThrow(cancelled).when(runtimeStateService)
                .prepareAcceptance("task-2", request);
        assertSame(cancelled, assertThrows(
                CodexTaskRuntimeStateService.AcceptanceCancelledException.class,
                () -> adapter.acceptInitial(
                        adapter.bind("task-2", null, AFFINITY), request)));
    }

    private CodexAppServerAcceptanceService.UnknownException newUnknownException() {
        try {
            Constructor<CodexAppServerAcceptanceService.UnknownException> constructor =
                    CodexAppServerAcceptanceService.UnknownException.class
                            .getDeclaredConstructor(String.class, Throwable.class);
            constructor.setAccessible(true);
            return constructor.newInstance(
                    "CODEX_RUNTIME_ACCEPTANCE_UNKNOWN",
                    new IllegalStateException("provider response unavailable"));
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Cannot construct test UnknownException", error);
        }
    }
}
