package com.foggy.navigator.launcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.claude.worker.model.entity.ClaudeWorkerEntity;
import com.foggy.navigator.claude.worker.service.ClaudeTaskService;
import com.foggy.navigator.claude.worker.service.ClaudeWorkerService;
import com.foggy.navigator.claude.worker.service.WorkerStreamRelay;
import com.foggy.navigator.claude.worker.spi.ClaudeWorkerFacadeImpl;
import com.foggy.navigator.codex.worker.controller.CodexRuntimeController;
import com.foggy.navigator.codex.worker.model.dto.CodexRuntimeAvailabilityDTO;
import com.foggy.navigator.codex.worker.service.CodexRuntimeRateLimitsService;
import com.foggy.navigator.codex.worker.service.CodexRuntimeRegistryService;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.CurrentUser;
import com.foggy.navigator.common.repository.WorkingDirectoryRepository;
import com.foggy.navigator.spi.auth.UserAuthService;
import com.foggy.navigator.spi.config.LlmModelManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CodexRuntimeWorkerOwnershipIntegrationTest {

    @Mock
    private ClaudeWorkerService workerService;
    @Mock
    private ClaudeTaskService taskService;
    @Mock
    private WorkerStreamRelay streamRelay;
    @Mock
    private WorkingDirectoryRepository directoryRepository;
    @Mock
    private LlmModelManager llmModelManager;
    @Mock
    private UserAuthService userAuthService;
    @Mock
    private CodexRuntimeRegistryService runtimeRegistryService;
    @Mock
    private CodexRuntimeRateLimitsService runtimeRateLimitsService;

    private CodexRuntimeController controller;

    @BeforeEach
    void setUp() {
        ClaudeWorkerFacadeImpl workerFacade = new ClaudeWorkerFacadeImpl(
                workerService,
                taskService,
                streamRelay,
                directoryRepository,
                llmModelManager,
                userAuthService,
                new ObjectMapper(),
                Runnable::run
        );
        controller = new CodexRuntimeController(runtimeRegistryService, runtimeRateLimitsService, workerFacade);

        ClaudeWorkerEntity worker = new ClaudeWorkerEntity();
        worker.setWorkerId("worker-1");
        worker.setUserId("owner-1");
        worker.setTenantId("tenant-1");
        when(workerService.getWorkerEntity("worker-1")).thenReturn(worker);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void sameTenantNonOwnerCanReadAvailabilityButOnlyOwnerCanListRuntimeDetails() {
        when(runtimeRegistryService.availability("worker-1", null))
                .thenReturn(CodexRuntimeAvailabilityDTO.builder()
                        .appServerManaged(true)
                        .ultraAvailable(true)
                        .build());
        when(runtimeRegistryService.listByWorker("worker-1")).thenReturn(List.of());

        setCurrentUser("member-1", "tenant-1");

        assertDoesNotThrow(() -> controller.availability("worker-1", null));
        assertThrows(IllegalArgumentException.class, () -> controller.list("worker-1"));
        verify(runtimeRegistryService).availability("worker-1", null);
        verify(runtimeRegistryService, never()).listByWorker("worker-1");

        setCurrentUser("owner-1", "tenant-1");

        assertDoesNotThrow(() -> controller.list("worker-1"));
        verify(runtimeRegistryService).listByWorker("worker-1");
    }

    private void setCurrentUser(String userId, String tenantId) {
        UserContext.setCurrentUser(CurrentUser.builder()
                .userId(userId)
                .tenantId(tenantId)
                .build());
    }
}
