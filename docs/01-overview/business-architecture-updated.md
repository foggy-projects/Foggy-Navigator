# Foggy Navigator 业务架构设计方案（更新版）

> 历史参考文档
>
> 本文档仍以“数据分析 / 语义层平台”为主线，和当前代码实现已不一致。
> 当前系统说明请优先参考：
> - [系统架构概览](../00-system-overview.md)
> - [功能架构说明](../02-modules/functional-architecture.md)

## 1. 业务背景和定位

### 1.1 项目定位

Foggy Navigator 是一个基于 AI 驱动的数据分析平台，核心价值在于：

- **自动化语义层管理**：通过编程 Agent 自动修改和维护语义层（JavaScript 文件）
- **智能引导**：通过"导师"Agent 引导管理员快速上手
- **灵活配置**：Agent 通过配置而非定制开发，支持灵活扩展
- **版本管理**：使用 Git 管理语义层文件，支持版本控制和回滚
- **编程能力**：擅长编程的 Agent 可以修改代码、创建分支、测试、提交合并

### 1.2 核心价值

**对管理员**：
- 快速上手：导师 Agent 引导完成系统配置
- 自动化：编程 Agent 自动修改语义层代码
- 安全可控：Git 版本管理 + 分支测试
- 智能提醒：定时监控数据库变化，主动提醒更新

**对用户**：
- 自然语言查询：基于语义层的 AI 数据分析
- 准确理解：语义层提供准确的业务含义
- 权限控制：基于语义层的精细化权限管理

### 1.3 业务模式

```
┌─────────────────────────────────────────────────────────────┐
│                    Foggy Navigator 平台                    │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  ┌──────────────────────────────────────────────────────┐      │
│  │              管理员功能                          │      │
│  │  - 数据源管理                                      │      │
│  │  - 语义层管理（JavaScript 文件）                   │      │
│  │  - 权限配置                                        │      │
│  │  - Git 操作管理                                    │      │
│  └──────────────────────────────────────────────────────┘      │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐      │
│  │              用户功能                              │      │
│  │  - AI 数据分析（自然语言查询）                      │      │
│  │  - 数据可视化                                      │      │
│  │  - 报表生成                                        │      │
│  └──────────────────────────────────────────────────────┘      │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐      │
│  │              Agent 系统                            │      │
│  │  - 导师 Agent（引导管理员）                        │      │
│  │  - 编程 Agent（修改语义层代码）                    │      │
│  │  - 数据分析 Agent                                  │      │
│  │  - 定时监控 Agent                                  │      │
│  └──────────────────────────────────────────────────────┘      │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐      │
│  │              Git 集成                            │      │
│  │  - GitLab / GitHub                                │      │
│  │  - 代码下载                                        │      │
│  │  - 分支管理                                        │      │
│  │  - 代码提交和合并                                  │      │
│  └──────────────────────────────────────────────────────┘      │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

## 2. 用户角色和职责

### 2.1 管理员（Admin）

**职责**：
- 配置数据源
- 管理语义层（JavaScript 文件）
- 配置权限
- 管理 Git 操作
- 监控系统状态

**核心场景**：
1. 首次登录：导师 Agent 引导完成系统初始化
2. 添加数据源：配置数据库连接
3. 修改语义层：通过编程 Agent 修改 JavaScript 代码
4. Git 操作：创建分支、提交代码、合并 PR
5. 配置权限：基于语义层配置数据访问权限

### 2.2 用户（User）

**职责**：
- 使用自然语言查询数据
- 查看数据分析结果
- 生成报表和可视化

**核心场景**：
1. 自然语言查询："上个月销售额是多少？"
2. 复杂查询："对比今年和去年同期的销售趋势"
3. 数据可视化：生成图表和报表
4. 导出结果：导出为 Excel、PDF 等格式

### 2.3 Agent（系统角色）

**导师 Agent（Tutor Agent）**
- 职责：引导管理员完成系统配置和任务
- 触发：管理员登录、系统配置检查
- 能力：检查配置、规划任务、推荐 Agent

**编程 Agent（Coding Agent）**
- 职责：修改语义层 JavaScript 代码
- 触发：管理员手动触发、定时任务触发
- 能力：Git 操作、代码修改、代码测试、代码提交

**数据分析 Agent（Data Analysis Agent）**
- 职责：理解用户查询，执行数据分析
- 触发：用户发起查询
- 能力：自然语言理解、SQL 生成、数据查询、结果可视化

**定时监控 Agent（Monitoring Agent）**
- 职责：监控数据库变化，提醒更新语义层
- 触发：定时任务
- 能力：Schema 对比、变化分析、生成更新建议

## 3. 核心业务流程

### 3.1 管理员首次登录流程

```
管理员登录
    ↓
