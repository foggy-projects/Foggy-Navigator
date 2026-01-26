# 配置管理模块设计文档

> 系统配置数据管理服务（CQRS-写入侧）

**模块名称**: configuration-module
**实施阶段**: Phase 1 (MVP)
**依赖方**: tutor-agent, metadata-query-module
**工期预估**: 2-3天
**关联文档**: [元数据语义层统一查询设计](./metadata-semantic-layer-design.md)

---

## 1. 模块概述

### 1.1 定位

配置管理模块是Foggy Navigator的**配置数据管理中心**，采用 **CQRS（命令查询责任分离）** 模式设计：

**本模块职责**（写入侧）：
- ✅ 管理系统配置项的写入、更新、删除（数据源、语义层等）
- ✅ 保证配置数据的一致性和完整性
- ✅ 提供事务保证的配置管理接口

**查询职责**（由 metadata-query-module 提供）：
- 🔗 配置状态查询
- 🔗 配置进度追踪
- 🔗 配置数据分析

### 1.2 核心价值

**为什么采用 CQRS 设计？**

1. **职责分离**: 写入侧关注数据一致性，查询侧关注灵活性
2. **查询灵活**: 通过语义层（TM/QM）提供统一查询，避免接口碎片化
3. **易扩展**: 新增查询需求无需修改本模块代码
4. **解耦设计**: 配置存储与查询逻辑分离

### 1.3 设计原则

- **单一职责**: 专注配置数据的写入管理
- **强类型**: 使用 JPA Entity 保证数据结构
- **事务保证**: 配置写入操作的 ACID 特性
- **易测试**: 接口清晰，Mock友好

---

## 2. 功能范围

### 2.1 Phase 1 (MVP) - 必须实现

| 功能 | 说明 | 优先级 |
|------|------|--------|
| 数据源配置管理 | 存储、更新、删除数据源配置 | P0 |
| 语义层配置管理 | 存储、更新、删除语义层配置 | P0 |
| 配置项状态管理 | 更新配置项状态（如连接测试结果） | P0 |
| ConfigurationManager实现 | 提供配置写入接口 | P0 |

**查询功能**（由 `metadata-query-module` 提供）：
- 配置状态查询
- 配置进度追踪
- 配置数据分析

### 2.2 Phase 2+ - 后续扩展

| 功能 | 说明 | 阶段 |
|------|------|------|
| 权限配置管理 | 用户权限配置 | Phase 2 |
| 配置历史记录 | 配置变更历史 | Phase 2 |
| 配置校验 | 配置项合法性校验 | Phase 2 |
| 配置导入导出 | JSON/YAML格式导入导出 | Phase 3 |

---

## 3. 接口定义

### 3.1 核心接口：ConfigurationManager

**包路径**: `com.foggy.navigator.config`

**设计理念**: 专注配置数据的写入、更新、删除，查询由语义层提供

```java
package com.foggy.navigator.config;

/**
 * 配置管理接口（写入侧）
 * 提供配置项的增删改能力，查询功能由 metadata-query-module 提供
 *
 * 设计原则：
 * - 使用 Form/DTO 而非 Entity 作为参数
 * - 支持部分更新（只传需要修改的字段）
 * - 分层结构：基本信息 + 类型特定信息
 */
public interface ConfigurationManager {

    // ===== 数据源配置 =====

    /**
     * 保存数据源配置
     * @param form 数据源配置表单
     * @return 保存后的配置ID
     */
    String saveDatasourceConfig(DatasourceConfigForm form);

    /**
     * 更新数据源配置
     * @param configId 配置ID
     * @param form 数据源配置表单（仅更新非null字段）
     */
    void updateDatasourceConfig(String configId, DatasourceConfigForm form);

    /**
     * 更新数据源配置状态
     * @param configId 配置ID
     * @param status 新状态
     */
    void updateDatasourceStatus(String configId, ConfigItemStatus status);

    /**
     * 更新数据源连接测试结果
     * @param configId 配置ID
     * @param connectionValid 连接是否有效
     */
    void updateDatasourceConnectionStatus(String configId, boolean connectionValid);

    /**
     * 删除数据源配置
     * @param configId 配置ID
     */
    void deleteDatasourceConfig(String configId);

    // ===== 语义层配置 =====

    /**
     * 保存语义层配置
     * @param form 语义层配置表单
     * @return 保存后的配置ID
     */
    String saveSemanticLayerConfig(SemanticLayerConfigForm form);

    /**
     * 更新语义层配置
     * @param configId 配置ID
     * @param form 语义层配置表单（仅更新非null字段）
     */
    void updateSemanticLayerConfig(String configId, SemanticLayerConfigForm form);

    /**
     * 更新语义层配置状态
     * @param configId 配置ID
     * @param status 新状态
     */
    void updateSemanticLayerStatus(String configId, ConfigItemStatus status);

    /**
     * 更新语义层模型数量
     * @param configId 配置ID
     * @param modelCount 模型数量
     */
    void updateSemanticLayerModelCount(String configId, int modelCount);

    /**
     * 删除语义层配置
     * @param configId 配置ID
     */
    void deleteSemanticLayerConfig(String configId);
}
```

