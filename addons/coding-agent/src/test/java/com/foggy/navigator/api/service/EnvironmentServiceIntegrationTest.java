package com.foggy.navigator.api.service;

import com.foggy.navigator.api.model.CreateEnvironmentRequest;
import com.foggy.navigator.api.model.Environment;
import com.foggy.navigator.config.TestBeanConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EnvironmentService 集成测试
 * 真实的 Docker 容器管理器测试
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestBeanConfiguration.class)
class EnvironmentServiceIntegrationTest {

    @Autowired
    private EnvironmentService environmentService;

    private Environment createdEnvironment;

    @BeforeEach
    void setUp() {
        // 测试前准备
    }

    @AfterEach
    void tearDown() {
        // 清理创建的环境
        if (createdEnvironment != null && environmentService.exists(createdEnvironment.getId())) {
            try {
                environmentService.destroyEnvironment(createdEnvironment.getId());
            } catch (Exception e) {
                // 忽略清理错误
            }
        }
    }

    @Test
    void testCreateEnvironment_WithRealDocker() {
        // Given
        CreateEnvironmentRequest request = CreateEnvironmentRequest.builder()
                .userId("test-user")
                .projectId("test-project")
                .gitRepoUrl("https://github.com/test/repo.git")
                .branchName("main")
                .build();

        // When
        createdEnvironment = environmentService.createEnvironment(request);

        // Then
        assertNotNull(createdEnvironment);
        assertNotNull(createdEnvironment.getId());
        assertEquals("test-user", createdEnvironment.getUserId());
        assertEquals("test-project", createdEnvironment.getProjectId());
        assertEquals("main", createdEnvironment.getBranchName());
        assertNotNull(createdEnvironment.getContainerId());
        assertNotNull(createdEnvironment.getNamespace());
        assertTrue(createdEnvironment.getNamespace().startsWith("user-test-user-"));
        assertEquals(Environment.EnvironmentStatus.READY, createdEnvironment.getStatus());

        // 验证容器确实被创建
        assertTrue(environmentService.exists(createdEnvironment.getId()));
    }

    @Test
    void testGetEnvironment_AfterCreation() {
        // Given - 先创建环境
        CreateEnvironmentRequest request = CreateEnvironmentRequest.builder()
                .userId("test-user")
                .projectId("test-project")
                .gitRepoUrl("https://github.com/test/repo.git")
                .branchName("main")
                .build();

        createdEnvironment = environmentService.createEnvironment(request);

        // When - 获取环境
        Environment retrieved = environmentService.getEnvironment(createdEnvironment.getId());

        // Then
        assertNotNull(retrieved);
        assertEquals(createdEnvironment.getId(), retrieved.getId());
        assertEquals(createdEnvironment.getUserId(), retrieved.getUserId());
        assertEquals(createdEnvironment.getContainerId(), retrieved.getContainerId());
        assertEquals(createdEnvironment.getNamespace(), retrieved.getNamespace());
    }

    @Test
    void testDestroyEnvironment_WithRealDocker() {
        // Given - 先创建环境
        CreateEnvironmentRequest request = CreateEnvironmentRequest.builder()
                .userId("test-user")
                .projectId("test-project")
                .gitRepoUrl("https://github.com/test/repo.git")
                .branchName("main")
                .build();

        createdEnvironment = environmentService.createEnvironment(request);
        String environmentId = createdEnvironment.getId();

        assertTrue(environmentService.exists(environmentId));

        // When - 销毁环境
        environmentService.destroyEnvironment(environmentId);

        // Then - 验证环境已被销毁
        assertFalse(environmentService.exists(environmentId));

        // 清理标记，避免 tearDown 重复销毁
        createdEnvironment = null;
    }

    @Test
    void testMultipleEnvironments_Isolation() {
        // Given - 创建两个环境
        CreateEnvironmentRequest request1 = CreateEnvironmentRequest.builder()
                .userId("user-1")
                .projectId("project-A")
                .gitRepoUrl("https://github.com/test/repo.git")
                .branchName("main")
                .build();

        CreateEnvironmentRequest request2 = CreateEnvironmentRequest.builder()
                .userId("user-2")
                .projectId("project-A")
                .branchName("main")
                .gitRepoUrl("https://github.com/test/repo.git")
                .build();

        // When
        Environment env1 = environmentService.createEnvironment(request1);
        Environment env2 = environmentService.createEnvironment(request2);

        try {
            // Then - 验证两个环境是独立的
            assertNotEquals(env1.getId(), env2.getId());
            assertNotEquals(env1.getSessionId(), env2.getSessionId());
            assertNotEquals(env1.getContainerId(), env2.getContainerId());
            assertNotEquals(env1.getNamespace(), env2.getNamespace());
            assertNotEquals(env1.getWorkspacePath(), env2.getWorkspacePath());

            // 验证 namespace 格式正确
            assertTrue(env1.getNamespace().startsWith("user-user-1-"));
            assertTrue(env2.getNamespace().startsWith("user-user-2-"));

        } finally {
            // 清理
            environmentService.destroyEnvironment(env1.getId());
            environmentService.destroyEnvironment(env2.getId());
        }
    }
}
