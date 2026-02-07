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
 * 获取单个数据源的详细信息
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GetDatasourceDetailTool implements BuiltInTool {

    private final MetadataFacade metadataFacade;

    @Override
    public String getName() {
        return "get_datasource_detail";
    }

    @Override
    public String getDescription() {
        return "获取指定数据源的详细配置信息";
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
        log.info("Executing get_datasource_detail for configId={}", configId);
        try {
            Map<String, Object> ds = metadataFacade.getDatasource(configId);
            if (ds.containsKey("error")) {
                return ToolExecutionResult.error((String) ds.get("error"));
            }
            StringBuilder sb = new StringBuilder("数据源详情：\n");
            sb.append("ID: ").append(ds.get("id")).append("\n");
            sb.append("名称: ").append(ds.get("name")).append("\n");
            sb.append("类型: ").append(ds.get("type")).append("\n");
            sb.append("数据库类型: ").append(ds.get("dbType")).append("\n");
            sb.append("主机: ").append(ds.get("host")).append("\n");
            sb.append("端口: ").append(ds.get("port")).append("\n");
            sb.append("数据库名: ").append(ds.get("databaseName")).append("\n");
            sb.append("用户名: ").append(ds.get("username")).append("\n");
            sb.append("状态: ").append(ds.get("status")).append("\n");
            sb.append("连接有效: ").append(ds.get("connectionValid")).append("\n");
            sb.append("描述: ").append(ds.get("description")).append("\n");
            sb.append("创建时间: ").append(ds.get("createdAt")).append("\n");
            return ToolExecutionResult.success(sb.toString());
        } catch (Exception e) {
            log.error("get_datasource_detail failed", e);
            return ToolExecutionResult.error("获取数据源详情失败：" + e.getMessage());
        }
    }
}
