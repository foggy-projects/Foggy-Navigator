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
 * 列出语义层配置
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ListSemanticLayersTool implements BuiltInTool {

    private final MetadataFacade metadataFacade;

    @Override
    public String getName() {
        return "list_semantic_layers";
    }

    @Override
    public String getDescription() {
        return "列出语义层配置，可按数据源 ID 筛选";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> datasourceId = new LinkedHashMap<>();
        datasourceId.put("type", "string");
        datasourceId.put("description", "数据源配置 ID（可选，不传则列出所有）");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("datasourceId", datasourceId);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        return schema;
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request) {
        String datasourceId = request.getParameters() != null
                ? (String) request.getParameters().get("datasourceId")
                : null;
        log.info("Executing list_semantic_layers for tenantId={}, datasourceId={}",
                request.getTenantId(), datasourceId);
        try {
            List<Map<String, Object>> layers = metadataFacade.listSemanticLayers(request.getTenantId(), datasourceId);
            if (layers.isEmpty()) {
                return ToolExecutionResult.success("当前没有配置任何语义层。您可以通过 save_semantic_layer 工具为数据源添加语义层配置。");
            }
            StringBuilder sb = new StringBuilder("已配置的语义层：\n");
            for (Map<String, Object> layer : layers) {
                sb.append("- ID: ").append(layer.get("id"))
                  .append(", 数据源ID: ").append(layer.get("datasourceId"))
                  .append(", Git仓库: ").append(layer.get("gitRepoUrl"))
                  .append(", 分支: ").append(layer.get("gitBranch"))
                  .append(", 模型数: ").append(layer.get("modelCount"))
                  .append(", 状态: ").append(layer.get("status"))
                  .append("\n");
            }
            return ToolExecutionResult.success(sb.toString());
        } catch (Exception e) {
            log.error("list_semantic_layers failed", e);
            return ToolExecutionResult.error("获取语义层列表失败：" + e.getMessage());
        }
    }
}
