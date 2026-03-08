# Foggy Navigator 项目结构

本文档描述 Foggy Navigator 项目的整体目录结构。

## 📁 目录结构

```
foggy-navigator/
├── README.md                          # 项目主 README
├── .gitignore                         # Git 忽略文件配置
├── LICENSE                            # 许可证文件
│
├── docs/                              # 文档目录
│   ├── README.md                      # 文档中心导航
│   │
│   ├── 01-overview/                   # 概览与规划
│   │   ├── requirements.md            # 需求文档
│   │   ├── system-architecture.md     # 系统架构
│   │   ├── business-architecture.md   # 业务架构（旧版）
│   │   ├── business-architecture-updated.md  # 业务架构（更新版）
│   │   └── mvp-roadmap.md             # MVP 路线图
│   │
│   ├── 02-modules/                    # 核心模块设计
│   │   ├── session-module.md          # 会话模块
│   │   ├── task-orchestration-module.md  # 任务编排模块
│   │   ├── memory-system.md           # 记忆系统
│   │   ├── memory-adapter-layer.md    # 记忆适配层
│   │   ├── rag-module.md              # RAG 模块
│   │   ├── tool-module.md             # 工具模块
│   │   ├── orchestration-layer.md     # 编排层
│   │   └── observability-system.md    # 可观察性系统
│   │
│   ├── 03-implementation/             # 实施指南
│   │   ├── development-setup.md       # 开发环境搭建
│   │   ├── code-style-guide.md        # 代码规范
│   │   ├── api-documentation.md       # API 文档
│   │   ├── database-design.md         # 数据库设计
│   │   └── deployment-guide.md        # 部署指南
│   │
│   └── 04-research/                   # 研究与调研
│       ├── open-source-analysis.md   # 开源产品调研
│       ├── technology-selection.md    # 技术选型分析
│       └── best-practices.md          # 最佳实践总结
│
├── backend/                           # 后端项目
│   ├── pom.xml                        # Maven 配置文件
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/
│   │   │   │   └── com/foggy/navigator/
│   │   │   │       ├── NavigatorApplication.java  # 启动类
│   │   │   │       │
│   │   │   │       ├── config/        # 配置类
│   │   │   │       │   ├── LangChain4jConfig.java
│   │   │   │       │   ├── DatabaseConfig.java
│   │   │   │       │   ├── RedisConfig.java
│   │   │   │       │   └── GitConfig.java
│   │   │   │       │
│   │   │   │       ├── orchestration/  # 编排层
│   │   │   │       │   ├── OrchestrationLayer.java
│   │   │   │       │   ├── RequestRouter.java
│   │   │   │       │   ├── SessionManager.java
│   │   │   │       │   ├── TaskOrchestrator.java
│   │   │   │       │   ├── MemoryCoordinator.java
│   │   │   │       │   ├── RAGCoordinator.java
│   │   │   │       │   ├── ToolExecutor.java
│   │   │   │       │   ├── ModelInvoker.java
│   │   │   │       │   └── ResponseGenerator.java
│   │   │   │       │
│   │   │   │       ├── agent/         # Agent 层
│   │   │   │       │   ├── Agent.java
│   │   │   │       │   ├── AgentDefinition.java
│   │   │   │       │   ├── MentorAgent.java
│   │   │   │       │   ├── CodingAgent.java
│   │   │   │       │   ├── DataAnalysisAgent.java
│   │   │   │       │   ├── MonitoringAgent.java
│   │   │   │       │   └── CustomAgent.java
│   │   │   │       │
│   │   │   │       ├── session/       # 会话模块
│   │   │   │       │   ├── Session.java
│   │   │   │       │   ├── SessionManager.java
│   │   │   │       │   ├── Message.java
│   │   │   │       │   ├── ContextManager.java
│   │   │   │       │   └── SessionStatus.java
│   │   │   │       │
│   │   │   │       ├── task/          # 任务编排模块
│   │   │   │       │   ├── Task.java
│   │   │   │       │   ├── TaskOrchestrator.java
│   │   │   │       │   ├── TaskExecutor.java
│   │   │   │       │   ├── TaskStack.java
│   │   │   │       │   └── TaskStatus.java
│   │   │   │       │
│   │   │   │       ├── memory/        # 记忆系统
│   │   │   │       │   ├── Memory.java
│   │   │   │       │   ├── ShortTermMemory.java
│   │   │   │       │   ├── LongTermMemory.java
│   │   │   │       │   ├── EpisodicMemory.java
│   │   │   │       │   ├── SemanticMemory.java
│   │   │   │       │   ├── MemoryAdapter.java
│   │   │   │       │   ├── MEM0Adapter.java
│   │   │   │       │   └── CustomMemoryAdapter.java
│   │   │   │       │
│   │   │   │       ├── rag/           # RAG 模块
│   │   │   │       │   ├── RAGService.java
│   │   │   │       │   ├── PreRetrieval.java
│   │   │   │       │   ├── PostRetrieval.java
│   │   │   │       │   ├── VectorStore.java
│   │   │   │       │   └── DocumentRetriever.java
│   │   │   │       │
│   │   │   │       ├── tool/          # 工具模块
│   │   │   │       │   ├── Tool.java
│   │   │   │       │   ├── ToolRegistry.java
│   │   │   │       │   ├── BuiltinTool.java
│   │   │   │       │   ├── MCPTool.java
│   │   │   │       │   ├── MCPClient.java
│   │   │   │       │   ├── HITLService.java
│   │   │   │       │   └── ToolPermission.java
│   │   │   │       │
│   │   │   │       ├── foundation/    # 基础层
│   │   │   │       │   ├── datasource/  # 数据源管理
│   │   │   │       │   │   ├── DataSource.java
│   │   │   │       │   │   ├── DataSourceManager.java
│   │   │   │       │   │   └── DataSourceConfig.java
│   │   │   │       │   ├── semantic/    # 语义层管理
│   │   │   │       │   │   ├── SemanticLayer.java
│   │   │   │       │   │   ├── SemanticLayerManager.java
│   │   │   │       │   │   └── JavaScriptRuntime.java
│   │   │   │       │   ├── git/         # Git 集成
│   │   │   │       │   │   ├── GitService.java
│   │   │   │       │   │   ├── GitRepository.java
│   │   │   │       │   │   ├── GitHubService.java
│   │   │   │       │   │   └── GitLabService.java
│   │   │   │       │   └── permission/  # 权限管理
│   │   │   │       │       ├── Permission.java
│   │   │   │       │       ├── Role.java
│   │   │   │       │       ├── User.java
│   │   │   │       │       └── PermissionManager.java
│   │   │   │       │
│   │   │   │       ├── observability/  # 可观察性系统
│   │   │   │       │   ├── MetricsCollector.java
│   │   │   │       │   ├── SessionMonitor.java
│   │   │   │       │   ├── TaskController.java
│   │   │   │       │   ├── AlertManager.java
│   │   │   │       │   └── Logger.java
│   │   │   │       │
│   │   │   │       ├── model/         # 数据模型
│   │   │   │       │   ├── entity/
│   │   │   │       │   ├── dto/
│   │   │   │       │   ├── vo/
│   │   │   │       │   └── enums/
│   │   │   │       │
│   │   │   │       ├── repository/    # 数据访问层
│   │   │   │       │   ├── AgentRepository.java
│   │   │   │       │   ├── SessionRepository.java
│   │   │   │       │   ├── TaskRepository.java
│   │   │   │       │   ├── UserRepository.java
│   │   │   │       │   └── ...
│   │   │   │       │
│   │   │   │       ├── service/       # 业务逻辑层
│   │   │   │       │   ├── AgentService.java
│   │   │   │       │   ├── SessionService.java
│   │   │   │       │   ├── TaskService.java
│   │   │   │       │   ├── MemoryService.java
│   │   │   │       │   ├── RAGService.java
│   │   │   │       │   ├── ToolService.java
│   │   │   │       │   └── ...
│   │   │   │       │
│   │   │   │       ├── controller/    # 控制器层
│   │   │   │       │   ├── AgentController.java
│   │   │   │       │   ├── SessionController.java
│   │   │   │       │   ├── TaskController.java
│   │   │   │       │   ├── QueryController.java
│   │   │   │       │   ├── SemanticLayerController.java
│   │   │   │       │   └── ...
│   │   │   │       │
│   │   │   │       ├── exception/     # 异常处理
│   │   │   │       │   ├── GlobalExceptionHandler.java
│   │   │   │       │   ├── BusinessException.java
│   │   │   │       │   └── ...
│   │   │   │       │
│   │   │   │       └── util/          # 工具类
│   │   │   │           ├── JsonUtil.java
│   │   │   │           ├── DateUtil.java
│   │   │   │           └── ...
│   │   │   │
│   │   │   └── resources/
│   │   │       ├── application.yml    # 应用配置
│   │   │       ├── application-dev.yml  # 开发环境配置
│   │   │       ├── application-test.yml # 测试环境配置
│   │   │       ├── application-prod.yml # 生产环境配置
│   │   │       ├── logback.xml        # 日志配置
│   │   │       └── db/
│   │   │           └── migration/      # 数据库迁移脚本
│   │   │
│   │   └── test/
│   │       └── java/
│   │           └── com/foggy/navigator/
│   │               ├── agent/
│   │               ├── session/
│   │               ├── task/
│   │               ├── memory/
│   │               ├── rag/
│   │               └── ...
│   │
│   └── docker/
│       ├── Dockerfile                  # Docker 镜像构建文件
│       └── docker-compose.yml          # Docker Compose 配置
│
├── frontend/                          # 前端项目
│   ├── package.json                   # npm 配置
│   ├── vite.config.js                 # Vite 配置
│   ├── tsconfig.json                  # TypeScript 配置
│   ├── index.html                     # HTML 入口文件
│   │
│   ├── src/
│   │   ├── main.tsx                    # 应用入口
│   │   ├── App.tsx                    # 根组件
│   │   │
│   │   ├── assets/                    # 静态资源
│   │   │   ├── images/
│   │   │   └── styles/
│   │   │
│   │   ├── components/                # 公共组件
│   │   │   ├── Layout/
│   │   │   ├── Chat/
│   │   │   ├── Query/
│   │   │   ├── SemanticLayer/
│   │   │   ├── Agent/
│   │   │   └── ...
│   │   │
│   │   ├── pages/                     # 页面
│   │   │   ├── Home/
│   │   │   ├── Query/
│   │   │   ├── SemanticLayer/
│   │   │   ├── Agent/
│   │   │   ├── Session/
│   │   │   ├── Task/
│   │   │   ├── Settings/
│   │   │   └── ...
│   │   │
│   │   ├── services/                  # API 服务
│   │   │   ├── api.ts
│   │   │   ├── agent.ts
│   │   │   ├── session.ts
│   │   │   ├── query.ts
│   │   │   └── ...
│   │   │
│   │   ├── store/                     # 状态管理
│   │   │   ├── index.ts
│   │   │   ├── modules/
│   │   │   │   ├── user.ts
│   │   │   │   ├── session.ts
│   │   │   │   └── ...
│   │   │
│   │   ├── types/                     # TypeScript 类型定义
│   │   │   ├── agent.ts
│   │   │   ├── session.ts
│   │   │   └── ...
│   │   │
│   │   ├── utils/                     # 工具函数
│   │   │   ├── request.ts
│   │   │   ├── format.ts
│   │   │   └── ...
│   │   │
│   │   └── router/                    # 路由配置
│   │       └── index.tsx
│   │
│   ├── public/                        # 公共静态资源
│   │   └── favicon.ico
│   │
│   └── tests/                         # 测试文件
│       └── ...
│
├── scripts/                           # 脚本文件
│   ├── setup.sh                       # 环境搭建脚本
│   ├── build.sh                       # 构建脚本
│   ├── deploy.sh                      # 部署脚本
│   └── test.sh                        # 测试脚本
│
├── docker/                            # Docker 配置
│   ├── docker-compose.yml             # Docker Compose 配置
│   ├── docker-compose.dev.yml         # 开发环境配置
│   ├── docker-compose.prod.yml        # 生产环境配置
│   └── nginx/
│       └── nginx.conf                 # Nginx 配置
│
├── kubernetes/                        # Kubernetes 配置（可选）
│   ├── namespace.yaml
│   ├── deployment.yaml
│   ├── service.yaml
│   ├── ingress.yaml
│   └── configmap.yaml
│
└── .github/                           # GitHub 配置
    └── workflows/
        ├── ci.yml                     # CI 工作流
        ├── cd.yml                     # CD 工作流
        └── release.yml                # 发布工作流
```

