package com.foggy.navigator.session.service;

import com.foggy.navigator.common.entity.TerminationOperationEntity;
import com.foggy.navigator.session.repository.TerminationOperationRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TerminationOperationServiceTest {

    @Test
    void rejectsManualPidKillWithoutNonblankAuthorizationDecisionId() {
        TerminationOperationRepository repository = mock(TerminationOperationRepository.class);
        TerminationOperationService service = new TerminationOperationService(repository);

        IllegalArgumentException missingError = assertThrows(IllegalArgumentException.class,
                () -> service.accept(manualPidKillCommand(null)));
        IllegalArgumentException blankError = assertThrows(IllegalArgumentException.class,
                () -> service.accept(manualPidKillCommand("   ")));

        assertEquals("TERMINATION_MANUAL_PID_AUTHORIZATION_REQUIRED", missingError.getMessage());
        assertEquals("TERMINATION_MANUAL_PID_AUTHORIZATION_REQUIRED", blankError.getMessage());
        verifyNoInteractions(repository);
    }

    @Test
    void acceptsManualPidKillWithAuthorizationDecisionId() {
        TerminationOperationRepository repository = mock(TerminationOperationRepository.class);
        TerminationOperationService service = new TerminationOperationService(repository);
        when(repository.saveAndFlush(any(TerminationOperationEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TerminationOperationEntity operation = service.accept(
                manualPidKillCommand("authz-v1:tenant_admin_manual:decision-42"));

        assertEquals("MANUAL_PID_KILL", operation.getKind());
        assertEquals("authz-v1:tenant_admin_manual:decision-42", operation.getAuthorizationDecisionId());
    }

    @Test
    void rejectsManualPidKillForNonAdminActorType() {
        TerminationOperationRepository repository = mock(TerminationOperationRepository.class);
        TerminationOperationService service = new TerminationOperationService(repository);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.accept(manualPidKillCommand("authz-v1:user:decision-42", "USER")));

        assertEquals("TERMINATION_MANUAL_PID_AUTHORIZATION_REQUIRED", error.getMessage());
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsManualPidKillWithCallerControlledAuthorizationDecision() {
        TerminationOperationRepository repository = mock(TerminationOperationRepository.class);
        TerminationOperationService service = new TerminationOperationService(repository);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.accept(manualPidKillCommand("decision-42")));

        assertEquals("TERMINATION_MANUAL_PID_AUTHORIZATION_REQUIRED", error.getMessage());
        verifyNoInteractions(repository);
    }

    @Test
    void ownedAuditProjectionIncludesImmutableProcessIdentity() {
        TerminationOperationRepository repository = mock(TerminationOperationRepository.class);
        TerminationOperationService service = new TerminationOperationService(repository);
        TerminationOperationEntity operation = new TerminationOperationEntity();
        operation.setOperationId("to-process-identity");
        operation.setTaskId("task-1");
        operation.setOwnerUserId("user-1");
        operation.setTenantId("tenant-1");
        operation.setExpectedPid(321);
        operation.setExpectedProcessIdentity("codex-cli:321:2026-07-16T03:40:13.655Z");

        when(repository.findByTaskIdAndOwnerUserIdAndTenantIdOrderByCreatedAtDesc(
                "task-1", "user-1", "tenant-1")).thenReturn(List.of(operation));

        var result = service.findOwned("task-1", "user-1", "tenant-1");

        assertEquals(1, result.size());
        assertEquals(321, result.get(0).getExpectedPid());
        assertEquals("codex-cli:321:2026-07-16T03:40:13.655Z",
                result.get(0).getExpectedProcessIdentity());
    }

    @Test
    void acceptsStaleTurnInterruptWithServerIssuedAuthorizationAndNoProcessFields() {
        TerminationOperationRepository repository = mock(TerminationOperationRepository.class);
        TerminationOperationService service = new TerminationOperationService(repository);
        when(repository.saveAndFlush(any(TerminationOperationEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TerminationOperationEntity operation = service.accept(staleTurnCommand(
                "UPSTREAM_USER", "authz-v1:task_owner_stale_turn_cleanup:decision-42", null, null));

        assertEquals("STALE_TURN_INTERRUPT", operation.getKind());
        assertEquals("provider-task-1", operation.getProviderTaskId());
        assertEquals("UPSTREAM_USER", operation.getOrigin());
        assertEquals("TASK_OWNER_STALE_TURN_CLEANUP", operation.getActorType());
        assertEquals("ACCEPTED", operation.getStatus());
        assertEquals("PENDING", operation.getDispatchState());
    }

    @Test
    void rejectsStaleTurnInterruptOutsideItsNarrowAuthorizationShape() {
        TerminationOperationRepository repository = mock(TerminationOperationRepository.class);
        TerminationOperationService service = new TerminationOperationService(repository);

        IllegalArgumentException wrongOrigin = assertThrows(IllegalArgumentException.class,
                () -> service.accept(staleTurnCommand(
                        "ADMIN_MANUAL", "authz-v1:task_owner_stale_turn_cleanup:decision-42", null, null)));
        IllegalArgumentException callerControlledAuthorization = assertThrows(IllegalArgumentException.class,
                () -> service.accept(staleTurnCommand("UPSTREAM_USER", "decision-42", null, null)));
        IllegalArgumentException pidShape = assertThrows(IllegalArgumentException.class,
                () -> service.accept(staleTurnCommand(
                        "UPSTREAM_USER", "authz-v1:task_owner_stale_turn_cleanup:decision-42",
                        321, "codex-cli:321")));

        assertEquals("TERMINATION_STALE_TURN_AUTHORIZATION_REQUIRED", wrongOrigin.getMessage());
        assertEquals("TERMINATION_STALE_TURN_AUTHORIZATION_REQUIRED",
                callerControlledAuthorization.getMessage());
        assertEquals("TERMINATION_STALE_TURN_AUTHORIZATION_REQUIRED", pidShape.getMessage());
        verifyNoInteractions(repository);
    }

    @Test
    void markFailedUnconfirmedClosesStaleCleanupAttemptForSafeRetry() {
        TerminationOperationRepository repository = mock(TerminationOperationRepository.class);
        TerminationOperationService service = new TerminationOperationService(repository);
        TerminationOperationEntity operation = new TerminationOperationEntity();
        operation.setOperationId("to-stale-1");
        operation.setStatus("RUNNING");
        operation.setDispatchState("PENDING");
        when(repository.findByOperationIdForUpdate("to-stale-1")).thenReturn(Optional.of(operation));

        service.markFailedUnconfirmed("to-stale-1", "STALE_TURN_CLEANUP_UNCONFIRMED");

        assertEquals("FAILED", operation.getStatus());
        assertEquals("UNCONFIRMED", operation.getDispatchState());
        assertEquals("TERMINATION_UNCONFIRMED", operation.getAttentionCode());
        assertEquals("STALE_TURN_CLEANUP_UNCONFIRMED", operation.getFailureCode());
        verify(repository).save(operation);
    }

    private static TerminationOperationService.CreateCommand manualPidKillCommand(
            String authorizationDecisionId) {
        return manualPidKillCommand(authorizationDecisionId, "TENANT_ADMIN_MANUAL");
    }

    private static TerminationOperationService.CreateCommand manualPidKillCommand(
            String authorizationDecisionId, String actorType) {
        return new TerminationOperationService.CreateCommand(
                "task-1",
                "provider-task-1",
                "session-1",
                "user-1",
                "tenant-1",
                "CODEX",
                "worker-1",
                "MANUAL_PID_KILL",
                "ADMIN_MANUAL",
                "admin-1",
                actorType,
                authorizationDecisionId,
                "USER_REQUESTED_TERMINATION",
                "correlation-1",
                321,
                "codex-cli:321:2026-07-16T03:40:13.655Z",
                300);
    }

    private static TerminationOperationService.CreateCommand staleTurnCommand(
            String origin, String authorizationDecisionId,
            Integer expectedPid, String expectedProcessIdentity) {
        return new TerminationOperationService.CreateCommand(
                "task-1",
                "provider-task-1",
                "session-1",
                "user-1",
                null,
                "codex-app-server-worker",
                "worker-1",
                "STALE_TURN_INTERRUPT",
                origin,
                "user-1",
                "TASK_OWNER_STALE_TURN_CLEANUP",
                authorizationDecisionId,
                "STALE_TURN_CLEANUP",
                "stale-turn-cleanup:correlation-1",
                expectedPid,
                expectedProcessIdentity,
                300);
    }
}