---

## 4. 数据模型

### 4.1 ConfigItemStatus（配置项状态枚举）

```java
package com.foggy.navigator.config;

/**
 * 配置项状态
 */
public enum ConfigItemStatus {
    NOT_STARTED("未开始"),
    IN_PROGRESS("配置中"),
    CONFIGURED("已配置"),
    VALIDATED("已验证"),
    FAILED("配置失败");

    private final String description;

    ConfigItemStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
```

### 4.2 DatasourceConfigForm（数据源配置表单）

采用**二层结构**设计，支持多种数据源类型的扩展：

```java
package com.foggy.navigator.config.form;

import lombok.Data;

/**
 * 数据源配置表单（二层结构）
 * 第一层：通用信息
 * 第二层：类型特定信息
 */
@Data
public class DatasourceConfigForm {
    /**
     * 数据源ID（新建时可为空，更新时必填）
     */
    private String id;

    /**
     * 租户ID（多租户场景）
     */
    private String tenantId;

    /**
     * 数据源基本信息
     */
    private DatasourceBasicInfo basicInfo;

    /**
     * JDBC类数据源信息（MySQL, PostgreSQL, Oracle, SQL Server等）
     */
    private JdbcDatasourceInfo jdbcInfo;

    /**
     * MongoDB数据源信息（可选）
     */
    private MongoDatasourceInfo mongoInfo;

    /**
     * Redis数据源信息（可选，Phase 2）
     */
    private RedisDatasourceInfo redisInfo;

    // 注：根据 basicInfo.type 决定使用哪个具体配置
}

/**
 * 数据源基本信息
 */
@Data
class DatasourceBasicInfo {
    /**
     * 数据源名称
     */
    private String name;

    /**
     * 数据源类型：JDBC, MONGO, REDIS, ELASTICSEARCH等
     */
    private DatasourceType type;

    /**
     * 配置描述
     */
    private String description;

    /**
     * 配置状态（可选，默认为NOT_STARTED）
     */
    private ConfigItemStatus status;
}

/**
 * JDBC数据源信息
 */
@Data
class JdbcDatasourceInfo {
    /**
     * 数据库类型：MySQL, PostgreSQL, Oracle, SQL Server
     */
    private String dbType;

    /**
     * 主机地址
     */
    private String host;

    /**
     * 端口号
     */
    private Integer port;

    /**
     * 数据库名称
     */
    private String databaseName;

    /**
     * 用户名
     */
    private String username;

    /**
     * 密码（明文，后端加密存储）
     */
    private String password;

    /**
     * JDBC URL（可选，如果提供则优先使用）
     */
    private String jdbcUrl;

    /**
     * 额外参数（如 useSSL=false&serverTimezone=UTC）
     */
    private String extraParams;
}

/**
 * MongoDB数据源信息
 */
@Data
class MongoDatasourceInfo {
    /**
     * 主机地址（支持多个，逗号分隔）
     */
    private String hosts;

    /**
     * 端口号
     */
    private Integer port;

    /**
     * 数据库名称
     */
    private String database;

    /**
     * 用户名
     */
    private String username;

    /**
     * 密码
     */
    private String password;

    /**
     * 认证数据库
     */
    private String authDatabase;

    /**
     * 连接字符串（可选）
     */
    private String connectionString;
}

/**
 * 数据源类型枚举
 */
enum DatasourceType {
    JDBC,          // JDBC类数据源
    MONGO,         // MongoDB
    REDIS,         // Redis
    ELASTICSEARCH  // Elasticsearch (Phase 2)
}
```

