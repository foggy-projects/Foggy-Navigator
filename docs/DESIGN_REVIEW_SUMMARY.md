# 设计方案审阅总结

**日期**: 2026-01-25
**审阅状态**: ⏳ 待审阅
**相关文档**:
- [配置管理模块设计](./configuration-module-design.md)
- [元数据语义层统一查询设计](./metadata-semantic-layer-design.md)

---

## 核心设计理念

### CQRS（命令查询责任分离）

```
┌─────────────────────────────────────────────────────────────┐
│                        前端/调用方                            │
└───────────────┬─────────────────────────────┬───────────────┘
                │                             │
        写入请求（强类型）              查询请求（灵活）
                │                             │
                ↓                             ↓
┌───────────────────────────┐   ┌───────────────────────────┐
│  configuration-module     │   │  metadata-query-module    │
│  ConfigurationManager     │   │  MetadataQueryService     │
│  - 数据写入/更新/删除       │   │  - 简单查询（queryId）    │
│  - JPA + 事务保证          │   │  - 高级查询（DSL）        │
│  - 强类型校验              │   │  - TM/QM 语义层           │
└───────────────────────────┘   └───────────────────────────┘
```

---

## 设计变更说明

### 1. configuration-module（配置管理模块）

#### 变更内容

**之前设计**（已废弃）:
- 包含 `ConfigurationService` 查询接口
- `getDataSourceStatus()`, `getSemanticLayerStatus()`, `getOverallProgress()` 等方法
- 职责混合：既负责写入又负责查询

**新设计**:
- ✅ **只负责写入侧**：`ConfigurationManager`
- ✅ **接口精简**：只保留 `save`, `update`, `delete` 方法
- ✅ **移除查询方法**：所有查询功能由 `metadata-query-module` 提供
- ✅ **CQRS 模式**：写入侧关注数据一致性和事务

#### 核心接口

```java
public interface ConfigurationManager {
    // 数据源配置
    String saveDatasourceConfig(DatasourceConfig config);
    void updateDatasourceConfig(String configId, DatasourceConfig config);
    void updateDatasourceStatus(String configId, ConfigItemStatus status);
    void deleteDatasourceConfig(String configId);

    // 语义层配置
    String saveSemanticLayerConfig(SemanticLayerConfig config);
    void updateSemanticLayerConfig(String configId, SemanticLayerConfig config);
    void updateSemanticLayerStatus(String configId, ConfigItemStatus status);
    void deleteSemanticLayerConfig(String configId);
}
```

---

### 2. metadata-query-module（元数据查询模块）

#### 核心创新：两层查询接口

**问题**：完整的 DSL 查询虽然灵活，但对简单场景来说过于复杂。

**解决方案**：提供两层接口

##### 简单查询接口（推荐，适合 80% 场景）

**特点**:
- ✅ REST 风格，直观易用
- ✅ 预定义 queryId（如 `datasource-latest`, `sessions-active`）
- ✅ 简单参数传递（GET 参数或 POST JSON）
- ✅ **无需拼 DSL**

**使用示例**:
```http
# 查询最新数据源配置
GET /api/metadata/query/datasource-latest?tenantId=tenant-001

# 查询数据源列表（分页）
GET /api/metadata/query/datasource-list?status=CONFIGURED&page=1&pageSize=10

# 查询配置进度
GET /api/metadata/query/config-progress?tenantId=tenant-001

# 查询活跃会话
GET /api/metadata/query/sessions-active?userId=user-123
```

**预定义 queryId 列表**:

| queryId | 说明 | 参数 |
|---------|------|------|
| `datasource-latest` | 最新数据源配置 | `tenantId` (可选) |
| `datasource-list` | 数据源配置列表 | `tenantId`, `status`, `dbType`, `page`, `pageSize` |
| `datasource-by-id` | 根据ID查询数据源 | `id` |
| `semantic-layer-latest` | 最新语义层配置 | `tenantId` (可选) |
| `semantic-layer-list` | 语义层配置列表 | `tenantId`, `datasourceId`, `page`, `pageSize` |
| `config-progress` | 配置进度 | `tenantId` (可选) |
| `config-status-summary` | 配置状态汇总 | `tenantId` (可选) |
| `sessions-active` | 活跃会话列表 | `userId`, `status` |
| `messages-by-session` | 会话消息列表 | `sessionId`, `page`, `pageSize` |

##### 高级 DSL 查询接口（适合 20% 复杂场景）

**特点**:
- ✅ 完全灵活，支持自定义过滤、排序、聚合
- ✅ 适合预定义 queryId 不满足的场景
- ✅ 支持复杂聚合和多条件组合

**使用示例**:
```http
POST /api/metadata/query/execute
Content-Type: application/json

{
  "queryName": "datasource_configs_list",
  "filters": {
    "tenant_id": "tenant-001",
    "status": "CONFIGURED",
    "db_type": "MySQL"
  },
  "sort": {
    "field": "created_at",
    "order": "DESC"
  },
  "pagination": {
    "page": 1,
    "pageSize": 20
  },
  "aggregation": {
    "function": "COUNT",
    "field": "id",
    "groupBy": "status"
  }
}
```

#### 核心接口