┌─────────────────────────────────────────────────────────┐
│              导师 Agent 启动                         │
└─────────────────────────────────────────────────────────┘
    ↓
┌─────────────────────────────────────────────────────────┐
│              检查系统配置                           │
│  - 检查数据源配置                                   │
│  - 检查 Git 配置（GitLab/GitHub）                │
│  - 检查语义层配置                                   │
│  - 检查权限配置                                     │
└─────────────────────────────────────────────────────────┘
    ↓
┌─────────────────────────────────────────────────────────┐
│              生成初始化计划                         │
│  - 识别缺失的配置                                   │
│  - 规划配置步骤                                     │
│  - 推荐合适的 Agent                                  │
└─────────────────────────────────────────────────────────┘
    ↓
┌─────────────────────────────────────────────────────────┐
│              引导管理员执行任务                     │
│  步骤 1: 配置 Git 连接（GitLab/GitHub）         │
│  步骤 2: 创建数据源                                 │
│  步骤 3: 下载语义层项目                              │
│  步骤 4: 修改语义层代码（通过编程 Agent）         │
│  步骤 5: 测试语义层                                  │
│  步骤 6: 提交代码到 Git                              │
│  步骤 7: 合并到主分支                                 │
└─────────────────────────────────────────────────────────┘
    ↓
系统配置完成
```

### 3.2 语义层修改流程（编程 Agent）

```
管理员发起修改请求
    ↓
┌─────────────────────────────────────────────────────────┐
│              编程 Agent 启动                       │
└─────────────────────────────────────────────────────────┘
    ↓
┌─────────────────────────────────────────────────────────┐
│              从 Git 下载项目                       │
│  - 连接 GitLab/GitHub                            │
│  - 克隆或拉取最新代码                               │
│  - 下载语义层 JavaScript 文件                        │
└─────────────────────────────────────────────────────────┘
    ↓
┌─────────────────────────────────────────────────────────┐
│              创建新分支                             │
│  - 基于主分支创建新分支                             │
│  - 分支命名：feature/修改描述                         │
└─────────────────────────────────────────────────────────┘
    ↓
┌─────────────────────────────────────────────────────────┐
│              理解用户意图                           │
│  - 分析用户的修改需求                                 │
│  - 识别需要修改的文件和函数                         │
│  - 理解业务逻辑变化                               │
└─────────────────────────────────────────────────────────┘
    ↓
┌─────────────────────────────────────────────────────────┐
│              修改 JavaScript 代码                    │
│  - 读取现有代码                                     │
│  - 根据用户意图修改代码                             │
│  - 保持代码风格和结构                                 │
│  - 添加必要的注释                                     │
└─────────────────────────────────────────────────────────┘
    ↓
┌─────────────────────────────────────────────────────────┐
│              测试代码                               │
│  - 运行单元测试                                     │
│  - 运行集成测试                                     │
│  - 验证代码功能                                     │
│  - 检查代码质量（ESLint）                           │
└─────────────────────────────────────────────────────────┘
    ↓
┌─────────────────────────────────────────────────────────┐
│              提交代码到 Git                       │
│  - 添加修改的文件到暂存区                           │
│  - 创建提交信息                                     │
│  - 推送到远程仓库                                   │
└─────────────────────────────────────────────────────────┘
    ↓
┌─────────────────────────────────────────────────────────┐
│              创建 Pull Request                     │
│  - 创建 PR 描述                                     │
│  - 关联相关 Issue                                   │
│  - 请求代码审查                                     │
└─────────────────────────────────────────────────────────┘
    ↓
管理员审核
    ↓
┌─────────────────────────────────────────────────────────┐
│              合并到主分支                           │
│  - 审核通过后合并                                 │
│  - 删除特性分支                                     │
│  - 自动部署到生产环境                               │
└─────────────────────────────────────────────────────────┘
    ↓
修改完成
```

### 3.3 数据分析查询流程

```
用户发起自然语言查询
    ↓
┌─────────────────────────────────────────────────────────┐
│              数据分析 Agent 启动                     │
└─────────────────────────────────────────────────────────┘
    ↓
┌─────────────────────────────────────────────────────────┐
│              加载语义层（JavaScript）              │
│  - 从 Git 拉取最新代码                             │
│  - 加载语义层 JavaScript 文件                        │
│  - 解析语义层定义                                   │
└─────────────────────────────────────────────────────────┘
    ↓
