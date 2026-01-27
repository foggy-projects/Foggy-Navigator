package com.foggy.navigator.metadata.config;

import com.foggy.navigator.common.entity.DatasourceConfigEntity;
import com.foggy.navigator.common.entity.SemanticLayerConfigEntity;
import com.foggy.navigator.common.enums.ConfigItemStatus;
import com.foggy.navigator.common.enums.DatasourceType;
import com.foggy.navigator.common.enums.GitAuthType;
import com.foggy.navigator.common.form.*;
import com.foggy.navigator.metadata.config.repository.DatasourceConfigRepository;
import com.foggy.navigator.metadata.config.repository.SemanticLayerConfigRepository;
import com.foggy.navigator.spi.config.ConfigurationManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ConfigurationManager 单元测试
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ConfigurationManagerTest {

    @Autowired
    private ConfigurationManager configManager;

    @Autowired
    private DatasourceConfigRepository datasourceRepo;

    @Autowired
    private SemanticLayerConfigRepository semanticLayerRepo;

    @Test
    void testSaveDatasourceConfig_Jdbc() {
        // 准备测试数据
        DatasourceConfigForm form = new DatasourceConfigForm();
        form.setTenantId("tenant-001");

        DatasourceBasicInfo basicInfo = new DatasourceBasicInfo();
        basicInfo.setName("Test MySQL");
        basicInfo.setType(DatasourceType.JDBC);
        basicInfo.setDescription("Test datasource");
        basicInfo.setStatus(ConfigItemStatus.NOT_STARTED);
        form.setBasicInfo(basicInfo);

        JdbcDatasourceInfo jdbcInfo = new JdbcDatasourceInfo();
        jdbcInfo.setDbType("MySQL");
        jdbcInfo.setHost("localhost");
        jdbcInfo.setPort(3306);
        jdbcInfo.setDatabaseName("test_db");
        jdbcInfo.setUsername("root");
        jdbcInfo.setPassword("password");
        form.setJdbcInfo(jdbcInfo);

        // 执行保存
        String id = configManager.saveDatasourceConfig(form);

        // 验证结果
        assertNotNull(id);
        Optional<DatasourceConfigEntity> saved = datasourceRepo.findById(id);
        assertTrue(saved.isPresent());
        assertEquals("Test MySQL", saved.get().getName());
        assertEquals(DatasourceType.JDBC, saved.get().getType());
        assertEquals("MySQL", saved.get().getDbType());
        assertEquals("localhost", saved.get().getHost());
        assertEquals(3306, saved.get().getPort());
    }

    @Test
    void testUpdateDatasourceConfig() {
        // 先保存一个配置
        String id = createTestDatasource();

        // 准备更新数据
        DatasourceConfigForm updateForm = new DatasourceConfigForm();
        DatasourceBasicInfo basicInfo = new DatasourceBasicInfo();
        basicInfo.setName("Updated MySQL");
        basicInfo.setStatus(ConfigItemStatus.CONFIGURED);
        updateForm.setBasicInfo(basicInfo);

        JdbcDatasourceInfo jdbcInfo = new JdbcDatasourceInfo();
        jdbcInfo.setHost("192.168.1.100");
        updateForm.setJdbcInfo(jdbcInfo);

        // 执行更新
        configManager.updateDatasourceConfig(id, updateForm);

        // 验证结果
        Optional<DatasourceConfigEntity> updated = datasourceRepo.findById(id);
        assertTrue(updated.isPresent());
        assertEquals("Updated MySQL", updated.get().getName());
        assertEquals(ConfigItemStatus.CONFIGURED, updated.get().getStatus());
        assertEquals("192.168.1.100", updated.get().getHost());
        // 验证未更新的字段保持不变
        assertEquals(3306, updated.get().getPort());
    }

    @Test
    void testUpdateDatasourceStatus() {
        String id = createTestDatasource();

        configManager.updateDatasourceStatus(id, ConfigItemStatus.VALIDATED);

        Optional<DatasourceConfigEntity> entity = datasourceRepo.findById(id);
        assertTrue(entity.isPresent());
        assertEquals(ConfigItemStatus.VALIDATED, entity.get().getStatus());
    }

    @Test
    void testUpdateDatasourceConnectionStatus() {
        String id = createTestDatasource();

        configManager.updateDatasourceConnectionStatus(id, true);

        Optional<DatasourceConfigEntity> entity = datasourceRepo.findById(id);
        assertTrue(entity.isPresent());
        assertEquals(Boolean.TRUE, entity.get().getConnectionValid());
    }

    @Test
    void testDeleteDatasourceConfig() {
        String id = createTestDatasource();

        configManager.deleteDatasourceConfig(id);

        assertFalse(datasourceRepo.existsById(id));
    }

    @Test
    void testSaveSemanticLayerConfig() {
        // 先创建数据源
        String datasourceId = createTestDatasource();

        // 准备语义层配置
        SemanticLayerConfigForm form = new SemanticLayerConfigForm();
        form.setTenantId("tenant-001");
        form.setDatasourceId(datasourceId);
        form.setDescription("Test semantic layer");

        GitRepoConfig gitConfig = new GitRepoConfig();
        gitConfig.setRepoUrl("https://github.com/test/repo.git");
        gitConfig.setBranch("main");
        gitConfig.setAuthType(GitAuthType.ACCESS_TOKEN);
        gitConfig.setAccessToken("test-token");
        form.setGitConfig(gitConfig);

        SemanticLayerPathConfig pathConfig = new SemanticLayerPathConfig();
        pathConfig.setRootPath("semantic-models");
        pathConfig.setModelsPath("models");
        pathConfig.setQueriesPath("queries");
        pathConfig.setAutoSync(true);
        pathConfig.setSyncInterval(30);
        form.setPathConfig(pathConfig);

        // 执行保存
        String id = configManager.saveSemanticLayerConfig(form);

        // 验证结果
        assertNotNull(id);
        Optional<SemanticLayerConfigEntity> saved = semanticLayerRepo.findById(id);
        assertTrue(saved.isPresent());
        assertEquals(datasourceId, saved.get().getDatasourceId());
        assertEquals("https://github.com/test/repo.git", saved.get().getGitRepoUrl());
        assertEquals("main", saved.get().getGitBranch());
        assertEquals(GitAuthType.ACCESS_TOKEN, saved.get().getGitAuthType());
        assertEquals("semantic-models", saved.get().getSemanticLayerPath());
        assertTrue(saved.get().getAutoSync());
    }

    @Test
    void testUpdateSemanticLayerModelCount() {
        String datasourceId = createTestDatasource();
        String semanticLayerId = createTestSemanticLayer(datasourceId);

        configManager.updateSemanticLayerModelCount(semanticLayerId, 10);

        Optional<SemanticLayerConfigEntity> entity = semanticLayerRepo.findById(semanticLayerId);
        assertTrue(entity.isPresent());
        assertEquals(10, entity.get().getModelCount());
    }

    @Test
    void testDeleteSemanticLayerConfig() {
        String datasourceId = createTestDatasource();
        String semanticLayerId = createTestSemanticLayer(datasourceId);

        configManager.deleteSemanticLayerConfig(semanticLayerId);

        assertFalse(semanticLayerRepo.existsById(semanticLayerId));
    }

    @Test
    void testDeleteDatasourceCascadesSemanticLayer() {
        String datasourceId = createTestDatasource();
        String semanticLayerId = createTestSemanticLayer(datasourceId);

        // 删除数据源应该级联删除语义层配置
        configManager.deleteDatasourceConfig(datasourceId);

        assertFalse(datasourceRepo.existsById(datasourceId));
        assertFalse(semanticLayerRepo.existsById(semanticLayerId));
    }

    // ===== 辅助方法 =====

    private String createTestDatasource() {
        DatasourceConfigForm form = new DatasourceConfigForm();
        form.setTenantId("tenant-001");

        DatasourceBasicInfo basicInfo = new DatasourceBasicInfo();
        basicInfo.setName("Test MySQL");
        basicInfo.setType(DatasourceType.JDBC);
        form.setBasicInfo(basicInfo);

        JdbcDatasourceInfo jdbcInfo = new JdbcDatasourceInfo();
        jdbcInfo.setDbType("MySQL");
        jdbcInfo.setHost("localhost");
        jdbcInfo.setPort(3306);
        jdbcInfo.setDatabaseName("test_db");
        jdbcInfo.setUsername("root");
        jdbcInfo.setPassword("password");
        form.setJdbcInfo(jdbcInfo);

        return configManager.saveDatasourceConfig(form);
    }

    private String createTestSemanticLayer(String datasourceId) {
        SemanticLayerConfigForm form = new SemanticLayerConfigForm();
        form.setTenantId("tenant-001");
        form.setDatasourceId(datasourceId);

        GitRepoConfig gitConfig = new GitRepoConfig();
        gitConfig.setRepoUrl("https://github.com/test/repo.git");
        gitConfig.setAuthType(GitAuthType.NONE);
        form.setGitConfig(gitConfig);

        return configManager.saveSemanticLayerConfig(form);
    }
}