### 4.3 SemanticLayerConfigForm（语义层配置表单）

支持**私有GitLab/GitHub仓库**，提供多种认证方式：

```java
package com.foggy.navigator.config.form;

import lombok.Data;

/**
 * 语义层配置表单
 * 支持私有Git仓库（GitLab、GitHub、Gitee等）
 */
@Data
public class SemanticLayerConfigForm {
    /**
     * 配置ID（新建时可为空）
     */
    private String id;

    /**
     * 租户ID
     */
    private String tenantId;

    /**
     * 关联的数据源ID
     */
    private String datasourceId;

    /**
     * Git仓库配置
     */
    private GitRepoConfig gitConfig;

    /**
     * 语义层路径配置
     */
    private SemanticLayerPathConfig pathConfig;

    /**
     * 配置描述
     */
    private String description;
}

/**
 * Git仓库配置
 * 支持私有仓库（GitLab、GitHub、Gitee等）
 */
@Data
class GitRepoConfig {
    /**
     * Git仓库URL
     * 示例：
     * - 公开：https://github.com/org/repo.git
     * - 私有GitLab：https://gitlab.company.com/team/project.git
     * - 私有GitHub：https://github.com/private-org/private-repo.git
     */
    private String repoUrl;

    /**
     * Git分支（默认：main）
     */
    private String branch;

    /**
     * Git认证方式
     */
    private GitAuthType authType;

    /**
     * 访问令牌（AccessToken/PAT）
     * - GitHub: Personal Access Token
     * - GitLab: Project Access Token / Personal Access Token
     * - Gitee: 私人令牌
     * 后端加密存储
     */
    private String accessToken;

    /**
     * 用户名（BASIC认证时使用）
     */
    private String username;

    /**
     * 密码（BASIC认证时使用，后端加密存储）
     */
    private String password;

    /**
     * SSH私钥（SSH认证时使用，后端加密存储）
     */
    private String sshPrivateKey;

    /**
     * SSH公钥密码（可选）
     */
    private String sshPassphrase;
}

/**
 * Git认证方式
 */
enum GitAuthType {
    NONE,           // 公开仓库，无需认证
    ACCESS_TOKEN,   // 访问令牌（推荐，适用于GitHub/GitLab/Gitee）
    BASIC,          // 用户名密码（不推荐，部分平台已废弃）
    SSH             // SSH密钥（适用于企业内部GitLab）
}

/**
 * 语义层路径配置
 */
@Data
class SemanticLayerPathConfig {
    /**
     * 语义层根目录（相对于Git仓库根目录）
     * 示例：semantic-models, models, datasets
     * 默认：semantic-models
     */
    private String rootPath;

    /**
     * TM模型目录（相对于rootPath）
     * 默认：models
     */
    private String modelsPath;

    /**
     * QM查询目录（相对于rootPath）
     * 默认：queries
     */
    private String queriesPath;

    /**
     * 是否自动同步（定时从Git拉取最新）
     */
    private Boolean autoSync;

    /**
     * 同步间隔（分钟，仅当autoSync=true时有效）
     */
    private Integer syncInterval;
}
```

---

## 5. 数据库设计

### 5.1 数据源配置表