┌─────────────────────────────────────────────────────────┐
│              理解用户意图                           │
│  - 自然语言理解（NLU）                            │
│  - 提取关键信息                                   │
│  - 识别查询意图                                   │
└─────────────────────────────────────────────────────────┘
    ↓
┌─────────────────────────────────────────────────────────┐
│              查询语义层                             │
│  - 匹配业务实体（通过 JavaScript 函数）             │
│  - 匹配业务字段（通过 JavaScript 函数）             │
│  - 获取权限信息（通过 JavaScript 函数）             │
└─────────────────────────────────────────────────────────┘
    ↓
┌─────────────────────────────────────────────────────────┐
│              生成 SQL 查询                          │
│  - 基于语义层生成 SQL                            │
│  - 应用权限过滤（通过 JavaScript 函数）             │
│  - 优化查询性能                                   │
└─────────────────────────────────────────────────────────┘
    ↓
┌─────────────────────────────────────────────────────────┐
│              执行查询                               │
│  - 连接数据源                                     │
│  - 执行 SQL 查询                                  │
│  - 获取查询结果                                   │
└─────────────────────────────────────────────────────────┘
    ↓
┌─────────────────────────────────────────────────────────┐
│              生成可视化                             │
│  - 分析数据特征                                   │
│  - 选择合适的图表类型                               │
│  - 生成可视化图表                                 │
└─────────────────────────────────────────────────────────┘
    ↓
返回结果和可视化
```

### 3.4 定时监控流程

```
定时任务触发（每天/每小时）
    ↓
┌─────────────────────────────────────────────────────────┐
│              监控 Agent 启动                         │
└─────────────────────────────────────────────────────────┘
    ↓
┌─────────────────────────────────────────────────────────┐
│              读取当前数据库 Schema                   │
│  - 连接数据源                                     │
│  - 读取最新 Schema                                │
│  - 保存快照                                       │
└─────────────────────────────────────────────────────────┘
    ↓
┌─────────────────────────────────────────────────────────┐
│              对比 Schema 变化                       │
│  - 对比上次快照                                   │
│  - 识别新增表/字段                                │
│  - 识别删除表/字段                                │
│  - 识别修改字段                                   │
└─────────────────────────────────────────────────────────┘
    ↓
┌─────────────────────────────────────────────────────────┐
│              有变化？                               │
│  ┌──────────────┐    ┌──────────────┐              │
│  │ 是           │    │ 否           │              │
│  └──────┬───────┘    └──────┬───────┘              │
│         ↓                   ↓                           │
│  ┌──────────────────┐  ┌──────────────────┐           │
│  │ AI 分析变化     │  │ 记录监控日志    │           │
│  │ - 评估影响      │  │ - 等待下次检查   │           │
│  │ - 生成修改建议  │  └──────────────────┘           │
│  └────────┬─────────┘                              │
│           ↓                                        │
│  ┌──────────────────┐                              │
│  │ 通知管理员      │                              │
│  │ - 建议使用编程 Agent 修改语义层        │
│  │ - 提供修改方案  │                              │
│  └──────────────────┘                              │
└─────────────────────────────────────────────────────────┘
```

## 4. Agent 配置和管理

### 4.1 Agent 配置化设计

**核心原则**：Agent 通过配置文件定义，而非硬编码开发

#### 4.1.1 导师 Agent 配置

```yaml
agent:
  id: "tutor-agent"
  name: "导师 Agent"
  description: "引导管理员完成系统配置和任务"
  type: "SYSTEM"
  
  capabilities:
    - name: "check_system_config"
      description: "检查系统配置"
      parameters:
        - name: "config_type"
          type: "string"
          required: true
          enum: ["datasource", "git", "semantic", "permission"]
    
    - name: "plan_initialization"
      description: "规划初始化步骤"
      parameters:
        - name: "current_state"
          type: "object"
          required: true
    
    - name: "recommend_agent"
      description: "推荐合适的 Agent"
      parameters:
        - name: "task_type"
          type: "string"
          required: true
  
  triggers:
    - type: "user_login"
      enabled: true
      conditions:
        - key: "user_role"
          operator: "equals"
          value: "admin"
    
    - type: "system_check"
      enabled: true
      schedule: "0 */6 * * * *"  # 每6小时
  
  tools:
    - id: "check_datasource_config"
      type: "builtin"
      config:
        timeout: 30
    
    - id: "check_git_config"
      type: "builtin"
      config:
        timeout: 30
    
    - id: "check_semantic_config"
      type: "builtin"
      config:
        timeout: 30
  
  model:
    provider: "openai"
    model: "gpt-4"
    temperature: 0.7
    max_tokens: 2000
  
  memory:
    short_term:
      enabled: true
      max_messages: 50
    long_term:
      enabled: false
