# 语义层验证服务设计

> 独立部署的语义层文件实时验证服务

## 1. 概述

### 1.1 目标

提供独立的验证服务，用于实时验证语义层文件（TM/QM）的正确性，包括：
- 加载指定目录下的所有模型文件
- 验证表和字段的正确性
- 返回详细的验证结果和错误信息

### 1.2 核心特性

- **实时验证**：接收目录路径，立即加载并验证
- **独立部署**：Docker 容器独立运行
- **用户配置数据库**：支持用户自定义数据库连接
- **基础验证**：先实现表和字段的基础验证

## 2. 架构设计

### 2.1 服务架构

```
┌─────────────────────────────────────────────────────────┐
│           Validation Service (Spring Boot)              │
│                                                         │
│  ┌──────────────────────────────────────────────────┐  │
│  │  ValidationController                            │  │
│  │  - POST /api/validation/validate                 │  │
│  │  - GET  /api/validation/health                   │  │
│  └──────────────────────────────────────────────────┘  │
│                        ↓                                │
│  ┌──────────────────────────────────────────────────┐  │
│  │  SemanticLayerLoader                             │  │
│  │  - 加载 TM/QM 文件                                │  │
│  │  - 解析模型定义                                   │  │
│  └──────────────────────────────────────────────────┘  │
│                        ↓                                │
│  ┌──────────────────────────────────────────────────┐  │
│  │  ValidationEngine                                │  │
│  │  - 验证表是否存在                                 │  │
│  │  - 验证字段是否存在                               │  │
│  │  - 验证字段类型是否匹配                           │  │
│  └──────────────────────────────────────────────────┘  │
│                        ↓                                │
│  ┌──────────────────────────────────────────────────┐  │
│  │  DatabaseMetadataService                         │  │
│  │  - 获取数据库表结构                               │  │
│  │  - 缓存元数据                                     │  │
│  └──────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────┘
                        ↓
              ┌──────────────────┐
              │  User Database   │
              │  (MySQL/PG/...)  │
              └──────────────────┘
```

### 2.2 工作空间访问

```
┌──────────────────────┐
│  Shared Workspace    │
│  /workspace/         │
│  ├── user-123/       │
│  │   └── session-1/  │
│  │       ├── FactOrderModel.tm
│  │       └── FactOrderQueryModel.qm
│  └── user-456/       │
└──────────────────────┘
         ↑
         │ 挂载
         │
┌──────────────────────┐
│  Validation Service  │
│  读取目录下的所有    │
│  .tm 和 .qm 文件     │
└──────────────────────┘
```

## 3. API 设计

### 3.1 验证接口

#### POST /api/validation/validate

**请求**:
```json
{
  "workspacePath": "/workspace/user-123/session-1",
  "datasource": {
    "url": "jdbc:mysql://localhost:3306/demo",
    "username": "root",
    "password": "password",
    "driverClassName": "com.mysql.cj.jdbc.Driver"
  }
}
```

**响应**:
```json
{
  "success": true,
  "message": "验证通过",
  "validatedAt": "2026-01-21T10:30:00",
  "summary": {
    "totalFiles": 2,
    "validFiles": 2,
    "invalidFiles": 0
  },
  "results": [
    {
      "file": "FactOrderModel.tm",
      "success": true,
      "errors": []
    },
    {
      "file": "FactOrderQueryModel.qm",
      "success": true,
      "errors": []
    }
  ]
}
```

**验证失败响应**:
```json
{
  "success": false,
  "message": "验证失败",
  "validatedAt": "2026-01-21T10:30:00",
  "summary": {
    "totalFiles": 2,
    "validFiles": 1,
    "invalidFiles": 1
  },
  "results": [
    {
      "file": "FactOrderModel.tm",
      "success": false,
      "errors": [
        {
          "type": "TABLE_NOT_FOUND",
          "message": "表 'fact_order' 不存在",
          "field": "tableName",
          "line": 5
        },
        {
          "type": "COLUMN_NOT_FOUND",
          "message": "字段 'order_amount' 在表 'fact_order' 中不存在",
          "field": "measures[0].column",
          "line": 25
        }
      ]
    }
  ]
}
```

### 3.2 健康检查接口

#### GET /api/validation/health

**响应**:
```json
{
  "status": "UP",
  "database": "CONNECTED",
  "version": "1.0.0"
}
```

## 4. 验证规则

### 4.1 基础验证规则

#### 规则 1: 表存在性验证

