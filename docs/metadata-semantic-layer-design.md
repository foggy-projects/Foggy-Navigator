# 元数据语义层统一查询设计方案

> 基于 foggy-dataset-model 的统一元数据查询架构

**版本**: 1.0.0
**日期**: 2026-01-25
**依赖**: configuration-module-design.md

---

## 1. 设计目标

### 1.1 问题

当前设计中，每次新增查询需求都需要：
1. 在 Service 层添加新方法
2. 定义新的 DTO/Response 类
3. 修改 Controller 暴露新接口
4. 前端调用新接口

**痛点**：频繁修改后端代码，接口碎片化，维护成本高。

### 1.2 目标

**核心理念**: Write via API, Query via Semantic Layer

- ✅ **写入侧**: 保持传统 CRUD API（强类型、事务保证）
- ✅ **查询侧**: 通过语义层提供统一、灵活的查询能力
- ✅ **扩展性**: 新增查询需求只需添加 QM 定义，无需修改代码
- ✅ **一致性**: 所有元数据（配置、会话、消息等）统一查询入口

---

## 2. 技术方案

### 2.1 整体架构

```
┌─────────────────────────────────────────────────────────────┐
│                        前端/调用方                            │
└───────────────┬─────────────────────────────┬───────────────┘
                │                             │
        写入请求（POST/PUT）            查询请求（POST /query）
                │                             │
                ↓                             ↓
┌───────────────────────────┐   ┌───────────────────────────┐
│  Configuration Manager    │   │  Metadata Query Service   │
│  ┌─────────────────────┐  │   │  ┌─────────────────────┐  │
│  │ saveDatasource()    │  │   │  │ executeQuery()      │  │
│  │ saveSemanticLayer() │  │   │  │ - 解析 QM 定义      │  │
│  │ updateStatus()      │  │   │  │ - 生成 SQL          │  │
│  └─────────────────────┘  │   │  │ - 执行查询          │  │
└───────────┬───────────────┘   │  │ - 返回结构化数据    │  │
            │                   │  └─────────────────────┘  │
            │                   └───────────┬───────────────┘
            │                               │
            ↓                               ↓
┌─────────────────────────────────────────────────────────────┐
│                      MySQL 数据库                            │
│  datasource_configs                                         │
│  semantic_layer_configs                                     │
│  sessions (coding-agent)                                    │
│  messages (coding-agent)                                    │
└─────────────────────────────────────────────────────────────┘
            ↑                               ↑
            │                               │
┌───────────┴───────────────┐   ┌───────────┴───────────────┐
│    JPA Repository         │   │    TM/QM 语义层           │
│    - 写入优化             │   │    - 查询优化             │
│    - 事务管理             │   │    - 表模型 (.tm)          │
└───────────────────────────┘   │    - 查询模型 (.qm)        │
                                └───────────────────────────┘
```

### 2.2 模块划分

#### 方案 A: 独立模块（推荐）

```
metadata-query-module/
├── pom.xml                         # 依赖 foggy-dataset-model
├── src/main/java/
│   └── com/foggy/navigator/metadata/
│       ├── MetadataQueryService.java       # 统一查询接口
│       ├── MetadataQueryServiceImpl.java
│       ├── model/
│       │   ├── QueryRequest.java           # 查询请求
│       │   ├── QueryResponse.java          # 查询响应
│       │   └── QueryResult.java            # 查询结果
│       └── controller/
│           └── MetadataQueryController.java # REST API
└── src/main/resources/
    └── semantic-models/
        ├── models/                         # TM表模型目录
        │   ├── DatasourceConfigsModel.tm
        │   ├── SemanticLayerConfigsModel.tm
        │   ├── SessionsModel.tm
        │   └── MessagesModel.tm
        └── queries/                        # QM查询模型目录
            ├── datasource-latest.qm
            ├── datasource-list.qm
            ├── semantic-layer-latest.qm
            ├── semantic-layer-list.qm
            ├── config-progress.qm
            ├── sessions-active.qm
            └── messages-by-session.qm
```

**优势**：
- 职责单一：专注于元数据查询
- 解耦：不影响 configuration-module 的写入逻辑
- 可复用：可为多个模块提供查询能力