```

#### 4.1.2 编程 Agent 配置

```yaml
agent:
  id: "coding-agent"
  name: "编程 Agent"
  description: "擅长编程，可以修改语义层 JavaScript 代码"
  type: "TASK"
  
  capabilities:
    - name: "clone_repository"
      description: "从 Git 下载项目"
      parameters:
        - name: "git_url"
          type: "string"
          required: true
        - name: "branch"
          type: "string"
          required: false
    
    - name: "create_branch"
      description: "创建新分支"
      parameters:
        - name: "branch_name"
          type: "string"
          required: true
        - name: "base_branch"
          type: "string"
          required: false
    
    - name: "modify_code"
      description: "修改 JavaScript 代码"
      parameters:
        - name: "file_path"
          type: "string"
          required: true
        - name: "modification_intent"
          type: "string"
          required: true
        - name: "context"
          type: "object"
          required: false
    
    - name: "test_code"
      description: "测试代码"
      parameters:
        - name: "test_type"
          type: "string"
          required: true
          enum: ["unit", "integration", "lint"]
    
    - name: "commit_code"
      description: "提交代码到 Git"
      parameters:
        - name: "commit_message"
          type: "string"
          required: true
        - name: "files"
          type: "array"
          required: true
    
    - name: "create_pull_request"
      description: "创建 Pull Request"
      parameters:
        - name: "title"
          type: "string"
          required: true
        - name: "description"
          type: "string"
          required: false
        - name: "base_branch"
          type: "string"
          required: false
  
  triggers:
    - type: "manual"
      enabled: true
    
    - type: "scheduled"
      enabled: true
      schedule: "0 2 * * *"  # 每天凌晨2点
  
  tools:
    - id: "git_clone"
      type: "builtin"
      config:
        timeout: 60
    
    - id: "git_branch"
      type: "builtin"
      config:
        timeout: 30
    
    - id: "code_editor"
      type: "builtin"
      config:
        timeout: 120
    
    - id: "code_tester"
      type: "builtin"
      config:
        timeout: 180
    
    - id: "git_commit"
      type: "builtin"
      config:
        timeout: 30
    
    - id: "git_pr"
      type: "builtin"
      config:
        timeout: 30
  
  model:
    provider: "openai"
    model: "gpt-4"
    temperature: 0.3  # 更低的温度，更精确的代码
    max_tokens: 4000
  
  memory:
    short_term:
      enabled: true
      max_messages: 100
    long_term:
      enabled: true
      vector_db: "milvus"
  
  code_context:
    language: "javascript"
    framework: "custom"
    style_guide: "semantic-layer-style-guide"
```

#### 4.1.3 数据分析 Agent 配置

```yaml
agent:
  id: "data-analysis-agent"
  name: "数据分析 Agent"
  description: "理解用户查询，执行数据分析"
  type: "QUERY"
  
  capabilities:
    - name: "understand_query"
      description: "理解自然语言查询"
      parameters:
        - name: "query"
          type: "string"
          required: true
    
    - name: "query_semantic_layer"
      description: "查询语义层"
      parameters:
        - name: "entities"
          type: "array"
          required: true
        - name: "fields"
          type: "array"
          required: true
    
    - name: "generate_sql"
      description: "生成 SQL 查询"
      parameters:
        - name: "semantic_query"
          type: "object"
          required: true
    
    - name: "execute_query"
      description: "执行查询"
      parameters:
        - name: "sql"
          type: "string"
          required: true
        - name: "datasource_id"
          type: "string"
          required: true
    
    - name: "visualize_result"
      description: "可视化结果"
      parameters:
        - name: "data"
          type: "array"
          required: true
        - name: "chart_type"
          type: "string"
          required: false
  
  triggers:
    - type: "user_query"
      enabled: true
  
  tools:
    - id: "semantic_layer_loader"
      type: "builtin"
      config:
        timeout: 30
    
    - id: "sql_generator"
      type: "builtin"
      config:
        timeout: 60
    
    - id: "query_executor"
      type: "builtin"
      config:
        timeout: 120
    
    - id: "data_visualizer"
      type: "builtin"
      config:
        timeout: 60
  
  model:
    provider: "openai"
    model: "gpt-4"
    temperature: 0.5
    max_tokens: 3000
  
  memory:
    short_term:
      enabled: true
      max_messages: 20
    long_term:
      enabled: false
