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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("GetDatasourceDetailTool 测试")
class GetDatasourceDetailToolTest {

    @Mock
    private MetadataFacade metadataFacade;

    private GetDatasourceDetailTool tool;

    @BeforeEach
    void setUp() {
        tool = new GetDatasourceDetailTool(metadataFacade);
    }

    @Test
    @DisplayName("工具名称应为 get_datasource_detail")
    void getName_shouldReturnCorrectName() {
        assertEquals("get_datasource_detail", tool.getName());
    }

    @Test
    @DisplayName("成功获取详情应返回格式化信息")
    void execute_shouldReturnFormattedDetail() {
        Map<String, Object> ds = Map.ofEntries(
                Map.entry("id", "ds-1"),
                Map.entry("name", "MySQL主库"),
                Map.entry("type", "JDBC"),
                Map.entry("dbType", "mysql"),
                Map.entry("host", "10.0.0.1"),
                Map.entry("port", 3306),
                Map.entry("databaseName", "prod"),
                Map.entry("username", "app"),
                Map.entry("status", "ACTIVE"),
                Map.entry("connectionValid", true),
                Map.entry("description", "生产主库"),
                Map.entry("createdAt", "2025-01-01")
        );
        when(metadataFacade.getDatasource("ds-1")).thenReturn(ds);

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .parameters(Map.of("configId", "ds-1"))
                .build();

        ToolExecutionResult result = tool.execute(request);

        assertTrue(result.isSuccess());
        String output = (String) result.getData();
        assertTrue(output.contains("MySQL主库"));
        assertTrue(output.contains("10.0.0.1"));
        assertTrue(output.contains("3306"));
    }

    @Test
    @DisplayName("facade 返回 error 字段时应返回错误")
    void execute_shouldReturnError_whenFacadeReturnsError() {
        when(metadataFacade.getDatasource("ds-999"))
                .thenReturn(Map.of("error", "数据源不存在"));

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .parameters(Map.of("configId", "ds-999"))
                .build();

        ToolExecutionResult result = tool.execute(request);

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("数据源不存在"));
    }

    @Test
    @DisplayName("facade 异常时应返回错误结果")
    void execute_shouldReturnError_whenFacadeThrows() {
        when(metadataFacade.getDatasource("ds-1"))
                .thenThrow(new RuntimeException("timeout"));

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .parameters(Map.of("configId", "ds-1"))
                .build();

        ToolExecutionResult result = tool.execute(request);

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("timeout"));
    }
}
