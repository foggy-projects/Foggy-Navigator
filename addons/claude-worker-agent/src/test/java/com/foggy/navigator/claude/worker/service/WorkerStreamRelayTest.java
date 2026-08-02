package com.foggy.navigator.claude.worker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.agent.framework.event.TaskCompletionEvent;
import com.foggy.navigator.agent.framework.event.WorkerTaskStartEvent;
import com.foggy.navigator.agent.framework.protocol.AgentMessage;
import com.foggy.navigator.agent.framework.protocol.MessageType;
import com.foggy.navigator.agent.framework.protocol.WorkerEvent;
import com.foggy.navigator.claude.worker.client.ClaudeWorkerClient;
import com.foggy.navigator.claude.worker.model.entity.ClaudeTaskEntity;
import com.foggy.navigator.claude.worker.model.entity.ClaudeWorkerEntity;
import com.foggy.navigator.claude.worker.repository.ClaudeTaskRepository;
import com.foggy.navigator.common.repository.WorkingDirectoryRepository;
import com.foggy.navigator.session.event.SessionEventListener;
import com.foggy.navigator.spi.recovery.BackgroundRecoveryBounds;
import com.foggy.navigator.spi.recovery.BackgroundRecoveryCapability;
import com.foggy.navigator.spi.recovery.BackgroundRecoveryPolicy;
import com.foggy.navigator.spi.recovery.BackgroundRecoveryPolicyResolver;
import com.foggy.navigator.spi.recovery.ResolvedBackgroundRecoveryPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.mockito.ArgumentCaptor;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static reactor.core.publisher.Mono.just;

class WorkerStreamRelayTest {

    private ClaudeTaskRepository taskRepository;
    private ClaudeWorkerService workerService;
    private ClaudeTaskService taskService;
    private ClaudeWorkerClient client;
    private SessionEventListener sessionEventListener;
    private ApplicationEventPublisher eventPublisher;
    private ClaudeBackgroundRecoveryPolicy backgroundRecoveryPolicy;
    private WorkerStreamRelay relay;

