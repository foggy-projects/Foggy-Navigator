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
 * 创建新的数据源配置
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SaveDatasourceTool implements BuiltInTool {

    private final MetadataFacade metadataFacade;

    @Override
    public String getName() {
        return "save_datasource";
    }

    @Override
    public String getDescription() {
        return "创建一个新的数据源配置（支持 JDBC 和 MongoDB 类型）";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> name = new LinkedHashMap<>();
        name.put("type", "string");
        name.put("description", "数据源名称");

        Map<String, Object> type = new LinkedHashMap<>();
        type.put("type", "string");
        type.put("description", "数据源类型：JDBC 或 MONGO");
        type.put("enum", new String[]{"JDBC", "MONGO"});

        Map<String, Object> dbType = new LinkedHashMap<>();
        dbType.put("type", "string");
        dbType.put("description", "数据库类型（JDBC时必填）：mysql, postgresql, sqlserver, oracle");

        Map<String, Object> host = new LinkedHashMap<>();
        host.put("type", "string");
        host.put("description", "主机地址");

        Map<String, Object> port = new LinkedHashMap<>();
        port.put("type", "integer");
        port.put("description", "端口号");

        Map<String, Object> databaseName = new LinkedHashMap<>();
        databaseName.put("type", "string");
        databaseName.put("description", "数据库名称");

        Map<String, Object> username = new LinkedHashMap<>();
        username.put("type", "string");
        username.put("description", "用户名");

        Map<String, Object> password = new LinkedHashMap<>();
        password.put("type", "string");
        password.put("description", "密码");

        Map<String, Object> description = new LinkedHashMap<>();
        description.put("type", "string");
        description.put("description", "数据源描述（可选）");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", name);
        properties.put("type", type);
        properties.put("dbType", dbType);
        properties.put("host", host);
        properties.put("port", port);
        properties.put("databaseName", databaseName);
        properties.put("username", username);
        properties.put("password", password);
        properties.put("description", description);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", new String[]{"name", "type", "host", "port", "databaseName", "username", "password"});
        return schema;
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request) {
        Map<String, Object> params = request.getParameters();
        log.info("Executing save_datasource for tenantId={}, name={}", request.getTenantId(), params.get("name"));
        try {
            Map<String, Object> result = metadataFacade.saveDatasource(request.getTenantId(), params);
            if (result.containsKey("error")) {
                return ToolExecutionResult.error((String) result.get("error"));
            }
            return ToolExecutionResult.success(
                    "数据源创建成功！\n配置 ID: " + result.get("id") + "\n状态: " + result.get("status"));
        } catch (Exception e) {
            log.error("save_datasource failed", e);
            return ToolExecutionResult.error("创建数据源失败：" + e.getMessage());
        }
    }
}