```sql
CREATE TABLE datasource_configs (
    id VARCHAR(64) PRIMARY KEY COMMENT '配置ID',
    tenant_id VARCHAR(64) COMMENT '租户ID',
    db_type VARCHAR(32) NOT NULL COMMENT '数据库类型: MySQL, PostgreSQL等',
    host VARCHAR(255) NOT NULL COMMENT '主机地址',
    port INT NOT NULL COMMENT '端口号',
    database_name VARCHAR(128) NOT NULL COMMENT '数据库名称',
    username VARCHAR(128) NOT NULL COMMENT '用户名',
    password VARCHAR(512) NOT NULL COMMENT '加密后的密码',
    status VARCHAR(32) NOT NULL COMMENT '配置状态',
    connection_valid BOOLEAN COMMENT '连接是否有效',
    description TEXT COMMENT '配置描述',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_tenant_id (tenant_id),
    INDEX idx_status (status)
) COMMENT='数据源配置表';
```

### 5.2 语义层配置表

```sql
CREATE TABLE semantic_layer_configs (
    id VARCHAR(64) PRIMARY KEY COMMENT '配置ID',
    tenant_id VARCHAR(64) COMMENT '租户ID',
    datasource_id VARCHAR(64) NOT NULL COMMENT '数据源ID',
    git_repo_url VARCHAR(512) COMMENT 'Git仓库URL',
    git_branch VARCHAR(128) DEFAULT 'main' COMMENT 'Git分支',
    semantic_layer_path VARCHAR(512) COMMENT '语义层文件路径',
    model_count INT DEFAULT 0 COMMENT '模型数量',
    status VARCHAR(32) NOT NULL COMMENT '配置状态',
    last_validated_at DATETIME COMMENT '最后验证时间',
    description TEXT COMMENT '配置描述',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_tenant_id (tenant_id),
    INDEX idx_datasource_id (datasource_id),
    INDEX idx_status (status),
    FOREIGN KEY (datasource_id) REFERENCES datasource_configs(id) ON DELETE CASCADE
) COMMENT='语义层配置表';
```

### 5.3 配置进度表（可选）

```sql
CREATE TABLE system_config_progress (
    id VARCHAR(64) PRIMARY KEY COMMENT '进度ID',
    tenant_id VARCHAR(64) COMMENT '租户ID',
    total_steps INT NOT NULL DEFAULT 3 COMMENT '总步骤数',
    completed_steps INT NOT NULL DEFAULT 0 COMMENT '已完成步骤数',
    current_step VARCHAR(128) COMMENT '当前步骤',
    pending_steps JSON COMMENT '待完成步骤列表',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_tenant_id (tenant_id)
) COMMENT='系统配置进度表';
```

---

## 6. 实现方案

### 6.1 技术栈

| 组件 | 技术选型 | 说明 |
|------|---------|------|
| 框架 | Spring Boot 3.x | 与agent-framework保持一致 |
| ORM | Spring Data JPA | 简化数据库操作 |
| 数据库 | MySQL 8.0+ | 系统标准数据库 |
| 密码加密 | Jasypt | 加密敏感信息 |
| 日志 | SLF4J + Logback | 标准日志方案 |

### 6.2 模块结构

```
configuration-module/
├── src/main/java/com/foggy/navigator/config/
│   ├── ConfigurationService.java           # 核心接口
│   ├── ConfigurationManager.java           # 管理接口
│   ├── model/
│   │   ├── ConfigStatus.java
│   │   ├── ConfigProgress.java
│   │   ├── ConfigItemStatus.java
│   │   ├── DatasourceConfig.java
│   │   └── SemanticLayerConfig.java
│   ├── entity/
│   │   ├── DatasourceConfigEntity.java     # JPA Entity
│   │   └── SemanticLayerConfigEntity.java  # JPA Entity
│   ├── repository/
│   │   ├── DatasourceConfigRepository.java # JPA Repository
│   │   └── SemanticLayerConfigRepository.java
│   ├── service/
│   │   ├── ConfigurationServiceImpl.java   # 服务实现
│   │   └── ConfigurationManagerImpl.java
│   └── util/
│       └── PasswordEncryptor.java           # 密码加密工具
├── src/main/resources/
│   ├── db/migration/
│   │   └── V1__init_config_tables.sql      # Flyway脚本
│   └── application.yml
└── pom.xml
```

### 6.3 核心实现示例

#### ConfigurationManagerImpl.java