#### 方案 B: 集成到 configuration-module

在 configuration-module 中增加查询能力，适合小型项目。

---

## 3. 语义层设计

### 3.1 表模型（TM）示例

#### models/DatasourceConfigsModel.tm

**命名规范**：
- **文件名**：`{ModelName}.tm`（驼峰命名）
- **模型name**：`{TableName}Model`（如 `DatasourceConfigsModel`）
- **目录**：`semantic-models/models/`

```json
{
  "name": "DatasourceConfigsModel",
  "displayName": "数据源配置",
  "description": "系统数据源配置表",
  "type": "mysql",
  "tableName": "datasource_configs",
  "columns": [
    {
      "name": "id",
      "displayName": "配置ID",
      "dataType": "VARCHAR",
      "primaryKey": true
    },
    {
      "name": "tenant_id",
      "displayName": "租户ID",
      "dataType": "VARCHAR"
    },
    {
      "name": "db_type",
      "displayName": "数据库类型",
      "dataType": "VARCHAR",
      "description": "MySQL, PostgreSQL, Oracle, SQL Server"
    },
    {
      "name": "host",
      "displayName": "主机地址",
      "dataType": "VARCHAR"
    },
    {
      "name": "port",
      "displayName": "端口",
      "dataType": "INT"
    },
    {
      "name": "database_name",
      "displayName": "数据库名",
      "dataType": "VARCHAR"
    },
    {
      "name": "username",
      "displayName": "用户名",
      "dataType": "VARCHAR"
    },
    {
      "name": "status",
      "displayName": "状态",
      "dataType": "VARCHAR",
      "description": "NOT_STARTED, IN_PROGRESS, CONFIGURED, VALIDATED, FAILED"
    },
    {
      "name": "connection_valid",
      "displayName": "连接有效性",
      "dataType": "BOOLEAN"
    },
    {
      "name": "created_at",
      "displayName": "创建时间",
      "dataType": "DATETIME"
    },
    {
      "name": "updated_at",
      "displayName": "更新时间",
      "dataType": "DATETIME"
    }
  ]
}
```

### 3.2 查询模型（QM）示例

#### queries/datasource-list.qm

**命名规范**：
- **文件名**：`{query-id}.qm`（kebab-case，与queryId一致）
- **queryId**：`datasource-list`（与文件名一致）
- **目录**：`semantic-models/queries/`

```json
{
  "queryId": "datasource-list",
  "displayName": "数据源配置列表",
  "description": "查询所有数据源配置",
  "tableModel": "DatasourceConfigsModel",
  "fields": [
    {
      "name": "id",
      "displayName": "配置ID"
    },
    {
      "name": "db_type",
      "displayName": "数据库类型"
    },
    {
      "name": "host",
      "displayName": "主机"
    },
    {
      "name": "port",
      "displayName": "端口"
    },
    {
      "name": "database_name",
      "displayName": "数据库"
    },
    {
      "name": "status",
      "displayName": "状态"
    },
    {
      "name": "connection_valid",
      "displayName": "连接有效"
    },
    {
      "name": "created_at",
      "displayName": "创建时间"
    }
  ],
  "filters": [
    {
      "name": "tenant_id",
      "operator": "=",
      "description": "按租户筛选"
    },
    {
      "name": "status",
      "operator": "=",
      "description": "按状态筛选"
    },
    {
      "name": "db_type",
      "operator": "=",
      "description": "按数据库类型筛选"
    }
  ],
  "defaultSort": {
    "field": "created_at",
    "order": "DESC"
  }
}
```

#### queries/datasource-latest.qm

```json
{
  "queryId": "datasource-latest",
  "displayName": "最新数据源配置",
  "description": "查询最新的数据源配置（按创建时间倒序，返回第一条）",
  "tableModel": "DatasourceConfigsModel",
  "fields": [
    "id",
    "db_type",
    "host",
    "port",
    "database_name",
    "status",
    "connection_valid",
    "created_at"
  ],
  "defaultSort": {
    "field": "created_at",
    "order": "DESC"
  },
  "limit": 1
}
```

---

## 4. 两层查询接口设计

### 4.1 设计理念

