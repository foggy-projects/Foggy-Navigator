---
id: guide-datasource-config
name: 引导配置数据源
description: 引导用户配置数据库数据源连接
type: instruction
triggers:
  - 配置数据源
  - 连接数据库
  - 添加数据库
intents:
  - configure_datasource
  - add_datasource
---

# 执行逻辑

1. 调用 checkDatasourceStatus() 检查当前状态
2. 如果已配置，询问是否添加新数据源或修改现有数据源
3. 如果未配置，开始逐步收集信息：
   - 数据库类型（MySQL / PostgreSQL / Oracle / SQL Server）
   - 主机地址
   - 端口号
   - 数据库名称
   - 用户名
   - 密码（提醒加密存储）
4. 验证信息完整性
5. 如果信息完整，分派给 datasource-agent

# 分派条件

- 所有必要信息已收集（dbType, host, port, database, username, password）
- 用户确认开始配置
- 触发 delegate-datasource-config 规则

# 上下文传递

```json
{
  "dbType": "MySQL",
  "connectionInfo": {
    "host": "localhost",
    "port": 3306,
    "database": "sales_db",
    "username": "admin",
    "password": "******"
  }
}
```

# 示例对话流程

用户: 我想配置数据源
导师: 好的，请选择数据库类型：1. MySQL 2. PostgreSQL 3. Oracle 4. SQL Server
用户: MySQL
导师: 请提供主机地址（如: localhost）
[继续收集其他信息...]
导师: 信息收集完成，是否确认开始配置？
用户: 确认
导师: [分派给 datasource-agent]
