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

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SaveSemanticLayerTool 测试")
class SaveSemanticLayerToolTest {

    @Mock
    private MetadataFacade metadataFacade;

    private SaveSemanticLayerTool tool;

    @BeforeEach
    void setUp() {
        tool = new SaveSemanticLayerTool(metadataFacade);
    }

    @Test
    @DisplayName("工具名称应为 save_semantic_layer")
    void getName_shouldReturnCorrectName() {
        assertEquals("save_semantic_layer", tool.getName());
    }

    @Test
    @DisplayName("参数 schema 应包含 datasourceId 和 repoUrl 为必填")
    void getParameters_shouldRequireDatasourceIdAndRepoUrl() {
        Map<String, Object> schema = tool.getParameters();
        String[] required = (String[]) schema.get("required");
        assertNotNull(required);
        assertEquals(2, required.length);
        assertEquals("datasourceId", required[0]);
        assertEquals("repoUrl", required[1]);
    }

    @Test
    @DisplayName("成功创建应返回配置 ID 和状态")
    void execute_shouldReturnIdAndStatus_whenSuccess() {
        Map<String, Object> params = Map.of(
                "datasourceId", "ds-1",
                "repoUrl", "https://github.com/test/repo",
                "branch", "main",
                "authType", "ACCESS_TOKEN",
                "accessToken", "ghp_xxx"
        );
        when(metadataFacade.saveSemanticLayer(eq("tenant-1"), eq(params)))
                .thenReturn(Map.of("id", "sl-1", "status", "DRAFT"));

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .tenantId("tenant-1")
                .parameters(params)
                .build();

        ToolExecutionResult result = tool.execute(request);

        assertTrue(result.isSuccess());
        String output = (String) result.getData();
        assertTrue(output.contains("sl-1"));
        assertTrue(output.contains("DRAFT"));
    }

    @Test
    @DisplayName("facade 返回 error 字段时应返回错误")
    void execute_shouldReturnError_whenFacadeReturnsError() {
        Map<String, Object> params = Map.of("datasourceId", "ds-1", "repoUrl", "bad-url");
        when(metadataFacade.saveSemanticLayer(eq("tenant-1"), eq(params)))
                .thenReturn(Map.of("error", "无效的仓库 URL"));

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .tenantId("tenant-1")
                .parameters(params)
                .build();

        ToolExecutionResult result = tool.execute(request);

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("无效的仓库 URL"));
    }

    @Test
    @DisplayName("facade 异常时应返回错误结果")
    void execute_shouldReturnError_whenFacadeThrows() {
        Map<String, Object> params = Map.of("datasourceId", "ds-1", "repoUrl", "https://repo");
        when(metadataFacade.saveSemanticLayer(eq("tenant-1"), eq(params)))
                .thenThrow(new RuntimeException("数据源不存在"));

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .tenantId("tenant-1")
                .parameters(params)
                .build();

        ToolExecutionResult result = tool.execute(request);

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("数据源不存在"));
    }
}
