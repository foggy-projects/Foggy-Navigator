package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.claude.worker.model.dto.CodingAgentDTO;
import com.foggy.navigator.claude.worker.model.form.RegisterAgentForm;
import com.foggy.navigator.claude.worker.model.form.UpdateAgentForm;
import com.foggy.navigator.claude.worker.repository.AgentDirectoryBindingRepository;
import com.foggy.navigator.claude.worker.repository.CodingAgentRepository;
import com.foggy.navigator.claude.worker.repository.WorkingDirectoryRepository;
import com.foggy.navigator.claude.worker.model.entity.ClaudeWorkerEntity;
import com.foggy.navigator.claude.worker.model.entity.WorkingDirectoryEntity;
import com.foggy.navigator.common.entity.AgentDirectoryBindingEntity;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CodingAgentServiceTest {

    private CodingAgentRepository agentRepository;
    private AgentDirectoryBindingRepository bindingRepository;
    private ClaudeWorkerService workerService;
    private WorkingDirectoryRepository directoryRepository;
    private CodingAgentService service;

    private static final String USER_ID = "user-1";
    private static final String TENANT_ID = "tenant-1";
    private static final String WORKER_ID = "worker-1";
    private static final String DIR_ID = "dir-1";
    private static final String AGENT_ID = "agent-001";

    @BeforeEach
    void setUp() {
        agentRepository = mock(CodingAgentRepository.class);
        bindingRepository = mock(AgentDirectoryBindingRepository.class);
        workerService = mock(ClaudeWorkerService.class);
        directoryRepository = mock(WorkingDirectoryRepository.class);
        service = new CodingAgentService(agentRepository, bindingRepository, workerService, directoryRepository);

        // Default: worker belongs to user
        ClaudeWorkerEntity worker = createWorker();
        when(workerService.getWorkerEntity(WORKER_ID)).thenReturn(worker);

        // Default: directory exists
        when(directoryRepository.findByDirectoryIdAndUserId(DIR_ID, USER_ID))
                .thenReturn(Optional.of(createDirectory(DIR_ID)));

        // Default: save returns argument
        when(agentRepository.save(any())).thenAnswer(inv -> {
            CodingAgentEntity e = inv.getArgument(0);
            e.setCreatedAt(LocalDateTime.now());
            e.setUpdatedAt(LocalDateTime.now());
            return e;
        });
        when(bindingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ========== Register ==========

    @Nested
    class RegisterTests {

        @Test
        void registerAgent_success() {
            when(bindingRepository.findByAgentId(anyString())).thenReturn(List.of());
            when(directoryRepository.findByDirectoryId(DIR_ID)).thenReturn(Optional.of(createDirectory(DIR_ID)));

            RegisterAgentForm form = new RegisterAgentForm();
            form.setName("payment-agent");
            form.setDescription("Handles payment service");
            form.setWorkerId(WORKER_ID);
            form.setDefaultDirectoryId(DIR_ID);
            form.setDefaultBranch("dev");

            CodingAgentDTO result = service.registerAgent(USER_ID, TENANT_ID, form);

            assertNotNull(result);
            assertEquals("payment-agent", result.getName());
            assertEquals("Handles payment service", result.getDescription());
            assertEquals("LOCAL_CLAUDE_WORKER", result.getAgentType());
            assertEquals(WORKER_ID, result.getWorkerId());
            assertEquals(DIR_ID, result.getDefaultDirectoryId());
            assertEquals("dev", result.getDefaultBranch());

            // Verify entity + binding saved
            verify(agentRepository).save(any(CodingAgentEntity.class));
            verify(bindingRepository).save(any(AgentDirectoryBindingEntity.class));
        }

        @Test
        void registerAgent_autoBindsDefaultDirectory() {
            when(bindingRepository.findByAgentId(anyString())).thenReturn(List.of());
            when(directoryRepository.findByDirectoryId(DIR_ID)).thenReturn(Optional.of(createDirectory(DIR_ID)));

            RegisterAgentForm form = new RegisterAgentForm();
            form.setName("test-agent");
            form.setWorkerId(WORKER_ID);
            form.setDefaultDirectoryId(DIR_ID);

            service.registerAgent(USER_ID, TENANT_ID, form);

            ArgumentCaptor<AgentDirectoryBindingEntity> captor =
                    ArgumentCaptor.forClass(AgentDirectoryBindingEntity.class);
            verify(bindingRepository).save(captor.capture());
            assertEquals(DIR_ID, captor.getValue().getDirectoryId());
        }

        @Test
        void registerAgent_missingName_throws() {
            RegisterAgentForm form = new RegisterAgentForm();
            form.setWorkerId(WORKER_ID);
            form.setDefaultDirectoryId(DIR_ID);

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.registerAgent(USER_ID, TENANT_ID, form));
            assertTrue(ex.getMessage().contains("name"));
        }

        @Test
        void registerAgent_missingWorkerId_throws() {
            RegisterAgentForm form = new RegisterAgentForm();
            form.setName("test-agent");
            form.setDefaultDirectoryId(DIR_ID);

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.registerAgent(USER_ID, TENANT_ID, form));
            assertTrue(ex.getMessage().contains("workerId"));
        }

        @Test
        void registerAgent_workerNotOwned_throws() {
            ClaudeWorkerEntity otherWorker = new ClaudeWorkerEntity();
            otherWorker.setWorkerId("other-worker");
            otherWorker.setUserId("other-user");
            when(workerService.getWorkerEntity("other-worker")).thenReturn(otherWorker);

            RegisterAgentForm form = new RegisterAgentForm();
            form.setName("test-agent");
            form.setWorkerId("other-worker");
            form.setDefaultDirectoryId(DIR_ID);

            assertThrows(IllegalArgumentException.class,
                    () -> service.registerAgent(USER_ID, TENANT_ID, form));
        }

        @Test
        void registerAgent_directoryNotFound_throws() {
            when(directoryRepository.findByDirectoryIdAndUserId("missing", USER_ID))
                    .thenReturn(Optional.empty());

            RegisterAgentForm form = new RegisterAgentForm();
            form.setName("test-agent");
            form.setWorkerId(WORKER_ID);
            form.setDefaultDirectoryId("missing");

            assertThrows(IllegalArgumentException.class,
                    () -> service.registerAgent(USER_ID, TENANT_ID, form));
        }
    }

    // ========== Update ==========

    @Nested
    class UpdateTests {

        @Test
        void updateAgent_success() {
            CodingAgentEntity existing = createAgentEntity(AGENT_ID);
            when(agentRepository.findByAgentIdAndUserId(AGENT_ID, USER_ID))
                    .thenReturn(Optional.of(existing));
            when(bindingRepository.findByAgentId(AGENT_ID)).thenReturn(List.of());
            when(directoryRepository.findByDirectoryId(DIR_ID)).thenReturn(Optional.of(createDirectory(DIR_ID)));

            UpdateAgentForm form = new UpdateAgentForm();
            form.setName("updated-agent");
            form.setDescription("Updated description");
            form.setDefaultBranch("main");

            CodingAgentDTO result = service.updateAgent(USER_ID, AGENT_ID, form);

            assertEquals("updated-agent", result.getName());
            assertEquals("Updated description", result.getDescription());
            assertEquals("main", result.getDefaultBranch());
            verify(agentRepository).save(any());
        }

        @Test
        void updateAgent_notFound_throws() {
            when(agentRepository.findByAgentIdAndUserId("missing", USER_ID))
                    .thenReturn(Optional.empty());

            UpdateAgentForm form = new UpdateAgentForm();
            form.setName("x");

            assertThrows(IllegalArgumentException.class,
                    () -> service.updateAgent(USER_ID, "missing", form));
        }
    }

    // ========== Delete ==========

    @Test
    void deleteAgent_success() {
        CodingAgentEntity existing = createAgentEntity(AGENT_ID);
        when(agentRepository.findByAgentIdAndUserId(AGENT_ID, USER_ID))
                .thenReturn(Optional.of(existing));

        service.deleteAgent(USER_ID, AGENT_ID);

        verify(bindingRepository).deleteByAgentId(AGENT_ID);
        verify(agentRepository).delete(existing);
    }

    // ========== Bind / Unbind ==========

    @Nested
    class BindingTests {

        @Test
        void bindDirectory_success() {
            CodingAgentEntity agent = createAgentEntity(AGENT_ID);
            when(agentRepository.findByAgentIdAndUserId(AGENT_ID, USER_ID))
                    .thenReturn(Optional.of(agent));
            when(bindingRepository.findByAgentIdAndDirectoryId(AGENT_ID, DIR_ID))
                    .thenReturn(Optional.empty());

            service.bindDirectory(USER_ID, AGENT_ID, DIR_ID);

            ArgumentCaptor<AgentDirectoryBindingEntity> captor =
                    ArgumentCaptor.forClass(AgentDirectoryBindingEntity.class);
            verify(bindingRepository).save(captor.capture());
            assertEquals(AGENT_ID, captor.getValue().getAgentId());
            assertEquals(DIR_ID, captor.getValue().getDirectoryId());
        }

        @Test
        void bindDirectory_idempotent() {
            CodingAgentEntity agent = createAgentEntity(AGENT_ID);
            when(agentRepository.findByAgentIdAndUserId(AGENT_ID, USER_ID))
                    .thenReturn(Optional.of(agent));
            AgentDirectoryBindingEntity existingBinding = new AgentDirectoryBindingEntity();
            existingBinding.setAgentId(AGENT_ID);
            existingBinding.setDirectoryId(DIR_ID);
            when(bindingRepository.findByAgentIdAndDirectoryId(AGENT_ID, DIR_ID))
                    .thenReturn(Optional.of(existingBinding));

            service.bindDirectory(USER_ID, AGENT_ID, DIR_ID);

            // Should NOT save again
            verify(bindingRepository, never()).save(any());
        }

        @Test
        void unbindDirectory_success() {
            CodingAgentEntity agent = createAgentEntity(AGENT_ID);
            when(agentRepository.findByAgentIdAndUserId(AGENT_ID, USER_ID))
                    .thenReturn(Optional.of(agent));

            service.unbindDirectory(USER_ID, AGENT_ID, DIR_ID);

            verify(bindingRepository).deleteByAgentIdAndDirectoryId(AGENT_ID, DIR_ID);
        }
    }

    // ========== List with enrichment ==========

    @Test
    void listAgents_returnsWithEnrichment() {
        CodingAgentEntity agent = createAgentEntity(AGENT_ID);
        when(agentRepository.findByUserIdOrderByCreatedAtDesc(USER_ID))
                .thenReturn(List.of(agent));
        when(directoryRepository.findByDirectoryId(DIR_ID))
                .thenReturn(Optional.of(createDirectory(DIR_ID)));

        AgentDirectoryBindingEntity binding = new AgentDirectoryBindingEntity();
        binding.setAgentId(AGENT_ID);
        binding.setDirectoryId(DIR_ID);
        when(bindingRepository.findByAgentId(AGENT_ID)).thenReturn(List.of(binding));

        List<CodingAgentDTO> result = service.listAgents(USER_ID);

        assertEquals(1, result.size());
        CodingAgentDTO dto = result.get(0);
        assertEquals("test-agent", dto.getName());
        assertEquals("test-worker", dto.getWorkerName());
        assertNotNull(dto.getDefaultDirectory());
        assertEquals(DIR_ID, dto.getDefaultDirectory().getDirectoryId());
        assertEquals(1, dto.getAuthorizedDirectories().size());
    }

    // ========== Helpers ==========

    private ClaudeWorkerEntity createWorker() {
        ClaudeWorkerEntity worker = new ClaudeWorkerEntity();
        worker.setWorkerId(WORKER_ID);
        worker.setUserId(USER_ID);
        worker.setName("test-worker");
        worker.setBaseUrl("http://localhost:3031");
        return worker;
    }

    private WorkingDirectoryEntity createDirectory(String directoryId) {
        WorkingDirectoryEntity dir = new WorkingDirectoryEntity();
        dir.setDirectoryId(directoryId);
        dir.setWorkerId(WORKER_ID);
        dir.setUserId(USER_ID);
        dir.setTenantId(TENANT_ID);
        dir.setProjectName("test-project-" + directoryId);
        dir.setPath("/home/user/" + directoryId);
        dir.setDirectoryType("STANDARD");
        dir.setGitBranch("main");
        return dir;
    }

    private CodingAgentEntity createAgentEntity(String agentId) {
        CodingAgentEntity entity = new CodingAgentEntity();
        entity.setAgentId(agentId);
        entity.setUserId(USER_ID);
        entity.setTenantId(TENANT_ID);
        entity.setName("test-agent");
        entity.setDescription("Test agent");
        entity.setAgentType("LOCAL_CLAUDE_WORKER");
        entity.setWorkerId(WORKER_ID);
        entity.setDefaultDirectoryId(DIR_ID);
        entity.setDefaultBranch("dev");
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        return entity;
    }
}