**问题**：完整的 DSL 查询（QueryRequest）虽然灵活，但对简单场景来说过于复杂。

**解决方案**：提供两层接口
- **简单层**：预定义 queryId + 参数化查询（REST 风格，适合 80% 的场景）
- **高级层**：完整 DSL 查询（适合复杂、灵活查询需求）

```
┌─────────────────────────────────────────────┐
│             前端/调用方                      │
└───────────┬────────────────────┬────────────┘
            │                    │
    简单查询（80%）         高级查询（20%）
            │                    │
            ↓                    ↓
    GET /api/metadata/    POST /api/metadata/
    query/{queryId}       query/execute
            │                    │
            │                    │
            ├────────────────────┤
            ↓                    ↓
    ┌──────────────────────────────────┐
    │   MetadataQueryService           │
    │   1. 简单查询转 DSL              │
    │   2. 执行 DSL                    │
    └──────────────────────────────────┘
```

---

### 4.2 简单查询接口（推荐优先使用）

#### 4.2.1 REST API 设计

**方式1：路径参数（推荐，RESTful）**

```http
GET /api/metadata/query/datasource-latest?tenantId={tenantId}
GET /api/metadata/query/datasource-list?status=CONFIGURED&page=1&pageSize=10
GET /api/metadata/query/sessions-active?userId={userId}
GET /api/metadata/query/config-progress?tenantId={tenantId}
```

**方式2：查询ID + JSON参数（更灵活）**

```http
POST /api/metadata/query/{queryId}/execute
Body:
{
  "tenantId": "tenant-001",
  "status": "CONFIGURED"
}
```

#### 4.2.2 预定义 queryId 列表

| queryId | 说明 | 参数 | 返回示例 |
|---------|------|------|---------|
| `datasource-latest` | 最新数据源配置 | `tenantId` (可选) | 单条记录 |
| `datasource-list` | 数据源配置列表 | `tenantId`, `status`, `dbType`, `page`, `pageSize` | 分页列表 |
| `datasource-by-id` | 根据ID查询数据源 | `id` | 单条记录 |
| `semantic-layer-latest` | 最新语义层配置 | `tenantId` (可选) | 单条记录 |
| `semantic-layer-list` | 语义层配置列表 | `tenantId`, `datasourceId`, `page`, `pageSize` | 分页列表 |
| `config-progress` | 配置进度 | `tenantId` (可选) | 进度信息 |
| `config-status-summary` | 配置状态汇总 | `tenantId` (可选) | 统计信息 |
| `sessions-active` | 活跃会话列表 | `userId`, `status` | 会话列表 |
| `messages-by-session` | 会话消息列表 | `sessionId`, `page`, `pageSize` | 消息列表 |

#### 4.2.3 接口实现

```java
package com.foggy.navigator.metadata;

import java.util.Map;

/**
 * 元数据统一查询服务
 */
public interface MetadataQueryService {

    // ===== 简单查询接口（推荐） =====

    /**
     * 执行预定义查询（简单接口）
     * @param queryId 查询ID（如 "datasource-latest", "sessions-active"）
     * @param params 查询参数（如 {"tenantId": "tenant-001", "status": "CONFIGURED"}）
     * @return 查询结果
     */
    QueryResponse executeSimpleQuery(String queryId, Map<String, Object> params);

    // ===== 高级查询接口（灵活） =====

    /**
     * 执行完整 DSL 查询（高级接口）
     * @param queryRequest 完整的查询请求（包含过滤、排序、分页、聚合等）
     * @return 查询结果
     */
    QueryResponse executeQuery(QueryRequest queryRequest);

    // ===== 元数据接口 =====

    /**
     * 获取可用的查询ID列表
     * @return 查询ID列表及说明
     */
    List<QueryInfo> getAvailableQueries();

    /**
     * 获取查询参数定义
     * @param queryId 查询ID
     * @return 查询参数定义
     */
    QueryParametersDefinition getQueryParameters(String queryId);
}
```

#### 4.2.4 REST Controller

