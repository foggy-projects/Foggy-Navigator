package com.foggy.navigator.tutor.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TutorToolDefinitionsTest {

    @Mock
    private CodingAgentToolExecutor toolExecutor;

    private TutorToolDefinitions toolDefinitions;

    @BeforeEach
    void setUp() {
        toolDefinitions = new TutorToolDefinitions(toolExecutor);
    }

    @Test
    void listGitCredentials_shouldReturnFormattedList() {
        // Arrange
        toolDefinitions.setAuthToken("test-token");
        List<Map<String, Object>> mockCredentials = Arrays.asList(
            Map.of("id", "cred-1", "name", "GitLab", "type", "GITLAB"),
            Map.of("id", "cred-2", "name", "GitHub", "type", "GITHUB")
        );
        when(toolExecutor.listGitCredentials("test-token")).thenReturn(mockCredentials);

        // Act
        String result = toolDefinitions.listGitCredentials();

        // Assert
        assertTrue(result.contains("Git 凭证"));
        assertTrue(result.contains("cred-1"));
        assertTrue(result.contains("GitLab"));
        assertTrue(result.contains("cred-2"));
        assertTrue(result.contains("GitHub"));
        verify(toolExecutor).listGitCredentials("test-token");
    }

    @Test
    void listGitCredentials_shouldHandleEmptyList() {
        // Arrange
        toolDefinitions.setAuthToken("test-token");
        when(toolExecutor.listGitCredentials("test-token")).thenReturn(List.of());

        // Act
        String result = toolDefinitions.listGitCredentials();

        // Assert
        assertTrue(result.contains("没有配置任何 Git 凭证"));
    }

    @Test
    void listGitCredentials_shouldHandleException() {
        // Arrange
        toolDefinitions.setAuthToken("test-token");
        when(toolExecutor.listGitCredentials("test-token"))
            .thenThrow(new RuntimeException("Connection failed"));

        // Act
        String result = toolDefinitions.listGitCredentials();

        // Assert
        assertTrue(result.contains("获取 Git 凭证列表失败"));
        assertTrue(result.contains("Connection failed"));
    }

    @Test
    void listGitProjects_shouldReturnFormattedList() {
        // Arrange
        toolDefinitions.setAuthToken("test-token");
        List<Map<String, Object>> mockProjects = Arrays.asList(
            Map.of("id", "proj-1", "name", "Project A", "path_with_namespace", "group/project-a"),
            Map.of("id", "proj-2", "name", "Project B", "path_with_namespace", "group/project-b")
        );
        when(toolExecutor.listGitProjects("cred-1", "test-token")).thenReturn(mockProjects);

        // Act
        String result = toolDefinitions.listGitProjects("cred-1");

        // Assert
        assertTrue(result.contains("项目列表"));
        assertTrue(result.contains("proj-1"));
        assertTrue(result.contains("Project A"));
        assertTrue(result.contains("group/project-a"));
        verify(toolExecutor).listGitProjects("cred-1", "test-token");
    }

    @Test
    void listGitProjects_shouldHandleEmptyList() {
        // Arrange
        toolDefinitions.setAuthToken("test-token");
        when(toolExecutor.listGitProjects("cred-1", "test-token")).thenReturn(List.of());

        // Act
        String result = toolDefinitions.listGitProjects("cred-1");

        // Assert
        assertTrue(result.contains("没有找到任何项目"));
    }

    @Test
    void listGitBranches_shouldReturnFormattedList() {
        // Arrange
        toolDefinitions.setAuthToken("test-token");
        List<Map<String, Object>> mockBranches = Arrays.asList(
            Map.of("name", "main", "default", true),
            Map.of("name", "develop", "default", false),
            Map.of("name", "feature/new", "default", false)
        );
        when(toolExecutor.listGitBranches("cred-1", "proj-1", "test-token")).thenReturn(mockBranches);

        // Act
        String result = toolDefinitions.listGitBranches("cred-1", "proj-1");

        // Assert
        assertTrue(result.contains("分支列表"));
        assertTrue(result.contains("main"));
        assertTrue(result.contains("(默认)"));
        assertTrue(result.contains("develop"));
        assertTrue(result.contains("feature/new"));
        verify(toolExecutor).listGitBranches("cred-1", "proj-1", "test-token");
    }

    @Test
    void createCodingConversation_shouldReturnSuccessMessage() {
        // Arrange
        toolDefinitions.setAuthToken("test-token");
        Map<String, Object> mockResult = Map.of("id", "conv-123", "status", "CREATED");
        when(toolExecutor.createCodingConversation(any(), eq("test-token"))).thenReturn(mockResult);

        // Act
        String result = toolDefinitions.createCodingConversation(
            "New Feature", "cred-1", "proj-1", "main");

        // Assert
        assertTrue(result.contains("会话创建成功"));
        assertTrue(result.contains("conv-123"));
        assertTrue(result.contains("CREATED"));
    }

    @Test
    void sendCodingMessage_shouldReturnResponse() {
        // Arrange
        toolDefinitions.setAuthToken("test-token");
        Map<String, Object> mockResult = Map.of("content", "Code generated successfully");
        when(toolExecutor.sendCodingMessage(eq("conv-123"), any(), eq("test-token")))
            .thenReturn(mockResult);

        // Act
        String result = toolDefinitions.sendCodingMessage("conv-123", "Write a hello world function");

        // Assert
        assertTrue(result.contains("消息已发送"));
        assertTrue(result.contains("Code generated successfully"));
        verify(toolExecutor).sendCodingMessage(eq("conv-123"), any(), eq("test-token"));
    }

    @Test
    void getConversationStatus_shouldReturnFormattedStatus() {
        // Arrange
        toolDefinitions.setAuthToken("test-token");
        Map<String, Object> mockResult = new HashMap<>();
        mockResult.put("id", "conv-123");
        mockResult.put("title", "New Feature");
        mockResult.put("status", "IN_PROGRESS");
        mockResult.put("messageCount", 5);
        when(toolExecutor.getConversationStatus("conv-123", "test-token")).thenReturn(mockResult);

        // Act
        String result = toolDefinitions.getConversationStatus("conv-123");

        // Assert
        assertTrue(result.contains("会话状态"));
        assertTrue(result.contains("conv-123"));
        assertTrue(result.contains("New Feature"));
        assertTrue(result.contains("IN_PROGRESS"));
        assertTrue(result.contains("5"));
        verify(toolExecutor).getConversationStatus("conv-123", "test-token");
    }

    @Test
    void authToken_shouldBeThreadLocal() {
        // Arrange & Act
        toolDefinitions.setAuthToken("token-1");
        String token1 = toolDefinitions.listGitCredentials(); // Will use token-1

        toolDefinitions.setAuthToken("token-2");
        // Now token-2 is set

        toolDefinitions.clearAuthToken();
        // Now no token

        // Assert - should throw exception when no token set
        assertThrows(IllegalStateException.class, () -> toolDefinitions.listGitCredentials());
    }

    @Test
    void listGitCredentials_shouldThrowWhenNoAuthToken() {
        // Act & Assert
        assertThrows(IllegalStateException.class, () -> toolDefinitions.listGitCredentials());
    }

    @Test
    void listGitProjects_shouldThrowWhenNoAuthToken() {
        // Act & Assert
        assertThrows(IllegalStateException.class, () -> toolDefinitions.listGitProjects("cred-1"));
    }

    @Test
    void createCodingConversation_shouldHandleException() {
        // Arrange
        toolDefinitions.setAuthToken("test-token");
        when(toolExecutor.createCodingConversation(any(), eq("test-token")))
            .thenThrow(new RuntimeException("Server error"));

        // Act
        String result = toolDefinitions.createCodingConversation(
            "New Feature", "cred-1", "proj-1", "main");

        // Assert
        assertTrue(result.contains("创建会话失败"));
        assertTrue(result.contains("Server error"));
    }
}
