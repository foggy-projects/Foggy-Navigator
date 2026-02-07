package com.foggy.navigator.tutor.tool.builtin;

import com.foggy.navigator.agent.framework.tool.BuiltInTool;
import com.foggy.navigator.agent.framework.tool.ToolExecutionRequest;
import com.foggy.navigator.agent.framework.tool.ToolExecutionResult;
import com.foggy.navigator.spi.metadata.MetadataFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 列出租户下所有数据源���置
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ListDatasourcesTool implements BuiltInTool {

    private final MetadataFacade metadataFacade;

    @Override
    public String getName() {
        return "list_datasources";
    }

    @Override
    public String getDescription() {
        return "列出当前租户下所有已配置的数据源";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of());
        return schema;
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request) {
        log.info("Executing list_datasources for tenantId={}", request.getTenantId());
        try {
            List<Map<String, Object>> datasources = metadataFacade.listDatasources(request.getTenantId());
            if (datasources.isEmpty()) {
                return ToolExecutionResult.success("当前没有配置任何数据源。您可以通过 save_datasource 工具添加一个新的数据源配置。");
            }
            StringBuilder sb = new StringBuilder("已配置的数据源：\n");
            for (Map<String, Object> ds : datasources) {
                sb.append("- ID: ").append(ds.get("id"))
                  .append(", 名称: ").append(ds.get("name"))
                  .append(", 类型: ").append(ds.get("type"))
                  .append(", 数据库: ").append(ds.get("dbType"))
                  .append(", 主机: ").append(ds.get("host"))
                  .append(", 状态: ").append(ds.get("status"))
                  .append(", 连接有效: ").append(ds.get("connectionValid"))
                  .append("\n");
            }
            return ToolExecutionResult.success(sb.toString());
        } catch (Exception e) {
            log.error("list_datasources failed", e);
            return ToolExecutionResult.error("获取数据源列表失败：" + e.getMessage());
        }
    }
}
