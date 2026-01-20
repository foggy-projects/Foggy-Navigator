# Foggy Navigator

> 基于 LangChain4j 的企业级动态 Agent 编排系统

## 📖 项目简介

Foggy Navigator 是一个企业级的动态 Agent 编排系统，旨在通过自然语言查询实现智能数据分析。系统基于 LangChain4j 和 Spring Boot 构建，支持多 Agent 协作、动态任务编排、Git 集成、语义层管理等核心功能。

### 核心特性

- 🤖 **多 Agent 协作**：导师 Agent、编程 Agent、数据分析 Agent、监控 Agent 等多种角色
- 🔄 **动态任务编排**：支持任务分解、动态调整、中断恢复
- 💾 **智能记忆系统**：短期/长期记忆、向量存储、记忆压缩
- 🔍 **RAG 检索增强**：前置/后置检索策略，提升查询准确性
- 🛠️ **工具集成**：内置工具 + MCP 协议外部工具
- ✅ **HITL 人工确认**：关键操作需要人工确认，确保安全
- 📊 **Git 集成**：支持代码版本管理、PR/MR 流程
- 🎯 **语义层管理**：JavaScript 语义层，支持动态修改
- 👁️ **可观察性**：实时监控、日志追踪、任务控制

## 🏗️ 系统架构

```
用户 → Web UI → API Gateway → Orchestration Layer → Agent Layer → Foundation Layer → Data Layer
```

详细架构请查看：[系统架构文档](./docs/01-overview/system-architecture.md)

## 📚 文档中心

完整文档请查看：[文档中心](./docs/README.md)

### 快速导航

| 文档 | 说明 |
|------|------|
| [需求文档](./docs/01-overview/requirements.md) | 系统需求分析、功能清单、技术栈 |
| [系统架构](./docs/01-overview/system-architecture.md) | 整体系统架构、模块依赖、部署架构 |
| [业务架构](./docs/01-overview/business-architecture-updated.md) | 业务架构设计、核心流程、Agent 设计 |
| [MVP 路线图](./docs/01-overview/mvp-roadmap.md) | 最小可行产品、分阶段实施计划 |
| [核心模块设计](./docs/02-modules/) | 各核心模块的详细设计文档 |

## 🚀 快速开始

### 环境要求

- JDK 17+
- Maven 3.8+
- MySQL 8.0+ / PostgreSQL 14+
- Redis 7.0+
- Node.js 18+ (前端开发)
- GitLab/GitHub (代码托管)

### 安装步骤

```bash
# 克隆项目
git clone https://github.com/your-org/foggy-navigator.git
cd foggy-navigator

# 配置数据库
# 修改 src/main/resources/application.yml 中的数据库配置

# 启动后端服务
mvn spring-boot:run

# 启动前端服务（可选）
cd frontend
npm install
npm run dev
```

### 配置说明

1. **数据库配置**：修改 `application.yml` 中的数据库连接信息
2. **LLM 配置**：配置 OpenAI 或其他 LLM 服务的 API Key
3. **Git 配置**：配置 GitLab/GitHub 的访问令牌
4. **Redis 配置**：配置 Redis 连接信息

详细配置请查看：[部署指南](./docs/03-implementation/deployment-guide.md)

## 🎯 核心功能

### 1. 自然语言查询

用户可以通过自然语言查询数据，系统自动理解意图并生成 SQL。

```
用户：查询最近一个月的销售额
系统：自动生成 SQL → 执行查询 → 返回结果
```

### 2. 语义层管理

管理员可以通过编程 Agent 修改语义层 JavaScript 代码，系统自动完成 Git 操作。

```
管理员：修改客户实体的定义
系统：克隆项目 → 创建分支 → 修改代码 → 测试 → 提交 → 创建 PR → 合并
```

### 3. 多 Agent 协作

不同 Agent 各司其职，协同完成复杂任务。

- **导师 Agent**：配置检查、任务规划、Agent 推荐
- **编程 Agent**：Git 操作、代码修改、代码测试
- **数据分析 Agent**：NLU、SQL 生成、数据查询
- **监控 Agent**：Schema 监控、变化检测、通知提醒

### 4. 任务编排

支持动态任务调整，可以中断、恢复、重试任务。

```
任务 A → 任务 B → 任务 C
         ↓ (发现需要执行任务 D)
      任务 D → 任务 E → 任务 C
```

## 🛠️ 技术栈

### 后端

- **框架**：Spring Boot 3.x
- **AI 框架**：LangChain4j 1.x
- **数据库**：MySQL / PostgreSQL
- **缓存**：Redis
- **Git 集成**：JGit / GitHub API / GitLab API
- **JavaScript 运行时**：GraalVM JavaScript / Node.js
- **测试**：JUnit 5, Mockito

### 前端

- **框架**：React / Vue.js
- **UI 库**：Ant Design / Element Plus
- **状态管理**：Redux / Pinia
- **构建工具**：Vite

### 基础设施

- **容器化**：Docker, Docker Compose
- **监控**：Prometheus, Grafana
- **日志**：ELK Stack
- **CI/CD**：GitHub Actions / GitLab CI

## 📊 项目进度

### Phase 1: MVP（8-10 周）

- [ ] Week 1-2: 基础设施搭建
- [ ] Week 3-4: 核心功能开发
- [ ] Week 5-6: 用户功能开发
- [ ] Week 7-8: 测试和优化
- [ ] Week 9-10: 部署和运维

详细计划请查看：[MVP 路线图](./docs/01-overview/mvp-roadmap.md)

## 🤝 贡献指南

欢迎贡献代码、文档、Bug 报告和功能建议！

### 开发流程

1. Fork 本仓库
2. 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 创建 Pull Request

### 代码规范

- 遵循 [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html)
- 使用 Checkstyle 进行代码检查
- 编写单元测试，覆盖率不低于 80%
- 提交前运行 `mvn clean test`

详细规范请查看：[代码规范](./docs/03-implementation/code-style-guide.md)

## 📄 许可证

本项目采用 MIT 许可证 - 详见 [LICENSE](LICENSE) 文件

## 📧 联系方式

- 项目主页：[https://github.com/your-org/foggy-navigator](https://github.com/your-org/foggy-navigator)
- 问题反馈：[GitHub Issues](https://github.com/your-org/foggy-navigator/issues)
- 邮箱：team@foggy-navigator.com

## 🙏 致谢

感谢以下开源项目：

- [LangChain4j](https://github.com/langchain4j/langchain4j) - Java LLM 框架
- [Spring Boot](https://github.com/spring-projects/spring-boot) - Java 应用框架
- [Dify](https://github.com/langgenius/dify) - LLM 应用开发平台
- [LangGraph](https://github.com/langchain-ai/langgraph) - Agent 编排框架
- [MetaGPT](https://github.com/geekan/MetaGPT) - 多 Agent 协作框架

---

**Foggy Navigator** - 让数据查询更智能 🚀