```java
public class TableExistenceValidator {

    public ValidationError validate(TableModel model, DatabaseMetadata metadata) {
        String tableName = model.getTableName();

        if (!metadata.tableExists(tableName)) {
            return ValidationError.builder()
                .type("TABLE_NOT_FOUND")
                .message(String.format("表 '%s' 不存在", tableName))
                .field("tableName")
                .build();
        }

        return null; // 验证通过
    }
}
```

#### 规则 2: 字段存在性验证

```java
public class ColumnExistenceValidator {

    public List<ValidationError> validate(TableModel model, DatabaseMetadata metadata) {
        List<ValidationError> errors = new ArrayList<>();
        String tableName = model.getTableName();

        // 验证属性字段
        for (Property property : model.getProperties()) {
            if (!metadata.columnExists(tableName, property.getColumn())) {
                errors.add(ValidationError.builder()
                    .type("COLUMN_NOT_FOUND")
                    .message(String.format("字段 '%s' 在表 '%s' 中不存在",
                        property.getColumn(), tableName))
                    .field("properties[].column")
                    .build());
            }
        }

        // 验证度量字段
        for (Measure measure : model.getMeasures()) {
            if (!metadata.columnExists(tableName, measure.getColumn())) {
                errors.add(ValidationError.builder()
                    .type("COLUMN_NOT_FOUND")
                    .message(String.format("字段 '%s' 在表 '%s' 中不存在",
                        measure.getColumn(), tableName))
                    .field("measures[].column")
                    .build());
            }
        }

        return errors;
    }
}
```

#### 规则 3: 字段类型验证

```java
public class ColumnTypeValidator {

    public List<ValidationError> validate(TableModel model, DatabaseMetadata metadata) {
        List<ValidationError> errors = new ArrayList<>();
        String tableName = model.getTableName();

        // 验证度量字段类型（必须是数值类型）
        for (Measure measure : model.getMeasures()) {
            String columnName = measure.getColumn();
            String dbType = metadata.getColumnType(tableName, columnName);

            if (!isNumericType(dbType)) {
                errors.add(ValidationError.builder()
                    .type("TYPE_MISMATCH")
                    .message(String.format(
                        "度量字段 '%s' 的类型 '%s' 不是数值类型",
                        columnName, dbType))
                    .field("measures[].column")
                    .build());
            }
        }

        return errors;
    }

    private boolean isNumericType(String dbType) {
        return dbType.matches("(?i)(INT|BIGINT|DECIMAL|NUMERIC|FLOAT|DOUBLE|MONEY).*");
    }
}
```

#### 规则 4: 维度外键验证

```java
public class DimensionForeignKeyValidator {

    public List<ValidationError> validate(TableModel model, DatabaseMetadata metadata) {
        List<ValidationError> errors = new ArrayList<>();
        String tableName = model.getTableName();

        for (Dimension dimension : model.getDimensions()) {
            String foreignKey = dimension.getForeignKey();
            String dimTableName = dimension.getTableName();

            // 验证外键字段存在
            if (!metadata.columnExists(tableName, foreignKey)) {
                errors.add(ValidationError.builder()
                    .type("FOREIGN_KEY_NOT_FOUND")
                    .message(String.format(
                        "外键字段 '%s' 在表 '%s' 中不存在",
                        foreignKey, tableName))
                    .field("dimensions[].foreignKey")
                    .build());
            }

            // 验证维度表存在
            if (!metadata.tableExists(dimTableName)) {
                errors.add(ValidationError.builder()
                    .type("DIMENSION_TABLE_NOT_FOUND")
                    .message(String.format(
                        "维度表 '%s' 不存在",
                        dimTableName))
                    .field("dimensions[].tableName")
                    .build());
            }
        }

        return errors;
    }
}
```

### 4.2 验证错误类型

| 错误类型 | 说明 | 示例 |
|---------|------|------|
| `TABLE_NOT_FOUND` | 表不存在 | 表 'fact_order' 不存在 |
| `COLUMN_NOT_FOUND` | 字段不存在 | 字段 'order_amount' 不存在 |
| `TYPE_MISMATCH` | 类型不匹配 | 度量字段类型不是数值类型 |
| `FOREIGN_KEY_NOT_FOUND` | 外键字段不存在 | 外键 'customer_id' 不存在 |
| `DIMENSION_TABLE_NOT_FOUND` | 维度表不存在 | 维度表 'dim_customer' 不存在 |
| `SYNTAX_ERROR` | 语法错误 | TM 文件语法错误 |
| `FILE_NOT_FOUND` | 文件不存在 | 找不到 TM 文件 |

## 5. 核心实现

### 5.1 ValidationController