    @BeforeEach
    void setUp() {
        taskRepository = mock(ClaudeTaskRepository.class);
        workerService = mock(ClaudeWorkerService.class);
        taskService = mock(ClaudeTaskService.class);
        client = mock(ClaudeWorkerClient.class);
        sessionEventListener = mock(SessionEventListener.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        BackgroundRecoveryPolicyResolver resolver = declaration ->
                new ResolvedBackgroundRecoveryPolicy(declaration,
                        new BackgroundRecoveryPolicy(true, new BackgroundRecoveryBounds(
                                3, Duration.ofHours(1), Duration.ofMinutes(5), Duration.ofMinutes(5),
                                1, Duration.ofMinutes(1))));
        backgroundRecoveryPolicy = new ClaudeBackgroundRecoveryPolicy(
                resolver, Clock.systemUTC());
        when(taskRepository.findByTaskId(anyString())).thenAnswer(invocation ->
                Optional.of(recoverableTask(invocation.getArgument(0))));

        relay = newRelay(backgroundRecoveryPolicy);
    }

    private WorkerStreamRelay newRelay(ClaudeBackgroundRecoveryPolicy policy) {
        return new WorkerStreamRelay(
                workerService,
                taskService,
                taskRepository,
                mock(WorkingDirectoryRepository.class),
                mock(ConversationConfigService.class),
                sessionEventListener,
                eventPublisher,
                new ObjectMapper(),
                policy
        );
    }

    @AfterEach
    void tearDown() {
        relay.abortStream("local-task-1");
    }

    @Test
    void reconnectTaskUsesPersistedWorkerTaskIdAndAckSeq() {
        ClaudeTaskEntity entity = new ClaudeTaskEntity();
        entity.setTaskId("local-task-1");
        entity.setWorkerId("worker-1");
        entity.setSessionId("session-1");
        entity.setWorkerTaskId("worker-task-9");
        entity.setClaudeSessionId("claude-session-1");
        entity.setStatus("RUNNING");
        entity.setLastAckedSeq(7);

        ClaudeWorkerEntity worker = new ClaudeWorkerEntity();
        worker.setWorkerId("worker-1");

        when(taskRepository.findByTaskId("local-task-1")).thenReturn(Optional.of(entity));
        when(workerService.getWorkerEntity("worker-1")).thenReturn(worker);
        when(workerService.createClient(worker)).thenReturn(client);
        when(client.getTaskStatus("worker-task-9")).thenReturn(just(Map.of("latest_seq", 9, "closed", false)));
        when(client.subscribeToTask("worker-task-9", 7)).thenReturn(Flux.never());
        when(taskService.resolveWorkerTaskLookupId(entity)).thenReturn("worker-task-9");

        relay.reconnectTask("local-task-1", "session-1", "worker-1");

        verify(client).subscribeToTask("worker-task-9", 7);
    }

    @Test
    void reconnectTaskReplaysEvidenceForCancelRequestedWithoutDispatchingAnotherAbort() {
        ClaudeTaskEntity entity = new ClaudeTaskEntity();
        entity.setTaskId("local-task-1");
        entity.setWorkerId("worker-1");
        entity.setSessionId("session-1");
        entity.setWorkerTaskId("worker-task-9");
        entity.setStatus("CANCEL_REQUESTED");
        entity.setLastAckedSeq(7);

        ClaudeWorkerEntity worker = new ClaudeWorkerEntity();
        worker.setWorkerId("worker-1");

        when(taskRepository.findByTaskId("local-task-1")).thenReturn(Optional.of(entity));
        when(workerService.getWorkerEntity("worker-1")).thenReturn(worker);
        when(workerService.createClient(worker)).thenReturn(client);
        when(taskService.resolveWorkerTaskLookupId(entity)).thenReturn("worker-task-9");
        when(client.getTaskStatus("worker-task-9")).thenReturn(just(Map.of("latest_seq", 9, "closed", false)));
        when(client.subscribeToTask("worker-task-9", 7)).thenReturn(Flux.never());

        relay.reconnectTask("local-task-1", "session-1", "worker-1");

        verify(client).subscribeToTask("worker-task-9", 7);
        verify(taskService, never()).abortTask(anyString());
    }

    @Test
    void initialStreamSetupFailureLeavesTaskPendingAndDefersRecovery() {
        WorkerTaskStartEvent event = WorkerTaskStartEvent.builder()
                .taskId("local-task-1")
                .sessionId("session-1")
                .workerId("worker-1")
                .providerType("claude-worker")
                .build();
        when(workerService.getWorkerEntity("worker-1")).thenThrow(new IllegalStateException("worker offline"));

        relay.onTaskStart(event);

        verify(taskService, never()).markLifecycleAttention(anyString(), anyString());
        verify(taskService, never()).failTask(anyString(), any(), any(), any());
        assertEquals(1, scheduledRecoveries().size());
    }

    @Test
    void clientSubscriptionErrorLeavesTaskPendingAndRetainsReplayCursor() throws Exception {
        WebClientResponseException error = WebClientResponseException.create(
                HttpStatus.TOO_MANY_REQUESTS.value(), "too many requests", HttpHeaders.EMPTY, null, null);
        invokeSubscribeSseFlux(Flux.error(error), "local-task-1", "session-1", "worker-1");

        verify(taskService, never()).markLifecycleAttention(anyString(), anyString());
        verify(taskService, never()).failTask(anyString(), any(), any(), any());
        assertEquals(0, relay.getLastAckedSeq("local-task-1").get());
    }

    @Test
    void postTerminalSubscriptionErrorIsIgnoredAndClearsReplayTracking() throws Exception {
        ClaudeTaskEntity entity = new ClaudeTaskEntity();
        entity.setTaskId("local-task-1");
        entity.setStatus("COMPLETED");
        when(taskRepository.findByTaskId("local-task-1")).thenReturn(Optional.of(entity));
        WebClientResponseException error = WebClientResponseException.create(
                HttpStatus.NOT_FOUND.value(), "not found", HttpHeaders.EMPTY, null, null);

        invokeSubscribeSseFlux(Flux.error(error), "local-task-1", "session-1", "worker-1");

        verify(taskService, never()).markLifecycleAttention(anyString(), anyString());
        verifyNoInteractions(eventPublisher);
        assertNull(relay.getLastAckedSeq("local-task-1"));
    }

    @Test
    void repeatedTransportErrorsPublishOnePendingNoticeAndScheduleOneRecovery() throws Exception {
        WebClientResponseException error = WebClientResponseException.create(
                HttpStatus.NOT_FOUND.value(), "not found", HttpHeaders.EMPTY, null, null);

        invokeSubscribeSseFlux(Flux.error(error), "local-task-1", "session-1", "worker-1");
        invokeSubscribeSseFlux(Flux.error(error), "local-task-1", "session-1", "worker-1");

        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, times(1)).publishEvent(published.capture());
        AgentMessage message = assertInstanceOf(AgentMessage.class, published.getValue());
        assertEquals(MessageType.STATE_SYNC, message.getType());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) message.getPayload();
        assertEquals("reconnect_pending", payload.get("subtype"));
        assertEquals(Boolean.TRUE, payload.get("reconnectable"));

