---
name: metadata-config-module
description: 元数据配置模块开发指导。当用户需要开发 metadata-config-module 的配置管理功能、添加新配置类型、扩展数据源支持、编写单元测试时使用。触发词：/mc, /config-module, 提及"配置管理"、"ConfigurationManager"、"数据源配置"、"语义层配置"。
---

# Metadata Config Module 开发指导

为 `metadata-config-module` 提供配置管理服务（写入侧）的开发、扩展和测试指导。

## 使用场景

当用户需要以下操作时激活：
- 扩展数据源类型支持（JDBC、MongoDB、其他）
- 添加新的配置实体
- 修改 ConfigurationManager 实现
- 处理配置相关事件
- 编写或补充单元测试

## 模块结构

```
metadata-config-module/
├── src/main/java/com/foggy/navigator/metadata/config/
│   ├── controller/
│   │   └── ConfigurationController.java     # REST API（写入侧）
│   ├── service/
│   │   ├── ConfigurationManagerImpl.java    # 配置管理实现
│   │   ├── DatasourceBeanRegistrar.java     # 数据源动态注册
│   │   └── DatasourceLookupService.java     # 数据源查找服务
│   ├── repository/
│   │   ├── DatasourceConfigRepository.java  # 数据源配置仓库
│   │   └── SemanticLayerConfigRepository.java # 语义层配置仓库
│   └── configuration/
│       └── DatasourceModuleConfig.java      # 模块配置
├── src/main/resources/
│   └── application.yml
└── src/test/java/
    └── ConfigurationManagerTest.java
```

## API 接口

### 数据源配置

```
POST   /api/config/datasource                    # 保存数据源
PUT    /api/config/datasource/{configId}         # 更新数据源
PATCH  /api/config/datasource/{configId}/status  # 更新状态
PATCH  /api/config/datasource/{configId}/connection # 更新连接状态
DELETE /api/config/datasource/{configId}         # 删除数据源
```

### 语义层配置

```
POST   /api/config/semantic-layer                    # 保存语义层
PUT    /api/config/semantic-layer/{configId}         # 更新语义层
PATCH  /api/config/semantic-layer/{configId}/status  # 更新状态
PATCH  /api/config/semantic-layer/{configId}/model-count # 更新模型数
DELETE /api/config/semantic-layer/{configId}         # 删除语义层
```

## 核心类说明

### ConfigurationManager（SPI 接口）

```java
public interface ConfigurationManager {
    // 数据源配置
    String saveDatasourceConfig(DatasourceConfigForm form);
    void updateDatasourceConfig(String configId, DatasourceConfigForm form);
    void updateDatasourceStatus(String configId, ConfigItemStatus status);
    void updateDatasourceConnectionStatus(String configId, boolean valid);
    void deleteDatasourceConfig(String configId);

    // 语义层配置
    String saveSemanticLayerConfig(SemanticLayerConfigForm form);
    void updateSemanticLayerConfig(String configId, SemanticLayerConfigForm form);
    void updateSemanticLayerStatus(String configId, ConfigItemStatus status);
    void updateSemanticLayerModelCount(String configId, int count);
    void deleteSemanticLayerConfig(String configId);
}
```

### Form 结构（二层设计）

```java
// 数据源配置表单
DatasourceConfigForm
├── tenantId: String
├── basicInfo: DatasourceBasicInfo
│   ├── name, type, description, status
├── jdbcInfo: JdbcDatasourceInfo      # JDBC 类型专用
│   ├── dbType, host, port, databaseName
│   ├── username, password, jdbcUrl, extraParams
└── mongoInfo: MongoDatasourceInfo    # MongoDB 类型专用
    ├── hosts, port, database
    ├── username, password, connectionString

// 语义层配置表单
SemanticLayerConfigForm
├── tenantId, datasourceId, description
├── gitConfig: GitRepoConfig
│   ├── repoUrl, branch, authType
│   ├── accessToken, username, password
└── pathConfig: SemanticLayerPathConfig
    ├── rootPath, modelsPath, queriesPath
    ├── autoSync, syncInterval
```

## 执行流程

### 1. 添加新的数据源类型

```java
// 1. 在 navigator-common 中创建新的 Form 子类
@Data
public class ElasticsearchDatasourceInfo {
    private List<String> nodes;
    private String indexPrefix;
    private String username;
    private String password;
}

// 2. 在 DatasourceConfigForm 中添加字段
@Data
public class DatasourceConfigForm {
    // ... 现有字段
    private ElasticsearchDatasourceInfo esInfo;  // 新增
}

// 3. 在 DatasourceConfigEntity 中添加对应字段
@Entity
public class DatasourceConfigEntity {
    // ... 现有字段
    @Column(name = "es_nodes")
    private String esNodes;
    @Column(name = "es_index_prefix")
    private String esIndexPrefix;
}

// 4. 在 ConfigurationManagerImpl 中处理新类型
if (form.getEsInfo() != null) {
    ElasticsearchDatasourceInfo es = form.getEsInfo();
    entity.setEsNodes(String.join(",", es.getNodes()));
    entity.setEsIndexPrefix(es.getIndexPrefix());
    entity.setUsername(es.getUsername());
    entity.setPassword(es.getPassword());
}
```

