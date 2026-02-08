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
@DisplayName("DeleteDatasourceTool 测试")
class DeleteDatasourceToolTest {

    @Mock
    private MetadataFacade metadataFacade;

    private DeleteDatasourceTool tool;

    @BeforeEach
    void setUp() {
        tool = new DeleteDatasourceTool(metadataFacade);
    }

    @Test
    @DisplayName("工具名称应为 delete_datasource")
    void getName_shouldReturnCorrectName() {
        assertEquals("delete_datasource", tool.getName());
    }

    @Test
    @DisplayName("成功删除应返回成功消息")
    void execute_shouldReturnSuccess_whenDeleted() {
        when(metadataFacade.deleteDatasource("ds-1"))
                .thenReturn(Map.of("deleted", true));

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .parameters(Map.of("configId", "ds-1"))
                .build();

        ToolExecutionResult result = tool.execute(request);

        assertTrue(result.isSuccess());
        String output = (String) result.getData();
        assertTrue(output.contains("ds-1"));
        verify(metadataFacade).deleteDatasource("ds-1");
    }

    @Test
    @DisplayName("facade 返回 error 字段时应返回错误")
    void execute_shouldReturnError_whenFacadeReturnsError() {
        when(metadataFacade.deleteDatasource("ds-1"))
                .thenReturn(Map.of("error", "数据源不存在"));

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .parameters(Map.of("configId", "ds-1"))
                .build();

        ToolExecutionResult result = tool.execute(request);

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("数据源不存在"));
    }

    @Test
    @DisplayName("facade 异常时应返回错误结果")
    void execute_shouldReturnError_whenFacadeThrows() {
        when(metadataFacade.deleteDatasource("ds-1"))
                .thenThrow(new RuntimeException("foreign key constraint"));

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .parameters(Map.of("configId", "ds-1"))
                .build();

        ToolExecutionResult result = tool.execute(request);

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("foreign key constraint"));
    }
}