```java
package com.foggy.navigator.config.service;

import com.foggy.navigator.config.*;
import com.foggy.navigator.config.repository.*;
import com.foggy.navigator.config.entity.*;
import com.foggy.navigator.config.util.PasswordEncryptor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigurationManagerImpl implements ConfigurationManager {

    private final DatasourceConfigRepository datasourceRepo;
    private final SemanticLayerConfigRepository semanticLayerRepo;
    private final PasswordEncryptor passwordEncryptor;

    @Override
    @Transactional
    public String saveDatasourceConfig(DatasourceConfig config) {
        log.info("Saving datasource config: dbType={}, host={}",
                 config.getDbType(), config.getHost());

        DatasourceConfigEntity entity = new DatasourceConfigEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setTenantId(config.getTenantId());
        entity.setDbType(config.getDbType());
        entity.setHost(config.getHost());
        entity.setPort(config.getPort());
        entity.setDatabaseName(config.getDatabase());
        entity.setUsername(config.getUsername());
        // 密码加密
        entity.setPassword(passwordEncryptor.encrypt(config.getPassword()));
        entity.setStatus(config.getStatus());
        entity.setConnectionValid(config.getConnectionValid());
        entity.setDescription(config.getDescription());
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());

        datasourceRepo.save(entity);

        log.info("Datasource config saved: id={}", entity.getId());
        return entity.getId();
    }

    @Override
    @Transactional
    public void updateDatasourceStatus(String configId, ConfigItemStatus status) {
        log.info("Updating datasource status: id={}, status={}", configId, status);

        datasourceRepo.findById(configId).ifPresent(entity -> {
            entity.setStatus(status);
            entity.setUpdatedAt(LocalDateTime.now());
            datasourceRepo.save(entity);
        });
    }

    @Override
    @Transactional
    public void updateDatasourceConnectionStatus(String configId, boolean connectionValid) {
        log.info("Updating datasource connection status: id={}, valid={}",
                 configId, connectionValid);

        datasourceRepo.findById(configId).ifPresent(entity -> {
            entity.setConnectionValid(connectionValid);
            entity.setUpdatedAt(LocalDateTime.now());
            datasourceRepo.save(entity);
        });
    }

    @Override
    @Transactional
    public void deleteDatasourceConfig(String configId) {
        log.info("Deleting datasource config: id={}", configId);
        datasourceRepo.deleteById(configId);
    }

    @Override
    @Transactional
    public String saveSemanticLayerConfig(SemanticLayerConfig config) {
        log.info("Saving semantic layer config: datasourceId={}",
                 config.getDatasourceId());

        SemanticLayerConfigEntity entity = new SemanticLayerConfigEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setTenantId(config.getTenantId());
        entity.setDatasourceId(config.getDatasourceId());
        entity.setGitRepoUrl(config.getGitRepoUrl());
        entity.setGitBranch(config.getGitBranch());
        entity.setSemanticLayerPath(config.getSemanticLayerPath());
        entity.setModelCount(config.getModelCount());
        entity.setStatus(config.getStatus());
        entity.setDescription(config.getDescription());
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());

        semanticLayerRepo.save(entity);

        log.info("Semantic layer config saved: id={}", entity.getId());
        return entity.getId();
    }

    @Override
    @Transactional
    public void updateSemanticLayerModelCount(String configId, int modelCount) {
        log.info("Updating semantic layer model count: id={}, count={}",
                 configId, modelCount);

        semanticLayerRepo.findById(configId).ifPresent(entity -> {
            entity.setModelCount(modelCount);
            entity.setUpdatedAt(LocalDateTime.now());
            semanticLayerRepo.save(entity);
        });
    }

    // ... 其他方法实现
}
```

---

## 7. 可观察性设计

### 7.1 日志规范

**日志级别**:
- `INFO`: 正常操作（配置保存、状态查询）
- `WARN`: 异常情况（连接失败、配置不完整）
- `ERROR`: 错误（数据库异常、加密失败）

