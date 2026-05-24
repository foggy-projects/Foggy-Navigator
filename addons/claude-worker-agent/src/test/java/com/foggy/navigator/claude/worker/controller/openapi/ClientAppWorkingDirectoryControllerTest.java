package com.foggy.navigator.claude.worker.controller.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.business.agent.model.dto.ClientAppControlPlanePrincipal;
import com.foggy.navigator.business.agent.service.ClientAppControlCredentialService;
import com.foggy.navigator.claude.worker.model.dto.WorkingDirectoryDTO;
import com.foggy.navigator.claude.worker.model.entity.ClaudeWorkerEntity;
import com.foggy.navigator.claude.worker.model.form.ClientAppDirectoryInitForm;
import com.foggy.navigator.claude.worker.repository.ClaudeWorkerRepository;
import com.foggy.navigator.claude.worker.service.ClaudeWorkerService;
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

class ClientAppWorkingDirectoryControllerTest {

    private final ClientAppControlCredentialService controlCredentialService =
            mock(ClientAppControlCredentialService.class);
    private final ClaudeWorkerService workerService = mock(ClaudeWorkerService.class);
    private final WorkingDirectoryService directoryService = mock(WorkingDirectoryService.class);
    private final ClaudeWorkerFacade claudeWorkerFacade = mock(ClaudeWorkerFacade.class);
    private final ClaudeWorkerRepository workerRepository = mock(ClaudeWorkerRepository.class);
    private final WorkingDirectoryRepository directoryRepository = mock(WorkingDirectoryRepository.class);

    private final ClientAppWorkingDirectoryController controller =
            new ClientAppWorkingDirectoryController(
                    controlCredentialService,
                    workerService,
                    directoryService,
                    claudeWorkerFacade,
                    workerRepository,
                    directoryRepository,
                    new ObjectMapper());

    @Test
    void initDirectory_stampsClientAppSharedOwnerAndScope() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        ClientAppControlPlanePrincipal principal = principal("app-1");
        ClaudeWorkerEntity worker = worker("worker-1", "user-1", "tenant-1");
        ClientAppDirectoryInitForm form = new ClientAppDirectoryInitForm();
        form.setWorkerId("worker-1");
        form.setPath("/workspace/app-shared");
        form.setProjectName("app-shared");
        form.setWorkspaceScope(WorkspaceScope.CLIENT_APP_SHARED);
        form.setFiles(Map.of("README.md", "# app"));
        WorkingDirectoryEntity directory = directory("dir-1", "worker-1", "user-1",
                null, null, null, null, null);

        when(controlCredentialService.requireAccess(
                same(request),
                eq(ClientAppControlCredentialService.SCOPE_WORKING_DIRECTORY_MANAGE),
                eq("app-1")))
                .thenReturn(principal);
        when(workerRepository.findByWorkerId("worker-1")).thenReturn(Optional.of(worker));
        when(directoryRepository.findByWorkerIdAndPathAndUserId("worker-1", "/workspace/app-shared", "user-1"))
                .thenReturn(Optional.empty());
        when(claudeWorkerFacade.initDirectory(
                eq("user-1"), eq("worker-1"), eq("/workspace/app-shared"), same(form.getFiles()), eq("app-shared")))
                .thenReturn("dir-1");
        when(directoryRepository.findByDirectoryId("dir-1")).thenReturn(Optional.of(directory));

        RX<WorkingDirectoryDTO> result = controller.initDirectory(request, "app-1", form);