```

#### 4.1.4 监控 Agent 配置

```yaml
agent:
  id: "monitoring-agent"
  name: "监控 Agent"
  description: "监控数据库变化，提醒更新语义层"
  type: "SCHEDULED"
  
  capabilities:
    - name: "read_schema"
      description: "读取数据库 Schema"
      parameters:
        - name: "datasource_id"
          type: "string"
          required: true
    
    - name: "compare_schema"
      description: "对比 Schema 变化"
      parameters:
        - name: "current_schema"
          type: "object"
          required: true
        - name: "previous_schema"
          type: "object"
          required: true
    
    - name: "analyze_changes"
      description: "分析变化影响"
      parameters:
        - name: "changes"
          type: "array"
          required: true
    
    - name: "notify_admin"
      description: "通知管理员"
      parameters:
        - name: "notification_type"
          type: "string"
          required: true
        - name: "message"
          type: "string"
          required: true
        - name: "suggestions"
          type: "array"
          required: false
  
  triggers:
    - type: "scheduled"
      enabled: true
      schedule: "0 */1 * * *"  # 每小时
  
  tools:
    - id: "schema_reader"
      type: "builtin"
      config:
        timeout: 60
    
    - id: "schema_comparator"
      type: "builtin"
      config:
        timeout: 30
    
    - id: "change_analyzer"
      type: "builtin"
      config:
        timeout: 60
    
    - id: "notification_sender"
      type: "builtin"
      config:
        timeout: 30
  
  model:
    provider: "openai"
    model: "gpt-4"
    temperature: 0.7
    max_tokens: 2000
  
  memory:
    short_term:
      enabled: true
      max_messages: 50
    long_term:
      enabled: true
      vector_db: "milvus"
```

### 4.2 Agent 类型

**导师 Agent（Tutor Agent）**
- 类型：SYSTEM
- 触发：用户登录、系统检查
- 能力：配置检查、任务规划、Agent 推荐

**编程 Agent（Coding Agent）**
- 类型：TASK
- 触发：手动触发、定时任务
- 能力：Git 操作、代码修改、代码测试、代码提交
- 特点：擅长编程，支持 JavaScript 代码修改

**数据分析 Agent（Data Analysis Agent）**
- 类型：QUERY
- 触发：用户查询
- 能力：NLU、SQL 生成、数据查询、可视化

**监控 Agent（Monitoring Agent）**
- 类型：SCHEDULED
- 触发：定时任务
- 能力：Schema 对比、变化分析、通知

### 4.3 Agent 管理界面

**Agent 列表**：
- 显示所有配置的 Agent
- 显示 Agent 状态（启用/禁用）
- 显示 Agent 触发条件
- 显示 Agent 能力

**Agent 配置**：
- 编辑 Agent 配置文件
- 启用/禁用 Agent
- 配置触发条件
- 配置工具和模型

**Agent 监控**：
- 查看 Agent 执行历史
- 查看 Agent 执行日志
- 查看 Agent 性能指标

## 5. 语义层管理（JavaScript）

### 5.1 语义层结构

**语义层 JavaScript 文件示例**：

```javascript
// semantic-layer.js

