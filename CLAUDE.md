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

## JPA 使用规则

1. **自动建表**: 使用 `@Entity` 定义实体，JPA自动创建表结构，不维护任何建表SQL
2. **单体设计**: Entity间不使用 `@ManyToOne/@OneToMany/@ManyToMany`，使用外键字段(如 `String userId`)，在Service层组合查询
3. **精简代码**: 使用Lombok(`@Data`)，代码自解释，减少注释
4. **CLAUDE.md** 保持精简，复杂的信息可以放到外部文件，保留引用
5. **Form/DTO设计**: 接口参数使用Form/DTO而非Entity，遵循二层结构设计，详见 `/form-design` 技能