        @SuppressWarnings("unchecked")
        Map<String, Disposable> scheduled = (Map<String, Disposable>)
                org.springframework.test.util.ReflectionTestUtils.getField(relay, "scheduledRecoveries");
        assertEquals(1, scheduled.size());
        assertTrue(scheduled.containsKey("local-task-1"));
    }

    @Test
    void terminalObservationCancelsPreviouslyScheduledRecovery() throws Exception {
        WebClientResponseException error = WebClientResponseException.create(
                HttpStatus.NOT_FOUND.value(), "not found", HttpHeaders.EMPTY, null, null);
        invokeSubscribeSseFlux(Flux.error(error), "local-task-1", "session-1", "worker-1");

        @SuppressWarnings("unchecked")
        Map<String, Disposable> scheduled = (Map<String, Disposable>)
                org.springframework.test.util.ReflectionTestUtils.getField(relay, "scheduledRecoveries");
        Disposable recovery = scheduled.get("local-task-1");

        ClaudeTaskEntity completed = new ClaudeTaskEntity();
        completed.setTaskId("local-task-1");
        completed.setStatus("COMPLETED");
        when(taskRepository.findByTaskId("local-task-1")).thenReturn(Optional.of(completed));
        invokeSubscribeSseFlux(Flux.error(error), "local-task-1", "session-1", "worker-1");

        assertTrue(scheduled.isEmpty());
        assertTrue(recovery.isDisposed());
    }

    @Test
    void startupPolicyOffReturnsBeforeDelayAndRepositoryScan() {
        BackgroundRecoveryPolicyResolver disabledResolver = declaration ->
                new ResolvedBackgroundRecoveryPolicy(declaration,
                        new BackgroundRecoveryPolicy(false, new BackgroundRecoveryBounds(
                                3, Duration.ofHours(1), Duration.ofSeconds(1), Duration.ofMinutes(1),
                                1, Duration.ofMinutes(1))));
        WorkerStreamRelay disabledRelay = newRelay(new ClaudeBackgroundRecoveryPolicy(
                disabledResolver, Clock.systemUTC()));

        disabledRelay.onApplicationReady();

        verify(taskRepository, never()).findByStatusIn(any());
        verifyNoInteractions(workerService);
    }

    @Test
    void legacyTaskTransportFailureCreatesNoTimerAttentionOrProviderEffect() throws Exception {
        ClaudeTaskEntity legacy = recoverableTask("legacy-task");
        legacy.setCreatedAtEpochMs(null);
        when(taskRepository.findByTaskId("legacy-task")).thenReturn(Optional.of(legacy));
        clearInvocations(taskService, eventPublisher, workerService);
        WebClientResponseException error = WebClientResponseException.create(
                HttpStatus.SERVICE_UNAVAILABLE.value(), "unavailable", HttpHeaders.EMPTY, null, null);

        invokeSubscribeSseFlux(Flux.error(error), "legacy-task", "session-1", "worker-1");

        verifyNoInteractions(workerService);
        verify(taskService, never()).markLifecycleAttention(anyString(), anyString());
        verifyNoInteractions(eventPublisher);
        assertFalse(scheduledRecoveries().containsKey("legacy-task"));
        assertEquals(0, backgroundRecoveryPolicy.attempts("legacy-task"));
    }

    @Test
    void exhaustedAttentionLatchesOnlyAfterDurableMarkAndStateSyncSucceed() throws Exception {
        ClaudeTaskEntity task = recoverableTask("exhausted-task");
        when(taskRepository.findByTaskId("exhausted-task")).thenReturn(Optional.of(task));
        for (int index = 0; index < 3; index++) {
            var decision = backgroundRecoveryPolicy.tryAcquire(
                    task, BackgroundRecoveryCapability.DELAYED_RETRY);
            assertTrue(decision.permitted());
            decision.lease().close();
        }
        doThrow(new IllegalStateException("db unavailable"))
                .doNothing()
                .when(taskService).markLifecycleAttention(
                        "exhausted-task", "CLAUDE_BACKGROUND_RECOVERY_ATTEMPTS_EXHAUSTED");
        WebClientResponseException error = WebClientResponseException.create(
                HttpStatus.SERVICE_UNAVAILABLE.value(), "unavailable", HttpHeaders.EMPTY, null, null);

        invokeSubscribeSseFlux(Flux.error(error), "exhausted-task", "session-1", "worker-1");
        invokeSubscribeSseFlux(Flux.error(error), "exhausted-task", "session-1", "worker-1");
        invokeSubscribeSseFlux(Flux.error(error), "exhausted-task", "session-1", "worker-1");

        verify(taskService, times(2)).markLifecycleAttention(
                "exhausted-task", "CLAUDE_BACKGROUND_RECOVERY_ATTEMPTS_EXHAUSTED");
        ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, times(1)).publishEvent(events.capture());
        AgentMessage attention = assertInstanceOf(AgentMessage.class, events.getValue());
        assertEquals(MessageType.STATE_SYNC, attention.getType());
        assertEquals("CLAUDE_BACKGROUND_RECOVERY_ATTEMPTS_EXHAUSTED",
                ((Map<?, ?>) attention.getPayload()).get("attentionStatus"));
    }

    @Test
    void explicitAbortDoesNotResetAttemptsButCommittedDeleteDoes() {
        ClaudeTaskEntity task = recoverableTask("local-task-1");
        var decision = backgroundRecoveryPolicy.tryAcquire(
                task, BackgroundRecoveryCapability.DELAYED_RETRY);
        assertTrue(decision.permitted());
        decision.lease().close();

        relay.abortStream("local-task-1");
        assertEquals(1, backgroundRecoveryPolicy.attempts("local-task-1"));

        relay.clearDeletedTask("local-task-1");
        assertEquals(0, backgroundRecoveryPolicy.attempts("local-task-1"));
    }

    @Test
    void timerImmediateFireRemovesExactRegisteredHandle() throws Exception {
        ClaudeTaskEntity task = recoverableTask("race-task");
        RegistrationRaceScheduler scheduler = new RegistrationRaceScheduler();
        ReflectionTestUtils.setField(relay, "recoveryScheduler", scheduler);
        when(taskRepository.findByTaskId("race-task")).thenReturn(Optional.empty());

        ReflectionTestUtils.invokeMethod(relay, "scheduleReconnect", task, Duration.ofNanos(1));

        assertTrue(scheduler.awaitCompletion());
        assertFalse(scheduledRecoveries().containsKey("race-task"));
        verify(taskRepository).findByTaskId("race-task");
    }

    @Test
    void reconnectTaskDoesNotContactWorkerForLocalTerminalTask() {
        ClaudeTaskEntity completed = new ClaudeTaskEntity();
        completed.setTaskId("local-task-1");
        completed.setStatus("COMPLETED");
        when(taskRepository.findByTaskId("local-task-1")).thenReturn(Optional.of(completed));

        relay.reconnectTask("local-task-1", "session-1", "worker-1");

        verifyNoInteractions(workerService);
        assertNull(relay.getLastAckedSeq("local-task-1"));
    }

    @Test
    void reconnectTaskSkipsWhenWorkerStreamAlreadyClosedAndAligned() {
        ClaudeTaskEntity entity = new ClaudeTaskEntity();
        entity.setTaskId("local-task-1");
        entity.setWorkerId("worker-1");
        entity.setSessionId("session-1");
        entity.setWorkerTaskId("worker-task-9");
        entity.setStatus("RUNNING");
        entity.setLastAckedSeq(7);

        ClaudeWorkerEntity worker = new ClaudeWorkerEntity();
        worker.setWorkerId("worker-1");

        when(taskRepository.findByTaskId("local-task-1")).thenReturn(Optional.of(entity));
        when(workerService.getWorkerEntity("worker-1")).thenReturn(worker);
        when(workerService.createClient(worker)).thenReturn(client);
        when(taskService.resolveWorkerTaskLookupId(entity)).thenReturn("worker-task-9");
        when(client.getTaskStatus("worker-task-9")).thenReturn(just(Map.of(
                "latest_seq", 7,
                "closed", true,
                "terminal_observed", true,
                "terminal_status", "COMPLETED")));
        entity.setSource("PLATFORM");
        entity.setCreatedAtEpochMs(Instant.now().minusSeconds(30).toEpochMilli());
        var attempt = backgroundRecoveryPolicy.tryAcquire(
                entity, BackgroundRecoveryCapability.DELAYED_RETRY);
        assertTrue(attempt.permitted());
        attempt.lease().close();

        relay.reconnectTask("local-task-1", "session-1", "worker-1");

        verify(client, never()).subscribeToTask(anyString(), anyInt());
        assertEquals(1, backgroundRecoveryPolicy.attempts("local-task-1"));
        verify(taskService, never()).completeTask(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(taskService, never()).failTask(anyString(), any(), any(), any(), any());
    }

    @Test
    void reconnectTaskDoesNotTreatClosedStreamAsTerminalWithoutEvidence() {
        ClaudeTaskEntity entity = new ClaudeTaskEntity();
        entity.setTaskId("local-task-1");
        entity.setWorkerId("worker-1");
        entity.setSessionId("session-1");
        entity.setWorkerTaskId("worker-task-9");
        entity.setStatus("RUNNING");
        entity.setLastAckedSeq(7);
        entity.setSource("PLATFORM");
        entity.setCreatedAtEpochMs(Instant.now().minusSeconds(30).toEpochMilli());

        ClaudeWorkerEntity worker = new ClaudeWorkerEntity();
        worker.setWorkerId("worker-1");

        when(taskRepository.findByTaskId("local-task-1")).thenReturn(Optional.of(entity));
        when(workerService.getWorkerEntity("worker-1")).thenReturn(worker);
        when(workerService.createClient(worker)).thenReturn(client);
        when(taskService.resolveWorkerTaskLookupId(entity)).thenReturn("worker-task-9");
        when(client.getTaskStatus("worker-task-9")).thenReturn(just(Map.of(
                "latest_seq", 7,
                "closed", true,
                "terminal_observed", false)));
        when(client.subscribeToTask("worker-task-9", 7)).thenReturn(Flux.never());

        relay.reconnectTask("local-task-1", "session-1", "worker-1");

        verify(client).subscribeToTask("worker-task-9", 7);
    }

    @Test
    void getWorkerTaskIdFallsBackToPersistedValue() {
        ClaudeTaskEntity entity = new ClaudeTaskEntity();
        entity.setTaskId("local-task-1");
        entity.setWorkerTaskId("worker-task-9");

        when(taskRepository.findByTaskId("local-task-1")).thenReturn(Optional.of(entity));
        when(taskService.resolveWorkerTaskLookupId(entity)).thenReturn("worker-task-9");

        assertEquals("worker-task-9", relay.getWorkerTaskId("local-task-1"));
    }

    @Test
    void relayEventMapsUnknownSystemSubtypeToStateSync() throws Exception {
        WorkerEvent event = new WorkerEvent();
        event.setType("system");
        event.setSubtype("heartbeat_warning");
        event.setContent("Still processing");
        event.setSessionId("claude-session-2");

        invokeRelayEvent("session-1", "local-task-1", event);

        verify(taskService).updateClaudeSessionId("local-task-1", "claude-session-2");

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());

        AgentMessage message = assertInstanceOf(AgentMessage.class, captor.getValue());
        assertEquals(MessageType.STATE_SYNC, message.getType());

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) message.getPayload();
        assertEquals("Still processing", payload.get("content"));
        assertEquals("heartbeat_warning", payload.get("subtype"));
        assertEquals("local-task-1", payload.get("taskId"));
        assertEquals("claude-session-2", payload.get("claudeSessionId"));
    }

    @Test
    void relayEventKeepsWaitingSubtypeAsStateSync() throws Exception {
        WorkerEvent event = new WorkerEvent();
        event.setType("system");
        event.setSubtype("waiting");
        event.setSessionId("claude-session-3");
        event.setData(Map.of("elapsed_seconds", 30, "timeout_seconds", 600));

        invokeRelayEvent("session-2", "local-task-2", event);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());
        AgentMessage message = assertInstanceOf(AgentMessage.class, captor.getValue());
        assertEquals(MessageType.STATE_SYNC, message.getType());

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) message.getPayload();
        assertEquals("waiting", payload.get("subtype"));
        assertEquals(30, payload.get("elapsedSeconds"));
        assertEquals(600, payload.get("timeoutSeconds"));
    }

    @Test
    void sequencedToolResultUsesReplayStableMessageIdAndSynchronousPersistence() throws Exception {
        WorkerEvent event = new WorkerEvent();
        event.setType("tool_result");
        event.setSeq(42);
        event.setToolUseId("tool-42");
        event.setTool("read_file");
        event.setOutput("large result");

        invokeRelayEvent("session-42", "local-task-42", event);

        ArgumentCaptor<AgentMessage> durableCaptor = ArgumentCaptor.forClass(AgentMessage.class);
        verify(sessionEventListener).handleMessageDurably(durableCaptor.capture());
        AgentMessage durable = durableCaptor.getValue();
        assertEquals("claude-event:local-task-42:42", durable.getMessageId());
        assertEquals(MessageType.TOOL_CALL_RESULT, durable.getType());

        ArgumentCaptor<Object> publishedCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(publishedCaptor.capture());
        AgentMessage published = assertInstanceOf(AgentMessage.class, publishedCaptor.getValue());
        assertEquals(durable.getMessageId(), published.getMessageId());
    }

    @Test
    void sequencedToolResultDoesNotAdvanceAckWhenDurablePersistenceFails() throws Exception {
        WorkerEvent event = new WorkerEvent();
        event.setType("tool_result");
        event.setSeq(43);
        event.setTaskId("worker-task-43");
        event.setToolUseId("tool-43");
        event.setTool("read_file");
        event.setOutput("payload");
        ServerSentEvent<String> sse = ServerSentEvent.<String>builder()
                .data(new ObjectMapper().writeValueAsString(event))
                .build();
        doThrow(new IllegalStateException("mysql unavailable"))
                .when(sessionEventListener).handleMessageDurably(any(AgentMessage.class));

        invokeSubscribeSseFlux(sse, "local-task-43", "session-43", "worker-43");

        verifyNoInteractions(taskService);
        assertEquals(0, relay.getLastAckedSeq("local-task-43").get());
    }

    @Test
    void legacyToolResultIsDurableBeforeItsCounterProgressAndKeepsStableIdentity() throws Exception {
        WorkerEvent event = new WorkerEvent();
        event.setType("tool_result");
        event.setTaskId("worker-task-legacy");
        event.setToolUseId("tool-legacy");
        event.setTool("shell");
        event.setOutput("large legacy output");
        ServerSentEvent<String> sse = ServerSentEvent.<String>builder()
                .data(new ObjectMapper().writeValueAsString(event))
                .build();

        invokeSubscribeSseFlux(sse, "local-task-legacy", "session-legacy", "worker-legacy");

        ArgumentCaptor<AgentMessage> durableCaptor = ArgumentCaptor.forClass(AgentMessage.class);
        InOrder durableBeforeProgress = inOrder(sessionEventListener, taskService);
        durableBeforeProgress.verify(sessionEventListener).handleMessageDurably(durableCaptor.capture());
        durableBeforeProgress.verify(taskService).recordWorkerProgress(
                "local-task-legacy", "worker-task-legacy", null, null, 1, true);
        assertEquals("cl-lt:local-task-legacy:tool-legacy", durableCaptor.getValue().getMessageId());
        assertEquals(1, relay.getLastAckedSeq("local-task-legacy").get());
    }

    @Test
    void legacyToolResultDoesNotAdvanceCounterWhenDurablePersistenceFails() throws Exception {
        WorkerEvent event = new WorkerEvent();
        event.setType("tool_result");
        event.setTaskId("worker-task-legacy-failure");
        event.setToolUseId("tool-legacy-failure");
        event.setTool("shell");
        event.setOutput("payload");
        ServerSentEvent<String> sse = ServerSentEvent.<String>builder()
                .data(new ObjectMapper().writeValueAsString(event))
                .build();
        doThrow(new IllegalStateException("mysql unavailable"))
                .when(sessionEventListener).handleMessageDurably(any(AgentMessage.class));

        invokeSubscribeSseFlux(sse, "local-task-legacy-failure", "session-legacy", "worker-legacy");

        verify(taskService, never()).recordWorkerProgress(any(), any(), any(), any(), any(), anyBoolean());
        assertEquals(0, relay.getLastAckedSeq("local-task-legacy-failure").get());
    }

    @Test
    void sequencedResultKeepsReplayTrackerWhenAtomicTerminalAckFails() throws Exception {
        WorkerEvent event = new WorkerEvent();
        event.setType("result");
        event.setSeq(44);
        event.setTaskId("worker-task-44");
        event.setSessionId("claude-session-44");
        event.setContent("final reply");
        ServerSentEvent<String> sse = ServerSentEvent.<String>builder()
                .data(new ObjectMapper().writeValueAsString(event))
                .build();
        doThrow(new IllegalStateException("mysql unavailable"))
                .when(taskService).completeTask(eq("local-task-44"), eq("worker-task-44"),
                        eq("claude-session-44"), eq("final reply"), any(), any(), any(), any(), any(), any(), eq(44));

        invokeSubscribeSseFlux(sse, "local-task-44", "session-44", "worker-44");

        verify(taskService).completeTask(eq("local-task-44"), eq("worker-task-44"),
                eq("claude-session-44"), eq("final reply"), any(), any(), any(), any(), any(), any(), eq(44));
        verify(taskService, never()).recordWorkerProgress(any(), any(), any(), any(), any(), anyBoolean());
        assertEquals(0, relay.getLastAckedSeq("local-task-44").get());
    }

    @Test
    void sequencedErrorKeepsReplayTrackerWhenAtomicTerminalAckFails() throws Exception {
        WorkerEvent event = new WorkerEvent();
        event.setType("error");
        event.setSeq(45);
        event.setTaskId("worker-task-45");
        event.setSessionId("claude-session-45");
        event.setError("worker failure");
        event.setTerminalObserved(true);
        event.setTerminalStatus("FAILED");
        ServerSentEvent<String> sse = ServerSentEvent.<String>builder()
                .data(new ObjectMapper().writeValueAsString(event))
                .build();
        doThrow(new IllegalStateException("mysql unavailable"))
                .when(taskService).failTask(eq("local-task-45"), eq("worker-task-45"),
                        eq("claude-session-45"), eq("CLAUDE_RUNTIME_REMOTE_ERROR"), eq(45));

        invokeSubscribeSseFlux(sse, "local-task-45", "session-45", "worker-45");

        verify(taskService).failTask(eq("local-task-45"), eq("worker-task-45"),
                eq("claude-session-45"), eq("CLAUDE_RUNTIME_REMOTE_ERROR"), eq(45));
        verify(taskService, never()).recordWorkerProgress(any(), any(), any(), any(), any(), anyBoolean());
        assertEquals(0, relay.getLastAckedSeq("local-task-45").get());
    }

    @Test
    void bareSequencedErrorStaysPendingAndAdvancesReplayCursor() throws Exception {
        WorkerEvent event = new WorkerEvent();
        event.setType("error");
        event.setSeq(46);
        event.setTaskId("worker-task-46");
        event.setSessionId("claude-session-46");
        event.setError("connection reset while worker may still run");
        ServerSentEvent<String> sse = ServerSentEvent.<String>builder()
                .data(new ObjectMapper().writeValueAsString(event))
                .build();
        invokeSubscribeSseFlux(sse, "local-task-46", "session-46", "worker-46");

        verify(taskService).markLifecycleAttention("local-task-46", "STREAM_ERROR_UNCONFIRMED");
        verify(taskService, never()).failTask(anyString(), any(), any(), any(), any());
        verify(taskService).recordWorkerProgress("local-task-46", "worker-task-46",
                "claude-session-46", null, 46, true);
        assertEquals(46, relay.getLastAckedSeq("local-task-46").get());
    }

    @Test
    void verifiedAbortedErrorRecordsAbortWithoutMappingToFailure() throws Exception {
        WorkerEvent event = new WorkerEvent();
        event.setType("error");
        event.setSeq(47);
        event.setTaskId("worker-task-47");
        event.setSessionId("claude-session-47");
        event.setError("Task was cancelled");
        event.setTerminalObserved(true);
        event.setTerminalStatus("ABORTED");
        event.setTerminalSource("PROVIDER_TERMINAL_EVENT");

        invokeRelayEvent("session-47", "local-task-47", event);

        verify(taskService).recordWorkerTerminalAbort("local-task-47", "worker-task-47",
                "claude-session-47", 47);
        verify(taskService, never()).failTask(anyString(), any(), any(), any(), any());
    }

    @Test
    void verifiedWorkerFailureNeverPublishesOrPersistsRawRemoteError() throws Exception {
        WorkerEvent event = new WorkerEvent();
        event.setType("error");
        event.setSeq(48);
        event.setTaskId("worker-task-48");
        event.setSessionId("claude-session-48");
        event.setError("stderr: token=super-secret /tmp/private-path");
        event.setTerminalObserved(true);
        event.setTerminalStatus("FAILED");
        event.setTerminalSource("PROVIDER_TERMINAL_EVENT");

        invokeRelayEvent("session-48", "local-task-48", event);

        verify(taskService).failTask("local-task-48", "worker-task-48", "claude-session-48",
                "CLAUDE_RUNTIME_REMOTE_ERROR", 48);
        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeast(2)).publishEvent(published.capture());

        AgentMessage errorMessage = published.getAllValues().stream()
                .filter(AgentMessage.class::isInstance)
                .map(AgentMessage.class::cast)
                .filter(message -> message.getType() == MessageType.ERROR)
                .findFirst()
                .orElseThrow();
        assertFalse(String.valueOf(errorMessage.getPayload()).contains("super-secret"));
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) errorMessage.getPayload();
        assertEquals("CLAUDE_RUNTIME_REMOTE_ERROR", payload.get("content"));

        TaskCompletionEvent completion = published.getAllValues().stream()
                .filter(TaskCompletionEvent.class::isInstance)
                .map(TaskCompletionEvent.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals("CLAUDE_RUNTIME_REMOTE_ERROR", completion.getResultSummary());
    }

    private void invokeRelayEvent(String sessionId, String taskId, WorkerEvent event) throws Exception {
        clearInvocations(eventPublisher, taskService);
        Method method = WorkerStreamRelay.class.getDeclaredMethod(
                "relayEvent",
                String.class,
                String.class,
                WorkerEvent.class,
                AtomicReference.class,
                AtomicReference.class
        );
        method.setAccessible(true);
        method.invoke(relay, sessionId, taskId, event, new AtomicReference<String>(), new AtomicReference<String>());
    }

    private void invokeSubscribeSseFlux(ServerSentEvent<String> sse, String taskId, String sessionId, String workerId)
            throws Exception {
        invokeSubscribeSseFlux(Flux.just(sse), taskId, sessionId, workerId);
    }

    private void invokeSubscribeSseFlux(Flux<ServerSentEvent<String>> flux, String taskId, String sessionId,
                                        String workerId) throws Exception {
        Method method = WorkerStreamRelay.class.getDeclaredMethod(
                "subscribeSseFlux",
                Flux.class,
                String.class,
                String.class,
                String.class,
                AtomicReference.class,
                AtomicReference.class,
                int.class
        );
        method.setAccessible(true);
        method.invoke(relay, flux, taskId, sessionId, workerId,
                new AtomicReference<String>(), new AtomicReference<String>(), 0);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Disposable> scheduledRecoveries() {
        return (Map<String, Disposable>)
                org.springframework.test.util.ReflectionTestUtils.getField(relay, "scheduledRecoveries");
    }

    private ClaudeTaskEntity recoverableTask(String taskId) {
        ClaudeTaskEntity task = new ClaudeTaskEntity();
        task.setTaskId(taskId);
        task.setSessionId("session-1");
        task.setWorkerId("worker-1");
        task.setStatus("RUNNING");
        task.setSource("PLATFORM");
        task.setCreatedAtEpochMs(Instant.now().minusSeconds(30).toEpochMilli());
        return task;
    }

    private static final class RegistrationRaceScheduler implements Scheduler {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch completed = new CountDownLatch(1);
        private final AtomicBoolean disposed = new AtomicBoolean(false);

        @Override
        public Disposable schedule(Runnable task) {
            AtomicBoolean taskDisposed = new AtomicBoolean(false);
            Disposable handle = new Disposable() {
                @Override
                public void dispose() {
                    taskDisposed.set(true);
                }

                @Override
                public boolean isDisposed() {
                    return taskDisposed.get();
                }
            };
            Thread callback = new Thread(() -> {
                started.countDown();
                try {
                    if (!disposed.get() && !taskDisposed.get()) task.run();
                } finally {
                    completed.countDown();
                }
            }, "claude-recovery-registration-race");
            callback.setDaemon(true);
            callback.start();
            try {
                if (!started.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("timer callback did not start");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("timer registration interrupted", interrupted);
            }
            return handle;
        }

        @Override
        public Disposable schedule(Runnable task, long delay, TimeUnit unit) {
            return schedule(task);
        }

        @Override
        public Worker createWorker() {
            throw new UnsupportedOperationException("worker scheduling is not used by this test");
        }

        @Override
        public void dispose() {
            disposed.set(true);
        }

        @Override
        public boolean isDisposed() {
            return disposed.get();
        }

        private boolean awaitCompletion() throws InterruptedException {
            return completed.await(2, TimeUnit.SECONDS);
        }
    }
}
