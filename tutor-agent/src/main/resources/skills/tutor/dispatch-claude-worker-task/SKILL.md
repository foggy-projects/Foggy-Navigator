---
name: dispatch-claude-worker-task
description: 帮助用户将编程任务派发到 Claude Worker 执行。当用户需要使用 Claude Code、远程编程、Claude Worker 完成任务时使用。
---

# 派发 Claude Worker 任务

将用户的编程需求派发到远程 Claude Worker 机器上执行。

## 执行流程

1. 确认用户的需求详情（任务描述、工作目录等）
2. 调用 `list_claude_workers` 获取可用 Worker 列表
3. 帮助用户选择目标 Worker（如果有多个）
4. 确认工作目录（cwd），如果用户未指定则询问
5. 调用 `dispatch_claude_worker_task` 派发任务
6. 返回任务 ID 和状态

## 输出格式

**任务已派发**

- 任务 ID: {taskId}
- 目标 Worker: {workerName}
- 状态: RUNNING

可随时使用「查看任务状态」来检查进度。

## 约束条件

- 必须先通过 `list_claude_workers` 确认有可用的 ONLINE Worker
- 如果没有可用 Worker，提示用户先在「Workers」页面添加和配置 Worker
- 任务描述应该具体明确，包含足够的上下文让 Claude Code 理解要做什么
