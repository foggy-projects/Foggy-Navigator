---
name: metadata-query-module
description: 元数据查询模块开发指导。当用户需要开发 metadata-query-module 的查询功能、添加新查询定义、扩展查询模型、编写单元测试时使用。触发词：/mq, /query-module, 提及"元数据查询"、"QueryService"、"DSL查询"。
---

# Metadata Query Module 开发指导

为 `metadata-query-module` 提供元数据统一查询服务的开发、扩展和测试指导。

## 使用场景

当用户需要以下操作时激活：
- 添加新的预定义查询（queryId）
- 扩展查询模型（QueryRequest、QueryResponse）
- 修改 MetadataQueryService 实现
- 编写或补充单元测试
- 添加新的查询参数定义

## 模块结构

```
metadata-query-module/
├── src/main/java/com/foggy/navigator/metadata/
│   ├── controller/
│   │   └── MetadataQueryController.java   # REST API 入口
│   ├── service/
│   │   ├── MetadataQueryService.java      # 查询服务接口
│   │   └── MetadataQueryServiceImpl.java  # 查询服务实现
│   ├── model/
│   │   ├── QueryRequest.java              # 完整DSL查询请求
│   │   ├── QueryResponse.java             # 查询响应
│   │   ├── QueryResult.java               # 查询结果
│   │   ├── QueryMetadata.java             # 结果元数据
│   │   ├── QueryInfo.java                 # 查询定义信息
│   │   ├── QueryParametersDefinition.java # 查询参数定义
│   │   ├── FieldDefinition.java           # 字段定义
│   │   ├── Pagination.java                # 分页参数
│   │   ├── SortCriteria.java              # 排序条件
│   │   └── Aggregation.java               # 聚合配置
│   └── config/
│       └── RestTemplateConfig.java        # HTTP客户端配置
├── src/main/resources/
│   └── application.yml
└── src/test/java/
    └── service/MetadataQueryServiceTest.java
```

## API 接口

### 简单查询（推荐）

```
GET  /api/metadata/query/{queryId}?param1=value1&param2=value2
POST /api/metadata/query/{queryId}/execute   Body: {"param1": "value1"}
```

### 高级 DSL 查询

```
POST /api/metadata/query/execute
Body: {
  "queryName": "datasource-latest",
  "filters": {"tenantId": "xxx", "status": "CONFIGURED"},
  "sort": [{"field": "createdAt", "direction": "DESC"}],
  "pagination": {"page": 0, "size": 20}
}
```

### 元数据接口

```
GET /api/metadata/query/available           # 获取可用查询列表
GET /api/metadata/query/{queryId}/parameters  # 获取查询参数定义
```

## 执行流程

### 1. 添加新的预定义查询

在 `MetadataQueryServiceImpl` 中添加查询定义：

```java
// 1. 在 QUERY_DEFINITIONS 中添加查询信息
static {
    QUERY_DEFINITIONS.put("new-query-id", QueryInfo.builder()
            .queryId("new-query-id")
            .name("新查询名称")
            .description("查询描述")
            .category("分类")
            .build());
}

// 2. 在 getQueryParameters 方法中添加参数定义
case "new-query-id":
    return QueryParametersDefinition.builder()
            .queryId(queryId)
            .parameters(List.of(
                    FieldDefinition.builder()
                            .name("paramName")
                            .type("string")
                            .required(true)
                            .description("参数描述")
                            .build()
            ))
            .build();

// 3. 在 handleSimpleQuery 方法中添加处理逻辑
case "new-query-id":
    return handleNewQuery(params);

// 4. 实现具体查询方法
private QueryResponse handleNewQuery(Map<String, Object> params) {
    String param = (String) params.get("paramName");
    // 调用后端服务获取数据
    List<Map<String, Object>> data = fetchData(param);
    return buildSuccessResponse(data);
}
```

### 2. 扩展查询模型

添加新的查询条件或返回字段：

```java
// 在 QueryRequest 中添加新字段
@Data
@Builder
public class QueryRequest {
    private String queryName;
    private Map<String, Object> filters;
    private List<SortCriteria> sort;
    private Pagination pagination;
    private List<Aggregation> aggregations;  // 新增聚合支持
    private List<String> fields;              // 字段选择
}
```

### 3. 编写单元测试

```java
@ExtendWith(MockitoExtension.class)
class MetadataQueryServiceTest {

    @Mock
    private RestTemplate restTemplate;

    @InjectMocks
    private MetadataQueryServiceImpl queryService;

    @Test
    void testExecuteSimpleQuery_Success() {
        // Given
        String queryId = "datasource-latest";
        Map<String, Object> params = Map.of("tenantId", "tenant-001");

        // Mock 后端响应
        when(restTemplate.exchange(anyString(), any(), any(), any(Class.class)))
                .thenReturn(ResponseEntity.ok(mockResponse()));

        // When
        QueryResponse response = queryService.executeSimpleQuery(queryId, params);

        // Then
        assertTrue(response.isSuccess());
        assertNotNull(response.getResult());
    }

    @Test
    void testGetAvailableQueries() {
        List<QueryInfo> queries = queryService.getAvailableQueries();

        assertFalse(queries.isEmpty());
        assertTrue(queries.stream().anyMatch(q -> "datasource-latest".equals(q.getQueryId())));
    }
}
```

## 约束条件

### 查询定义规则
- queryId 使用小写字母和连字符：`entity-action` 格式
- 每个 queryId 必须在三处定义：QUERY_DEFINITIONS、getQueryParameters、handleSimpleQuery
- 必需参数必须在 FieldDefinition 中标记 `required: true`

### 响应格式
- 统一使用 `RX<T>` 返回结构
- 成功返回 `RX.ok(result)`
- 失败返回 `RX.failB(errorMessage)`

### 代码规范
- Controller 使用 `@RequiredArgsConstructor` 构造器注入
- Service 方法必须有日志记录
- 所有外部调用需要异常处理

## 决策规则

- 如果查询需要分页 → 使用 Pagination 模型，默认 page=0, size=20
- 如果查询需要排序 → 使用 SortCriteria，支持多字段排序
- 如果查询需要聚合 → 扩展 Aggregation 模型
- 如果添加新查询 → 必须同步更新三处定义
- 如果修改模型 → 同时更新对应的测试用例

## 常用命令

```bash
# 编译模块
mvn compile -pl metadata-query-module -am

# 运行测试
mvn test -pl metadata-query-module

# 启动应用（开发环境）
mvn spring-boot:run -pl metadata-query-module
```

## 依赖关系

```
metadata-query-module
├── navigator-common    # 公共实体和工具类
├── navigator-spi       # SPI 接口定义
└── foggy-core         # 核心框架（RX 返回类型）
```