```java
package com.foggy.navigator.metadata.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/metadata/query")
@RequiredArgsConstructor
public class MetadataQueryController {

    private final MetadataQueryService queryService;

    // ===== 简单查询接口 =====

    /**
     * 方式1：REST风格（推荐）
     * GET /api/metadata/query/datasource-latest?tenantId=xxx
     */
    @GetMapping("/{queryId}")
    public QueryResponse simpleQuery(
            @PathVariable String queryId,
            @RequestParam Map<String, Object> params) {
        return queryService.executeSimpleQuery(queryId, params);
    }

    /**
     * 方式2：POST + JSON（更灵活）
     * POST /api/metadata/query/datasource-latest/execute
     * Body: {"tenantId": "xxx", "status": "CONFIGURED"}
     */
    @PostMapping("/{queryId}/execute")
    public QueryResponse simpleQueryPost(
            @PathVariable String queryId,
            @RequestBody Map<String, Object> params) {
        return queryService.executeSimpleQuery(queryId, params);
    }

    // ===== 高级查询接口 =====

    /**
     * 完整 DSL 查询
     * POST /api/metadata/query/execute
     */
    @PostMapping("/execute")
    public QueryResponse advancedQuery(@RequestBody QueryRequest request) {
        return queryService.executeQuery(request);
    }

    // ===== 元数据接口 =====

    /**
     * 获取可用查询列表
     * GET /api/metadata/query/available
     */
    @GetMapping("/available")
    public List<QueryInfo> getAvailableQueries() {
        return queryService.getAvailableQueries();
    }

    /**
     * 获取查询参数定义
     * GET /api/metadata/query/{queryId}/parameters
     */
    @GetMapping("/{queryId}/parameters")
    public QueryParametersDefinition getQueryParameters(@PathVariable String queryId) {
        return queryService.getQueryParameters(queryId);
    }
}
```

### 4.2 QueryRequest

```java
package com.foggy.navigator.metadata.model;

import lombok.Data;
import java.util.Map;

/**
 * 查询请求
 */
@Data
public class QueryRequest {
    /**
     * 查询模型名称
     * 示例: "datasource_configs_list", "sessions_active"
     */
    private String queryName;

    /**
     * 筛选条件
     * 示例: {"tenant_id": "tenant-001", "status": "CONFIGURED"}
     */
    private Map<String, Object> filters;

    /**
     * 排序字段
     * 示例: {"field": "created_at", "order": "DESC"}
     */
    private SortCriteria sort;

    /**
     * 分页参数
     */
    private Pagination pagination;

    /**
     * 聚合参数（可选）
     */
    private Aggregation aggregation;
}

@Data
class SortCriteria {
    private String field;
    private String order; // ASC, DESC
}

@Data
class Pagination {
    private int page = 1;
    private int pageSize = 20;
}

@Data
class Aggregation {
    private String function; // COUNT, SUM, AVG, MAX, MIN
    private String field;
    private String groupBy;
}
```

### 4.3 QueryResponse

```java
package com.foggy.navigator.metadata.model;

import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * 查询响应
 */
@Data
public class QueryResponse {
    /**
     * 查询是否成功
     */
    private boolean success;

    /**
     * 错误消息（失败时）
     */
    private String errorMessage;

    /**
     * 查询结果
     */
    private QueryResult result;

    /**
     * 元数据
     */
    private QueryMetadata metadata;
}

@Data
class QueryResult {
    /**
     * 数据行列表
     * 每行是一个 Map<列名, 值>
     */
    private List<Map<String, Object>> rows;

    /**
     * 总行数（分页时使用）
     */
    private long totalCount;

    /**
     * 聚合结果（如果有）
     */
    private Map<String, Object> aggregationResult;
}

@Data
class QueryMetadata {
    /**
     * 查询名称
     */
    private String queryName;

    /**
     * 执行耗时（毫秒）
     */
    private long executionTime;

    /**
     * 字段定义
     */
    private List<FieldDefinition> fields;
}

@Data
class FieldDefinition {
    private String name;
    private String displayName;
    private String dataType;
}
```

### 4.4 REST API

