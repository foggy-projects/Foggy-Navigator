package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.form.RepairUpstreamSystemWorkingDirectoryForm;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkingDirectoryAdminRepairServiceTest {

    private WorkingDirectoryRepository directoryRepository;
    private BusinessCodingAgentRepository agentRepository;
    private BusinessAgentDirectoryBindingRepository bindingRepository;
    private WorkingDirectoryAdminRepairService service;

    @BeforeEach
    void setUp() {
        directoryRepository = mock(WorkingDirectoryRepository.class);
        agentRepository = mock(BusinessCodingAgentRepository.class);
        bindingRepository = mock(BusinessAgentDirectoryBindingRepository.class);
        service = new WorkingDirectoryAdminRepairService(directoryRepository, agentRepository, bindingRepository);
    }

    @Test
    void repairUpstreamSystemDirectoryMigratesTenantOwnerAndDefaultBinding() {
        WorkingDirectoryEntity directory = new WorkingDirectoryEntity();
        directory.setDirectoryId("20260525-8fa8");
        directory.setTenantId("old-tenant");
        directory.setPath("/workspace/tms");
        directory.setEnabled(true);
        CodingAgentEntity agent = new CodingAgentEntity();
        agent.setTenantId("nav_tms_110");
        agent.setAgentId("tms.ops-root-agent");
        agent.setOwnerType(ResourceOwnerType.UPSTREAM_SYSTEM);
        agent.setOwnerId("TMS");
        agent.setEnabled(true);

        when(directoryRepository.findByDirectoryId("20260525-8fa8")).thenReturn(Optional.of(directory));
        when(agentRepository.findByAgentIdAndTenantId("tms.ops-root-agent", "nav_tms_110"))
                .thenReturn(Optional.of(agent));
        when(directoryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(bindingRepository.findByTenantIdAndAgentIdAndDirectoryId(
                "nav_tms_110", "tms.ops-root-agent", "20260525-8fa8")).thenReturn(Optional.empty());
        when(bindingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(agentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.repairUpstreamSystemDirectory("20260525-8fa8", form());

        assertEquals("20260525-8fa8", result.getDirectoryId());
        assertEquals("nav_tms_110", result.getTenantId());
        assertEquals(ResourceOwnerType.UPSTREAM_SYSTEM, result.getOwnerType());
        assertEquals("TMS", result.getOwnerId());
        assertEquals(WorkspaceScope.UPSTREAM_SYSTEM_SHARED, result.getWorkspaceScope());
        assertEquals("tms.ops-root-agent", result.getRootAgentId());
        assertEquals(ResourceOwnerType.UPSTREAM_SYSTEM, result.getRootAgentOwnerType());
        assertEquals("TMS", result.getRootAgentOwnerId());
        assertFalse(result.getRootAgentOwnerRepaired());
        assertTrue(result.getDefaultDirectory());
        verify(directoryRepository).save(argThat(saved ->
                "nav_tms_110".equals(saved.getTenantId())
                        && saved.getOwnerType() == ResourceOwnerType.UPSTREAM_SYSTEM
                        && "TMS".equals(saved.getOwnerId())
                        && saved.getWorkspaceScope() == WorkspaceScope.UPSTREAM_SYSTEM_SHARED
                        && saved.getClientAppId() == null
                        && saved.getUpstreamUserId() == null));
        verify(bindingRepository).save(argThat(binding ->
                "nav_tms_110".equals(binding.getTenantId())
                        && "tms.ops-root-agent".equals(binding.getAgentId())
                        && "20260525-8fa8".equals(binding.getDirectoryId())));
        verify(agentRepository).save(argThat(saved -> "20260525-8fa8".equals(saved.getDefaultDirectoryId())));
    }

    @Test
    void repairUpstreamSystemDirectoryRejectsAgentOwnedByAnotherSystemUnlessExplicitlyRequested() {
        WorkingDirectoryEntity directory = new WorkingDirectoryEntity();
        directory.setDirectoryId("dir-1");
        CodingAgentEntity agent = new CodingAgentEntity();
        agent.setAgentId("tms.ops-root-agent");
        agent.setOwnerType(ResourceOwnerType.UPSTREAM_SYSTEM);
        agent.setOwnerId("OTHER");
        agent.setEnabled(true);

        when(directoryRepository.findByDirectoryId("dir-1")).thenReturn(Optional.of(directory));
        when(agentRepository.findByAgentIdAndTenantId("tms.ops-root-agent", "nav_tms_110"))
                .thenReturn(Optional.of(agent));

        assertThrows(SecurityException.class, () -> service.repairUpstreamSystemDirectory("dir-1", form()));
    }

    @Test
    void repairUpstreamSystemDirectoryCanRepairLegacyRootAgentOwnerWhenExplicitlyRequested() {
        WorkingDirectoryEntity directory = new WorkingDirectoryEntity();
        directory.setDirectoryId("dir-1");
        directory.setPath("/workspace/tms");
        CodingAgentEntity agent = new CodingAgentEntity();
        agent.setAgentId("tms.ops-root-agent");
        agent.setOwnerType(ResourceOwnerType.UPSTREAM_SYSTEM);
        agent.setOwnerId("OTHER");
        agent.setEnabled(true);

        when(directoryRepository.findByDirectoryId("dir-1")).thenReturn(Optional.of(directory));
        when(agentRepository.findByAgentIdAndTenantId("tms.ops-root-agent", "nav_tms_110"))
                .thenReturn(Optional.of(agent));
        when(directoryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(bindingRepository.findByTenantIdAndAgentIdAndDirectoryId(
                "nav_tms_110", "tms.ops-root-agent", "dir-1")).thenReturn(Optional.empty());
        when(bindingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(agentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RepairUpstreamSystemWorkingDirectoryForm form = form();
        form.setRepairRootAgentOwner(true);

        var result = service.repairUpstreamSystemDirectory("dir-1", form);

        assertTrue(result.getRootAgentOwnerRepaired());
        assertEquals(ResourceOwnerType.UPSTREAM_SYSTEM, result.getRootAgentOwnerType());
        assertEquals("TMS", result.getRootAgentOwnerId());
        verify(agentRepository).save(argThat(saved ->
                saved.getOwnerType() == ResourceOwnerType.UPSTREAM_SYSTEM
                        && "TMS".equals(saved.getOwnerId())
                        && "dir-1".equals(saved.getDefaultDirectoryId())));
    }

    private RepairUpstreamSystemWorkingDirectoryForm form() {
        RepairUpstreamSystemWorkingDirectoryForm form = new RepairUpstreamSystemWorkingDirectoryForm();
        form.setTenantId("nav_tms_110");
        form.setUpstreamSystemId("TMS");
        form.setRootAgentId("tms.ops-root-agent");
        return form;
    }
}