**日志格式**:
```json
{
  "timestamp": "2026-01-25T10:30:00Z",
  "level": "INFO",
  "module": "configuration-module",
  "event": "datasource_config_saved",
  "details": {
    "configId": "ds-001",
    "dbType": "MySQL",
    "status": "CONFIGURED"
  },
  "traceId": "trace-abc-123"
}
```

**关键事件**:
- `datasource_config_saved`: 数据源配置保存
- `datasource_status_updated`: 数据源状态更新
- `semantic_layer_config_saved`: 语义层配置保存
- `config_progress_refreshed`: 配置进度刷新
- `config_status_queried`: 配置状态查询

### 7.2 监控指标

**Prometheus指标**:

```java
@Component
public class ConfigurationMetrics {

    private final Counter configSaveCounter;
    private final Gauge configStatusGauge;
    private final Histogram configQueryDuration;

    public ConfigurationMetrics(MeterRegistry registry) {
        // 配置保存计数
        this.configSaveCounter = Counter.builder("config_save_total")
            .description("Total configuration saves")
            .tag("type", "datasource/semantic_layer")
            .register(registry);

        // 配置状态（0=未配置, 1=已配置）
        this.configStatusGauge = Gauge.builder("config_status", () -> getConfigStatusValue())
            .description("Configuration status")
            .tag("type", "datasource/semantic_layer")
            .register(registry);

        // 查询耗时
        this.configQueryDuration = Histogram.builder("config_query_duration_seconds")
            .description("Configuration query duration")
            .register(registry);
    }
}
```

**指标清单**:
- `config_save_total`: 配置保存次数
- `config_status`: 配置状态（0/1）
- `config_query_duration_seconds`: 查询耗时
- `config_error_total`: 配置错误次数

---

## 8. 测试要求

### 8.1 单元测试

**覆盖率要求**: > 80%

**核心测试用例**:

```java
@SpringBootTest
class ConfigurationServiceTest {

    @Autowired
    private ConfigurationService configService;

    @Autowired
    private ConfigurationManager configManager;

    @Test
    void testGetDataSourceStatus_NotConfigured() {
        ConfigStatus status = configService.getDataSourceStatus();
        assertFalse(status.isConfigured());
        assertEquals("数据源尚未配置", status.getMessage());
    }

    @Test
    void testGetDataSourceStatus_Configured() {
        // 保存配置
        DatasourceConfig config = new DatasourceConfig();
        config.setDbType("MySQL");
        config.setHost("localhost");
        config.setPort(3306);
        config.setDatabase("test_db");
        config.setUsername("root");
        config.setPassword("password");
        config.setStatus(ConfigItemStatus.CONFIGURED);

        configManager.saveDatasourceConfig(config);

        // 查询状态
        ConfigStatus status = configService.getDataSourceStatus();
        assertTrue(status.isConfigured());
        assertTrue(status.getMessage().contains("MySQL"));
    }

    @Test
    void testGetOverallProgress() {
        ConfigProgress progress = configService.getOverallProgress();
        assertEquals(3, progress.getTotalSteps());
        assertEquals(0, progress.getCompletedSteps());
        assertEquals("配置数据源", progress.getCurrentStep());
    }
}
```

### 8.2 集成测试

**测试场景**:
1. 完整配置流程（数据源 → 语义层）
2. 配置状态查询
3. 配置进度追踪
4. 异常场景（重复配置、删除配置等）

### 8.3 Mock实现

为了不阻塞tutor-agent开发，提供Mock实现：

```java
@Service
@Profile("test")
public class MockConfigurationService implements ConfigurationService {

    @Override
    public ConfigStatus getDataSourceStatus() {
        ConfigStatus status = new ConfigStatus();
        status.setConfigured(false);
        status.setMessage("数据源尚未配置");
        status.setDetails(new HashMap<>());
        return status;
    }

    @Override
    public ConfigStatus getSemanticLayerStatus() {
        ConfigStatus status = new ConfigStatus();
        status.setConfigured(false);
        status.setMessage("语义层尚未生成");
        status.setDetails(new HashMap<>());
        return status;
    }

    @Override
    public ConfigProgress getOverallProgress() {
        ConfigProgress progress = new ConfigProgress();
        progress.setTotalSteps(3);
        progress.setCompletedSteps(0);
        progress.setCurrentStep("配置数据源");
        progress.setPendingSteps(Arrays.asList(
            "配置数据源", "生成语义层", "配置权限"
        ));
        return progress;
    }
}
```