```java
package com.foggy.navigator.metadata.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 元数据查询 REST API
 */
@RestController
@RequestMapping("/api/metadata/query")
@RequiredArgsConstructor
public class MetadataQueryController {

    private final MetadataQueryService queryService;

    /**
     * 执行查询
     * POST /api/metadata/query/execute
     */
    @PostMapping("/execute")
    public QueryResponse executeQuery(@RequestBody QueryRequest request) {
        return queryService.executeQuery(request);
    }

    /**
     * 获取可用查询列表
     * GET /api/metadata/query/available
     */
    @GetMapping("/available")
    public List<String> getAvailableQueries() {
        return queryService.getAvailableQueries();
    }

    /**
     * 获取查询定义
     * GET /api/metadata/query/definition/{queryName}
     */
    @GetMapping("/definition/{queryName}")
    public QueryModelDefinition getQueryDefinition(@PathVariable String queryName) {
        return queryService.getQueryDefinition(queryName);
    }
}
```

---

## 5. 使用示例

### 5.1 简单查询示例（推荐）

#### 示例1：查询最新数据源配置

**方式1：GET + 路径参数**
```http
GET /api/metadata/query/datasource-latest?tenantId=tenant-001
```

**方式2：POST + JSON参数**
```http
POST /api/metadata/query/datasource-latest/execute
Content-Type: application/json

{
  "tenantId": "tenant-001"
}
```

**响应**:
```json
{
  "success": true,
  "result": {
    "rows": [
      {
        "id": "ds-001",
        "db_type": "MySQL",
        "host": "localhost",
        "port": 3306,
        "database_name": "sales_db",
        "status": "CONFIGURED",
        "connection_valid": true,
        "created_at": "2026-01-25T10:30:00"
      }
    ],
    "totalCount": 1
  },
  "metadata": {
    "queryId": "datasource-latest",
    "executionTime": 12
  }
}
```

#### 示例2：查询数据源配置列表（分页）

```http
GET /api/metadata/query/datasource-list?status=CONFIGURED&page=1&pageSize=10
```

**响应**:
```json
{
  "success": true,
  "result": {
    "rows": [
      {"id": "ds-001", "db_type": "MySQL", "host": "localhost", ...},
      {"id": "ds-002", "db_type": "PostgreSQL", "host": "192.168.1.100", ...}
    ],
    "totalCount": 15
  },
  "metadata": {
    "queryId": "datasource-list",
    "executionTime": 25
  }
}
```

#### 示例3：查询配置进度

```http
GET /api/metadata/query/config-progress?tenantId=tenant-001
```

**响应**:
```json
{
  "success": true,
  "result": {
    "rows": [
      {
        "total_steps": 3,
        "completed_steps": 2,
        "current_step": "配置权限",
        "progress_percentage": 67
      }
    ]
  }
}
```

#### 示例4：查询活跃会话

```http
GET /api/metadata/query/sessions-active?userId=user-123&status=ACTIVE
```

#### 示例5：查询会话消息

```http
GET /api/metadata/query/messages-by-session?sessionId=session-456&page=1&pageSize=20
```

### 5.2 高级 DSL 查询示例（复杂场景）

#### 示例1：自定义过滤和排序

**请求**:
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
  }
}
```

#### 示例2：聚合查询（统计各状态的配置数量）

**请求**:
```http
POST /api/metadata/query/execute
Content-Type: application/json

{
  "queryName": "datasource_configs_list",
  "aggregation": {
    "function": "COUNT",
    "field": "id",
    "groupBy": "status"
  }
}
```

**响应**:
```json
{
  "success": true,
  "result": {
    "aggregationResult": {
      "CONFIGURED": 5,
      "IN_PROGRESS": 2,
      "FAILED": 1
    }
  },
  "metadata": {
    "queryName": "datasource_configs_list",
    "executionTime": 18
  }
}
```

---

## 6. 与其他模块的集成

### 6.1 tutor-agent 集成示例

**简单查询**（推荐）:
```java
@Service
@RequiredArgsConstructor
public class TutorAgentService {
    private final MetadataQueryService queryService;

    public void checkDatasourceStatus() {
        // 简单查询：查询最新数据源配置
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", "tenant-001");

        QueryResponse response = queryService.executeSimpleQuery(
            "datasource-latest",
            params
        );

        if (response.isSuccess() && !response.getResult().getRows().isEmpty()) {
            Map<String, Object> datasource = response.getResult().getRows().get(0);
            String status = (String) datasource.get("status");
            boolean connectionValid = (Boolean) datasource.get("connection_valid");

            // 根据状态引导用户
            if ("CONFIGURED".equals(status) && connectionValid) {
                // 数据源已配置且连接有效
            } else {
                // 引导用户配置数据源
            }
        }
    }

