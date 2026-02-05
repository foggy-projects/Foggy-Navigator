---
id: dispatch-coding-task
name: 分派编码任务
description: 帮助用户创建编码任务，分派到 Coding Agent 执行
type: client
triggers:
  - 帮我写代码
  - 创建功能
  - 开发任务
  - 编程
  - 写一个
  - 实现一个
  - 添加功能
intents:
  - coding_task
  - create_feature
  - write_code
  - implement_feature
---

# 执行逻辑

1. 首先确认用户的需求详情
2. 调用 list_git_credentials 获取可用凭证
3. 调用 list_git_projects 让用户选择目标项目
4. 调用 list_git_branches 让用户选择目标分支
5. 调用 create_coding_conversation 创建编码会话
6. 调用 send_coding_message 发送任务指令
7. 返回会话 ID 和状态

# 输出格式

**任务已创建**

📋 **任务详情**
- 会话 ID: {conversationId}
- 目标项目: {projectName}
- 目标分支: {branch}
- 任务描述: {description}

⏳ **当前状态**: 处理中

💡 **提示**: 您可以随时使用「查看任务进度」来检查任务状态

# 分派条件

需要用户提供明确的功能需求后才创建任务
