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
@DisplayName("TestDatasourceConnectionTool 测试")
class TestDatasourceConnectionToolTest {

    @Mock
    private MetadataFacade metadataFacade;

    private TestDatasourceConnectionTool tool;

    @BeforeEach
    void setUp() {
        tool = new TestDatasourceConnectionTool(metadataFacade);
    }

    @Test
    @DisplayName("工具名称应为 test_datasource_connection")
    void getName_shouldReturnCorrectName() {
        assertEquals("test_datasource_connection", tool.getName());
    }

    @Test
    @DisplayName("连接成功时应返回成功消息")
    void execute_shouldReturnSuccess_whenConnectionSucceeds() {
        when(metadataFacade.testDatasourceConnection("ds-1"))
                .thenReturn(Map.of("success", true, "message", "连接正常，延迟 5ms"));

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .parameters(Map.of("configId", "ds-1"))
                .build();

        ToolExecutionResult result = tool.execute(request);

        assertTrue(result.isSuccess());
        String output = (String) result.getData();
        assertTrue(output.contains("连接测试成功"));
        assertTrue(output.contains("5ms"));
    }

    @Test
    @DisplayName("连接失败时应返回失败消息（仍为 success result）")
    void execute_shouldReturnFailureMessage_whenConnectionFails() {
        when(metadataFacade.testDatasourceConnection("ds-1"))
                .thenReturn(Map.of("success", false, "message", "Connection refused"));

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .parameters(Map.of("configId", "ds-1"))
                .build();

        ToolExecutionResult result = tool.execute(request);

        assertTrue(result.isSuccess()); // tool execution succeeded, connection test failed
        String output = (String) result.getData();
        assertTrue(output.contains("连接测试失败"));
        assertTrue(output.contains("Connection refused"));
    }

    @Test
    @DisplayName("facade 异常时应返回错误结果")
    void execute_shouldReturnError_whenFacadeThrows() {
        when(metadataFacade.testDatasourceConnection("ds-1"))
                .thenThrow(new RuntimeException("service unavailable"));

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .parameters(Map.of("configId", "ds-1"))
                .build();

        ToolExecutionResult result = tool.execute(request);

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("service unavailable"));
    }
}
