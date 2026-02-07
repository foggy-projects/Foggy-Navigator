package com.foggy.navigator.tutor.tool.builtin;

import com.foggy.navigator.agent.framework.tool.BuiltInTool;
import com.foggy.navigator.agent.framework.tool.ToolExecutionRequest;
import com.foggy.navigator.agent.framework.tool.ToolExecutionResult;
import com.foggy.navigator.spi.metadata.MetadataFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 测试数据源连接
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TestDatasourceConnectionTool implements BuiltInTool {

    private final MetadataFacade metadataFacade;

    @Override
    public String getName() {
        return "test_datasource_connection";
    }

    @Override
    public String getDescription() {
        return "测试指定数据源的连接是否正常";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> configId = new LinkedHashMap<>();
        configId.put("type", "string");
        configId.put("description", "数据源配置 ID");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("configId", configId);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", new String[]{"configId"});
        return schema;
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request) {
        String configId = (String) request.getParameters().get("configId");
        log.info("Executing test_datasource_connection for configId={}", configId);
        try {
            Map<String, Object> result = metadataFacade.testDatasourceConnection(configId);
            boolean success = Boolean.TRUE.equals(result.get("success"));
            String message = (String) result.get("message");
            if (success) {
                return ToolExecutionResult.success("连接测试成功：" + message);
            } else {
                return ToolExecutionResult.success("连接测试失败：" + message);
            }
        } catch (Exception e) {
            log.error("test_datasource_connection failed", e);
            return ToolExecutionResult.error("测试数据源连接失败：" + e.getMessage());
        }
    }
}
