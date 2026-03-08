# Metadata Query Module

> 元数据统一查询模块 - 基于 foggy-dataset-model 的语义层查询服务

## 模块概述

本模块提供统一的元数据查询接口，通过语义层（TM/QM）实现灵活的查询能力，避免接口碎片化。

### 核心特性

- ✅ **两层查询接口**：简单查询（queryId + params）+ 高级查询（完整DSL）
- ✅ **语义层驱动**：基于 TM/QM 模型，无需编写 SQL
- ✅ **统一查询入口**：所有元数据查询通过一个接口
- ✅ **易扩展**：新增查询只需添加 QM 文件，无需修改代码

## 技术栈

- Spring Boot 3.4.2
- Foggy Dataset Model 8.1.5.beta
- MySQL 8.0+
- Lombok

## 项目结构

```
metadata-query-module/
├── src/main/java/com/foggy/navigator/metadata/
│   └── MetadataQueryApplication.java           # 主应用类
├── src/main/resources/
│   ├── application.yml                         # 配置文件
│   └── foggy/templates/
│       ├── models/                             # TM 表模型
│       │   ├── DatasourceConfigsModel.tm       # 数据源配置模型
│       │   └── SemanticLayerConfigsModel.tm    # 语义层配置模型
│       └── queries/                            # QM 查询模型
│           ├── datasource-latest.qm            # 最新数据源配置
│           ├── datasource-list.qm              # 数据源配置列表
│           ├── semantic-layer-latest.qm        # 最新语义层配置
│           └── semantic-layer-list.qm          # 语义层配置列表
└── pom.xml
```

## 已创建的模型

### TM 表模型

| 模型名称 | 表名 | 说明 |
|---------|------|------|
| `DatasourceConfigsModel` | `datasource_configs` | 数据源配置表 |
| `SemanticLayerConfigsModel` | `semantic_layer_configs` | 语义层配置表 |

### QM 查询模型

| queryId | 说明 | 参数 |
|---------|------|------|
| `datasource-latest` | 最新数据源配置 | `tenantId` (可选) |
| `datasource-list` | 数据源配置列表 | `tenantId`, `status`, `dbType`, `page`, `pageSize` |
| `semantic-layer-latest` | 最新语义层配置 | `tenantId` (可选) |
| `semantic-layer-list` | 语义层配置列表 | `tenantId`, `datasourceId`, `status`, `page`, `pageSize` |

## 快速开始

### 1. 配置数据源

编辑 `src/main/resources/application.yml`：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:13309/foggy_navigator
    username: root
    password: root
```

### 2. 启动应用

```bash
cd metadata-query-module
mvn spring-boot:run
```

应用将在 **http://localhost:8082** 启动。

### 3. 测试查询

#### 查询最新数据源配置

```bash
POST http://localhost:8082/jdbc-model/query-model/v2/datasource-latest
Content-Type: application/json

{
  "filters": {
    "tenantId": "tenant-001"
  }
}
```

#### 查询数据源列表

```bash
POST http://localhost:8082/jdbc-model/query-model/v2/datasource-list
Content-Type: application/json

{
  "filters": {
    "status": "CONFIGURED"
  },
  "pagination": {
    "page": 1,
    "pageSize": 10
  }
}
```

## Foggy 框架配置

### 查询 API 端点

Foggy Dataset Model 提供的标准查询端点：

```
POST /jdbc-model/query-model/v2/{queryId}
```

### 开发工具 API

获取表结构信息（开发时使用）：

```bash
# 列出所有表
GET http://localhost:7108/dev/tables

# 获取表详细结构
GET http://localhost:7108/dev/tables/{tableName}
```

## 下一步开发

### 1. 实现 MetadataQueryService

创建 `service.com.foggy.navigator.metadata.query.MetadataQueryService`：

```java
public interface MetadataQueryService {
    // 简单查询接口
    QueryResponse executeSimpleQuery(String queryId, Map<String, Object> params);

    // 高级查询接口
    QueryResponse executeQuery(QueryRequest request);

    // 元数据接口
    List<QueryInfo> getAvailableQueries();
}
```

### 2. 实现 REST Controller

创建 `controller.com.foggy.navigator.metadata.query.MetadataQueryController`：

```java
@RestController
@RequestMapping("/api/metadata/query")
public class MetadataQueryController {

    @GetMapping("/{queryId}")
    public QueryResponse simpleQuery(
        @PathVariable String queryId,
        @RequestParam Map<String, Object> params) {
        // 简单查询接口
    }

    @PostMapping("/execute")
    public QueryResponse advancedQuery(@RequestBody QueryRequest request) {
        // 高级查询接口
    }
}
```

### 3. 添加更多查询模型

根据需求在 `foggy/templates/queries/` 目录下添加新的 QM 文件：

```bash
# 示例：配置进度查询
config-progress.qm

# 示例：会话列表查询
sessions-active.qm

# 示例：消息列表查询
messages-by-session.qm
```

## 相关文档

- [系统架构概览](../docs/00-system-overview.md)
- [Foggy Dataset Model 文档](https://foggy-projects.github.io/foggy-data-mcp-bridge/)

## 依赖版本

- Foggy Dataset Model: 8.1.5.beta
- Spring Boot: 3.4.2
- Java: 17
- MySQL Connector: 8.0+

---

## 实现状态

**创建时间**: 2026-01-26
**完成时间**: 2026-01-26
**状态**: ✅ **已完成并通过测试**

### 已实现功能

- ✅ Maven 模块搭建和依赖配置（foggy-dataset-model + foggy-core）
- ✅ TM/QM 模型文件（4个查询模型）
- ✅ 数据模型定义（10个类）
- ✅ MetadataQueryService 服务层实现
- ✅ MetadataQueryController REST API（使用 RX 统一返回对象）
- ✅ RestTemplate 配置
- ✅ 单元测试（13个测试用例，全部通过）

### API 端点

#### 简单查询接口

```bash
# 方式1：GET + URL参数
GET /api/metadata/query/{queryId}?param1=value1&param2=value2

# 方式2：POST + JSON Body
POST /api/metadata/query/{queryId}/execute
Body: {"param1": "value1", "param2": "value2"}

# 响应格式（RX 统一返回对象）
{
  "code": 200,                    # 成功=200, 失败=600(业务异常)
  "msg": null,                    # 错误消息
  "data": {                       # QueryResult 对象
    "rows": [...],                # 查询结果行
    "totalCount": 10              # 总记录数
  }
}
```

#### 高级查询接口

```bash
POST /api/metadata/query/execute
Body: {
  "queryName": "datasource-list",
  "filters": {"status": "CONFIGURED"},
  "sort": {"field": "createdAt", "order": "DESC"},
  "pagination": {"page": 1, "pageSize": 20}
}
```

#### 元数据接口

```bash
# 获取可用查询列表
GET /api/metadata/query/available
# 返回: RX<List<QueryInfo>>

# 获取查询参数定义
GET /api/metadata/query/{queryId}/parameters
# 返回: RX<QueryParametersDefinition>
```

### 测试结果

```
Tests run: 13, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

- **Controller 测试**: 6个用例，覆盖所有接口
- **Service 测试**: 7个用例，覆盖核心业务逻辑