```java
@RestController
@RequestMapping("/api/validation")
@Slf4j
public class ValidationController {

    @Autowired
    private ValidationService validationService;

    @PostMapping("/validate")
    public ResponseEntity<ValidationResponse> validate(
        @RequestBody ValidationRequest request
    ) {
        log.info("收到验证请求: workspacePath={}", request.getWorkspacePath());

        try {
            ValidationResponse response = validationService.validate(request);
            log.info("验证完成: success={}, errors={}",
                response.isSuccess(),
                response.getSummary().getInvalidFiles());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("验证失败", e);
            return ResponseEntity.status(500).body(
                ValidationResponse.error("验证服务异常: " + e.getMessage())
            );
        }
    }

    @GetMapping("/health")
    public ResponseEntity<HealthResponse> health() {
        return ResponseEntity.ok(
            HealthResponse.builder()
                .status("UP")
                .database("CONNECTED")
                .version("1.0.0")
                .build()
        );
    }
}
```

### 5.2 ValidationService

```java
@Service
@Slf4j
public class ValidationService {

    @Autowired
    private SemanticLayerLoader semanticLayerLoader;

    @Autowired
    private ValidationEngine validationEngine;

    @Autowired
    private DatabaseMetadataService metadataService;

    public ValidationResponse validate(ValidationRequest request) {
        String workspacePath = request.getWorkspacePath();

        // 1. 配置数据源
        if (request.getDatasource() != null) {
            metadataService.configureDatasource(request.getDatasource());
        }

        // 2. 加载语义层文件
        List<ModelFile> modelFiles = semanticLayerLoader.loadFromDirectory(workspacePath);
        log.info("加载了 {} 个模型文件", modelFiles.size());

        // 3. 获取数据库元数据
        DatabaseMetadata metadata = metadataService.getMetadata();

        // 4. 验证每个文件
        List<FileValidationResult> results = new ArrayList<>();
        int validCount = 0;
        int invalidCount = 0;

        for (ModelFile modelFile : modelFiles) {
            FileValidationResult result = validationEngine.validate(modelFile, metadata);
            results.add(result);

            if (result.isSuccess()) {
                validCount++;
            } else {
                invalidCount++;
            }
        }

        // 5. 构建响应
        boolean success = invalidCount == 0;
        return ValidationResponse.builder()
            .success(success)
            .message(success ? "验证通过" : "验证失败")
            .validatedAt(LocalDateTime.now())
            .summary(ValidationSummary.builder()
                .totalFiles(modelFiles.size())
                .validFiles(validCount)
                .invalidFiles(invalidCount)
                .build())
            .results(results)
            .build();
    }
}
```

### 5.3 SemanticLayerLoader

```java
@Component
@Slf4j
public class SemanticLayerLoader {

    public List<ModelFile> loadFromDirectory(String directoryPath) {
        List<ModelFile> modelFiles = new ArrayList<>();
        Path dir = Paths.get(directoryPath);

        if (!Files.exists(dir)) {
            throw new IllegalArgumentException("目录不存在: " + directoryPath);
        }

        try (Stream<Path> paths = Files.walk(dir)) {
            paths.filter(Files::isRegularFile)
                 .filter(p -> p.toString().endsWith(".tm") || p.toString().endsWith(".qm"))
                 .forEach(path -> {
                     try {
                         ModelFile modelFile = loadModelFile(path);
                         modelFiles.add(modelFile);
                     } catch (Exception e) {
                         log.error("加载文件失败: {}", path, e);
                     }
                 });
        } catch (IOException e) {
            throw new RuntimeException("读取目录失败", e);
        }

        return modelFiles;
    }

    private ModelFile loadModelFile(Path path) throws IOException {
        String content = Files.readString(path);
        String fileName = path.getFileName().toString();

        // 使用 Foggy Dataset Model 的 API 解析文件
        // 这里假设有一个解析器
        TableModel model = parseTableModel(content);

        return ModelFile.builder()
            .fileName(fileName)
            .filePath(path.toString())
            .content(content)
            .model(model)
            .build();
    }
}
```

### 5.4 DatabaseMetadataService

