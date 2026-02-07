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
 * 删除数据源配置
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeleteDatasourceTool implements BuiltInTool {

    private final MetadataFacade metadataFacade;

    @Override
    public String getName() {
        return "delete_datasource";
    }

    @Override
    public String getDescription() {
        return "删除指定的数据源配置（同时删除关联的语义层配置）";
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
        log.info("Executing delete_datasource for configId={}", configId);
        try {
            Map<String, Object> result = metadataFacade.deleteDatasource(configId);
            if (result.containsKey("error")) {
                return ToolExecutionResult.error((String) result.get("error"));
            }
            return ToolExecutionResult.success("数据源已删除，配置 ID: " + configId);
        } catch (Exception e) {
            log.error("delete_datasource failed", e);
            return ToolExecutionResult.error("删除数据源失败：" + e.getMessage());
        }
    }
}