```java
public interface MetadataQueryService {
    // 简单查询接口（推荐）
    QueryResponse executeSimpleQuery(String queryId, Map<String, Object> params);

    // 高级查询接口（灵活）
    QueryResponse executeQuery(QueryRequest queryRequest);

    // 元数据接口
    List<QueryInfo> getAvailableQueries();
    QueryParametersDefinition getQueryParameters(String queryId);
}
```

---

## 技术实现

### TM/QM 语义层

**表模型（TM）**: 描述数据库表结构

```json
{
  "name": "datasource_configs",
  "displayName": "数据源配置",
  "type": "mysql",
  "columns": [
    {"name": "id", "displayName": "配置ID", "dataType": "VARCHAR", "primaryKey": true},
    {"name": "db_type", "displayName": "数据库类型", "dataType": "VARCHAR"},
    {"name": "status", "displayName": "状态", "dataType": "VARCHAR"}
  ]
}
```

**查询模型（QM）**: 定义预设查询

```json
{
  "name": "datasource_latest",
  "displayName": "最新数据源配置",
  "tableModel": "datasource_configs",
  "fields": ["id", "db_type", "host", "port", "status"],
  "defaultSort": {"field": "created_at", "order": "DESC"},
  "limit": 1
}
```

---

## 核心优势

### 1. 简化查询

**之前**：每个查询需求都要加接口
```java
// 每次都要写新方法
ConfigStatus getDataSourceStatus();
ConfigStatus getSemanticLayerStatus();
ConfigProgress getOverallProgress();
List<Session> getActiveSessions(String userId);
// ... 接口越来越多
```

**现在**：统一接口
```java
// 所有查询都用这个
QueryResponse executeSimpleQuery(String queryId, Map<String, Object> params);
```

### 2. 扩展性强

**新增查询需求**：
- ✅ 只需添加 QM 文件（或在配置中注册 queryId）
- ✅ **无需修改代码**
- ✅ **无需重启服务**

### 3. 前端友好

**之前**：不同查询调用不同接口
```javascript
// 分散的接口调用
const datasource = await api.getDatasourceStatus();
const progress = await api.getOverallProgress();
const sessions = await api.getActiveSessions(userId);
```

**现在**：统一查询接口
```javascript
// 统一的查询方式
const datasource = await api.query('datasource-latest', { tenantId });
const progress = await api.query('config-progress', { tenantId });
const sessions = await api.query('sessions-active', { userId });
```

### 4. 可维护性

- ✅ **职责清晰**：写入和查询完全分离
- ✅ **易测试**：接口简单，Mock 友好
- ✅ **可扩展**：新增表只需添加 TM/QM 定义

---

## 实施步骤

### Phase 1: 集成语义层（使用现有技能）

```bash
# 1. 集成 foggy-dataset-model
/foggy-java-integration

# 2. 生成表模型
/tm-generate
# 基于 datasource_configs 表生成 TM

# 3. 生成查询模型
/qm-generate
# 基于 TM 生成常用查询的 QM
```

### Phase 2: 实现 metadata-query-module

1. **创建模块**
   ```bash
   mvn archetype:generate \
     -DgroupId=com.foggy.navigator \
     -DartifactId=metadata-query-module
   ```

2. **实现 MetadataQueryService**
   - `executeSimpleQuery()`: queryId → DSL 转换 → 执行
   - `executeQuery()`: 直接执行 DSL
   - 加载 TM/QM 定义

3. **实现 REST Controller**
   - `GET /api/metadata/query/{queryId}`
   - `POST /api/metadata/query/{queryId}/execute`
   - `POST /api/metadata/query/execute`

### Phase 3: 实现 configuration-module

1. **实现 ConfigurationManager**
   - 数据源配置管理
   - 语义层配置管理
   - JPA Repository + 事务

2. **单元测试**

---

## 待审阅事项

### 核心问题

1. **两层查询接口设计是否合理？**
   - 简单查询（queryId + params）
   - 高级查询（完整 DSL）

2. **预定义 queryId 列表是否完整？**
   - 是否需要添加更多常用查询？

3. **REST 风格是否符合预期？**
   - `GET /api/metadata/query/{queryId}?param1=value1`
   - 还是 `POST /api/metadata/query/{queryId}/execute`？

### 技术细节

4. **queryId 管理方式**
   - 硬编码在代码中？
   - 还是从 QM 文件动态加载？
   - 还是数据库配置？

5. **查询结果格式**
   - 当前是 `List<Map<String, Object>>`
   - 是否需要强类型 DTO？

6. **性能考虑**
   - 是否需要查询结果缓存？
   - 缓存策略是什么？

7. **安全性**
   - 租户隔离如何实现？
   - 敏感字段如何过滤？

---

## 下一步行动

审阅通过后，可以选择：

1. ✅ **立即开始实施**
   - 运行 `/foggy-java-integration` 集成依赖
   - 运行 `/tm-generate` 生成表模型
   - 运行 `/qm-generate` 生成查询模型
   - 实现 metadata-query-module

2. ✅ **调整设计**
   - 根据审阅意见调整设计文档
   - 重新评审

3. ✅ **创建开发任务**
   - 拆分为具体的开发任务
   - 排定优先级和时间表

---

**请审阅以上设计，并提出您的意见和建议。**
