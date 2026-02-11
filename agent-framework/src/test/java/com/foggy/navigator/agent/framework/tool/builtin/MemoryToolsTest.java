package com.foggy.navigator.agent.framework.tool.builtin;

import com.foggy.navigator.agent.framework.tool.ToolExecutionRequest;
import com.foggy.navigator.agent.framework.tool.ToolExecutionResult;
import com.foggy.navigator.common.dto.UserMemoryDTO;
import com.foggy.navigator.common.enums.UserMemoryCategory;
import com.foggy.navigator.common.enums.UserMemorySource;
import com.foggy.navigator.common.form.UserMemoryForm;
import com.foggy.navigator.spi.memory.UserMemoryManager;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MemoryToolsTest {

    // ========== SaveMemoryTool ==========

    @Test
    void saveMemory_success() {
        UserMemoryManager manager = mock(UserMemoryManager.class);
        when(manager.saveMemory(eq("user1"), eq("tenant1"), any(UserMemoryForm.class), eq(UserMemorySource.AUTO)))
                .thenReturn("mem-id-1");

        SaveMemoryTool tool = new SaveMemoryTool(manager);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .userId("user1").tenantId("tenant1")
                .parameters(Map.of("content", "I like Java", "category", "PREFERENCE"))
                .build();

        ToolExecutionResult result = tool.execute(request);
        assertTrue(result.isSuccess());
        assertTrue(result.getData().toString().contains("I like Java"));
        verify(manager).saveMemory(eq("user1"), eq("tenant1"), argThat(form ->
                form.getContent().equals("I like Java") && form.getCategory() == UserMemoryCategory.PREFERENCE
        ), eq(UserMemorySource.AUTO));
    }

    @Test
    void saveMemory_defaultsToFact() {
        UserMemoryManager manager = mock(UserMemoryManager.class);
        when(manager.saveMemory(anyString(), anyString(), any(), any())).thenReturn("id");

        SaveMemoryTool tool = new SaveMemoryTool(manager);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .userId("user1").tenantId("tenant1")
                .parameters(Map.of("content", "My name is Bob"))
                .build();

        tool.execute(request);
        verify(manager).saveMemory(anyString(), anyString(), argThat(form ->
                form.getCategory() == UserMemoryCategory.FACT
        ), any());
    }

    @Test
    void saveMemory_invalidCategory_fallsBackToFact() {
        UserMemoryManager manager = mock(UserMemoryManager.class);
        when(manager.saveMemory(anyString(), anyString(), any(), any())).thenReturn("id");

        SaveMemoryTool tool = new SaveMemoryTool(manager);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .userId("user1").tenantId("tenant1")
                .parameters(Map.of("content", "test", "category", "INVALID"))
                .build();

        ToolExecutionResult result = tool.execute(request);
        assertTrue(result.isSuccess());
        verify(manager).saveMemory(anyString(), anyString(), argThat(form ->
                form.getCategory() == UserMemoryCategory.FACT
        ), any());
    }

    @Test
    void saveMemory_managerNull_returnsError() {
        SaveMemoryTool tool = new SaveMemoryTool(null);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .userId("user1").tenantId("tenant1")
                .parameters(Map.of("content", "test"))
                .build();

        ToolExecutionResult result = tool.execute(request);
        assertFalse(result.isSuccess());
        assertEquals("MEMORY_UNAVAILABLE", result.getErrorCode());
    }

    @Test
    void saveMemory_emptyContent_returnsError() {
        UserMemoryManager manager = mock(UserMemoryManager.class);
        SaveMemoryTool tool = new SaveMemoryTool(manager);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .userId("user1").tenantId("tenant1")
                .parameters(Map.of("content", "  "))
                .build();

        ToolExecutionResult result = tool.execute(request);
        assertFalse(result.isSuccess());
        assertEquals("EMPTY_CONTENT", result.getErrorCode());
    }

    @Test
    void saveMemory_noUser_returnsError() {
        UserMemoryManager manager = mock(UserMemoryManager.class);
        SaveMemoryTool tool = new SaveMemoryTool(manager);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .parameters(Map.of("content", "test"))
                .build();

        ToolExecutionResult result = tool.execute(request);
        assertFalse(result.isSuccess());
        assertEquals("NO_USER", result.getErrorCode());
    }

    // ========== DeleteMemoryTool ==========

    @Test
    void deleteMemory_success() {
        UserMemoryManager manager = mock(UserMemoryManager.class);
        UserMemoryDTO mem = createDTO("id1", "I like Python", UserMemoryCategory.PREFERENCE);
        when(manager.listMemories("user1")).thenReturn(List.of(mem));

        DeleteMemoryTool tool = new DeleteMemoryTool(manager);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .userId("user1")
                .parameters(Map.of("keyword", "Python"))
                .build();

        ToolExecutionResult result = tool.execute(request);
        assertTrue(result.isSuccess());
        assertTrue(result.getData().toString().contains("1"));
        verify(manager).deleteMemory("id1");
    }

    @Test
    void deleteMemory_caseInsensitive() {
        UserMemoryManager manager = mock(UserMemoryManager.class);
        UserMemoryDTO mem = createDTO("id1", "I LIKE JAVA", UserMemoryCategory.PREFERENCE);
        when(manager.listMemories("user1")).thenReturn(List.of(mem));

        DeleteMemoryTool tool = new DeleteMemoryTool(manager);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .userId("user1")
                .parameters(Map.of("keyword", "java"))
                .build();

        ToolExecutionResult result = tool.execute(request);
        assertTrue(result.isSuccess());
        verify(manager).deleteMemory("id1");
    }

    @Test
    void deleteMemory_noMatch() {
        UserMemoryManager manager = mock(UserMemoryManager.class);
        UserMemoryDTO mem = createDTO("id1", "I like Python", UserMemoryCategory.PREFERENCE);
        when(manager.listMemories("user1")).thenReturn(List.of(mem));

        DeleteMemoryTool tool = new DeleteMemoryTool(manager);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .userId("user1")
                .parameters(Map.of("keyword", "Rust"))
                .build();

        ToolExecutionResult result = tool.execute(request);
        assertTrue(result.isSuccess());
        assertTrue(result.getData().toString().contains("未找到"));
        verify(manager, never()).deleteMemory(anyString());
    }

    @Test
    void deleteMemory_multipleMatches() {
        UserMemoryManager manager = mock(UserMemoryManager.class);
        when(manager.listMemories("user1")).thenReturn(List.of(
                createDTO("id1", "Python is great", UserMemoryCategory.PREFERENCE),
                createDTO("id2", "Use Python for ML", UserMemoryCategory.NOTE),
                createDTO("id3", "Java is fast", UserMemoryCategory.FACT)
        ));

        DeleteMemoryTool tool = new DeleteMemoryTool(manager);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .userId("user1")
                .parameters(Map.of("keyword", "Python"))
                .build();

        ToolExecutionResult result = tool.execute(request);
        assertTrue(result.isSuccess());
        assertTrue(result.getData().toString().contains("2"));
        verify(manager).deleteMemory("id1");
        verify(manager).deleteMemory("id2");
        verify(manager, never()).deleteMemory("id3");
    }

    @Test
    void deleteMemory_managerNull_returnsError() {
        DeleteMemoryTool tool = new DeleteMemoryTool(null);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .userId("user1")
                .parameters(Map.of("keyword", "test"))
                .build();

        ToolExecutionResult result = tool.execute(request);
        assertFalse(result.isSuccess());
        assertEquals("MEMORY_UNAVAILABLE", result.getErrorCode());
    }

    // ========== ListMemoryTool ==========

    @Test
    void listMemory_success() {
        UserMemoryManager manager = mock(UserMemoryManager.class);
        when(manager.listMemories("user1")).thenReturn(List.of(
                createDTO("id1", "I like Java", UserMemoryCategory.PREFERENCE),
                createDTO("id2", "My name is Bob", UserMemoryCategory.FACT)
        ));

        ListMemoryTool tool = new ListMemoryTool(manager);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .userId("user1")
                .parameters(Map.of())
                .build();

        ToolExecutionResult result = tool.execute(request);
        assertTrue(result.isSuccess());
        String data = result.getData().toString();
        assertTrue(data.contains("2 条"));
        assertTrue(data.contains("I like Java"));
        assertTrue(data.contains("My name is Bob"));
    }

    @Test
    void listMemory_empty() {
        UserMemoryManager manager = mock(UserMemoryManager.class);
        when(manager.listMemories("user1")).thenReturn(List.of());

        ListMemoryTool tool = new ListMemoryTool(manager);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .userId("user1")
                .parameters(Map.of())
                .build();

        ToolExecutionResult result = tool.execute(request);
        assertTrue(result.isSuccess());
        assertTrue(result.getData().toString().contains("没有保存任何记忆"));
    }

    @Test
    void listMemory_managerNull_returnsError() {
        ListMemoryTool tool = new ListMemoryTool(null);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .userId("user1")
                .parameters(Map.of())
                .build();

        ToolExecutionResult result = tool.execute(request);
        assertFalse(result.isSuccess());
        assertEquals("MEMORY_UNAVAILABLE", result.getErrorCode());
    }

    // ========== Helper ==========

    private UserMemoryDTO createDTO(String id, String content, UserMemoryCategory category) {
        UserMemoryDTO dto = new UserMemoryDTO();
        dto.setId(id);
        dto.setUserId("user1");
        dto.setCategory(category);
        dto.setContent(content);
        dto.setSource(UserMemorySource.MANUAL);
        dto.setCreatedAt(LocalDateTime.now());
        dto.setUpdatedAt(LocalDateTime.now());
        return dto;
    }
}
