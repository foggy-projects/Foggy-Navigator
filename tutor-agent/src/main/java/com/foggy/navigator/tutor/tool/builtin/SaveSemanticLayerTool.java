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
 * 创建新的语义层配置
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SaveSemanticLayerTool implements BuiltInTool {

    private final MetadataFacade metadataFacade;

    @Override
    public String getName() {
        return "save_semantic_layer";
    }

    @Override
    public String getDescription() {
        return "为数据源创建语义层配置（关联 Git 仓库中的 TM/QM 模型）";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> datasourceId = new LinkedHashMap<>();
        datasourceId.put("type", "string");
        datasourceId.put("description", "关联的数据源配置 ID");

        Map<String, Object> repoUrl = new LinkedHashMap<>();
        repoUrl.put("type", "string");
        repoUrl.put("description", "Git 仓库 URL");

        Map<String, Object> branch = new LinkedHashMap<>();
        branch.put("type", "string");
        branch.put("description", "Git 分支（默认 main）");

        Map<String, Object> authType = new LinkedHashMap<>();
        authType.put("type", "string");
        authType.put("description", "认证方式：NONE, ACCESS_TOKEN, BASIC, SSH");
        authType.put("enum", new String[]{"NONE", "ACCESS_TOKEN", "BASIC", "SSH"});

        Map<String, Object> accessToken = new LinkedHashMap<>();
        accessToken.put("type", "string");
        accessToken.put("description", "访问令牌（ACCESS_TOKEN 认证时使用）");

        Map<String, Object> description = new LinkedHashMap<>();
        description.put("type", "string");
        description.put("description", "语义层描述（可选）");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("datasourceId", datasourceId);
        properties.put("repoUrl", repoUrl);
        properties.put("branch", branch);
        properties.put("authType", authType);
        properties.put("accessToken", accessToken);
        properties.put("description", description);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", new String[]{"datasourceId", "repoUrl"});
        return schema;
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request) {
        Map<String, Object> params = request.getParameters();
        log.info("Executing save_semantic_layer for tenantId={}, datasourceId={}",
                request.getTenantId(), params.get("datasourceId"));
        try {
            Map<String, Object> result = metadataFacade.saveSemanticLayer(request.getTenantId(), params);
            if (result.containsKey("error")) {
                return ToolExecutionResult.error((String) result.get("error"));
            }
            return ToolExecutionResult.success(
                    "语义层配置创建成功！\n配置 ID: " + result.get("id") + "\n状态: " + result.get("status"));
        } catch (Exception e) {
            log.error("save_semantic_layer failed", e);
            return ToolExecutionResult.error("创建语义层配置失败：" + e.getMessage());
        }
    }
}
