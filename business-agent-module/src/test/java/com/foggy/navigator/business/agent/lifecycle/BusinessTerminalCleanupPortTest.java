package com.foggy.navigator.business.agent.lifecycle;

import com.foggy.navigator.business.agent.service.BusinessTaskScopedTokenLifecycleService;
import com.foggy.navigator.business.agent.service.RuntimeRequestAuditService;
import com.foggy.navigator.spi.lifecycle.TerminalCleanupCompletenessPort;
import com.foggy.navigator.spi.lifecycle.TerminalCleanupContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BusinessTerminalCleanupPortTest {

    @Test
    void tokenAndReceiptCleanupRevokeAuthorityAndProjectInactiveRegistration() {
        BusinessTaskScopedTokenLifecycleService tokens =
                mock(BusinessTaskScopedTokenLifecycleService.class);
        RuntimeRequestAuditService audits =
                mock(RuntimeRequestAuditService.class);
        BusinessTerminalCleanupPort port =
                new BusinessTerminalCleanupPort(tokens, audits);
        TerminalCleanupContext context = new TerminalCleanupContext(
                "task-cleanup", "session-cleanup", "codex-biz-worker",
                "tenant-cleanup", "provider-task-cleanup", "owner-cleanup",
                "agent-cleanup", "operation-cleanup", "request-cleanup",
                "CANCELLED");

        port.execute(
                "PHYSICAL_TOKEN_REVOKE", context,
                "cleanup:task-cleanup:token");
        port.execute(
                "TERMINATION_COMPAT_RECEIPT", context,
                "cleanup:task-cleanup:receipt");

        verify(tokens).revokeTaskScopedTokensForTask(
                "tenant-cleanup", "task-cleanup",
                "lifecycle-owner", "canonical task terminal");
        var evidence = forClass(
                RuntimeRequestAuditService.TaskEvidence.class);
        verify(audits).refreshCompletedTaskOperation(
                org.mockito.ArgumentMatchers.eq("request-cleanup"),
                org.mockito.ArgumentMatchers.eq("task-cleanup"),
                org.mockito.ArgumentMatchers.eq(
                        RuntimeRequestAuditService.OPERATION_TASK_TERMINATE),
                evidence.capture());
        assertThat(evidence.getValue().taskTokenStatus())
                .isEqualTo("REVOKED");
        assertThat(evidence.getValue().taskStatus()).isEqualTo("ABORTED");
        assertThat(evidence.getValue().taskTerminal()).isTrue();
    }

    @Test
    void completenessReportsOnlyDurableTokenRevocationState() {
        BusinessTaskScopedTokenLifecycleService tokens =
                mock(BusinessTaskScopedTokenLifecycleService.class);
        BusinessTerminalCleanupCompletenessPort port =
                new BusinessTerminalCleanupCompletenessPort(tokens);
        TerminalCleanupContext context = new TerminalCleanupContext(
                "task-cleanup", "session-cleanup", "codex-biz-worker",
                "tenant-cleanup", "provider-task-cleanup", "owner-cleanup",
                "agent-cleanup", "operation-cleanup", null, "FAILED");
        when(tokens.taskScopedTokensRevokedOrAbsent("tenant-cleanup", "task-cleanup"))
                .thenReturn(false);

        var facts = port.assess(context);

        assertThat(port.supports(context)).isTrue();
        assertThat(facts).containsExactly(
                new TerminalCleanupCompletenessPort.ParticipantCompleteness(
                        "PHYSICAL_TOKEN_REVOKE", false));
    }
}
