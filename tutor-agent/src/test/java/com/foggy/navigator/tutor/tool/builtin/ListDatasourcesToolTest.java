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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ListDatasourcesTool 测试")
class ListDatasourcesToolTest {

    @Mock
    private MetadataFacade metadataFacade;

    private ListDatasourcesTool tool;

    @BeforeEach
    void setUp() {
        tool = new ListDatasourcesTool(metadataFacade);
    }

    @Test
    @DisplayName("工具名称应为 list_datasources")
    void getName_shouldReturnCorrectName() {
        assertEquals("list_datasources", tool.getName());
    }

    @Test
    @DisplayName("有数据源时应返回格式化列表")
    void execute_shouldReturnFormattedList_whenDatasourcesExist() {
        Map<String, Object> ds = Map.of(
                "id", "ds-1",
                "name", "测试数据源",
                "type", "JDBC",
                "dbType", "mysql",
                "host", "localhost",
                "status", "ACTIVE",
                "connectionValid", true
        );
        when(metadataFacade.listDatasources("tenant-1")).thenReturn(List.of(ds));

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .tenantId("tenant-1")
                .build();

        ToolExecutionResult result = tool.execute(request);

        assertTrue(result.isSuccess());
        String output = (String) result.getData();
        assertTrue(output.contains("ds-1"));
        assertTrue(output.contains("测试数据源"));
        assertTrue(output.contains("mysql"));
    }

    @Test
    @DisplayName("无数据源时应返回引导提示")
    void execute_shouldReturnGuide_whenEmpty() {
        when(metadataFacade.listDatasources("tenant-1")).thenReturn(List.of());

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .tenantId("tenant-1")
                .build();

        ToolExecutionResult result = tool.execute(request);

        assertTrue(result.isSuccess());
        String output = (String) result.getData();
        assertTrue(output.contains("save_datasource"));
    }

    @Test
    @DisplayName("facade 异常时应返回错误结果")
    void execute_shouldReturnError_whenFacadeThrows() {
        when(metadataFacade.listDatasources("tenant-1"))
                .thenThrow(new RuntimeException("DB error"));

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .tenantId("tenant-1")
                .build();

        ToolExecutionResult result = tool.execute(request);

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("DB error"));
    }
}
