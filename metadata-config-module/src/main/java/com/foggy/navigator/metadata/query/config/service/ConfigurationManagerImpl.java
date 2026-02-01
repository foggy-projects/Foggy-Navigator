package com.foggy.navigator.metadata.query.config.service;

import com.foggy.navigator.common.entity.DatasourceConfigEntity;
import com.foggy.navigator.common.entity.SemanticLayerConfigEntity;
import com.foggy.navigator.common.enums.ConfigItemStatus;
import com.foggy.navigator.common.event.DatasourceConfigEvent;
import com.foggy.navigator.common.form.*;
import com.foggy.navigator.metadata.query.config.repository.DatasourceConfigRepository;
import com.foggy.navigator.metadata.query.config.repository.SemanticLayerConfigRepository;
import com.foggy.navigator.spi.config.ConfigurationManager;
import com.foggyframework.core.ex.RX;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 配置管理实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigurationManagerImpl implements ConfigurationManager {

    private final DatasourceConfigRepository datasourceRepo;
    private final SemanticLayerConfigRepository semanticLayerRepo;
    private final ApplicationEventPublisher eventPublisher;

    // ===== 数据源配置 =====

    @Override
    @Transactional
    public String saveDatasourceConfig(DatasourceConfigForm form) {
        log.info("Saving datasource config: name={}",
            form.getBasicInfo() != null ? form.getBasicInfo().getName() : "unknown");

        DatasourceConfigEntity entity = new DatasourceConfigEntity();
        entity.setId(form.getId() != null ? form.getId() : UUID.randomUUID().toString());
        entity.setTenantId(form.getTenantId());

        // 基本信息
        if (form.getBasicInfo() != null) {
            DatasourceBasicInfo basic = form.getBasicInfo();
            entity.setName(basic.getName());
            entity.setType(basic.getType());
            entity.setDescription(basic.getDescription());
            entity.setStatus(basic.getStatus() != null ? basic.getStatus() : ConfigItemStatus.NOT_STARTED);
        }

        // JDBC信息
        if (form.getJdbcInfo() != null) {
            JdbcDatasourceInfo jdbc = form.getJdbcInfo();
            entity.setDbType(jdbc.getDbType());
            entity.setHost(jdbc.getHost());
            entity.setPort(jdbc.getPort());
            entity.setDatabaseName(jdbc.getDatabaseName());
            entity.setUsername(jdbc.getUsername());
            entity.setPassword(jdbc.getPassword()); // TODO: 加密存储
            entity.setJdbcUrl(jdbc.getJdbcUrl());
            entity.setExtraParams(jdbc.getExtraParams());
        }

        // MongoDB信息
        if (form.getMongoInfo() != null) {
            MongoDatasourceInfo mongo = form.getMongoInfo();
            entity.setHost(mongo.getHosts());
            entity.setPort(mongo.getPort());
            entity.setDatabaseName(mongo.getDatabase());
            entity.setUsername(mongo.getUsername());
            entity.setPassword(mongo.getPassword()); // TODO: 加密存储
            entity.setConnectionString(mongo.getConnectionString());
        }

        datasourceRepo.save(entity);
        log.info("Datasource config saved: id={}", entity.getId());

        eventPublisher.publishEvent(DatasourceConfigEvent.created(this, entity));
        return entity.getId();
    }

    @Override
    @Transactional
    public void updateDatasourceConfig(String configId, DatasourceConfigForm form) {
        log.info("Updating datasource config: id={}", configId);

        DatasourceConfigEntity entity = datasourceRepo.findById(configId)
            .orElseThrow(() -> RX.throwB("Datasource config not found: " + configId));

        // 更新基本信息
        if (form.getBasicInfo() != null) {
            DatasourceBasicInfo basic = form.getBasicInfo();
            if (basic.getName() != null) entity.setName(basic.getName());
            if (basic.getType() != null) entity.setType(basic.getType());
            if (basic.getDescription() != null) entity.setDescription(basic.getDescription());
            if (basic.getStatus() != null) entity.setStatus(basic.getStatus());
        }

        // 更新JDBC信息
        if (form.getJdbcInfo() != null) {
            JdbcDatasourceInfo jdbc = form.getJdbcInfo();
            if (jdbc.getDbType() != null) entity.setDbType(jdbc.getDbType());
            if (jdbc.getHost() != null) entity.setHost(jdbc.getHost());
            if (jdbc.getPort() != null) entity.setPort(jdbc.getPort());
            if (jdbc.getDatabaseName() != null) entity.setDatabaseName(jdbc.getDatabaseName());
            if (jdbc.getUsername() != null) entity.setUsername(jdbc.getUsername());
            if (jdbc.getPassword() != null) entity.setPassword(jdbc.getPassword());
            if (jdbc.getJdbcUrl() != null) entity.setJdbcUrl(jdbc.getJdbcUrl());
            if (jdbc.getExtraParams() != null) entity.setExtraParams(jdbc.getExtraParams());
        }

        // 更新MongoDB信息
        if (form.getMongoInfo() != null) {
            MongoDatasourceInfo mongo = form.getMongoInfo();
            if (mongo.getHosts() != null) entity.setHost(mongo.getHosts());
            if (mongo.getPort() != null) entity.setPort(mongo.getPort());
            if (mongo.getDatabase() != null) entity.setDatabaseName(mongo.getDatabase());
            if (mongo.getUsername() != null) entity.setUsername(mongo.getUsername());
            if (mongo.getPassword() != null) entity.setPassword(mongo.getPassword());
            if (mongo.getConnectionString() != null) entity.setConnectionString(mongo.getConnectionString());
        }

        datasourceRepo.save(entity);
        log.info("Datasource config updated: id={}", configId);

        eventPublisher.publishEvent(DatasourceConfigEvent.updated(this, entity));
    }

    @Override
    @Transactional
    public void updateDatasourceStatus(String configId, ConfigItemStatus status) {
        log.info("Updating datasource status: id={}, status={}", configId, status);

        DatasourceConfigEntity entity = datasourceRepo.findById(configId)
            .orElseThrow(() -> RX.throwB("Datasource config not found: " + configId));
        ConfigItemStatus previousStatus = entity.getStatus();

        int updated = datasourceRepo.updateStatus(configId, status);
        if (updated == 0) {
            throw RX.throwB("Datasource config not found: " + configId);
        }

        entity.setStatus(status);
        eventPublisher.publishEvent(DatasourceConfigEvent.statusChanged(this, entity, previousStatus, status));
    }

    @Override
    @Transactional
    public void updateDatasourceConnectionStatus(String configId, boolean connectionValid) {
        log.info("Updating datasource connection status: id={}, valid={}", configId, connectionValid);
        int updated = datasourceRepo.updateConnectionValid(configId, connectionValid);
        if (updated == 0) {
            throw RX.throwB("Datasource config not found: " + configId);
        }
    }

    @Override
    @Transactional
    public void deleteDatasourceConfig(String configId) {
        log.info("Deleting datasource config: id={}", configId);

        DatasourceConfigEntity entity = datasourceRepo.findById(configId).orElse(null);

        // 先删除关联的语义层配置
        semanticLayerRepo.deleteByDatasourceId(configId);
        // 再删除数据源配置
        datasourceRepo.deleteById(configId);
        log.info("Datasource config deleted: id={}", configId);

        if (entity != null) {
            eventPublisher.publishEvent(DatasourceConfigEvent.deleted(this, entity));
        }
    }

    // ===== 语义层配置 =====

    @Override
    @Transactional
    public String saveSemanticLayerConfig(SemanticLayerConfigForm form) {
        log.info("Saving semantic layer config: datasourceId={}", form.getDatasourceId());

        // 验证数据源存在
        if (!datasourceRepo.existsById(form.getDatasourceId())) {
            throw RX.throwB("Datasource not found: " + form.getDatasourceId());
        }

        SemanticLayerConfigEntity entity = new SemanticLayerConfigEntity();
        entity.setId(form.getId() != null ? form.getId() : UUID.randomUUID().toString());
        entity.setTenantId(form.getTenantId());
        entity.setDatasourceId(form.getDatasourceId());
        entity.setDescription(form.getDescription());
        entity.setStatus(ConfigItemStatus.NOT_STARTED);

        // Git配置
        if (form.getGitConfig() != null) {
            GitRepoConfig git = form.getGitConfig();
            entity.setGitRepoUrl(git.getRepoUrl());
            entity.setGitBranch(git.getBranch() != null ? git.getBranch() : "main");
            entity.setGitAuthType(git.getAuthType());
            entity.setGitAccessToken(git.getAccessToken()); // TODO: 加密存储
            entity.setGitUsername(git.getUsername());
            entity.setGitPassword(git.getPassword()); // TODO: 加密存储
        }

        // 路径配置
        if (form.getPathConfig() != null) {
            SemanticLayerPathConfig path = form.getPathConfig();
            entity.setSemanticLayerPath(path.getRootPath());
            entity.setModelsPath(path.getModelsPath());
            entity.setQueriesPath(path.getQueriesPath());
            entity.setAutoSync(path.getAutoSync());
            entity.setSyncInterval(path.getSyncInterval());
        }

        semanticLayerRepo.save(entity);
        log.info("Semantic layer config saved: id={}", entity.getId());
        return entity.getId();
    }

    @Override
    @Transactional
    public void updateSemanticLayerConfig(String configId, SemanticLayerConfigForm form) {
        log.info("Updating semantic layer config: id={}", configId);

        SemanticLayerConfigEntity entity = semanticLayerRepo.findById(configId)
            .orElseThrow(() -> RX.throwB("Semantic layer config not found: " + configId));

        if (form.getDescription() != null) {
            entity.setDescription(form.getDescription());
        }

        // 更新Git配置
        if (form.getGitConfig() != null) {
            GitRepoConfig git = form.getGitConfig();
            if (git.getRepoUrl() != null) entity.setGitRepoUrl(git.getRepoUrl());
            if (git.getBranch() != null) entity.setGitBranch(git.getBranch());
            if (git.getAuthType() != null) entity.setGitAuthType(git.getAuthType());
            if (git.getAccessToken() != null) entity.setGitAccessToken(git.getAccessToken());
            if (git.getUsername() != null) entity.setGitUsername(git.getUsername());
            if (git.getPassword() != null) entity.setGitPassword(git.getPassword());
        }

        // 更新路径配置
        if (form.getPathConfig() != null) {
            SemanticLayerPathConfig path = form.getPathConfig();
            if (path.getRootPath() != null) entity.setSemanticLayerPath(path.getRootPath());
            if (path.getModelsPath() != null) entity.setModelsPath(path.getModelsPath());
            if (path.getQueriesPath() != null) entity.setQueriesPath(path.getQueriesPath());
            if (path.getAutoSync() != null) entity.setAutoSync(path.getAutoSync());
            if (path.getSyncInterval() != null) entity.setSyncInterval(path.getSyncInterval());
        }

        semanticLayerRepo.save(entity);
        log.info("Semantic layer config updated: id={}", configId);
    }

    @Override
    @Transactional
    public void updateSemanticLayerStatus(String configId, ConfigItemStatus status) {
        log.info("Updating semantic layer status: id={}, status={}", configId, status);
        int updated = semanticLayerRepo.updateStatus(configId, status);
        if (updated == 0) {
            throw RX.throwB("Semantic layer config not found: " + configId);
        }
    }

    @Override
    @Transactional
    public void updateSemanticLayerModelCount(String configId, int modelCount) {
        log.info("Updating semantic layer model count: id={}, count={}", configId, modelCount);
        int updated = semanticLayerRepo.updateModelCount(configId, modelCount);
        if (updated == 0) {
            throw RX.throwB("Semantic layer config not found: " + configId);
        }
    }

    @Override
    @Transactional
    public void deleteSemanticLayerConfig(String configId) {
        log.info("Deleting semantic layer config: id={}", configId);
        semanticLayerRepo.deleteById(configId);
        log.info("Semantic layer config deleted: id={}", configId);
    }
}