---

## 9. 安全考虑

### 9.1 密码加密

**要求**: 数据库密码必须加密存储

**方案**: 使用Jasypt加密

```java
@Component
public class PasswordEncryptor {

    private final StringEncryptor encryptor;

    public PasswordEncryptor(@Value("${jasypt.encryptor.password}") String secret) {
        this.encryptor = new StandardPBEStringEncryptor();
        ((StandardPBEStringEncryptor) encryptor).setPassword(secret);
        ((StandardPBEStringEncryptor) encryptor).setAlgorithm("PBEWithMD5AndDES");
    }

    public String encrypt(String plainText) {
        return encryptor.encrypt(plainText);
    }

    public String decrypt(String encryptedText) {
        return encryptor.decrypt(encryptedText);
    }
}
```

**使用示例**:
```java
// 保存时加密
String encryptedPassword = passwordEncryptor.encrypt(config.getPassword());
entity.setPassword(encryptedPassword);

// 使用时解密
String plainPassword = passwordEncryptor.decrypt(entity.getPassword());
```

### 9.2 访问控制

**要求**: 配置接口需要鉴权

**方案**: Spring Security集成（Phase 2）

---

## 10. 开发步骤

### 10.1 Phase 1: 基础框架（1天）

1. 创建Maven模块
2. 定义接口和数据模型
3. 创建JPA Entity和Repository
4. 编写Flyway数据库脚本

### 10.2 Phase 2: 核心实现（1天）

1. 实现ConfigurationServiceImpl
2. 实现ConfigurationManagerImpl
3. 实现密码加密工具
4. 单元测试

### 10.3 Phase 3: 集成测试（0.5天）

1. 编写集成测试
2. 测试与tutor-agent集成
3. 性能测试

### 10.4 Phase 4: 文档与交付（0.5天）

1. 编写README
2. API文档
3. 部署指南

**总工期**: 2-3天

---

## 11. 交付清单

### 11.1 代码交付

- [ ] ConfigurationService接口实现
- [ ] ConfigurationManager接口实现
- [ ] 6个数据模型类
- [ ] 2个JPA Entity
- [ ] 2个Repository
- [ ] 密码加密工具
- [ ] 单元测试（覆盖率 > 80%）
- [ ] 集成测试

### 11.2 配置交付

- [ ] pom.xml
- [ ] application.yml
- [ ] Flyway数据库脚本

### 11.3 文档交付

- [ ] README.md
- [ ] API文档
- [ ] 数据库设计文档

---

## 12. 集成指南

### 12.1 tutor-agent集成

**步骤1**: 添加Maven依赖

```xml
<dependency>
    <groupId>com.foggy.navigator</groupId>
    <artifactId>configuration-module</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

**步骤2**: 注入ConfigurationService

```java
@RestController
@RequestMapping("/api/tutor/config")
@RequiredArgsConstructor
public class SystemConfigController {

    private final ConfigurationService configService;

    @GetMapping("/datasource/status")
    public ConfigStatusResponse checkDatasourceStatus() {
        ConfigStatus status = configService.getDataSourceStatus();
        return ConfigStatusResponse.builder()
            .configured(status.isConfigured())
            .message(status.getMessage())
            .details(status.getDetails())
            .build();
    }

    // ... 其他接口
}
```

**步骤3**: 配置数据源

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/foggy_navigator
    username: root
    password: password
  jpa:
    hibernate:
      ddl-auto: validate
  flyway:
    enabled: true
```

---

## 13. 后续扩展

### 13.1 Phase 2

- 权限配置管理
- 配置历史记录
- 配置校验规则

### 13.2 Phase 3

- 配置导入导出（JSON/YAML）
- 配置模板
- 批量配置

---

**文档版本**: 1.0.0
**创建日期**: 2026-01-25
**作者**: Foggy Navigator Team
