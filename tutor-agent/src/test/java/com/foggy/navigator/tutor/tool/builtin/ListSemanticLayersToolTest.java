package com.foggy.navigator.tutor.tool.builtin;

import com.foggy.navigator.agent.framework.tool.ToolExecutionRequest;
import com.foggy.navigator.agent.framework.tool.ToolExecutionResult;
import com.foggy.navigator.spi.metadata.MetadataFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ListSemanticLayersTool 测试")
class ListSemanticLayersToolTest {

    @Mock
    private MetadataFacade metadataFacade;

    private ListSemanticLayersTool tool;

    @BeforeEach
    void setUp() {
        tool = new ListSemanticLayersTool(metadataFacade);
    }

    @Test
    @DisplayName("工具名称应为 list_semantic_layers")
    void getName_shouldReturnCorrectName() {
        assertEquals("list_semantic_layers", tool.getName());
    }

    @Test
    @DisplayName("有语义层时应返回格式化列表")
    void execute_shouldReturnFormattedList_whenLayersExist() {
        Map<String, Object> layer = Map.of(
                "id", "sl-1",
                "datasourceId", "ds-1",
                "gitRepoUrl", "https://github.com/test/repo",
                "gitBranch", "main",
                "modelCount", 5,
                "status", "ACTIVE"
        );
        when(metadataFacade.listSemanticLayers("tenant-1", null))
                .thenReturn(List.of(layer));

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .tenantId("tenant-1")
                .parameters(new HashMap<>()) // no datasourceId filter
                .build();

        ToolExecutionResult result = tool.execute(request);

        assertTrue(result.isSuccess());
        String output = (String) result.getData();
        assertTrue(output.contains("sl-1"));
        assertTrue(output.contains("github.com"));
    }

    @Test
    @DisplayName("按 datasourceId 筛选时应传递参数")
    void execute_shouldPassDatasourceId_whenProvided() {
        when(metadataFacade.listSemanticLayers("tenant-1", "ds-1"))
                .thenReturn(List.of());

        Map<String, Object> params = new HashMap<>();
        params.put("datasourceId", "ds-1");
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .tenantId("tenant-1")
                .parameters(params)
                .build();

        tool.execute(request);

        verify(metadataFacade).listSemanticLayers("tenant-1", "ds-1");
    }

    @Test
    @DisplayName("parameters 为 null 时 datasourceId 应为 null")
    void execute_shouldHandleNullParameters() {
        when(metadataFacade.listSemanticLayers("tenant-1", null))
                .thenReturn(List.of());

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .tenantId("tenant-1")
                .parameters(null)
                .build();

        ToolExecutionResult result = tool.execute(request);

        assertTrue(result.isSuccess());
        verify(metadataFacade).listSemanticLayers("tenant-1", null);
    }

    @Test
    @DisplayName("无语义层时应返回引导提示")
    void execute_shouldReturnGuide_whenEmpty() {
        when(metadataFacade.listSemanticLayers("tenant-1", null))
                .thenReturn(List.of());

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .tenantId("tenant-1")
                .parameters(new HashMap<>())
                .build();

        ToolExecutionResult result = tool.execute(request);

        assertTrue(result.isSuccess());
        String output = (String) result.getData();
        assertTrue(output.contains("save_semantic_layer"));
    }

    @Test
    @DisplayName("facade 异常时应返回错误结果")
    void execute_shouldReturnError_whenFacadeThrows() {
        when(metadataFacade.listSemanticLayers("tenant-1", null))
                .thenThrow(new RuntimeException("DB error"));

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .tenantId("tenant-1")
                .parameters(new HashMap<>())
                .build();

        ToolExecutionResult result = tool.execute(request);

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("DB error"));
    }
}
