package com.foggy.navigator.metadata.query.config.service;

import com.foggy.navigator.common.entity.DatasourceConfigEntity;
import com.foggy.navigator.common.entity.SemanticLayerConfigEntity;
import com.foggy.navigator.common.enums.DatasourceType;
import com.foggy.navigator.common.enums.GitAuthType;
import com.foggy.navigator.common.form.*;
import com.foggy.navigator.metadata.query.config.repository.DatasourceConfigRepository;
import com.foggy.navigator.metadata.query.config.repository.SemanticLayerConfigRepository;
import com.foggy.navigator.spi.config.ConfigurationManager;
import com.foggy.navigator.spi.metadata.MetadataFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.*;
import java.util.stream.Collectors;

/**
 * MetadataFacade 实现
 * 为 tutor-agent BuiltInTool 提供元数据配置操作
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetadataFacadeImpl implements MetadataFacade {

    private final ConfigurationManager configurationManager;
    private final DatasourceConfigRepository datasourceRepo;
    private final SemanticLayerConfigRepository semanticLayerRepo;
    private final DatasourceLookupService datasourceLookupService;

    // ===== 数据源 =====

    @Override
    public List<Map<String, Object>> listDatasources(String tenantId) {
        return datasourceRepo.findByTenantId(tenantId).stream()
                .map(this::datasourceToMap)
                .collect(Collectors.toList());
    }

    @Override
    public Map<String, Object> getDatasource(String configId) {
        return datasourceRepo.findById(configId)
                .map(this::datasourceToMap)
                .orElse(Map.of("error", "数据源不存在: " + configId));
    }

    @Override
    public Map<String, Object> saveDatasource(String tenantId, Map<String, Object> params) {
        try {
            DatasourceConfigForm form = buildDatasourceForm(tenantId, params);
            String configId = configurationManager.saveDatasourceConfig(form);
            return Map.of("id", configId, "status", "created");
        } catch (Exception e) {
            log.error("Failed to save datasource config", e);
            return Map.of("error", "保存数据源配置失败: " + e.getMessage());
        }
    }

    @Override
    public Map<String, Object> deleteDatasource(String configId) {
        try {
            configurationManager.deleteDatasourceConfig(configId);
            return Map.of("status", "deleted", "configId", configId);
        } catch (Exception e) {
            log.error("Failed to delete datasource config: {}", configId, e);
            return Map.of("error", "删除数据源配置失败: " + e.getMessage());
        }
    }

    @Override
    public Map<String, Object> testDatasourceConnection(String configId) {
        try {
            DatasourceConfigEntity entity = datasourceRepo.findById(configId).orElse(null);
            if (entity == null) {
                return Map.of("success", false, "message", "数据源不存在: " + configId);
            }

            boolean connected = false;
            String message;

            if (entity.getType() == DatasourceType.JDBC) {
                Optional<DataSource> dsOpt = datasourceLookupService.getDataSourceByConfigId(configId);
                if (dsOpt.isPresent()) {
                    try (Connection conn = dsOpt.get().getConnection()) {
                        connected = conn.isValid(5);
                        message = connected ? "连接成功" : "连接无效";
                    }
                } else {
                    message = "数据源 Bean 尚未注册，请先验证配置";
                }
            } else if (entity.getType() == DatasourceType.MONGO) {
                boolean available = datasourceLookupService.getMongoTemplateByConfigId(configId).isPresent();
                connected = available;
                message = available ? "MongoDB 连接可用" : "MongoDB 模板尚未注册";
            } else {
                message = "不支持的数据源类型: " + entity.getType();
            }

            configurationManager.updateDatasourceConnectionStatus(configId, connected);
            return Map.of("success", connected, "message", message);
        } catch (Exception e) {
            log.error("Failed to test datasource connection: {}", configId, e);
            return Map.of("success", false, "message", "测试连接失败: " + e.getMessage());
        }
    }

    // ===== 语义层 =====

    @Override
    public List<Map<String, Object>> listSemanticLayers(String tenantId, String datasourceId) {
        List<SemanticLayerConfigEntity> entities;
        if (datasourceId != null && !datasourceId.isEmpty()) {
            entities = semanticLayerRepo.findByDatasourceId(datasourceId);
        } else {
            entities = semanticLayerRepo.findByTenantId(tenantId);
        }
        return entities.stream()
                .map(this::semanticLayerToMap)
                .collect(Collectors.toList());
    }

    @Override
    public Map<String, Object> saveSemanticLayer(String tenantId, Map<String, Object> params) {
        try {
            SemanticLayerConfigForm form = buildSemanticLayerForm(tenantId, params);
            String configId = configurationManager.saveSemanticLayerConfig(form);
            return Map.of("id", configId, "status", "created");
        } catch (Exception e) {
            log.error("Failed to save semantic layer config", e);
            return Map.of("error", "保存语义层配置失败: " + e.getMessage());
        }
    }

    @Override
    public Map<String, Object> deleteSemanticLayer(String configId) {
        try {
            configurationManager.deleteSemanticLayerConfig(configId);
            return Map.of("status", "deleted", "configId", configId);
        } catch (Exception e) {
            log.error("Failed to delete semantic layer config: {}", configId, e);
            return Map.of("error", "删除语义层配置失败: " + e.getMessage());
        }
    }

    // ===== 私有方法 =====

    private Map<String, Object> datasourceToMap(DatasourceConfigEntity entity) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", entity.getId());
        map.put("tenantId", entity.getTenantId());
        map.put("name", entity.getName());
        map.put("type", entity.getType() != null ? entity.getType().name() : null);
        map.put("dbType", entity.getDbType());
        map.put("host", entity.getHost());
        map.put("port", entity.getPort());
        map.put("databaseName", entity.getDatabaseName());
        map.put("username", entity.getUsername());
        // password 不暴露
        map.put("status", entity.getStatus() != null ? entity.getStatus().name() : null);
        map.put("connectionValid", entity.getConnectionValid());
        map.put("description", entity.getDescription());
        map.put("createdAt", entity.getCreatedAt() != null ? entity.getCreatedAt().toString() : null);
        return map;
    }

    private Map<String, Object> semanticLayerToMap(SemanticLayerConfigEntity entity) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", entity.getId());
        map.put("tenantId", entity.getTenantId());
        map.put("datasourceId", entity.getDatasourceId());
        map.put("gitRepoUrl", entity.getGitRepoUrl());
        map.put("gitBranch", entity.getGitBranch());
        map.put("gitAuthType", entity.getGitAuthType() != null ? entity.getGitAuthType().name() : null);
        // gitAccessToken, gitPassword 不暴露
        map.put("semanticLayerPath", entity.getSemanticLayerPath());
        map.put("modelsPath", entity.getModelsPath());
        map.put("queriesPath", entity.getQueriesPath());
        map.put("autoSync", entity.getAutoSync());
        map.put("syncInterval", entity.getSyncInterval());
        map.put("modelCount", entity.getModelCount());
        map.put("status", entity.getStatus() != null ? entity.getStatus().name() : null);
        map.put("description", entity.getDescription());
        map.put("createdAt", entity.getCreatedAt() != null ? entity.getCreatedAt().toString() : null);
        return map;
    }

    private DatasourceConfigForm buildDatasourceForm(String tenantId, Map<String, Object> params) {
        DatasourceConfigForm form = new DatasourceConfigForm();
        form.setTenantId(tenantId);

        String name = (String) params.get("name");
        String typeStr = (String) params.get("type");
        String description = (String) params.get("description");

        DatasourceBasicInfo basicInfo = new DatasourceBasicInfo();
        basicInfo.setName(name);
        if (typeStr != null) {
            basicInfo.setType(DatasourceType.valueOf(typeStr.toUpperCase()));
        }
        basicInfo.setDescription(description);
        form.setBasicInfo(basicInfo);

        DatasourceType type = basicInfo.getType();
        if (type == DatasourceType.MONGO) {
            MongoDatasourceInfo mongoInfo = new MongoDatasourceInfo();
            mongoInfo.setHosts((String) params.get("host"));
            mongoInfo.setPort(toInteger(params.get("port")));
            mongoInfo.setDatabase((String) params.get("databaseName"));
            mongoInfo.setUsername((String) params.get("username"));
            mongoInfo.setPassword((String) params.get("password"));
            mongoInfo.setConnectionString((String) params.get("connectionString"));
            form.setMongoInfo(mongoInfo);
        } else {
            // 默认 JDBC
            JdbcDatasourceInfo jdbcInfo = new JdbcDatasourceInfo();
            jdbcInfo.setDbType((String) params.get("dbType"));
            jdbcInfo.setHost((String) params.get("host"));
            jdbcInfo.setPort(toInteger(params.get("port")));
            jdbcInfo.setDatabaseName((String) params.get("databaseName"));
            jdbcInfo.setUsername((String) params.get("username"));
            jdbcInfo.setPassword((String) params.get("password"));
            jdbcInfo.setJdbcUrl((String) params.get("jdbcUrl"));
            jdbcInfo.setExtraParams((String) params.get("extraParams"));
            form.setJdbcInfo(jdbcInfo);
        }

        return form;
    }

    private SemanticLayerConfigForm buildSemanticLayerForm(String tenantId, Map<String, Object> params) {
        SemanticLayerConfigForm form = new SemanticLayerConfigForm();
        form.setTenantId(tenantId);
        form.setDatasourceId((String) params.get("datasourceId"));
        form.setDescription((String) params.get("description"));

        GitRepoConfig gitConfig = new GitRepoConfig();
        gitConfig.setRepoUrl((String) params.get("repoUrl"));
        gitConfig.setBranch((String) params.get("branch"));
        String authTypeStr = (String) params.get("authType");
        if (authTypeStr != null) {
            gitConfig.setAuthType(GitAuthType.valueOf(authTypeStr.toUpperCase()));
        }
        gitConfig.setAccessToken((String) params.get("accessToken"));
        gitConfig.setUsername((String) params.get("username"));
        gitConfig.setPassword((String) params.get("password"));
        form.setGitConfig(gitConfig);

        return form;
    }

    private Integer toInteger(Object value) {
        if (value == null) return null;
        if (value instanceof Integer) return (Integer) value;
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