    public void checkOverallProgress() {
        // 查询配置进度
        QueryResponse response = queryService.executeSimpleQuery(
            "config-progress",
            Map.of("tenantId", "tenant-001")
        );

        // 处理进度信息
        Map<String, Object> progress = response.getResult().getRows().get(0);
        int completedSteps = (Integer) progress.get("completed_steps");
        int totalSteps = (Integer) progress.get("total_steps");
        String currentStep = (String) progress.get("current_step");

        // 引导用户完成当前步骤
    }
}
```

**高级查询**（复杂场景）:
```java
public List<Map<String, Object>> queryFailedDatasources() {
    // 需要自定义查询条件时使用 DSL
    QueryRequest request = new QueryRequest();
    request.setQueryName("datasource_configs_list");

    Map<String, Object> filters = new HashMap<>();
    filters.put("status", "FAILED");
    filters.put("connection_valid", false);
    request.setFilters(filters);

    QueryResponse response = queryService.executeQuery(request);
    return response.getResult().getRows();
}
```

### 6.2 配置管理模块集成

**写入**（使用 ConfigurationManager）:
```java
@Service
@RequiredArgsConstructor
public class DatasourceConfigService {
    private final ConfigurationManager configManager;

    public String saveDatasource(DatasourceConfigDTO dto) {
        // 保存数据源配置
        DatasourceConfig config = new DatasourceConfig();
        config.setDbType(dto.getDbType());
        config.setHost(dto.getHost());
        // ... 设置其他字段

        return configManager.saveDatasourceConfig(config);
    }
}
```

**查询**（使用 MetadataQueryService）:
```java
@Service
@RequiredArgsConstructor
public class DatasourceQueryService {
    private final MetadataQueryService queryService;

    public List<Map<String, Object>> listDatasources(String status) {
        // 查询数据源配置列表
        Map<String, Object> params = Map.of(
            "status", status,
            "page", 1,
            "pageSize", 10
        );

        QueryResponse response = queryService.executeSimpleQuery(
            "datasource-list",
            params
        );

        return response.getResult().getRows();
    }
}
```

---

## 7. 实现步骤

### 7.1 Phase 1: 基础设施（1天）

1. **创建 metadata-query-module**
   ```bash
   mvn archetype:generate \
     -DgroupId=com.foggy.navigator \
     -DartifactId=metadata-query-module
   ```

2. **添加依赖**
   ```xml
   <dependencies>
     <dependency>
       <groupId>com.foggy</groupId>
       <artifactId>foggy-dataset-model</artifactId>
       <version>1.0.0</version>
     </dependency>
     <dependency>
       <groupId>org.springframework.boot</groupId>
       <artifactId>spring-boot-starter-jdbc</artifactId>
     </dependency>
   </dependencies>
   ```

3. **定义接口和数据模型**
   - MetadataQueryService
   - QueryRequest / QueryResponse

### 7.2 Phase 2: 生成 TM/QM 模型（1天）

使用 `/tm-generate` 和 `/qm-generate` 技能：

1. **生成 datasource_configs 的 TM**
   ```
   /tm-generate
   # 基于表结构生成 TM
   ```

2. **生成常用查询的 QM**
   - datasource_configs_list.qm
   - datasource_configs_latest.qm
   - semantic_layer_configs_list.qm
   - config_progress_list.qm

### 7.3 Phase 3: 实现查询引擎（1-2天）

1. **实现 MetadataQueryServiceImpl**
   - 加载 TM/QM 定义
   - 解析 QueryRequest
   - 生成 SQL
   - 执行查询
   - 封装结果

2. **实现 REST Controller**

3. **单元测试**

### 7.4 Phase 4: 集成测试（0.5天）

1. 测试查询接口
2. 测试与 tutor-agent 集成
3. 性能测试

---

## 8. 扩展能力

### 8.1 支持的查询类型

- ✅ 简单查询（WHERE 条件）
- ✅ 排序（ORDER BY）
- ✅ 分页（LIMIT / OFFSET）
- ✅ 聚合（COUNT, SUM, AVG, MAX, MIN）
- ✅ 分组（GROUP BY）
- 🔄 关联查询（JOIN）- Phase 2
- 🔄 子查询 - Phase 2

### 8.2 支持的元数据范围

**初期**（Phase 1）:
- datasource_configs
- semantic_layer_configs
- system_config_progress

**扩展**（Phase 2）:
- coding-agent 的 sessions, messages
- 其他模块的配置表

**未来**:
- 支持跨模块查询
- 支持自定义 QM 动态生成

---

## 9. 性能优化

### 9.1 缓存策略

```java
@Service
public class MetadataQueryServiceImpl {

