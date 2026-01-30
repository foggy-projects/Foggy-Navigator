# Claude AI 开发指南

Foggy Navigator - 基于 LangChain4j 的企业级动态 Agent 编排系统。

## Maven 模块结构

```
Foggy-Navigator/
├── addons/
│   ├── coding-agent/      # Coding Agent服务 - 提供代码编写、Git操作、环境管理能力
│   └── openhands/         # OpenHands集成 - 研究和验证模块
├── docker/                # 开发环境 - MySQL(13309)、GitLab、phpMyAdmin
└── docs/                  # 项目文档
```

### coding-agent 模块
- **用途**: 对话式编程助手，管理会话、消息、环境
- **技术栈**: Spring Boot 3.x + JPA + Docker Java Client
- **API**: REST接口 + SSE实时流
- **测试**: JUnit单元测试(H2) + Vitest集成测试(Docker MySQL)

## 项目启动

### 快速启动（推荐）

```powershell
# 根目录执行启动脚本（自动构建+启动）
powershell -ExecutionPolicy Bypass -File start-launcher.ps1
```

启动脚本会自动：
1. 杀掉旧的 Java 进程
2. 执行 `mvn clean package -DskipTests`
3. 启动服务（端口 8112）
4. 健康检查

### 手动启动

```bash
# 1. 构建项目
mvn clean package -pl launcher -am -DskipTests

# 2. 启动服务
"C:\Program Files\Java\jdk-17.0.1\bin\java.exe" -jar launcher/target/launcher-1.0.0-SNAPSHOT.jar --spring.profiles.active=docker
```

### 验证

```bash
# 健康检查
curl http://localhost:8112/actuator/health

# 登录测试
curl -X POST http://localhost:8112/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"root","password":"root123"}'
```

## 重要配置

### 环境配置文件

- `application.yml` - 主配置
- `application-docker.yml` - Docker环境配置（包含LLM API配置，**已加入.gitignore**）
- 模板文件：`application-docker.yml.example`

### LLM 配置

编辑 `launcher/src/main/resources/application-docker.yml`（本地不会提交）：

```yaml
foggy:
  coding-agent:
    openhands:
      api-key: your-api-key
      model-name: glm-4.7
      api-base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
```

### 日志文件

- 输出日志：`logs/backend.log`
- 错误日志：`logs/backend-error.log`

## JPA 使用规则

1. **自动建表**: 使用 `@Entity` 定义实体，JPA自动创建表结构，不维护任何建表SQL
2. **单体设计**: Entity间不使用 `@ManyToOne/@OneToMany/@ManyToMany`，使用外键字段(如 `String userId`)，在Service层组合查询
3. **精简代码**: 使用Lombok(`@Data`)，代码自解释，减少注释
4. **CLAUDE.md** 保持精简，复杂的信息可以放到外部文件，保留引用
5. **Form/DTO设计**: 接口参数使用Form/DTO而非Entity，遵循二层结构设计，详见 `/form-design` 技能
6. **统一返回对象**: Controller返回使用 `RX<T>`（位于 `com.foggyframework.core.ex.RX`），成功用 `RX.ok(data)`，失败用 `RX.failA/B/C(msg)`

### 需求记录与确认

当用户要求记录需求时，必须：
1. 在 `docs/requirement-tracker/YYYY-QX/` 目录下创建需求文档
2. 文件命名格式：`DD-需求简述.md`（如：`2026-Q1/27-订单超时自动取消.md`）
3. 理解并整理完需求后，等待用户确认
4. ✅ **用户确认后才能开始开发**