## 📝 目录说明

### 根目录

- `README.md` - 项目主 README，包含项目简介、快速开始、技术栈等
- `.gitignore` - Git 忽略文件配置
- `LICENSE` - 项目许可证

### docs/ - 文档目录

- `01-overview/` - 概览与规划文档
- `02-modules/` - 核心模块设计文档
- `03-implementation/` - 实施指南文档
- `04-research/` - 研究与调研文档

### backend/ - 后端项目

基于 Spring Boot 的 Java 后端项目，包含：

- `src/main/java/` - Java 源代码
  - `config/` - 配置类
  - `orchestration/` - 编排层
  - `agent/` - Agent 层
  - `session/` - 会话模块
  - `task/` - 任务编排模块
  - `memory/` - 记忆系统
  - `rag/` - RAG 模块
  - `tool/` - 工具模块
  - `foundation/` - 基础层
  - `observability/` - 可观察性系统
  - `model/` - 数据模型
  - `repository/` - 数据访问层
  - `service/` - 业务逻辑层
  - `controller/` - 控制器层
  - `exception/` - 异常处理
  - `util/` - 工具类

- `src/main/resources/` - 资源文件
  - `application.yml` - 应用配置
  - `logback.xml` - 日志配置
  - `db/migration/` - 数据库迁移脚本