```java
@Service
@Slf4j
public class DatabaseMetadataService {

    private DataSource dataSource;
    private DatabaseMetadata cachedMetadata;

    public void configureDatasource(DatasourceConfig config) {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(config.getUrl());
        hikariConfig.setUsername(config.getUsername());
        hikariConfig.setPassword(config.getPassword());
        hikariConfig.setDriverClassName(config.getDriverClassName());

        this.dataSource = new HikariDataSource(hikariConfig);
        this.cachedMetadata = null; // 清除缓存
    }

    public DatabaseMetadata getMetadata() {
        if (cachedMetadata == null) {
            cachedMetadata = loadMetadata();
        }
        return cachedMetadata;
    }

    private DatabaseMetadata loadMetadata() {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData metaData = conn.getMetaData();
            Map<String, TableMetadata> tables = new HashMap<>();

            // 获取所有表
            ResultSet rs = metaData.getTables(null, null, "%", new String[]{"TABLE"});
            while (rs.next()) {
                String tableName = rs.getString("TABLE_NAME");
                TableMetadata tableMetadata = loadTableMetadata(metaData, tableName);
                tables.put(tableName.toLowerCase(), tableMetadata);
            }

            return new DatabaseMetadata(tables);

        } catch (SQLException e) {
            throw new RuntimeException("获取数据库元数据失败", e);
        }
    }

    private TableMetadata loadTableMetadata(DatabaseMetaData metaData, String tableName)
        throws SQLException {

        Map<String, ColumnMetadata> columns = new HashMap<>();

        ResultSet rs = metaData.getColumns(null, null, tableName, "%");
        while (rs.next()) {
            String columnName = rs.getString("COLUMN_NAME");
            String dataType = rs.getString("TYPE_NAME");

            columns.put(columnName.toLowerCase(), new ColumnMetadata(columnName, dataType));
        }

        return new TableMetadata(tableName, columns);
    }
}
```

## 6. 部署配置

### 6.1 Dockerfile

```dockerfile
FROM openjdk:17-slim

WORKDIR /app

COPY target/validation-service.jar app.jar

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "app.jar"]
```

### 6.2 docker-compose.yml

```yaml
version: '3.8'

services:
  validation-service:
    build: .
    image: foggy-validation:latest
    container_name: validation-service
    ports:
      - "8081:8081"
    environment:
      - SERVER_PORT=8081
      - LOGGING_LEVEL_ROOT=INFO
    volumes:
      - shared-workspace:/workspace:ro  # 只读挂载
    restart: unless-stopped

volumes:
  shared-workspace:
    external: true  # 使用外部创建的共享卷
```

### 6.3 application.yml

```yaml
server:
  port: 8081

spring:
  application:
    name: validation-service

logging:
  level:
    root: INFO
    com.foggy.validation: DEBUG

validation:
  workspace:
    base-path: /workspace
  cache:
    metadata-ttl: 300  # 元数据缓存5分钟
```

## 7. 使用示例

### 7.1 Foggy Navigator 调用示例

```java
@Service
public class CodingAgentService {

    @Autowired
    private ValidationServiceClient validationClient;

    public void validateSemanticLayer(String workspacePath, DatasourceConfig datasource) {
        // 构建验证请求
        ValidationRequest request = ValidationRequest.builder()
            .workspacePath(workspacePath)
            .datasource(datasource)
            .build();

        // 调用验证服务
        ValidationResponse response = validationClient.validate(request);

        if (!response.isSuccess()) {
            // 处理验证失败
            handleValidationErrors(response.getResults());
        }
    }

    private void handleValidationErrors(List<FileValidationResult> results) {
        for (FileValidationResult result : results) {
            if (!result.isSuccess()) {
                log.error("文件 {} 验证失败:", result.getFile());
                for (ValidationError error : result.getErrors()) {
                    log.error("  - {}: {}", error.getType(), error.getMessage());
                }
            }
        }
    }
}
```

### 7.2 curl 测试示例

```bash
# 验证语义层
curl -X POST http://localhost:8081/api/validation/validate \
  -H "Content-Type: application/json" \
  -d '{
    "workspacePath": "/workspace/user-123/session-1",
    "datasource": {
      "url": "jdbc:mysql://localhost:3306/demo",
      "username": "root",
      "password": "password",
      "driverClassName": "com.mysql.cj.jdbc.Driver"
    }
  }'

# 健康检查
curl http://localhost:8081/api/validation/health
```

## 8. 后续扩展

### 8.1 高级验证规则

- 字段长度验证
- 字段精度验证
- 外键约束验证
- 索引建议
- 性能优化建议

### 8.2 验证报告

- 生成详细的验证报告
- 导出为 HTML/PDF
- 验证历史记录

### 8.3 智能修复建议

- 根据错误类型提供修复建议
- 自动生成修复代码
- 字段映射推荐

---

**文档版本**: 1.0.0
**创建日期**: 2026-01-21
**作者**: Foggy Navigator Team
