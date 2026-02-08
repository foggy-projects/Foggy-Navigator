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
@DisplayName("SaveDatasourceTool 测试")
class SaveDatasourceToolTest {

    @Mock
    private MetadataFacade metadataFacade;

    private SaveDatasourceTool tool;

    @BeforeEach
    void setUp() {
        tool = new SaveDatasourceTool(metadataFacade);
    }

    @Test
    @DisplayName("工具名称应为 save_datasource")
    void getName_shouldReturnCorrectName() {
        assertEquals("save_datasource", tool.getName());
    }

    @Test
    @DisplayName("参数 schema 应包含必填字段")
    void getParameters_shouldContainRequiredFields() {
        Map<String, Object> schema = tool.getParameters();
        String[] required = (String[]) schema.get("required");
        assertNotNull(required);
        assertTrue(required.length >= 7); // name, type, host, port, databaseName, username, password
    }

    @Test
    @DisplayName("成功创建应返回配置 ID")
    void execute_shouldReturnId_whenSuccess() {
        Map<String, Object> params = Map.of(
                "name", "test-ds",
                "type", "JDBC",
                "host", "localhost",
                "port", 3306,
                "databaseName", "testdb",
                "username", "root",
                "password", "pass"
        );
        when(metadataFacade.saveDatasource(eq("tenant-1"), eq(params)))
                .thenReturn(Map.of("id", "ds-123", "status", "DRAFT"));

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .tenantId("tenant-1")
                .parameters(params)
                .build();

        ToolExecutionResult result = tool.execute(request);

        assertTrue(result.isSuccess());
        String output = (String) result.getData();
        assertTrue(output.contains("ds-123"));
        assertTrue(output.contains("DRAFT"));
    }

    @Test
    @DisplayName("facade 返回 error 字段时应返回错误结果")
    void execute_shouldReturnError_whenFacadeReturnsError() {
        Map<String, Object> params = Map.of("name", "bad-ds");
        when(metadataFacade.saveDatasource(eq("tenant-1"), eq(params)))
                .thenReturn(Map.of("error", "名称已存在"));

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .tenantId("tenant-1")
                .parameters(params)
                .build();

        ToolExecutionResult result = tool.execute(request);

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("名称已存在"));
    }

    @Test
    @DisplayName("facade 异常时应返回错误结果")
    void execute_shouldReturnError_whenFacadeThrows() {
        Map<String, Object> params = Map.of("name", "test");
        when(metadataFacade.saveDatasource(eq("tenant-1"), eq(params)))
                .thenThrow(new RuntimeException("connection refused"));

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .tenantId("tenant-1")
                .parameters(params)
                .build();

        ToolExecutionResult result = tool.execute(request);

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("connection refused"));
    }
}