- `src/test/` - 测试代码

### frontend/ - 前端项目

基于 React/Vue 的前端项目，包含：

- `src/` - 源代码
  - `components/` - 公共组件
  - `pages/` - 页面
  - `services/` - API 服务
  - `store/` - 状态管理
  - `types/` - TypeScript 类型定义
  - `utils/` - 工具函数
  - `router/` - 路由配置

### scripts/ - 脚本文件

- `setup.sh` - 环境搭建脚本
- `build.sh` - 构建脚本
- `deploy.sh` - 部署脚本
- `test.sh` - 测试脚本

### docker/ - Docker 配置

- `docker-compose.yml` - Docker Compose 配置
- `docker-compose.dev.yml` - 开发环境配置
- `docker-compose.prod.yml` - 生产环境配置
- `nginx/` - Nginx 配置

### kubernetes/ - Kubernetes 配置（可选）

- `namespace.yaml` - 命名空间配置
- `deployment.yaml` - 部署配置
- `service.yaml` - 服务配置
- `ingress.yaml` - 入口配置
- `configmap.yaml` - 配置映射

### .github/ - GitHub 配置

- `workflows/` - GitHub Actions 工作流
  - `ci.yml` - CI 工作流
  - `cd.yml` - CD 工作流
  - `release.yml` - 发布工作流

## 🚀 快速开始

### 1. 克隆项目

```bash
git clone https://github.com/your-org/foggy-navigator.git
cd foggy-navigator
```

### 2. 后端开发

```bash
cd backend
mvn clean install
mvn spring-boot:run
```

### 3. 前端开发

```bash
cd frontend
npm install
npm run dev
```

### 4. Docker 部署

```bash
docker-compose up -d
```

## 📚 更多信息

- [文档中心](./docs/README.md) - 查看完整文档
- [需求文档](./docs/01-overview/requirements.md) - 了解系统需求
- [系统架构](./docs/01-overview/system-architecture.md) - 了解系统架构
- [MVP 路线图](./docs/01-overview/mvp-roadmap.md) - 了解实施计划

---

**Foggy Navigator** - 让数据查询更智能 🚀
