---
name: user-memory
description: User Memory 用户长期记忆模块开发指导。当用户需要迭代记忆功能、添加新记忆类型、修改记忆注入逻辑、扩展记忆工具、或修复记忆相关bug时使用。触发词：/user-memory, /memory, 提及"用户记忆"、"长期记忆"、"User Memory"、"save_memory"。
---

# User Memory 模块开发指导

用户长期记忆（User Facts）模块的开发、迭代和维护指导。

## 架构概述

```
DB: user_memories (userId, category, content, source)
     ↓ UserMemoryManager SPI
DefaultAgentInvoker.doInvoke() step 3.5 ← 注入用户记忆到 system prompt
     ↓
Agent (LLM) → save_memory BuiltInTool → UserMemoryManager.save()
     ↓
前端 Settings → 记忆管理 tab → REST API CRUD
```

三条数据通路：
1. **读取注入**：Agent 对话时，DefaultAgentInvoker 从 DB 加载记忆 → 拼接到 system prompt
2. **自动保存**：LLM 调用 save_memory 工具 → UserMemoryManager.saveMemory(source=AUTO)
3. **手动管理**：用户在前端 Settings 页 CRUD → PlatformConfigController → UserMemoryManager

## 文件清单

### 后端

| 层 | 文件 | 路径 |
|---|------|------|
| Entity | `UserMemoryEntity.java` | `navigator-common/.../entity/` |
| Enums | `UserMemoryCategory.java` | `navigator-common/.../enums/` |
| Enums | `UserMemorySource.java` | `navigator-common/.../enums/` |
| DTO | `UserMemoryDTO.java` | `navigator-common/.../dto/` |
| Form | `UserMemoryForm.java` | `navigator-common/.../form/` |
| SPI | `UserMemoryManager.java` | `navigator-spi/.../memory/` |
| Repository | `UserMemoryRepository.java` | `metadata-config-module/.../repository/` |
| Service | `UserMemoryManagerImpl.java` | `metadata-config-module/.../service/` |
| Controller | `PlatformConfigController.java` | `metadata-config-module/.../controller/`（记忆管理段） |
| BuiltInTool | `SaveMemoryTool.java` | `agent-framework/.../tool/builtin/` |
| BuiltInTool | `DeleteMemoryTool.java` | `agent-framework/.../tool/builtin/` |
| Invoker 注入 | `DefaultAgentInvoker.java` | `agent-framework/.../core/impl/`（step 2 + step 3.5） |
| AutoConfig | `AgentFrameworkAutoConfiguration.java` | `agent-framework/.../config/` |

### 前端

| 文件 | 改动 |
|------|------|
| `packages/navigator-frontend/src/types/index.ts` | UserMemory, UserMemoryForm 类型 |
| `packages/navigator-frontend/src/api/platform.ts` | listMemories, saveMemory, updateMemory, deleteMemory |
| `packages/navigator-frontend/src/views/SettingsView.vue` | Tab 5「记忆管理」+ Memory Dialog |

### 测试

| 文件 | 说明 |
|------|------|
| `MemoryToolsTest.java` | SaveMemoryTool / DeleteMemoryTool / ListMemoryTool 单元测试（14 cases） |
| `DefaultAgentInvokerTest.java` | 构造函数含 UserMemoryManager 参数（null） |
| `DelegationChainTest.java` | 构造函数含 UserMemoryManager 参数（null） |

## 关键设计决策

### SPI 解耦
- `UserMemoryManager` 定义在 `navigator-spi`，实现在 `metadata-config-module`
- agent-framework 通过 `@Nullable` 注入，metadata-config-module 不存在时自动降级
- AutoConfiguration 中 `@Autowired(required = false)`

### System Prompt 注入位置
- `DefaultAgentInvoker.doInvoke()` 中 step 2 提前获取 userId/tenantId
- step 3.5 调用 `buildMemoryContext(userId)` 拼接到 enhancedSystemPrompt 末尾
- 格式：Markdown，按 category 分组，最多 50 条

### SaveMemoryTool 设计
- name: `save_memory`
- parameters: `category`（enum，可选，默认 FACT）+ `content`（必填）
- source 固定为 `AUTO`
- UserMemoryManager 为 null 时返回 `MEMORY_UNAVAILABLE` 错误
- enum 值必须用 `List.of()` 而非 `new String[]{}`（避免 LangChain4j 序列化 ClassCastException）

### DeleteMemoryTool 设计
- name: `delete_memory`
- parameters: `keyword`（必填，按内容关键词匹配删除）
- 大小写不敏感匹配，可能删除多条

### ListMemoryTool 设计
- name: `list_memory`
- 无参数，列出当前用户所有记忆
- 返回按 category 标注的列表文本

### 记忆去重
- `saveMemory()` 保存前检查用户已有记忆，`equalsIgnoreCase` + `trim()` 完全匹配时跳过
- 返回已有记忆的 ID，不创建新记录

### 记忆条数上限
- `MAX_MEMORIES_PER_USER = 200`，保存后自动淘汰最旧记忆
- `evictOldestIfOverLimit()` 按 updatedAt 升序查询，删除超出部分

