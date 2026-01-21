package com.foggy.navigator.api.service;

import com.foggy.navigator.api.model.CreateEnvironmentRequest;
import com.foggy.navigator.api.model.Environment;
import com.foggy.navigator.foundation.git.OpenHandsContainerManager;
import com.foggy.navigator.foundation.git.ValidationServiceClient;
import com.foggy.navigator.foundation.git.model.ContainerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * EnvironmentService 单元测试
 */
@ExtendWith(MockitoExtension.class)
class EnvironmentServiceTest {

    @Mock
    private OpenHandsContainerManager containerManager;

    @Mock
    private ValidationServiceClient validationClient;

    @InjectMocks
    private EnvironmentService environmentService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(environmentService, "workspaceBase", "/workspace");
    }

    @Test
    void testCreateEnvironment_Success() {
        // Given
        CreateEnvironmentRequest request = CreateEnvironmentRequest.builder()
                .userId("user-123")
                .projectId("project-A")
                .gitRepoUrl("https://github.com/org/semantic-layer.git")
                .branchName("main")
                .build();

        String mockContainerId = "container-xyz";
        when(containerManager.createContainer(anyString(), anyString(), any(ContainerConfig.class)))
                .thenReturn(mockContainerId);
        when(containerManager.waitForContainerReady(eq(mockContainerId), anyInt()))
                .thenReturn(true);

        // When
        Environment environment = environmentService.createEnvironment(request);

        // Then
        assertNotNull(environment);
        assertEquals("user-123", environment.getUserId());
        assertEquals("project-A", environment.getProjectId());
        assertEquals("main", environment.getBranchName());
        assertEquals(mockContainerId, environment.getContainerId());
        assertEquals(Environment.EnvironmentStatus.READY, environment.getStatus());
        assertNotNull(environment.getNamespace());
        assertTrue(environment.getNamespace().startsWith("user-user-123-"));

        verify(containerManager).createContainer(anyString(), anyString(), any(ContainerConfig.class));
        verify(containerManager).waitForContainerReady(eq(mockContainerId), anyInt());
    }

    @Test
    void testCreateEnvironment_ContainerTimeout() {
        // Given
        CreateEnvironmentRequest request = CreateEnvironmentRequest.builder()
                .userId("user-123")
                .projectId("project-A")
                .gitRepoUrl("https://github.com/org/semantic-layer.git")
                .branchName("main")
                .build();

        String mockContainerId = "container-xyz";
        when(containerManager.createContainer(anyString(), anyString(), any(ContainerConfig.class)))
                .thenReturn(mockContainerId);
        when(containerManager.waitForContainerReady(eq(mockContainerId), anyInt()))
                .thenReturn(false);

        // When & Then
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            environmentService.createEnvironment(request);
        });

        assertTrue(exception.getMessage().contains("容器启动超时"));
    }

    @Test
    void testGetEnvironment_Success() {
        // Given
        CreateEnvironmentRequest request = CreateEnvironmentRequest.builder()
                .userId("user-123")
                .projectId("project-A")
                .gitRepoUrl("https://github.com/org/semantic-layer.git")
                .branchName("main")
                .build();
//        org.apache.hc.client5.http.ssl.TlsSocketStrategy d;
        when(containerManager.createContainer(anyString(), anyString(), any(ContainerConfig.class)))
                .thenReturn("container-xyz");
        when(containerManager.waitForContainerReady(anyString(), anyInt()))
                .thenReturn(true);

        Environment created = environmentService.createEnvironment(request);

        // When
        Environment retrieved = environmentService.getEnvironment(created.getId());

        // Then
        assertNotNull(retrieved);
        assertEquals(created.getId(), retrieved.getId());
        assertEquals(created.getUserId(), retrieved.getUserId());
    }

    @Test
    void testGetEnvironment_NotFound() {
        // When & Then
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            environmentService.getEnvironment("non-existent-id");
        });

        assertTrue(exception.getMessage().contains("环境不存在"));
    }

    @Test
    void testDestroyEnvironment_Success() {
        // Given
        CreateEnvironmentRequest request = CreateEnvironmentRequest.builder()
                .userId("user-123")
                .projectId("project-A")
                .gitRepoUrl("https://github.com/org/semantic-layer.git")
                .branchName("main")
                .build();

        String mockContainerId = "container-xyz";
        when(containerManager.createContainer(anyString(), anyString(), any(ContainerConfig.class)))
                .thenReturn(mockContainerId);
        when(containerManager.waitForContainerReady(anyString(), anyInt()))
                .thenReturn(true);

        Environment created = environmentService.createEnvironment(request);

        // When
        environmentService.destroyEnvironment(created.getId());

        // Then
        verify(containerManager).destroyContainer(eq(mockContainerId));
        assertFalse(environmentService.exists(created.getId()));
    }

    @Test
    void testExists() {
        // Given
        CreateEnvironmentRequest request = CreateEnvironmentRequest.builder()
                .userId("user-123")
                .projectId("project-A")
                .gitRepoUrl("https://github.com/org/semantic-layer.git")
                .branchName("main")
                .build();

        when(containerManager.createContainer(anyString(), anyString(), any(ContainerConfig.class)))
                .thenReturn("container-xyz");
        when(containerManager.waitForContainerReady(anyString(), anyInt()))
                .thenReturn(true);

        Environment created = environmentService.createEnvironment(request);

        // When & Then
        assertTrue(environmentService.exists(created.getId()));
        assertFalse(environmentService.exists("non-existent-id"));
    }
}
