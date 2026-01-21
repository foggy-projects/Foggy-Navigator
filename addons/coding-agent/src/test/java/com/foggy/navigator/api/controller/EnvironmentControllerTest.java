package com.foggy.navigator.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.api.model.CreateEnvironmentRequest;
import com.foggy.navigator.api.model.Environment;
import com.foggy.navigator.api.service.EnvironmentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * EnvironmentController 单元测试
 */
@WebMvcTest(EnvironmentController.class)
class EnvironmentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private EnvironmentService environmentService;

    @Test
    void testCreateEnvironment_Success() throws Exception {
        // Given
        CreateEnvironmentRequest request = CreateEnvironmentRequest.builder()
                .userId("user-123")
                .projectId("project-A")
                .gitRepoUrl("https://github.com/org/semantic-layer.git")
                .branchName("main")
                .build();

        Environment mockEnvironment = Environment.builder()
                .id("env-123")
                .userId("user-123")
                .projectId("project-A")
                .gitRepoUrl("https://github.com/org/semantic-layer.git")
                .branchName("main")
                .containerId("container-xyz")
                .workspacePath("/workspace/user-123/session-abc")
                .namespace("user-user-123-session-abc")
                .status(Environment.EnvironmentStatus.READY)
                .createdAt(LocalDateTime.now())
                .build();

        when(environmentService.createEnvironment(any(CreateEnvironmentRequest.class)))
                .thenReturn(mockEnvironment);

        // When & Then
        mockMvc.perform(post("/api/environments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value("env-123"))
                .andExpect(jsonPath("$.data.userId").value("user-123"))
                .andExpect(jsonPath("$.data.status").value("READY"));

        verify(environmentService).createEnvironment(any(CreateEnvironmentRequest.class));
    }

    @Test
    void testCreateEnvironment_MissingUserId() throws Exception {
        // Given
        CreateEnvironmentRequest request = CreateEnvironmentRequest.builder()
                .projectId("project-A")
                .gitRepoUrl("https://github.com/org/semantic-layer.git")
                .branchName("main")
                .build();

        // When & Then
        mockMvc.perform(post("/api/environments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAM"));

        verify(environmentService, never()).createEnvironment(any());
    }

    @Test
    void testGetEnvironment_Success() throws Exception {
        // Given
        Environment mockEnvironment = Environment.builder()
                .id("env-123")
                .userId("user-123")
                .status(Environment.EnvironmentStatus.READY)
                .build();

        when(environmentService.getEnvironment("env-123"))
                .thenReturn(mockEnvironment);

        // When & Then
        mockMvc.perform(get("/api/environments/env-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value("env-123"));

        verify(environmentService).getEnvironment("env-123");
    }

    @Test
    void testGetEnvironment_NotFound() throws Exception {
        // Given
        when(environmentService.getEnvironment("non-existent"))
                .thenThrow(new RuntimeException("环境不存在"));

        // When & Then
        mockMvc.perform(get("/api/environments/non-existent"))
                .andExpect(status().isNotFound());

        verify(environmentService).getEnvironment("non-existent");
    }

    @Test
    void testDestroyEnvironment_Success() throws Exception {
        // Given
        doNothing().when(environmentService).destroyEnvironment("env-123");

        // When & Then
        mockMvc.perform(delete("/api/environments/env-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("环境已销毁"));

        verify(environmentService).destroyEnvironment("env-123");
    }

    @Test
    void testDestroyEnvironment_Failed() throws Exception {
        // Given
        doThrow(new RuntimeException("销毁失败"))
                .when(environmentService).destroyEnvironment("env-123");

        // When & Then
        mockMvc.perform(delete("/api/environments/env-123"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("DESTROY_FAILED"));

        verify(environmentService).destroyEnvironment("env-123");
    }
}