        assertEquals("dir-1", result.getData().getDirectoryId());
        assertEquals(ResourceOwnerType.CLIENT_APP, directory.getOwnerType());
        assertEquals("app-1", directory.getOwnerId());
        assertEquals("app-1", directory.getClientAppId());
        assertEquals(WorkspaceScope.CLIENT_APP_SHARED, directory.getWorkspaceScope());
        assertEquals(WorkingDirectoryResolverType.DELEGATED, directory.getResolverType());
        verify(directoryRepository).save(directory);
    }

    @Test
    void initDirectory_stampsUserPrivateOwnerAndScope() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        ClientAppControlPlanePrincipal principal = principal("app-1");
        ClaudeWorkerEntity worker = worker("worker-1", "user-1", "tenant-1");
        ClientAppDirectoryInitForm form = new ClientAppDirectoryInitForm();
        form.setWorkerId("worker-1");
        form.setPath("/workspace/user-a");
        form.setProjectName("user-a");
        form.setWorkspaceScope(WorkspaceScope.USER_PRIVATE);
        form.setUpstreamUserId("u-1");
        form.setFiles(Map.of("README.md", "# app"));
        WorkingDirectoryEntity directory = directory("dir-1", "worker-1", "user-1",
                null, null, null, null, null);

        when(controlCredentialService.requireAccess(
                same(request),
                eq(ClientAppControlCredentialService.SCOPE_WORKING_DIRECTORY_MANAGE),
                eq("app-1")))
                .thenReturn(principal);
        when(workerRepository.findByWorkerId("worker-1")).thenReturn(Optional.of(worker));
        when(directoryRepository.findByWorkerIdAndPathAndUserId("worker-1", "/workspace/user-a", "user-1"))
                .thenReturn(Optional.empty());
        when(claudeWorkerFacade.initDirectory(
                eq("user-1"), eq("worker-1"), eq("/workspace/user-a"), same(form.getFiles()), eq("user-a")))
                .thenReturn("dir-1");
        when(directoryRepository.findByDirectoryId("dir-1")).thenReturn(Optional.of(directory));

        RX<WorkingDirectoryDTO> result = controller.initDirectory(request, "app-1", form);

        assertEquals("dir-1", result.getData().getDirectoryId());
        assertEquals(ResourceOwnerType.UPSTREAM_USER, directory.getOwnerType());
        assertEquals("app-1:u-1", directory.getOwnerId());
        assertEquals("app-1", directory.getClientAppId());
        assertEquals("u-1", directory.getUpstreamUserId());
        assertEquals(WorkspaceScope.USER_PRIVATE, directory.getWorkspaceScope());
    }

    @Test
    void listDirectories_filtersToCurrentClientAppResources() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        ClientAppControlPlanePrincipal principal = principal("app-1");
        WorkingDirectoryEntity shared = directory("dir-shared", "worker-1", "user-1",
                "tenant-1", ResourceOwnerType.CLIENT_APP, "app-1", "app-1", null);
        shared.setWorkspaceScope(WorkspaceScope.CLIENT_APP_SHARED);
        WorkingDirectoryEntity userPrivate = directory("dir-user", "worker-1", "user-1",
                "tenant-1", ResourceOwnerType.UPSTREAM_USER, "app-1:u-1", "app-1", "u-1");
        userPrivate.setWorkspaceScope(WorkspaceScope.USER_PRIVATE);
        WorkingDirectoryEntity otherApp = directory("dir-other", "worker-1", "user-1",
                "tenant-1", ResourceOwnerType.CLIENT_APP, "app-2", "app-2", null);
        otherApp.setWorkspaceScope(WorkspaceScope.CLIENT_APP_SHARED);
        WorkingDirectoryEntity upstreamSystem = directory("dir-ups", "worker-1", "user-1",
                "tenant-1", ResourceOwnerType.UPSTREAM_SYSTEM, "ups-1", null, null);
        upstreamSystem.setWorkspaceScope(WorkspaceScope.UPSTREAM_SYSTEM_SHARED);

        when(controlCredentialService.requireAccess(
                same(request),
                eq(ClientAppControlCredentialService.SCOPE_WORKING_DIRECTORY_MANAGE),
                eq("app-1")))
                .thenReturn(principal);
        when(directoryRepository.findByTenantId("tenant-1"))
                .thenReturn(List.of(shared, userPrivate, otherApp, upstreamSystem));

        RX<List<WorkingDirectoryDTO>> result = controller.listDirectories(
                request, "app-1", null, null, null);

        assertEquals(2, result.getData().size());
        assertEquals(List.of("dir-shared", "dir-user"),
                result.getData().stream().map(WorkingDirectoryDTO::getDirectoryId).toList());
    }

    @Test
    void getDirectory_rejectsOtherClientAppDirectory() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        ClientAppControlPlanePrincipal principal = principal("app-1");
        WorkingDirectoryEntity other = directory("dir-other", "worker-1", "user-1",
                "tenant-1", ResourceOwnerType.CLIENT_APP, "app-2", "app-2", null);
        other.setWorkspaceScope(WorkspaceScope.CLIENT_APP_SHARED);

        when(controlCredentialService.requireAccess(
                same(request),
                eq(ClientAppControlCredentialService.SCOPE_WORKING_DIRECTORY_MANAGE),
                eq("app-1")))
                .thenReturn(principal);
        when(directoryRepository.findByDirectoryId("dir-other")).thenReturn(Optional.of(other));

        assertThrows(SecurityException.class, () -> controller.getDirectory(request, "app-1", "dir-other"));
    }

    private ClientAppControlPlanePrincipal principal(String clientAppId) {
        return ClientAppControlPlanePrincipal.builder()
                .tenantId("tenant-1")
                .clientAppId(clientAppId)
                .credentialId("cred-1")
                .actorUserId("client-app-control:cred-1")
                .principalType("CLIENT_APP")
                .principalId(clientAppId)
                .scopes(Set.of(ClientAppControlCredentialService.SCOPE_WORKING_DIRECTORY_MANAGE))
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
                                             String ownerId,
                                             String clientAppId,
                                             String upstreamUserId) {
        WorkingDirectoryEntity directory = new WorkingDirectoryEntity();
        directory.setDirectoryId(directoryId);
        directory.setWorkerId(workerId);
        directory.setUserId(userId);
        directory.setTenantId(tenantId);
        directory.setOwnerType(ownerType);
        directory.setOwnerId(ownerId);
        directory.setClientAppId(clientAppId);
        directory.setUpstreamUserId(upstreamUserId);
        directory.setWorkspaceScope(WorkspaceScope.CLIENT_APP_SHARED);
        directory.setResolverType(WorkingDirectoryResolverType.DELEGATED);
        directory.setProjectName(directoryId);
        directory.setPath("/workspace/" + directoryId);
        directory.setDirectoryType("STANDARD");
        directory.setEnabled(true);
        return directory;
    }
}