const SemanticLayer = {
  version: "1.0.0",
  createdAt: "2024-01-01T00:00:00Z",
  createdBy: "admin",
  
  entities: {
    Customer: {
      description: "客户信息",
      table: "customers",
      
      fields: {
        id: {
          type: "integer",
          description: "客户ID",
          primaryKey: true,
          searchable: false
        },
        
        name: {
          type: "string",
          description: "客户姓名",
          searchable: true,
          maxLength: 100
        },
        
        email: {
          type: "string",
          description: "客户邮箱",
          searchable: true,
          format: "email"
        },
        
        phone: {
          type: "string",
          description: "客户电话",
          searchable: true,
          format: "phone"
        },
        
        createdAt: {
          type: "datetime",
          description: "创建时间",
          format: "YYYY-MM-DD HH:mm:ss"
        }
      },
      
      relationships: [
        {
          type: "one_to_many",
          target: "Order",
          foreignKey: "customer_id",
          description: "一个客户可以有多个订单"
        }
      ]
    },
    
    Order: {
      description: "订单信息",
      table: "orders",
      
      fields: {
        id: {
          type: "integer",
          description: "订单ID",
          primaryKey: true,
          searchable: false
        },
        
        customerId: {
          type: "integer",
          description: "客户ID",
          foreignKey: "Customer.id",
          searchable: false
        },
        
        totalAmount: {
          type: "decimal",
          description: "订单总金额",
          format: "0.00",
          searchable: false
        },
        
        status: {
          type: "string",
          description: "订单状态",
          enum: ["pending", "paid", "shipped", "cancelled"],
          searchable: true
        },
        
        createdAt: {
          type: "datetime",
          description: "创建时间",
          format: "YYYY-MM-DD HH:mm:ss"
        }
      },
      
      relationships: [
        {
          type: "many_to_one",
          target: "Customer",
          foreignKey: "customer_id",
          description: "一个订单属于一个客户"
        }
      ]
    }
  },
  
  permissions: {
    admin: {
      Customer: {
        actions: ["read", "write", "delete"],
        fieldPermissions: {
          id: ["read"],
          name: ["read", "write"],
          email: ["read", "write"],
          phone: ["read", "write"],
          createdAt: ["read"]
        }
      },
      Order: {
        actions: ["read", "write", "delete"]
      }
    },
    
    user: {
      Customer: {
        actions: ["read"],
        fieldPermissions: {
          id: ["read"],
          name: ["read"],
          email: ["read"],
          phone: []  // 无权限
        }
      },
      Order: {
        actions: ["read"],
        conditions: [
          {
            field: "status",
            operator: "equals",
            value: "paid"  // 只能查看已支付订单
          }
        ]
      }
    }
  },
  
  functions: {
    getCustomerById: function(id) {
      return this.entities.Customer.fields;
    },
    
    getCustomerFields: function() {
      return Object.keys(this.entities.Customer.fields);
    },
    
    getCustomerPermissions: function(role) {
      return this.permissions[role]?.Customer;
    },
    
    validateQuery: function(query, role) {
      const permissions = this.permissions[role];
      // 验证查询权限
      return true;
    }
  }
};

module.exports = SemanticLayer;
```

### 5.2 语义层操作

**查看语义层**：
- 显示所有实体
- 显示实体字段
- 显示实体关系
- 显示权限配置

**编辑语义层**：
- 添加新实体
- 修改现有实体
- 删除实体
- 修改字段定义
- 修改关系定义
- 修改权限配置

**版本管理**：
- 查看版本历史
- 对比版本差异
- 回滚到历史版本

## 6. Git 集成

### 6.1 Git 配置

**Git 仓库配置**：

```yaml
git:
  provider: "gitlab"  # 或 "github"
  url: "https://gitlab.example.com/semantic-layer"
  branch: "main"
  
  auth:
    type: "token"  # 或 "ssh", "oauth"
    token: "${GIT_TOKEN}"  # 从环境变量读取
  
  webhook:
    enabled: true
    url: "https://foggy-navigator.example.com/api/webhooks/git"
    secret: "${WEBHOOK_SECRET}"
```

### 6.2 Git 操作流程

**克隆仓库**：
```
1. 从 GitLab/GitHub 克隆仓库
2. 检出到指定分支
3. 下载语义层 JavaScript 文件
```

**创建分支**：
```
1. 基于主分支创建新分支
2. 分支命名规则：feature/描述
3. 推送到远程仓库
```

**修改代码**：
```
1. 读取现有代码
2. 根据用户意图修改
3. 保持代码风格
4. 添加必要注释
```

**测试代码**：
```
1. 运行单元测试
2. 运行集成测试
3. 运行代码检查（ESLint）
4. 验证功能正确性
```

**提交代码**：
```
1. 添加修改的文件
2. 创建提交信息
3. 推送到远程仓库
```

**创建 PR**：
```
1. 创建 Pull Request
2. 填写 PR 描述
3. 关联相关 Issue
4. 请求代码审查
```

**合并代码**：
```
1. 审核通过
2. 合并到主分支
3. 删除特性分支
4. 自动部署
```

### 6.3 Git 工具集成

**GitLab API**：
- 获取仓库信息
- 创建分支
- 提交代码
- 创建 Merge Request
- 合并代码

**GitHub API**：
- 获取仓库信息
- 创建分支
- 提交代码
- 创建 Pull Request
- 合并代码

## 7. 代码修改和测试

### 7.1 代码修改流程

```
用户提出修改需求
    ↓
┌─────────────────────────────────────────────────────────┐
│              编程 Agent 理解需求                 │
│  - 分析修改意图                                     │
│  - 识别需要修改的文件                               │
│  - 识别需要修改的函数                               │
└─────────────────────────────────────────────────────────┘
    ↓