### 前端 Tab 位置
- SettingsView.vue 第 5 个 tab（Git → AI 模型 → Agent 模型 → 记忆管理 → Claude Workers）

## 执行流程

### 添加新记忆类型（如 SKILL、CONTEXT）

1. `UserMemoryCategory.java` 添加新枚举值 + 中文描述
2. `SaveMemoryTool.getDescription()` 更新描述文本，说明新类别用途
3. `SaveMemoryTool.getParameters()` 更新 enum 数组
4. `UserMemoryManagerImpl.buildMemoryContext()` 自动按 category 分组，无需改动
5. 前端 `SettingsView.vue` Memory Dialog 的 `<el-select>` 添加新选项
6. 前端 `memoryCategoryLabel()` + `memoryCategoryTag()` 添加映射
7. 编译验证：`mvn compile -pl launcher -am -DskipTests`

### 添加新记忆工具（如 delete_memory、list_memory）

1. `agent-framework/.../tool/builtin/` 新建工具类
2. 实现 `BuiltInTool` 接口（getName/getDescription/getParameters/execute）
3. 注入 `@Nullable UserMemoryManager`
4. 从 `ToolExecutionRequest` 取 userId/tenantId
5. 调用 UserMemoryManager SPI 方法
6. `@Component` 注解自动注册，无需改 AutoConfiguration
7. 运行测试：`mvn test -pl agent-framework -am`

### 修改记忆注入逻辑

1. 修改 `UserMemoryManagerImpl.buildMemoryContext()`
2. 或修改 `DefaultAgentInvoker.doInvoke()` step 3.5
3. 注意：buildMemoryContext 返回 null 时不注入（空记忆场景）
4. 注意：MAX_MEMORIES_IN_CONTEXT = 50，可按需调整

### 扩展 SPI 接口

1. `UserMemoryManager.java` 添加新方法
2. `UserMemoryManagerImpl.java` 实现
3. 如需新 Repository 查询 → `UserMemoryRepository.java` 添加方法
4. 如需 REST API → `PlatformConfigController.java` 添加端点
5. 如需前端调用 → `api/platform.ts` + `SettingsView.vue`

### 修复 bug

1. 确认影响层：Entity/SPI/Service/Controller/Tool/Invoker/Frontend
2. 修改代码
3. 运行测试：`mvn test -pl agent-framework -am`
4. 编译验证：`mvn compile -pl launcher -am -DskipTests`

## 约束条件

- **JPA 单体设计**：Entity 间无关联注解，用外键字段 + Service 组合
- **Form/DTO 分离**：Controller 入参用 Form，返回用 DTO，不暴露 Entity
- **统一返回**：Controller 返回 `RX<T>`，成功 `RX.ok(data)`，失败 `RX.throwB(msg)`
- **@Nullable 降级**：所有 agent-framework 对 UserMemoryManager 的引用必须判空
- **构造函数同步**：修改 DefaultAgentInvoker 构造函数后，同步更新 AutoConfiguration + 两个测试类
- **枚举 String 映射**：Entity 中 `@Enumerated(EnumType.STRING)`，DB 存字符串

## 决策规则

- 如果新功能只涉及记忆内容处理 → 改 `UserMemoryManagerImpl`
- 如果新功能涉及 Agent 行为 → 改 `DefaultAgentInvoker` 或新增 BuiltInTool
- 如果新功能涉及数据模型变更 → 从 Entity → DTO → Form → SPI 逐层改
- 如果需要新的查询方式 → `UserMemoryRepository` 添加 Spring Data 方法
- 如果修改构造函数参数 → 必须同步改 AutoConfiguration + 测试类

## 数据库表结构

```sql
CREATE TABLE user_memories (
    id          VARCHAR(64) PRIMARY KEY,
    user_id     VARCHAR(64) NOT NULL,
    tenant_id   VARCHAR(64) NOT NULL,
    category    VARCHAR(20) NOT NULL,  -- PREFERENCE, FACT, NOTE
    content     TEXT NOT NULL,
    source      VARCHAR(20) NOT NULL,  -- AUTO, MANUAL
    created_at  TIMESTAMP NOT NULL,
    updated_at  TIMESTAMP NOT NULL
);
CREATE INDEX idx_um_user_id ON user_memories(user_id);
CREATE INDEX idx_um_user_category ON user_memories(user_id, category);
```

## REST API

| Method | Path | 说明 |
|--------|------|------|
| GET | `/api/v1/config/platform/memories` | 列出当前用户所有记忆 |
| POST | `/api/v1/config/platform/memories` | 创建记忆（source=MANUAL） |
| PUT | `/api/v1/config/platform/memories/{id}` | 更新记忆 |
| DELETE | `/api/v1/config/platform/memories/{id}` | 删除记忆 |

## buildMemoryContext 输出格式

```markdown
## User Memory

以下是关于当前用户的长期记忆，请在回答时参考这些信息：

### 偏好
- I prefer TypeScript over Python

### 事实
- My name is Zhang San
```
