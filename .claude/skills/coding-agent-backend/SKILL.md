---
name: coding-agent-backend
description: Coding Agent 后端服务开发指导。当用户需要开发 coding-agent 模块的 Java 后端功能、编写单元测试、修改配置、实现新服务时使用。触发词：/ca-backend, /dev-backend, 提及"后端开发"、"服务实现"、"单元测试"。
---

# Coding Agent 后端开发指导

为 `addons/coding-agent` 模块提供 Java 后端服务开发、单元测试编写、配置管理的标准化指导。

## 使用场景

当用户需要以下操作时激活：
- 实现新的 Service 或 Controller
- 修改现有服务逻辑
- 编写或补充单元测试
- 添加或修改配置参数
- 创建新的 Entity 或 Repository
- 优化性能或修复 Bug

## 模块结构

```
addons/coding-agent/
├── src/main/java/com/foggy/navigator/
│   ├── api/
│   │   ├── controller/     # REST 控制器
│   │   ├── service/        # 业务服务层
│   │   ├── model/          # DTO 和领域模型
│   │   │   └── entity/     # JPA 实体
│   │   ├── repository/     # 数据访问层
│   │   ├── listener/       # 事件监听器
│   │   └── sse/            # SSE 事件推送
│   └── foundation/
│       └── git/            # Git 和容器管理
│           ├── config/     # 模块配置
│           └── model/      # 基础模型
├── src/main/resources/
│   └── application.yml     # 配置文件
└── src/test/java/          # 单元测试
```

## 执行流程

### 1. 需求分析

1. 明确功能需求和影响范围
2. 确定需要修改的文件列表
3. 检查是否有现有的类似实现可参考

### 2. 代码实现

#### 创建新 Service

```java
@Service
@Slf4j
public class XxxService {

    @Autowired
    private XxxRepository xxxRepository;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Value("${foggy.coding-agent.xxx.enabled:true}")
    private boolean enabled;

    @Transactional
    public Xxx doSomething(String param) {
        log.info("执行操作: param={}", param);

        // 业务逻辑

        // 发布事件
        Event event = Event.builder()
                .conversationId(conversationId)
                .kind(Event.EventKind.XXX)
                .data(Map.of("key", "value"))
                .build();
        eventPublisher.publishEvent(event);

        return result;
    }
}
```

#### 创建新 Entity

```java
@Entity
@Table(name = "xxx")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class XxxEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "xxx_id", nullable = false, unique = true)
    private String xxxId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private XxxStatus status;

    public enum XxxStatus {
        PENDING, ACTIVE, COMPLETED, ERROR
    }
}
```

#### 创建新 Repository

```java
@Repository
public interface XxxRepository extends JpaRepository<XxxEntity, Long> {

    Optional<XxxEntity> findByXxxId(String xxxId);

    boolean existsByXxxId(String xxxId);

    List<XxxEntity> findByStatus(XxxEntity.XxxStatus status);

    List<XxxEntity> findByCreatedAtBefore(LocalDateTime cutoffTime);

    void deleteByXxxId(String xxxId);
}
```

### 3. 单元测试

每个 Service 必须有对应的单元测试：

```java
@ExtendWith(MockitoExtension.class)
class XxxServiceTest {

    @Mock
    private XxxRepository xxxRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private XxxService xxxService;

    @BeforeEach
    void setUp() {
        // 使用 ReflectionTestUtils 设置 @Value 字段
        ReflectionTestUtils.setField(xxxService, "enabled", true);
    }

    @Test
    void testDoSomething_Success() {
        // Given
        String param = "test-param";
        when(xxxRepository.findByXxxId(anyString()))
                .thenReturn(Optional.of(XxxEntity.builder().build()));

        // When
        Xxx result = xxxService.doSomething(param);

        // Then
        assertNotNull(result);
        verify(xxxRepository).findByXxxId(param);
        verify(eventPublisher).publishEvent(any(Event.class));
    }

    @Test
    void testDoSomething_NotFound() {
        // Given
        when(xxxRepository.findByXxxId(anyString()))
                .thenReturn(Optional.empty());

        // When & Then
        assertThrows(NotFoundException.class, () -> {
            xxxService.doSomething("non-existent");
        });
    }
}
```

### 4. 配置管理

在 `application.yml` 中添加配置：

```yaml
foggy:
  coding-agent:
    xxx:
      enabled: true
      timeout: 30000  # 毫秒
      max-retries: 3
```

### 5. 验证

1. 运行单元测试：`mvn test -Dtest=XxxServiceTest`
2. 运行所有单元测试：`mvn test`
3. 检查编译：`mvn compile`

## 约束条件

### JPA 规则
- 使用 `@Entity` 定义实体，JPA 自动创建表结构
- Entity 间不使用 `@ManyToOne/@OneToMany/@ManyToMany`
- 使用外键字段（如 `String userId`），在 Service 层组合查询
- 使用 Lombok (`@Data`, `@Builder`) 简化代码

### 代码规范
- Service 类使用 `@Slf4j` 记录日志
- 所有公开方法必须有日志记录
- 异步操作使用 `@Async` 注解并指定线程池
- 事件发布使用 `ApplicationEventPublisher`
- 配置参数使用 `@Value` 注入，提供默认值

### 测试规范
- 每个 Service 方法至少有 2 个测试用例（成功和失败）
- 使用 Mockito mock 所有外部依赖
- 使用 `ReflectionTestUtils` 设置 `@Value` 字段
- 测试方法命名：`test{方法名}_{场景}`

## 决策规则

- 如果需要异步执行 → 使用 `@Async("eventPublisherExecutor")` 或 `@Async("validationExecutor")`
- 如果需要定时任务 → 使用 `@Scheduled` 注解，配置 `@ConditionalOnProperty`
- 如果需要发布事件 → 使用 `ApplicationEventPublisher.publishEvent()`
- 如果操作涉及数据库事务 → 使用 `@Transactional`
- 如果需要外部配置 → 添加到 `application.yml` 并使用 `@Value` 注入
- 如果修改了 Service → 同时更新或创建对应的单元测试

## 常用命令

```bash
# 运行指定测试
cd addons/coding-agent && mvn test -Dtest=XxxServiceTest

# 运行所有单元测试（排除集成测试）
cd addons/coding-agent && mvn test -Dtest="*ServiceTest,*ControllerTest"

# 编译检查
cd addons/coding-agent && mvn compile

# 打包
cd addons/coding-agent && mvn package -DskipTests
```

## 参考文件

详细的技术参考请查看：
- [reference.md](./reference.md) - 技术规范和 API 参考
- [module-structure.md](./module-structure.md) - 模块架构详解