┌─────────────────────────────────────────────────────────┐
│              读取现有代码                           │
│  - 从 Git 拉取最新代码                             │
│  - 读取语义层 JavaScript 文件                        │
│  - 分析代码结构                                     │
└─────────────────────────────────────────────────────────┘
    ↓
┌─────────────────────────────────────────────────────────┐
│              生成修改方案                           │
│  - 确定修改位置                                     │
│  - 确定修改内容                                     │
│  - 保持代码风格                                     │
└─────────────────────────────────────────────────────────┘
    ↓
┌─────────────────────────────────────────────────────────┐
│              修改代码                               │
│  - 应用修改方案                                     │
│  - 添加必要注释                                     │
│  - 保持代码格式                                     │
└─────────────────────────────────────────────────────────┘
    ↓
┌─────────────────────────────────────────────────────────┐
│              代码审查                               │
│  - 检查语法错误                                     │
│  - 检查代码风格                                     │
│  - 检查潜在问题                                     │
└─────────────────────────────────────────────────────────┘
    ↓
修改完成
```

### 7.2 测试流程

**单元测试**：
```javascript
describe('SemanticLayer', () => {
  describe('Customer entity', () => {
    it('should have correct fields', () => {
      const customer = SemanticLayer.entities.Customer;
      expect(customer.fields).toBeDefined();
      expect(customer.fields.id).toBeDefined();
      expect(customer.fields.name).toBeDefined();
    });
    
    it('should have correct permissions for admin', () => {
      const permissions = SemanticLayer.getCustomerPermissions('admin');
      expect(permissions.actions).toContain('read');
      expect(permissions.actions).toContain('write');
    });
  });
});
```

**集成测试**：
```javascript
describe('SemanticLayer Integration', () => {
  it('should load semantic layer from file', async () => {
    const semanticLayer = await loadSemanticLayer('semantic-layer.js');
    expect(semanticLayer).toBeDefined();
    expect(semanticLayer.entities).toBeDefined();
  });
  
  it('should validate query correctly', () => {
    const query = { entity: 'Customer', action: 'read' };
    const isValid = SemanticLayer.validateQuery(query, 'user');
    expect(isValid).toBe(true);
  });
});
```

**代码检查（ESLint）**：
```json
{
  "env": {
    "node": true,
    "es2021": true
  },
  "extends": "eslint:recommended",
  "rules": {
    "indent": ["error", 2],
    "quotes": ["error", "single"],
    "semi": ["error", "always"]
  }
}
```

## 8. 技术栈评估

### 8.1 核心框架

**LangChain4j**
- ✅ 保留：Java 生态、与 Spring Boot 集成良好
- 用途：Agent 框架、工具调用、模型适配

**Spring Boot**
- ✅ 保留：成熟稳定、生态丰富
- 用途：应用框架、依赖注入、配置管理

### 8.2 数据库

**MySQL / PostgreSQL**
- ✅ 保留：存储业务数据、用户数据、配置数据
- 用途：数据源、系统数据库

### 8.3 版本管理

**Git**
- ✅ 必需：管理语义层 JavaScript 文件
- 用途：版本控制、分支管理、代码审查

**GitLab / GitHub**
- ✅ 必需：托管 Git 仓库、提供 Web 界面
- 用途：代码托管、CI/CD、Pull Request

### 8.4 编程 Agent 工具

**Git 工具**
- ✅ 必需：Git 操作（克隆、分支、提交、PR）
- 用途：编程 Agent 的 Git 操作

**代码编辑器**
- ✅ 必需：代码修改、语法高亮、代码补全
- 用途：编程 Agent 的代码编辑

**代码测试工具**
- ✅ 必需：单元测试、集成测试、代码检查
- 用途：代码验证和质量检查

### 8.5 JavaScript 运行时

**Node.js**
- ✅ 必需：运行语义层 JavaScript 文件
- 用途：加载和执行语义层代码

**GraalVM JavaScript**
- ✅ 推荐：高性能 JavaScript 引擎，与 Java 集成
- 用途：在 Java 中运行 JavaScript

### 8.6 任务调度

**Spring Scheduler**
- ✅ 推荐：Spring Boot 内置、简单易用
- 用途：定时任务、任务调度

### 8.7 监控和日志

**Prometheus + Grafana**
- ✅ 推荐：开源、功能强大
- 用途：系统监控、指标收集、可视化

**ELK Stack**
- ✅ 推荐：完整的日志解决方案
- 用途：日志收集、查询、分析

### 8.8 前端框架

**React / Vue.js**
- ✅ 推荐：现代前端框架、生态丰富
- 用途：管理界面、用户界面

**Ant Design / Element UI**
- ✅ 推荐：企业级 UI 组件库
- 用途：UI 组件、表单、表格

## 9. MVP 功能清单（业务视角）

### 9.1 MVP 目标

**目标**：实现最小可用的数据分析平台，支持：
- 管理员通过导师 Agent 快速上手
- 编程 Agent 自动修改语义层 JavaScript 代码
- Git 版本管理和代码审查
- 基于语义层的自然语言查询

### 9.2 MVP 功能列表

#### 管理员功能

**系统初始化**
- ✅ 导师 Agent 引导
- ✅ 系统配置检查
- ✅ 初始化任务规划

**Git 管理**
- ✅ 配置 Git 连接（GitLab/GitHub）
- ✅ 克隆/拉取代码
- ✅ 创建分支
- ✅ 查看提交历史
- ✅ 查看 Pull Request

**语义层管理**
- ✅ 查看语义层（JavaScript 文件）
- ✅ 通过编程 Agent 修改代码
- ✅ 查看代码差异
- ✅ 代码审查和合并

**数据源管理**
- ✅ 创建数据源
- ✅ 测试连接
- ✅ 查看数据源列表

**权限管理**
- ✅ 角色管理
- ✅ 用户角色分配
- ✅ 基于语义层的权限配置

#### 用户功能

**数据分析**
- ✅ 自然语言查询
- ✅ 查询结果展示
- ✅ 数据可视化
- ✅ 结果导出

#### Agent 功能

**导师 Agent**
- ✅ 配置检查
- ✅ 任务规划
- ✅ Agent 推荐

**编程 Agent**
- ✅ Git 操作（克隆、分支、提交、PR）
- ✅ 代码修改（JavaScript）
- ✅ 代码测试（单元测试、集成测试）
- ✅ 代码审查（ESLint）

**数据分析 Agent**
- ✅ 自然语言理解
- ✅ SQL 生成
- ✅ 数据查询
- ✅ 结果可视化

**监控 Agent**
- ✅ Schema 监控
- ✅ 变化检测
- ✅ 通知提醒

## 10. 实施计划

### Phase 1: MVP（8-10 周）

**Week 1-2: 基础设施**
- 搭建 Spring Boot 项目
- 配置数据库连接
- 创建数据表
- 集成 Git（GitLab/GitHub API）
- 配置 JavaScript 运行时（GraalVM）

**Week 3-4: 核心功能**
- 实现数据源管理
- 实现导师 Agent
- 实现编程 Agent（Git 操作）
- 实现代码编辑和测试工具

**Week 5-6: 用户功能**
- 实现数据分析 Agent
- 实现前端界面（React/Vue）
- 实现权限管理
- 实现语义层加载和查询

**Week 7-8: 测试和优化**
- 编写测试
- 性能优化
- 文档编写

**Week 9-10: 部署和运维**
- 容器化部署
- 监控配置
- 运维手册

### Phase 2: 增强功能（4-6 周）

**Week 1-2: 监控和告警**
- 实现监控 Agent
- 实现告警规则
- 实现通知机制

**Week 3-4: 高级功能**
- 实现自动化测试
- 实现代码审查自动化
- 实现性能优化

**Week 5-6: 部署和运维**
- CI/CD 配置
- 自动化部署
- 文档完善

## 11. 总结

从业务角度重新审视系统后，我们发现：

1. **核心价值**：AI 驱动的语义层管理（JavaScript 文件）和数据分析
2. **关键特性**：
   - 导师引导
   - 编程 Agent（擅长修改 JavaScript 代码）
   - Git 版本管理（GitLab/GitHub）
   - 代码修改、测试、提交、合并流程
   - 定时监控和主动提醒
3. **用户角色**：管理员（配置和管理）、用户（数据分析）
4. **Agent 类型**：导师、编程、数据分析、监控
5. **技术栈**：
   - LangChain4j + Spring Boot
   - Git + GitLab/GitHub
   - JavaScript 运行时（GraalVM）
   - Docker
   - Spring Scheduler
   - Prometheus + Grafana
   - ELK Stack
   - React / Vue.js

相比之前的技术视角，业务视角更关注：
- 编程 Agent 的代码修改能力
- Git 集成和版本管理
- 代码测试和审查流程
- 语义层 JavaScript 文件的管理

这个业务架构设计方案为后续的技术实现提供了清晰的指导。
