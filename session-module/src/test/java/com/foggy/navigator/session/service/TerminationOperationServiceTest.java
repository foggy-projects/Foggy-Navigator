package com.foggy.navigator.session.service;

import com.foggy.navigator.common.entity.TerminationOperationEntity;
import com.foggy.navigator.session.repository.TerminationOperationRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
}