### 2. 添加新的配置事件

```java
// 1. 在 navigator-common 中定义事件
public class DatasourceConfigEvent extends ApplicationEvent {
    private final DatasourceConfigEntity config;
    private final EventType type;
    private final ConfigItemStatus previousStatus;
    private final ConfigItemStatus newStatus;

    public enum EventType {
        CREATED, UPDATED, DELETED, STATUS_CHANGED
    }

    // 静态工厂方法
    public static DatasourceConfigEvent created(Object source, DatasourceConfigEntity config);
    public static DatasourceConfigEvent updated(Object source, DatasourceConfigEntity config);
    public static DatasourceConfigEvent deleted(Object source, DatasourceConfigEntity config);
    public static DatasourceConfigEvent statusChanged(Object source, DatasourceConfigEntity config,
                                                       ConfigItemStatus previous, ConfigItemStatus next);
}

// 2. 在 Service 中发布事件
eventPublisher.publishEvent(DatasourceConfigEvent.created(this, entity));
```

### 3. 编写单元测试

```java
@ExtendWith(MockitoExtension.class)
class ConfigurationManagerTest {

    @Mock
    private DatasourceConfigRepository datasourceRepo;

    @Mock
    private SemanticLayerConfigRepository semanticLayerRepo;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private ConfigurationManagerImpl configManager;

    @Test
    void testSaveDatasourceConfig_Success() {
        // Given
        DatasourceConfigForm form = createTestForm();
        when(datasourceRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // When
        String id = configManager.saveDatasourceConfig(form);

        // Then
        assertNotNull(id);
        verify(datasourceRepo).save(any(DatasourceConfigEntity.class));
        verify(eventPublisher).publishEvent(any(DatasourceConfigEvent.class));
    }

    @Test
    void testDeleteDatasourceConfig_CascadeDeleteSemanticLayer() {
        // Given
        String configId = "test-config-id";
        when(datasourceRepo.findById(configId))
                .thenReturn(Optional.of(new DatasourceConfigEntity()));

        // When
        configManager.deleteDatasourceConfig(configId);

        // Then
        verify(semanticLayerRepo).deleteByDatasourceId(configId);  // 先删语义层
        verify(datasourceRepo).deleteById(configId);                // 再删数据源
        verify(eventPublisher).publishEvent(any(DatasourceConfigEvent.class));
    }
}
```

## 约束条件

### JPA 规则
- Entity 使用 `@Data` + `@Entity` + `@Table`
- 主键使用 `String id`，由 UUID 生成
- Entity 间不使用关联注解，通过外键字段关联
- 删除操作需级联处理（如删除数据源前先删语义层）

### 认证集成
- Controller 使用 `@RequireAuth` 注解
- 通过 `UserContext.getCurrentUser()` 获取当前用户
- 非超管用户自动填充 tenantId

### 事件驱动
- 配置变更必须发布对应事件
- 使用 `ApplicationEventPublisher` 发布
- 事件类型：CREATED, UPDATED, DELETED, STATUS_CHANGED

### 代码规范
- 使用 `@Transactional` 保证事务一致性
- 错误抛出使用 `RX.throwB(message)`
- 返回值使用 `RX<T>` 包装

## 决策规则

- 如果添加新数据源类型 → 创建对应的 Info 类，扩展 Form 和 Entity
- 如果需要级联操作 → 在同一事务中处理，先子后父
- 如果状态变更需要通知 → 发布 STATUS_CHANGED 事件
- 如果涉及敏感信息 → 存储时加密（TODO: 待实现）
- 如果修改 Entity → 检查是否影响 metadata-query-module 的查询

## 常用命令

```bash
# 编译模块
mvn compile -pl metadata-config-module -am

# 运行测试
mvn test -pl metadata-config-module

# 运行所有测试（包含依赖模块）
mvn test -pl metadata-config-module -am

# 启动应用
mvn spring-boot:run -pl metadata-config-module
```

## 依赖关系

```
metadata-config-module
├── navigator-common    # Entity、Form、Event 定义
├── navigator-spi       # ConfigurationManager 接口
├── user-auth-module    # 认证功能
└── foggy-core         # 核心框架（RX 返回类型）
```

## 与 metadata-query-module 的关系

- **config-module**：负责配置的写入（增删改）
- **query-module**：负责配置的读取（查询）
- 共享 Entity 定义（在 navigator-common 中）
- 配置变更后，query-module 通过事件或直接查询获取最新数据
