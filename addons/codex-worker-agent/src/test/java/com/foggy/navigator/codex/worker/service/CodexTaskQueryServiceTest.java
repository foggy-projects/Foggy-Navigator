package com.foggy.navigator.codex.worker.service;

import com.foggy.navigator.agent.framework.diagnostic.ErrorEnvelope;
import com.foggy.navigator.codex.worker.model.entity.CodexTaskEntity;
import com.foggy.navigator.codex.worker.repository.CodexTaskRepository;
import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.common.entity.SessionEntity;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.repository.SessionEntityRepository;
import com.foggy.navigator.common.repository.SessionTaskRepository;
import com.foggy.navigator.common.util.ProviderStateCodec;
import com.foggy.navigator.session.service.ErrorDiagnosticService;
import com.foggy.navigator.spi.agent.TaskPageResult;
import com.foggy.navigator.spi.agent.TaskSearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CodexTaskQueryServiceTest {

    @Mock
    private CodexTaskRepository taskRepository;
    @Mock
    private SessionTaskRepository sessionTaskRepository;
    @Mock
    private SessionEntityRepository sessionEntityRepository;
    @Mock
    private ErrorDiagnosticService errorDiagnosticService;

    private CodexTaskQueryService service;

    @BeforeEach
    void setUp() {
        service = new CodexTaskQueryService(taskRepository);
        ReflectionTestUtils.setField(
                service, "sessionTaskRepository", sessionTaskRepository);
        ReflectionTestUtils.setField(
                service, "sessionEntityRepository", sessionEntityRepository);
        ReflectionTestUtils.setField(
                service, "errorDiagnosticService", errorDiagnosticService);
    }

    @Test
    void hasReadOnlyBoundaryAndOnlyFrozenQueryDependencies() {
        Transactional transaction = CodexTaskQueryService.class.getAnnotation(Transactional.class);
        assertTrue(transaction.readOnly());

        List<Class<?>> constructorDependencies = Arrays.stream(
                        CodexTaskQueryService.class.getDeclaredConstructors()[0].getParameterTypes())
                .toList();
        assertEquals(List.of(CodexTaskRepository.class), constructorDependencies);

        Set<Class<?>> optionalDependencies = Arrays.stream(
                        CodexTaskQueryService.class.getDeclaredFields())
                .filter(field -> field.getAnnotation(Autowired.class) != null)
                .map(java.lang.reflect.Field::getType)
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of(
                SessionTaskRepository.class,
                SessionEntityRepository.class,
                ErrorDiagnosticService.class), optionalDependencies);

        assertFalse(Arrays.stream(CodexTaskQueryService.class.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(java.lang.reflect.Field::getType)
                .anyMatch(type -> type.getSimpleName().contains("Client")
                        || type.getSimpleName().contains("Relay")
                        || type.getSimpleName().contains("Runtime")));
    }

    @Test
    void ownedLookupFailsClosedAndOptionalFallbacksStayReadOnly() {
        when(taskRepository.findByTaskIdAndUserId("missing", "owner"))
                .thenReturn(Optional.empty());

        IllegalArgumentException missing = assertThrows(
                IllegalArgumentException.class,
                () -> service.getTask("owner", "missing"));
        assertEquals("Task not found: missing", missing.getMessage());

        CodexTaskEntity task = task(
                "task-default", "session-default", "RUNNING",
                LocalDateTime.of(2026, 8, 3, 10, 0));
        when(taskRepository.findByTaskIdAndUserId("task-default", "owner"))
                .thenReturn(Optional.of(task));

        CodexTaskQueryService optionalOnly = new CodexTaskQueryService(taskRepository);
        DispatchTaskDTO dto = optionalOnly.getTask("owner", "task-default");

        assertEquals(CodexTaskService.CODEX_PROVIDER_TYPE, dto.getProviderType());
        assertNull(dto.getAgentId());
        assertNull(dto.getContextId());
        assertNull(task.getProviderType());

        IllegalArgumentException mismatch = assertThrows(
                IllegalArgumentException.class,
                () -> optionalOnly.getTaskForProvider(
                        "owner", "task-default", CodexTaskService.CODEX_BIZ_PROVIDER_TYPE));
        assertEquals("Task not found: task-default", mismatch.getMessage());

        verify(taskRepository, never()).save(any(CodexTaskEntity.class));
        verify(taskRepository, never()).delete(any(CodexTaskEntity.class));
        verifyNoInteractions(sessionTaskRepository, sessionEntityRepository, errorDiagnosticService);
    }

    @Test
    void providerPartitionReusesBatchFactsWithoutMutatingEntitiesOrNPlusOne() {
        LocalDateTime observedBase = LocalDateTime.now().minusSeconds(5);
        CodexTaskEntity taskProjection = task(
                "task-projection", "session-1", "RUNNING", observedBase);
        CodexTaskEntity sessionFallback = task(
                "task-session", "session-2", "RUNNING", observedBase);
        CodexTaskEntity sdkDefault = task(
                "task-default", null, "RUNNING", observedBase);
        List<CodexTaskEntity> tasks = List.of(taskProjection, sessionFallback, sdkDefault);
        when(taskRepository.findByUserIdOrderByCreatedAtDesc("owner")).thenReturn(tasks);

        SessionTaskEntity projection = new SessionTaskEntity();
        projection.setTaskId("task-projection");
        projection.setSessionId("session-1");
        projection.setProviderType(CodexTaskService.CODEX_BIZ_PROVIDER_TYPE);
        projection.setAgentId("agent-from-task");
        projection.setTaskStateJson(ProviderStateCodec.mergeTaskValue(
                null,
                CodexTaskService.CODEX_BIZ_PROVIDER_TYPE,
                ProviderStateCodec.FIELD_CONTEXT_ID,
                "context-from-task"));
        when(sessionTaskRepository.findByTaskIdIn(any())).thenReturn(List.of(projection));

        SessionEntity session = new SessionEntity();
        session.setId("session-2");
        session.setProviderType(CodexTaskService.CODEX_BIZ_PROVIDER_TYPE);
        session.setAgentId("agent-from-session");
        when(sessionEntityRepository.findAllById(any())).thenReturn(List.of(session));

        List<DispatchTaskDTO> result = service.listTasksForProvider(
                "owner", CodexTaskService.CODEX_BIZ_PROVIDER_TYPE);

        assertEquals(List.of("task-projection", "task-session"),
                result.stream().map(DispatchTaskDTO::getTaskId).toList());
        assertEquals(List.of("agent-from-task", "agent-from-session"),
                result.stream().map(DispatchTaskDTO::getAgentId).toList());
        assertEquals("context-from-task", result.get(0).getContextId());
        assertNull(result.get(1).getContextId());
        assertEquals(result.get(0).getSilentForSeconds(), result.get(1).getSilentForSeconds());

        assertNull(taskProjection.getProviderType());
        assertNull(sessionFallback.getProviderType());
        assertNull(sdkDefault.getProviderType());
        verify(sessionTaskRepository).findByTaskIdIn(argThat(taskIds ->
                taskIds.equals(Set.of("task-projection", "task-session", "task-default"))));
        verify(sessionEntityRepository).findAllById(
                argThat(sessionIds -> sessionIds.equals(Set.of("session-2"))));
        verify(sessionTaskRepository, never()).findByTaskId(anyString());
        verify(sessionEntityRepository, never()).findById(anyString());
        verify(taskRepository, never()).save(any(CodexTaskEntity.class));
    }

    @Test
    void pageGroupingAndSearchKeepSessionOrderFallbackAndLatestTaskSemantics() {
        CodexTaskEntity sessionLatest = task(
                "task-latest", "session-a", "RUNNING",
                LocalDateTime.of(2026, 8, 3, 12, 0));
        sessionLatest.setWorkerId("worker-latest");
        sessionLatest.setDirectoryId("directory-latest");
        sessionLatest.setPrompt("new prompt");
        sessionLatest.setCostUsd(new BigDecimal("2.25"));

        CodexTaskEntity completed = task(
                "task-completed", "session-b", "COMPLETED",
                LocalDateTime.of(2026, 8, 3, 11, 30));
        completed.setWorkerId("worker-other");
        completed.setDirectoryId("directory-other");

        CodexTaskEntity withoutSession = task(
                "task-without-session", null, "COMPLETED",
                LocalDateTime.of(2026, 8, 3, 11, 0));

        CodexTaskEntity sessionEarlier = task(
                "task-earlier", "session-a", "COMPLETED",
                LocalDateTime.of(2026, 8, 3, 10, 0));
        sessionEarlier.setWorkerId("worker-old");
        sessionEarlier.setDirectoryId("directory-old");
        sessionEarlier.setPrompt("contains NEEDLE in an earlier task");
        sessionEarlier.setCostUsd(new BigDecimal("1.75"));

        List<CodexTaskEntity> tasks = List.of(
                sessionLatest, completed, withoutSession, sessionEarlier);
        when(taskRepository.findByUserIdOrderByCreatedAtDesc("owner")).thenReturn(tasks);

        TaskPageResult all = service.listTaskPage("owner", 0, 10, null);
        assertEquals(3L, all.totalSessions());
        assertEquals(List.of(
                        "task-latest", "task-earlier", "task-completed", "task-without-session"),
                all.content().stream()
                        .map(DispatchTaskDTO.class::cast)
                        .map(DispatchTaskDTO::getTaskId)
                        .toList());

        TaskPageResult processing = service.listTaskPage(
                "owner", 0, 10, "PROCESSING");
        assertEquals(1L, processing.totalSessions());
        assertEquals(List.of("task-latest", "task-earlier"),
                processing.content().stream()
                        .map(DispatchTaskDTO.class::cast)
                        .map(DispatchTaskDTO::getTaskId)
                        .toList());

        TaskSearchResult search = service.searchSessionPage(
                "owner", "needle", "worker-latest", "directory-latest", 0, 10);
        assertEquals(1L, search.total());
        Map<?, ?> hit = assertInstanceOf(Map.class, search.results().get(0));
        assertEquals("session-a", hit.get("sessionId"));
        assertEquals("task-latest", hit.get("latestTaskId"));
        assertEquals("worker-latest", hit.get("workerId"));
        assertEquals("directory-latest", hit.get("directoryId"));
        assertEquals(new BigDecimal("4.00"), hit.get("totalCost"));
        assertEquals(sessionEarlier.getCreatedAt(), hit.get("createdAt"));
        assertEquals(sessionLatest.getUpdatedAt(), hit.get("updatedAt"));

        assertEquals(0L, service.searchSessionPage(
                "owner", "needle", "worker-old", null, 0, 10).total());
    }

    @Test
    void failedDiagnosticsAreReadOncePerUniqueFailedTaskAndNeverForOtherStates() {
        CodexTaskEntity failed = task(
                "task-failed", "session-failed", "FAILED",
                LocalDateTime.of(2026, 8, 3, 12, 0));
        failed.setErrorMessage("safe failure");
        CodexTaskEntity running = task(
                "task-running", "session-running", "RUNNING",
                LocalDateTime.of(2026, 8, 3, 11, 0));
        when(taskRepository.findByUserIdOrderByCreatedAtDesc("owner"))
                .thenReturn(List.of(failed, failed, running));

        ErrorEnvelope envelope = ErrorEnvelope.builder()
                .errorCode("CODEX_SAFE_FAILURE")
                .message("safe failure")
                .diagnosticRef("diag-1")
                .occurredAt(Instant.parse("2026-08-03T04:00:00Z"))
                .taskId("task-failed")
                .providerType(CodexTaskService.CODEX_PROVIDER_TYPE)
                .build();
        when(errorDiagnosticService.findLatestEnvelope("task-failed"))
                .thenReturn(envelope);

        List<DispatchTaskDTO> result = service.listTasks("owner");

        assertEquals("CODEX_SAFE_FAILURE", result.get(0).getError().get("errorCode"));
        assertEquals("CODEX_SAFE_FAILURE", result.get(1).getError().get("errorCode"));
        assertNull(result.get(2).getError());
        verify(errorDiagnosticService, times(1)).findLatestEnvelope("task-failed");
        verify(errorDiagnosticService, never()).findLatestEnvelope("task-running");
        verify(taskRepository, never()).save(any(CodexTaskEntity.class));
    }

    private CodexTaskEntity task(
            String taskId,
            String sessionId,
            String status,
            LocalDateTime createdAt) {
        CodexTaskEntity task = new CodexTaskEntity();
        task.setTaskId(taskId);
        task.setSessionId(sessionId);
        task.setWorkerId("worker-1");
        task.setUserId("owner");
        task.setStatus(status);
        task.setPrompt("prompt " + taskId);
        task.setCreatedAt(createdAt);
        task.setUpdatedAt(createdAt);
        task.setLastOutputAt(createdAt);
        return task;
    }
}
