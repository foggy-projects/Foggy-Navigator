# 缺失工具与依赖清单

**模块**: tutor-agent
**评估时间**: 2026-01-25

---

## 1. 依赖清单总览

### 1.1 已完成依赖

| 依赖项 | 提供方 | 状态 | 说明 |
|--------|--------|------|------|
| `AgentRegistry` | agent-framework | ✅ 已完成 | Agent注册管理 |
| `AgentConfigLoader` | agent-framework | ✅ 已完成 | 配置加载器 |
| `SessionManager` | agent-framework | ✅ 已完成 | 会话管理 |
| `ToolRegistry` | agent-framework | ✅ 已完成 | 工具注册 |
| `SkillManager` | agent-framework | ✅ 已完成 | Skill管理 |
| `SessionRouter` | agent-framework | ✅ 已完成 | 会话路由 |

### 1.2 缺失依赖（阻塞项）

| 依赖项 | 提供方 | 状态 | 优先级 | 影响 |
|--------|--------|------|--------|------|
| **ConfigurationService** | 配置管理模块 | ⏳ 待实现 | **P0** | **阻塞tutor-agent工具接口** |

---

## 2. 详细分析

### 2.1 ConfigurationService 依赖分析

**被依赖方**: `SystemConfigController`（tutor-agent的工具接口）

**依赖关系**:
```
SystemConfigController
    │
    ├─ checkDatasourceStatus() ──→ ConfigurationService.getDataSourceStatus()
    ├─ checkSemanticLayerStatus() ──→ ConfigurationService.getSemanticLayerStatus()
    └─ getConfigProgress() ──→ ConfigurationService.getOverallProgress()
```

**接口定义**:
```java
public interface ConfigurationService {
    ConfigStatus getDataSourceStatus();      // 查询数据源配置状态
    ConfigStatus getSemanticLayerStatus();   // 查询语义层配置状态
    ConfigProgress getOverallProgress();      // 查询整体配置进度
}
```

**实现方案**:

#### 方案1: 独立配置管理模块（推荐）

```
┌─────────────────────────────────────────────────────────────┐
│                    配置管理模块                              │
│  ┌───────────────────────────────────────────────────────┐  │
│  │  ConfigurationService 实现                            │  │
│  │  - 统一管理所有配置项                                  │  │
│  │  - 提供配置状态查询                                    │  │
│  │  - 追踪配置进度                                        │  │
│  └───────────────────────────────────────────────────────┘  │
│                          ↓                                   │
│  ┌───────────────────────────────────────────────────────┐  │
│  │  ConfigurationRepository (JPA)                        │  │
│  │  - datasource_configs 表                              │  │
│  │  - semantic_layer_configs 表                          │  │
│  │  - system_config_progress 表                          │  │
│  └───────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

**优点**:
- 配置状态集中管理
- 实现简单，tutor-agent直接依赖
- MVP阶段快速实现

**缺点**:
- 需要与业务模块同步状态

#### 方案2: 聚合服务

```
ConfigurationService (聚合)
    │
    ├─ 数据源管理模块.getStatus() ─→ 返回数据源状态
    └─ 语义层管理模块.getStatus() ─→ 返回语义层状态
```

**优点**:
- 解耦，各模块独立管理自己的状态
- 数据一致性更好

**缺点**:
- 需要等待业务模块先实现
- 实现较复杂

---

## 3. 推荐实现方案

### 3.1 Phase 1 (MVP): 独立配置管理模块

**原因**:
1. tutor-agent是Phase 1核心功能，需要快速验证
2. 业务模块（数据源、语义层）可能还在设计中
3. 配置管理模块可独立开发，不阻塞其他团队

**实现范围**:
- 数据源配置管理（CRUD + 状态查询）
- 语义层配置管理（CRUD + 状态查询）
- 配置进度追踪
- 提供ConfigurationService接口实现

**工期**: 2-3天

### 3.2 Phase 2: 集成业务模块

等业务模块（数据源管理、语义层管理）实现后：
- 配置管理模块作为聚合层
- 或业务模块直接实现ConfigurationService

---

## 4. 结论

### 4.1 缺失清单

**是的，只差配置管理模块！**

| 模块 | 状态 | 说明 |
|------|------|------|
| agent-framework | ✅ 已完成 | 提供Agent运行时能力 |
| tutor-agent (配置) | ✅ 已完成 | Agent配置 + Skills |
| tutor-agent (代码) | ⏳ 待开发 | 6个Java类，150行代码，3-4天 |
| **配置管理模块** | **⏳ 待设计+开发** | **ConfigurationService实现，2-3天** |

### 4.2 依赖关系

```
tutor-agent (SystemConfigController)
    ↓ 依赖
配置管理模块 (ConfigurationService)
    ↓ MVP阶段独立实现
    ↓ Phase 2集成
数据源管理模块 + 语义层管理模块
```

### 4.3 开发顺序建议

1. **配置管理模块**（2-3天）- 提供ConfigurationService实现
2. **tutor-agent代码**（3-4天）- 依赖配置管理模块
3. **集成测试**（1天）- 验证完整流程

**总工期**: 6-8天

---

## 5. 下一步

生成**配置管理模块设计文档**，包括：
- 模块定位与职责
- 接口定义
- 数据模型
- MVP实现范围
- 可观察性设计
- 测试要求

**需要我现在生成吗？**
