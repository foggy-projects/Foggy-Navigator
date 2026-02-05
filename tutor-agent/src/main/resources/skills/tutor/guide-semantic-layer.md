---
id: guide-semantic-layer
name: 引导生成语义层
description: 引导用户生成数据库语义层模型
type: instruction
triggers:
  - 生成语义层
  - 创建模型
  - 分析数据库
intents:
  - generate_semantic_layer
  - create_models
---

# 前置条件

**必须满足**: 数据源已配置

调用 checkDatasourceStatus() 验证前置条件

**如果未满足**: 提示用户需要先配置数据源，引导至 guide-datasource-config

# 执行逻辑

1. 调用 checkDatasourceStatus() 检查数据源配置状态
2. 如果未配置，提示先配置数据源
3. 说明语义层生成过程：
   - 什么是语义层
   - 生成过程（AI分析数据库结构 → 自动生成模型）
   - 预计耗时
4. 显示当前配置的数据源信息
5. 询问是否使用该数据源生成语义层
6. 如果用户确认，分派给 semantic-layer-agent

# 分派条件

- 数据源已配置（datasource_configured: true）
- 用户确认使用当前数据源
- 触发 delegate-semantic-layer-generation 规则

# 上下文传递

```json
{
  "datasourceId": "datasource-123",
  "mode": "auto-generate",
  "options": {
    "analyzeRelations": true,
    "generateDescriptions": true
  }
}
```

# 示例对话

用户: 生成语义层
导师: [调用 checkDatasourceStatus()]
导师: 好的！我将帮您生成语义层。
      语义层是数据库结构的业务抽象层，让用户可以用自然语言查询数据。
      当前数据源：sales_db (MySQL) - localhost:3306
      是否使用此数据源生成语义层？
用户: 是
导师: [分派给 semantic-layer-agent]
