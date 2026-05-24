package com.foggy.navigator.claude.worker.controller.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.business.agent.model.dto.UpstreamClientAppAdminPrincipal;
import com.foggy.navigator.business.agent.service.UpstreamBootstrapRequestService;
import com.foggy.navigator.business.agent.service.UpstreamClientAppAdminCredentialService;
import com.foggy.navigator.claude.worker.model.dto.WorkingDirectoryDTO;
import com.foggy.navigator.claude.worker.model.entity.ClaudeWorkerEntity;
import com.foggy.navigator.claude.worker.model.form.InitDirectoryOpenForm;
import com.foggy.navigator.claude.worker.repository.ClaudeWorkerRepository;
import com.foggy.navigator.claude.worker.service.ClaudeWorkerService;
import com.foggy.navigator.claude.worker.service.WorkerHealthChecker;
import com.foggy.navigator.claude.worker.service.WorkingDirectoryService;
import com.foggy.navigator.common.entity.WorkingDirectoryEntity;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import com.foggy.navigator.common.enums.WorkingDirectoryResolverType;
import com.foggy.navigator.common.enums.WorkspaceScope;
import com.foggy.navigator.common.repository.WorkingDirectoryRepository;
import com.foggy.navigator.spi.claude.ClaudeWorkerFacade;
import com.foggyframework.core.ex.RX;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UpstreamAdminWorkerDirectoryControllerTest {

    private final UpstreamClientAppAdminCredentialService adminCredentialService =
            mock(UpstreamClientAppAdminCredentialService.class);
    private final ClaudeWorkerService workerService = mock(ClaudeWorkerService.class);
    private final WorkingDirectoryService directoryService = mock(WorkingDirectoryService.class);
    private final ClaudeWorkerFacade claudeWorkerFacade = mock(ClaudeWorkerFacade.class);
    private final ClaudeWorkerRepository workerRepository = mock(ClaudeWorkerRepository.class);
    private final WorkingDirectoryRepository directoryRepository = mock(WorkingDirectoryRepository.class);
    private final WorkerHealthChecker healthChecker = mock(WorkerHealthChecker.class);

    private final UpstreamAdminWorkerDirectoryController controller =
            new UpstreamAdminWorkerDirectoryController(
                    adminCredentialService,
                    workerService,
                    directoryService,
                    claudeWorkerFacade,
                    workerRepository,
                    directoryRepository,
                    healthChecker,
                    new ObjectMapper());

    @Test
    void initDirectory_stampsUpstreamSystemOwnerAndScope() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        UpstreamClientAppAdminPrincipal principal = principal("ups-1");
        ClaudeWorkerEntity worker = worker("worker-1", "user-1", "tenant-1");
        InitDirectoryOpenForm form = new InitDirectoryOpenForm();
        form.setWorkerId("worker-1");
        form.setPath("/workspace/app");
        form.setProjectName("app");
        form.setFiles(Map.of("README.md", "# app"));
        WorkingDirectoryEntity directory = directory("dir-1", "worker-1", "user-1", null, null, null);
        directory.setPath("/workspace/app");
        directory.setProjectName("app");

        when(adminCredentialService.requireAccess(
                same(request),
                eq(UpstreamBootstrapRequestService.SCOPE_WORKING_DIRECTORY_MANAGE)))
                .thenReturn(principal);
        when(workerRepository.findByWorkerId("worker-1")).thenReturn(Optional.of(worker));
        when(directoryRepository.findByWorkerIdAndPathAndUserId("worker-1", "/workspace/app", "user-1"))
                .thenReturn(Optional.empty());
        when(claudeWorkerFacade.initDirectory(
                eq("user-1"), eq("worker-1"), eq("/workspace/app"), same(form.getFiles()), eq("app")))
                .thenReturn("dir-1");
        when(directoryRepository.findByDirectoryId("dir-1")).thenReturn(Optional.of(directory));

        RX<WorkingDirectoryDTO> result = controller.initDirectory(request, form);

        assertEquals("dir-1", result.getData().getDirectoryId());
        assertEquals(ResourceOwnerType.UPSTREAM_SYSTEM, directory.getOwnerType());
        assertEquals("ups-1", directory.getOwnerId());
        assertEquals(WorkspaceScope.UPSTREAM_SYSTEM_SHARED, directory.getWorkspaceScope());
        assertEquals(WorkingDirectoryResolverType.DELEGATED, directory.getResolverType());
        assertEquals("/workspace/app", directory.getRootRef());
        assertEquals(ResourceOwnerType.UPSTREAM_SYSTEM, result.getData().getOwnerType());
        assertEquals("ups-1", result.getData().getOwnerId());
        verify(directoryRepository).save(directory);
    }

    @Test
    void listDirectories_filtersToCurrentUpstreamSystemOwner() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        UpstreamClientAppAdminPrincipal principal = principal("ups-1");
        ClaudeWorkerEntity worker = worker("worker-1", "user-1", "tenant-1");
        WorkingDirectoryEntity owned = directory("dir-owned", "worker-1", "user-1",
                "tenant-1", ResourceOwnerType.UPSTREAM_SYSTEM, "ups-1");
        WorkingDirectoryEntity other = directory("dir-other", "worker-1", "user-1",
                "tenant-1", ResourceOwnerType.UPSTREAM_SYSTEM, "ups-2");
        WorkingDirectoryEntity clientApp = directory("dir-client", "worker-1", "user-1",
                "tenant-1", ResourceOwnerType.CLIENT_APP, "app-1");

        when(adminCredentialService.requireAccess(
                same(request),
                eq(UpstreamBootstrapRequestService.SCOPE_WORKING_DIRECTORY_MANAGE)))
                .thenReturn(principal);
        when(workerRepository.findByWorkerId("worker-1")).thenReturn(Optional.of(worker));
        when(directoryRepository.findByWorkerIdOrderByProjectNameAsc("worker-1"))
                .thenReturn(List.of(owned, other, clientApp));

        RX<List<WorkingDirectoryDTO>> result = controller.listDirectories(request, null, "worker-1");

        assertEquals(1, result.getData().size());
        assertEquals("dir-owned", result.getData().get(0).getDirectoryId());
        assertEquals(ResourceOwnerType.UPSTREAM_SYSTEM, result.getData().get(0).getOwnerType());
        assertEquals("ups-1", result.getData().get(0).getOwnerId());
    }

    @Test
    void getDirectory_rejectsDirectoryOwnedByAnotherUpstreamSystem() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        UpstreamClientAppAdminPrincipal principal = principal("ups-1");
        WorkingDirectoryEntity other = directory("dir-other", "worker-1", "user-1",
                "tenant-1", ResourceOwnerType.UPSTREAM_SYSTEM, "ups-2");

        when(adminCredentialService.requireAccess(
                same(request),
                eq(UpstreamBootstrapRequestService.SCOPE_WORKING_DIRECTORY_MANAGE)))
                .thenReturn(principal);
        when(directoryRepository.findByDirectoryId("dir-other")).thenReturn(Optional.of(other));

        assertThrows(SecurityException.class, () -> controller.getDirectory(request, "dir-other"));
    }

    private UpstreamClientAppAdminPrincipal principal(String upstreamSystemId) {
        return UpstreamClientAppAdminPrincipal.builder()
                .credentialId("cred-1")
                .upstreamSystemId(upstreamSystemId)
                .authorizedTenantIds(Set.of("tenant-1"))
                .scopes(Set.of(UpstreamBootstrapRequestService.SCOPE_WORKING_DIRECTORY_MANAGE))
                .build();
    }

    private ClaudeWorkerEntity worker(String workerId, String userId, String tenantId) {
        ClaudeWorkerEntity worker = new ClaudeWorkerEntity();
        worker.setWorkerId(workerId);
        worker.setUserId(userId);
        worker.setTenantId(tenantId);
        return worker;
    }

    private WorkingDirectoryEntity directory(String directoryId,
                                             String workerId,
                                             String userId,
                                             String tenantId,
                                             ResourceOwnerType ownerType,
                                             String ownerId) {
        WorkingDirectoryEntity directory = new WorkingDirectoryEntity();
        directory.setDirectoryId(directoryId);
        directory.setWorkerId(workerId);
        directory.setUserId(userId);
        directory.setTenantId(tenantId);
        directory.setOwnerType(ownerType);
        directory.setOwnerId(ownerId);
        directory.setWorkspaceScope(WorkspaceScope.UPSTREAM_SYSTEM_SHARED);
        directory.setResolverType(WorkingDirectoryResolverType.DELEGATED);
        directory.setProjectName(directoryId);
        directory.setPath("/workspace/" + directoryId);
        directory.setDirectoryType("STANDARD");
        directory.setEnabled(true);
        return directory;
    }
}
