package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.claude.worker.client.ClaudeWorkerClient;
import com.foggy.navigator.claude.worker.model.dto.WorkingDirectoryDTO;
import com.foggy.navigator.claude.worker.model.entity.ClaudeWorkerEntity;
import com.foggy.navigator.claude.worker.model.entity.WorkingDirectoryEntity;
import com.foggy.navigator.claude.worker.model.form.CreateWorkingDirectoryForm;
import com.foggy.navigator.claude.worker.model.form.UpdateWorkingDirectoryForm;
import com.foggy.navigator.claude.worker.repository.WorkingDirectoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkingDirectoryServiceTest {

    private WorkingDirectoryRepository repository;
    private ClaudeWorkerService workerService;
    private WorkingDirectoryService service;

    private static final String USER_ID = "user-1";
    private static final String TENANT_ID = "tenant-1";
    private static final String WORKER_ID = "worker-1";

    @BeforeEach
    void setUp() {
        repository = mock(WorkingDirectoryRepository.class);
        workerService = mock(ClaudeWorkerService.class);
        service = new WorkingDirectoryService(repository, workerService);

        // Default: worker belongs to user
        ClaudeWorkerEntity worker = createWorker();
        when(workerService.getWorkerEntity(WORKER_ID)).thenReturn(worker);
    }

    // ========== createDirectory: PROJECT 验证 ==========

    @Nested
    class CreateDirectoryTests {

        @Test
        void createStandardDirectory_success() {
            when(repository.findByWorkerIdAndPathAndUserId(anyString(), anyString(), anyString()))
                    .thenReturn(Optional.empty());
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            mockGitInfoFailure();

            CreateWorkingDirectoryForm form = new CreateWorkingDirectoryForm();
            form.setWorkerId(WORKER_ID);
            form.setProjectName("my-service");
            form.setPath("/home/user/my-service");

            WorkingDirectoryDTO result = service.createDirectory(USER_ID, TENANT_ID, form);

            assertNotNull(result);
            assertEquals("my-service", result.getProjectName());
            assertEquals("STANDARD", result.getDirectoryType());
            assertNull(result.getParentProjectId());
            verify(repository).save(any());
        }

        @Test
        void createProjectDirectory_success() {
            when(repository.findByWorkerIdAndPathAndUserId(anyString(), anyString(), anyString()))
                    .thenReturn(Optional.empty());
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            mockGitInfoFailure();

            CreateWorkingDirectoryForm form = new CreateWorkingDirectoryForm();
            form.setWorkerId(WORKER_ID);
            form.setProjectName("Foggy-Microservices");
            form.setPath("/home/user/foggy");
            form.setDirectoryType("PROJECT");

            WorkingDirectoryDTO result = service.createDirectory(USER_ID, TENANT_ID, form);

            assertEquals("PROJECT", result.getDirectoryType());
            assertNull(result.getParentProjectId());
        }

        @Test
        void createProject_withParentProjectId_throws() {
            CreateWorkingDirectoryForm form = new CreateWorkingDirectoryForm();
            form.setWorkerId(WORKER_ID);
            form.setProjectName("test");
            form.setPath("/test");
            form.setDirectoryType("PROJECT");
            form.setParentProjectId("some-project");

            when(repository.findByWorkerIdAndPathAndUserId(anyString(), anyString(), anyString()))
                    .thenReturn(Optional.empty());

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.createDirectory(USER_ID, TENANT_ID, form));
            assertTrue(ex.getMessage().contains("PROJECT directory cannot have a parentProjectId"));
        }

        @Test
        void createChild_withValidProjectParent_success() {
            WorkingDirectoryEntity parent = createEntity("proj-1", "PROJECT");
            when(repository.findByDirectoryIdAndUserId("proj-1", USER_ID)).thenReturn(Optional.of(parent));
            when(repository.findByWorkerIdAndPathAndUserId(anyString(), anyString(), anyString()))
                    .thenReturn(Optional.empty());
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            mockGitInfoFailure();

            CreateWorkingDirectoryForm form = new CreateWorkingDirectoryForm();
            form.setWorkerId(WORKER_ID);
            form.setProjectName("child-service");
            form.setPath("/home/user/child");
            form.setParentProjectId("proj-1");

            WorkingDirectoryDTO result = service.createDirectory(USER_ID, TENANT_ID, form);

            assertEquals("STANDARD", result.getDirectoryType());
            assertEquals("proj-1", result.getParentProjectId());
        }

        @Test
        void createChild_parentNotProject_throws() {
            WorkingDirectoryEntity parent = createEntity("std-1", "STANDARD");
            when(repository.findByDirectoryIdAndUserId("std-1", USER_ID)).thenReturn(Optional.of(parent));
            when(repository.findByWorkerIdAndPathAndUserId(anyString(), anyString(), anyString()))
                    .thenReturn(Optional.empty());

            CreateWorkingDirectoryForm form = new CreateWorkingDirectoryForm();
            form.setWorkerId(WORKER_ID);
            form.setProjectName("child");
            form.setPath("/child");
            form.setParentProjectId("std-1");

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.createDirectory(USER_ID, TENANT_ID, form));
            assertTrue(ex.getMessage().contains("not a PROJECT"));
        }

        @Test
        void createChild_parentNotFound_throws() {
            when(repository.findByDirectoryIdAndUserId("missing", USER_ID)).thenReturn(Optional.empty());
            when(repository.findByWorkerIdAndPathAndUserId(anyString(), anyString(), anyString()))
                    .thenReturn(Optional.empty());

            CreateWorkingDirectoryForm form = new CreateWorkingDirectoryForm();
            form.setWorkerId(WORKER_ID);
            form.setProjectName("child");
            form.setPath("/child");
            form.setParentProjectId("missing");

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.createDirectory(USER_ID, TENANT_ID, form));
            assertTrue(ex.getMessage().contains("Parent project not found"));
        }

        @Test
        void createDirectory_duplicatePath_throws() {
            WorkingDirectoryEntity existing = createEntity("existing-1", "STANDARD");
            when(repository.findByWorkerIdAndPathAndUserId(WORKER_ID, "/dup", USER_ID))
                    .thenReturn(Optional.of(existing));

            CreateWorkingDirectoryForm form = new CreateWorkingDirectoryForm();
            form.setWorkerId(WORKER_ID);
            form.setProjectName("dup");
            form.setPath("/dup");

            assertThrows(IllegalArgumentException.class,
                    () -> service.createDirectory(USER_ID, TENANT_ID, form));
        }

        @Test
        void createDirectory_wrongWorkerOwner_throws() {
            ClaudeWorkerEntity otherWorker = new ClaudeWorkerEntity();
            otherWorker.setWorkerId("other-worker");
            otherWorker.setUserId("other-user");
            when(workerService.getWorkerEntity("other-worker")).thenReturn(otherWorker);

            CreateWorkingDirectoryForm form = new CreateWorkingDirectoryForm();
            form.setWorkerId("other-worker");
            form.setProjectName("test");
            form.setPath("/test");

            assertThrows(IllegalArgumentException.class,
                    () -> service.createDirectory(USER_ID, TENANT_ID, form));
        }
    }

    // ========== updateDirectory: PROJECT 相关 ==========

    @Nested
    class UpdateDirectoryTests {

        @Test
        void updateProjectTaskPrompt_onProjectType_success() {
            WorkingDirectoryEntity entity = createEntity("proj-1", "PROJECT");
            when(repository.findByDirectoryIdAndUserId("proj-1", USER_ID)).thenReturn(Optional.of(entity));
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            UpdateWorkingDirectoryForm form = new UpdateWorkingDirectoryForm();
            form.setProjectTaskPrompt("Dispatch tasks to services");

            WorkingDirectoryDTO result = service.updateDirectory(USER_ID, "proj-1", form);

            assertEquals("Dispatch tasks to services", result.getProjectTaskPrompt());
        }

        @Test
        void updateProjectTaskPrompt_onStandardType_ignored() {
            WorkingDirectoryEntity entity = createEntity("std-1", "STANDARD");
            when(repository.findByDirectoryIdAndUserId("std-1", USER_ID)).thenReturn(Optional.of(entity));
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            UpdateWorkingDirectoryForm form = new UpdateWorkingDirectoryForm();
            form.setProjectTaskPrompt("Should be ignored");

            WorkingDirectoryDTO result = service.updateDirectory(USER_ID, "std-1", form);

            assertNull(result.getProjectTaskPrompt());
        }

        @Test
        void updateParentProjectId_toProject_success() {
            WorkingDirectoryEntity entity = createEntity("std-1", "STANDARD");
            WorkingDirectoryEntity parent = createEntity("proj-1", "PROJECT");
            when(repository.findByDirectoryIdAndUserId("std-1", USER_ID)).thenReturn(Optional.of(entity));
            when(repository.findByDirectoryIdAndUserId("proj-1", USER_ID)).thenReturn(Optional.of(parent));
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            UpdateWorkingDirectoryForm form = new UpdateWorkingDirectoryForm();
            form.setParentProjectId("proj-1");

            WorkingDirectoryDTO result = service.updateDirectory(USER_ID, "std-1", form);

            assertEquals("proj-1", result.getParentProjectId());
        }

        @Test
        void updateParentProjectId_toNonProject_throws() {
            WorkingDirectoryEntity entity = createEntity("std-1", "STANDARD");
            WorkingDirectoryEntity notProject = createEntity("std-2", "STANDARD");
            when(repository.findByDirectoryIdAndUserId("std-1", USER_ID)).thenReturn(Optional.of(entity));
            when(repository.findByDirectoryIdAndUserId("std-2", USER_ID)).thenReturn(Optional.of(notProject));

            UpdateWorkingDirectoryForm form = new UpdateWorkingDirectoryForm();
            form.setParentProjectId("std-2");

            assertThrows(IllegalArgumentException.class,
                    () -> service.updateDirectory(USER_ID, "std-1", form));
        }

        @Test
        void updateParentProjectId_clearWithEmpty_success() {
            WorkingDirectoryEntity entity = createEntity("std-1", "STANDARD");
            entity.setParentProjectId("proj-1");
            when(repository.findByDirectoryIdAndUserId("std-1", USER_ID)).thenReturn(Optional.of(entity));
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            UpdateWorkingDirectoryForm form = new UpdateWorkingDirectoryForm();
            form.setParentProjectId("");

            WorkingDirectoryDTO result = service.updateDirectory(USER_ID, "std-1", form);

            assertNull(result.getParentProjectId());
        }
    }

    // ========== listChildDirectories ==========

    @Nested
    class ListChildDirectoriesTests {

        @Test
        void listChildDirectories_returnsChildren() {
            WorkingDirectoryEntity child1 = createEntity("child-1", "STANDARD");
            child1.setParentProjectId("proj-1");
            child1.setProjectName("svc-a");
            WorkingDirectoryEntity child2 = createEntity("child-2", "STANDARD");
            child2.setParentProjectId("proj-1");
            child2.setProjectName("svc-b");

            when(repository.findByParentProjectIdAndUserIdOrderByProjectNameAsc("proj-1", USER_ID))
                    .thenReturn(List.of(child1, child2));

            List<WorkingDirectoryDTO> result = service.listChildDirectories(USER_ID, "proj-1");

            assertEquals(2, result.size());
            assertEquals("svc-a", result.get(0).getProjectName());
            assertEquals("svc-b", result.get(1).getProjectName());
        }

        @Test
        void listChildDirectories_empty() {
            when(repository.findByParentProjectIdAndUserIdOrderByProjectNameAsc("proj-1", USER_ID))
                    .thenReturn(List.of());

            List<WorkingDirectoryDTO> result = service.listChildDirectories(USER_ID, "proj-1");

            assertTrue(result.isEmpty());
        }
    }

    // ========== Worktree ==========

    @Nested
    class WorktreeTests {

        @Test
        void createWorktree_success() {
            WorkingDirectoryEntity source = createEntity("src-1", "STANDARD");
            source.setWorkerId(WORKER_ID);
            source.setPath("/home/user/repo");
            when(repository.findByDirectoryIdAndUserId("src-1", USER_ID)).thenReturn(Optional.of(source));
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            ClaudeWorkerClient client = mock(ClaudeWorkerClient.class);
            when(workerService.createClient(any())).thenReturn(client);
            when(client.createWorktree("/home/user/repo", "hotfix", null))
                    .thenReturn(Mono.just(Map.of("path", "/home/user/repo-wt-hotfix", "branch", "hotfix")));
            when(client.getGitInfo(anyString())).thenReturn(Mono.error(new RuntimeException("skip")));

            WorkingDirectoryDTO result = service.createWorktree(USER_ID, TENANT_ID, "src-1", "hotfix");

            assertNotNull(result);
            assertEquals("/home/user/repo-wt-hotfix", result.getPath());
            assertTrue(result.getProjectName().contains("hotfix"));
            assertEquals(true, result.getWorktree());
            assertEquals("src-1", result.getSourceDirectoryId());
            assertEquals("hotfix", result.getGitBranch());
        }

        @Test
        void createWorktree_sourceNotFound_throws() {
            when(repository.findByDirectoryIdAndUserId("missing", USER_ID)).thenReturn(Optional.empty());

            assertThrows(IllegalArgumentException.class,
                    () -> service.createWorktree(USER_ID, TENANT_ID, "missing", "main"));
        }

        @Test
        void removeWorktree_success() {
            WorkingDirectoryEntity wt = createEntity("wt-1", "STANDARD");
            wt.setWorktree(true);
            wt.setWorkerId(WORKER_ID);
            wt.setPath("/home/user/repo-wt-hotfix");
            when(repository.findByDirectoryIdAndUserId("wt-1", USER_ID)).thenReturn(Optional.of(wt));

            ClaudeWorkerClient client = mock(ClaudeWorkerClient.class);
            when(workerService.createClient(any())).thenReturn(client);
            when(client.removeWorktree("/home/user/repo-wt-hotfix")).thenReturn(Mono.empty());

            service.removeWorktree(USER_ID, "wt-1");

            verify(repository).delete(wt);
            verify(client).removeWorktree("/home/user/repo-wt-hotfix");
        }

        @Test
        void removeWorktree_notWorktree_throws() {
            WorkingDirectoryEntity regular = createEntity("std-1", "STANDARD");
            regular.setWorktree(false);
            when(repository.findByDirectoryIdAndUserId("std-1", USER_ID)).thenReturn(Optional.of(regular));

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.removeWorktree(USER_ID, "std-1"));
            assertTrue(ex.getMessage().contains("not a worktree"));
        }

        @Test
        void removeWorktree_remoteFailure_stillDeletesDbRecord() {
            WorkingDirectoryEntity wt = createEntity("wt-2", "STANDARD");
            wt.setWorktree(true);
            wt.setWorkerId(WORKER_ID);
            wt.setPath("/home/user/repo-wt-fix");
            when(repository.findByDirectoryIdAndUserId("wt-2", USER_ID)).thenReturn(Optional.of(wt));

            ClaudeWorkerClient client = mock(ClaudeWorkerClient.class);
            when(workerService.createClient(any())).thenReturn(client);
            when(client.removeWorktree(anyString())).thenReturn(Mono.error(new RuntimeException("network error")));

            service.removeWorktree(USER_ID, "wt-2");

            // DB record should still be deleted even though remote call failed
            verify(repository).delete(wt);
        }
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

    private WorkingDirectoryEntity createEntity(String directoryId, String directoryType) {
        WorkingDirectoryEntity entity = new WorkingDirectoryEntity();
        entity.setDirectoryId(directoryId);
        entity.setWorkerId(WORKER_ID);
        entity.setUserId(USER_ID);
        entity.setTenantId(TENANT_ID);
        entity.setProjectName("test-" + directoryId);
        entity.setPath("/home/user/" + directoryId);
        entity.setDirectoryType(directoryType);
        return entity;
    }

    private void mockGitInfoFailure() {
        ClaudeWorkerClient client = mock(ClaudeWorkerClient.class);
        when(workerService.createClient(any())).thenReturn(client);
        when(client.getGitInfo(anyString())).thenReturn(Mono.error(new RuntimeException("not available")));
    }
}
