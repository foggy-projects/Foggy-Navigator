package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.dto.UpstreamClientAppAdminPrincipal;
import com.foggy.navigator.business.agent.model.form.BindAgentWorkspaceForm;
import com.foggy.navigator.business.agent.repository.BusinessAgentDirectoryBindingRepository;
import com.foggy.navigator.business.agent.repository.BusinessCodingAgentRepository;
import com.foggy.navigator.common.entity.AgentDirectoryBindingEntity;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.common.entity.WorkingDirectoryEntity;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import com.foggy.navigator.common.enums.WorkspaceScope;
import com.foggy.navigator.common.repository.WorkingDirectoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentWorkspaceBindingServiceTest {

    private BusinessAgentDirectoryBindingRepository bindingRepository;
    private BusinessCodingAgentRepository agentRepository;
    private WorkingDirectoryRepository workingDirectoryRepository;
    private ClientAppService clientAppService;
    private AgentWorkspaceBindingService service;

    @BeforeEach
    void setUp() {
        bindingRepository = mock(BusinessAgentDirectoryBindingRepository.class);
        agentRepository = mock(BusinessCodingAgentRepository.class);
        workingDirectoryRepository = mock(WorkingDirectoryRepository.class);
        clientAppService = mock(ClientAppService.class);
        service = new AgentWorkspaceBindingService(
                bindingRepository,
                agentRepository,
                workingDirectoryRepository,
                clientAppService);

        when(agentRepository.findByAgentIdAndTenantId("agent-1", "tenant-1"))
                .thenReturn(Optional.of(clientAppAgent()));
        when(bindingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void bind_requiresClientAppOwnedAgentAndVisibleDirectory() {
        when(workingDirectoryRepository.findByDirectoryId("dir-1"))
                .thenReturn(Optional.of(clientAppDirectory("dir-1")));
        when(bindingRepository.findByTenantIdAndAgentIdAndDirectoryId("tenant-1", "agent-1", "dir-1"))
                .thenReturn(Optional.empty());

        var result = service.bind("tenant-1", "capp-1", "agent-1", bindForm(" dir-1 "));

        assertEquals("tenant-1", result.getTenantId());
        assertEquals("capp-1", result.getClientAppId());
        assertEquals("agent-1", result.getAgentId());
        assertEquals("dir-1", result.getDirectoryId());
        assertEquals("project-dir-1", result.getProjectName());
        assertFalse(result.getDefaultDirectory());
        verify(clientAppService).requireActiveClientApp("tenant-1", "capp-1");
        verify(bindingRepository).save(argThat(binding ->
                "tenant-1".equals(binding.getTenantId())
                        && "agent-1".equals(binding.getAgentId())
                        && "dir-1".equals(binding.getDirectoryId())));
    }

    @Test
    void bind_rejectsSystemSharedDirectoryFromClientAppPlane() {
        when(workingDirectoryRepository.findByDirectoryId("dir-1"))
                .thenReturn(Optional.of(systemDirectory("dir-1")));

        assertThrows(SecurityException.class,
                () -> service.bind("tenant-1", "capp-1", "agent-1", bindForm("dir-1")));
    }

    @Test
    void setDefault_updatesAgentDefaultAndEnsuresBinding() {
        CodingAgentEntity agent = clientAppAgent();
        when(agentRepository.findByAgentIdAndTenantId("agent-1", "tenant-1"))
                .thenReturn(Optional.of(agent));
        when(workingDirectoryRepository.findByDirectoryId("dir-2"))
                .thenReturn(Optional.of(clientAppDirectory("dir-2")));
        when(bindingRepository.findByTenantIdAndAgentIdAndDirectoryId("tenant-1", "agent-1", "dir-2"))
                .thenReturn(Optional.empty());
        when(agentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.setDefault("tenant-1", "capp-1", "agent-1", bindForm("dir-2"));

        assertEquals("dir-2", result.getDirectoryId());
        assertTrue(result.getDefaultDirectory());
        verify(agentRepository).save(argThat(saved -> "dir-2".equals(saved.getDefaultDirectoryId())));
    }

    @Test
    void unbind_rejectsDefaultDirectoryBinding() {
        assertThrows(IllegalArgumentException.class,
                () -> service.unbind("tenant-1", "capp-1", "agent-1", " dir-default "));

        verify(bindingRepository, never()).deleteByTenantIdAndAgentIdAndDirectoryId(anyString(), anyString(), anyString());
    }

    @Test
    void bindSystemOwned_requiresSystemOwnedAgentAndSystemDirectory() {
        CodingAgentEntity agent = systemAgent();
        when(agentRepository.findByAgentIdAndTenantId("agent-1", "tenant-1"))
                .thenReturn(Optional.of(agent));
        when(workingDirectoryRepository.findByDirectoryId("dir-1"))
                .thenReturn(Optional.of(systemDirectory("dir-1")));
        when(bindingRepository.findByTenantIdAndAgentIdAndDirectoryId("tenant-1", "agent-1", "dir-1"))
                .thenReturn(Optional.empty());

        var result = service.bindSystemOwned("tenant-1", principal(), "agent-1", bindForm("dir-1"));

        assertEquals("dir-1", result.getDirectoryId());
        assertEquals(WorkspaceScope.UPSTREAM_SYSTEM_SHARED, result.getWorkspaceScope());
        verify(bindingRepository).save(argThat(binding ->
                "tenant-1".equals(binding.getTenantId())
                        && "agent-1".equals(binding.getAgentId())
                        && "dir-1".equals(binding.getDirectoryId())));
    }

    @Test
    void bindSystemOwned_rejectsClientAppOwnedDirectory() {
        CodingAgentEntity agent = systemAgent();
        when(agentRepository.findByAgentIdAndTenantId("agent-1", "tenant-1"))
                .thenReturn(Optional.of(agent));
        when(workingDirectoryRepository.findByDirectoryId("dir-1"))
                .thenReturn(Optional.of(clientAppDirectory("dir-1")));

        assertThrows(SecurityException.class,
                () -> service.bindSystemOwned("tenant-1", principal(), "agent-1", bindForm("dir-1")));
    }

    private BindAgentWorkspaceForm bindForm(String directoryId) {
        BindAgentWorkspaceForm form = new BindAgentWorkspaceForm();
        form.setDirectoryId(directoryId);
        return form;
    }

    private CodingAgentEntity clientAppAgent() {
        CodingAgentEntity agent = new CodingAgentEntity();
        agent.setTenantId("tenant-1");
        agent.setClientAppId("capp-1");
        agent.setAgentId("agent-1");
        agent.setOwnerType(ResourceOwnerType.CLIENT_APP);
        agent.setOwnerId("capp-1");
        agent.setDefaultDirectoryId("dir-default");
        agent.setEnabled(true);
        return agent;
    }

    private CodingAgentEntity systemAgent() {
        CodingAgentEntity agent = new CodingAgentEntity();
        agent.setTenantId("tenant-1");
        agent.setAgentId("agent-1");
        agent.setOwnerType(ResourceOwnerType.UPSTREAM_SYSTEM);
        agent.setOwnerId("ups-1");
        agent.setDefaultDirectoryId("dir-default");
        agent.setEnabled(true);
        return agent;
    }

    private WorkingDirectoryEntity clientAppDirectory(String directoryId) {
        WorkingDirectoryEntity directory = directory(directoryId);
        directory.setOwnerType(ResourceOwnerType.CLIENT_APP);
        directory.setOwnerId("capp-1");
        directory.setClientAppId("capp-1");
        directory.setWorkspaceScope(WorkspaceScope.CLIENT_APP_SHARED);
        return directory;
    }

    private WorkingDirectoryEntity systemDirectory(String directoryId) {
        WorkingDirectoryEntity directory = directory(directoryId);
        directory.setOwnerType(ResourceOwnerType.UPSTREAM_SYSTEM);
        directory.setOwnerId("ups-1");
        directory.setWorkspaceScope(WorkspaceScope.UPSTREAM_SYSTEM_SHARED);
        return directory;
    }

    private WorkingDirectoryEntity directory(String directoryId) {
        WorkingDirectoryEntity directory = new WorkingDirectoryEntity();
        directory.setTenantId("tenant-1");
        directory.setDirectoryId(directoryId);
        directory.setProjectName("project-" + directoryId);
        directory.setRootRef("/work/" + directoryId);
        directory.setPath("/work/" + directoryId);
        directory.setEnabled(true);
        return directory;
    }

    private UpstreamClientAppAdminPrincipal principal() {
        return UpstreamClientAppAdminPrincipal.builder()
                .upstreamSystemId("ups-1")
                .authorizedTenantIds(Set.of("tenant-1"))
                .build();
    }
}