    @Cacheable(value = "queryDefinitions", key = "#queryName")
    public QueryModelDefinition getQueryDefinition(String queryName) {
        // 加载 QM 定义
    }

    @Cacheable(value = "queryResults", key = "#request.hashCode()",
               condition = "#request.cacheable")
    public QueryResponse executeQuery(QueryRequest request) {
        // 执行查询
    }
}
```

### 9.2 索引优化

为常用查询条件添加索引：
```sql
CREATE INDEX idx_datasource_tenant_status
ON datasource_configs(tenant_id, status);

CREATE INDEX idx_semantic_layer_datasource
ON semantic_layer_configs(datasource_id);
```

---

## 10. 安全考虑

### 10.1 敏感字段过滤

某些字段（如 password）不应通过查询接口暴露：

```json
{
  "name": "password",
  "displayName": "密码",
  "dataType": "VARCHAR",
  "sensitive": true,  // 标记为敏感
  "queryable": false  // 禁止查询
}
```

### 10.2 权限控制

```java
@Service
public class MetadataQueryServiceImpl {

    public QueryResponse executeQuery(QueryRequest request) {
        // 1. 检查用户权限
        if (!hasPermission(currentUser, request.getQueryName())) {
            throw new UnauthorizedException();
        }

        // 2. 租户隔离
        request.getFilters().put("tenant_id", currentUser.getTenantId());

        // 3. 执行查询
    }
}
```

---

## 11. 监控与可观察性

### 11.1 日志

```java
log.info("Executing query: {}, filters: {}, user: {}",
         request.getQueryName(),
         request.getFilters(),
         currentUser.getId());
```

### 11.2 指标

```java
@Component
public class QueryMetrics {
    private final Counter queryCounter;
    private final Histogram queryDuration;

    public QueryMetrics(MeterRegistry registry) {
        this.queryCounter = Counter.builder("metadata_query_total")
            .tag("query_name", "xxx")
            .register(registry);

        this.queryDuration = Histogram.builder("metadata_query_duration_seconds")
            .register(registry);
    }
}
```

---

## 12. 总结

### 12.1 优势

1. **灵活性**: 新增查询需求无需修改代码，只需添加 QM 定义
2. **一致性**: 所有元数据统一查询入口，统一的返回格式
3. **解耦**: 查询逻辑与业务逻辑分离
4. **可扩展**: 轻松支持新的元数据表
5. **语义化**: QM 提供业务语义，降低使用门槛

### 12.2 适用场景

- ✅ 配置管理系统
- ✅ 会话/消息查询
- ✅ 系统状态监控
- ✅ 数据分析/报表
- ❌ 复杂事务写入（使用传统 API）
- ❌ 实时性要求极高的场景（< 10ms）

### 12.3 下一步

1. 使用 `/foggy-java-integration` 技能集成 foggy-dataset-model
2. 使用 `/tm-generate` 生成表模型
3. 使用 `/qm-generate` 生成查询模型
4. 实现 metadata-query-module
5. 集成到 tutor-agent

---

**相关文档**:
- [Configuration Module Design](./configuration-module-design.md)
- [Foggy Dataset Model 文档](https://github.com/foggy-navigator/foggy-dataset-model)

**相关技能**:
- `/foggy-java-integration` - Java 项目集成
- `/tm-generate` - 生成表模型
- `/qm-generate` - 生成查询模型
