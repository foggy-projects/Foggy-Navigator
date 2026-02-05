---
id: guide-initial-setup
name: 引导初始配置
description: 引导用户完成系统初始化配置，包括 Git 凭证配置、项目选择、分支设置等
type: instruction
triggers:
  - 初始配置
  - 开始配置
  - 系统配置
  - 如何开始
  - 配置向导
  - 我要开始
intents:
  - initial_setup
  - start_config
  - system_setup
  - get_started
---

# 执行逻辑

1. 首先调用 list_git_credentials 检查是否已配置 Git 凭证
2. 如果没有凭证，引导用户添加 Git 凭证
3. 如果有凭证，调用 list_git_projects 展示可用项目
4. 帮助用户选择目标项目和分支
5. 引导用户创建编码会话

# 输出格式

**系统配置向导**

👋 欢迎使用 Coding Agent！让我帮您完成初始配置。

**当前状态**
- Git 凭证: [已配置/未配置]
- 可用项目: [项目列表]

**下一步**
[根据当前状态提供具体指导]

# 分派条件

仅提供配置指导，不进行任务分派
