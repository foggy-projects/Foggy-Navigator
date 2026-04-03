# 04 Shared Ask External URL Propagation

## Date

- 2026-04-03

## Type

- Requirement
- Deferred

## Background

当前 `POST /api/v1/shared/ask` 在示例、脚本和部分默认值中经常以 `http://localhost:8112` 出现。

这在单机本地开发时没有问题，但在以下场景会直接失效：

- AI / Worker 运行在另一台机器上
- 定时任务脚本运行在远端机器上
- Sharing Key 被提供给外部系统或外部自动化任务使用

根因不是接口本身，而是“可访问的 Navigator 地址”还没有在平台、Worker、技能模板和 Sharing Key API 上形成统一真源和统一下发规范。

## Goal

为所有需要调用 `POST /api/v1/shared/ask` 的 AI 运行场景提供真实、可访问、可配置的 Navigator 地址，避免任何运行中的 AI 再依赖 `localhost` 假设。

## Current Implementation Sync

当前代码已经具备一半链路能力，但还没有完全收口成统一规范。

### 已存在能力

1. `addons/claude-worker-agent` 已使用 `navigator.api.external-url`，默认回退到 `http://localhost:${server.port:8112}`
2. Java 在任务派发时已将 `navigatorApiBase` 传给 Claude Worker
3. Python Worker 已把该值注入为 CLI 子进程环境变量 `NAVIGATOR_API_BASE`
4. 平台技能模板和部署器已支持 `{{NAVIGATOR_API_BASE}}`

### 仍然缺失的能力

1. `SharingKeyDTO` 还没有 `invokeBaseUrl` / `invokeUrl`
2. `SharingKeyService` 也还没有统一填充这些字段
3. 当前仓库检索结果中，没有发现 `NAVIGATOR_API_EXTERNAL_URL` 已被正式写入 Launcher 统一配置模板的实现依据
4. 仍可能有示例、脚本或说明文档使用 `localhost:8112`

因此这份文档当前可作为开发需求交付，但需要基于“现状部分已实现、部分待补齐”的口径。

## Related Code Checklist

### Claude Worker Java 链路

- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/ClaudeTaskService.java`
- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/WorkerStreamRelay.java`
- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/client/ClaudeWorkerClient.java`
- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/PlatformSkillSyncer.java`
- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/PlatformSkillDeployer.java`

### Claude Worker Python 链路

- `tools/claude-agent-worker/src/agent_worker/models.py`
- `tools/claude-agent-worker/src/agent_worker/claude/sdk_wrapper.py`
- `tools/claude-agent-worker/src/agent_worker/platform_skills/deployer.py`
- `tools/claude-agent-worker/src/agent_worker/platform_skills/scheduled_task.md`
- `tools/claude-agent-worker/src/agent_worker/platform_skills/navigator_admin.md`

### Sharing Key API

- `navigator-common/src/main/java/com/foggy/navigator/common/dto/SharingKeyDTO.java`
- `session-module/src/main/java/com/foggy/navigator/session/service/SharingKeyService.java`
- `session-module/src/main/java/com/foggy/navigator/session/controller/SharingKeyController.java`
- `session-module/src/main/java/com/foggy/navigator/session/controller/SharedAskController.java`

## Proposed Design

### 1. 统一配置真源

定义 `navigator.api.external-url` 为 Navigator 对外可达地址的唯一真源。

建议规范如下：

- Spring 配置键：`navigator.api.external-url`
- 环境变量名：`NAVIGATOR_API_EXTERNAL_URL`
- 部署入口：`launcher/.env` 或统一启动配置
- 语义：填写“远端 AI / Worker 实际可访问的 Navigator 地址”

示例：

- 本地开发：`http://localhost:8112`
- 局域网部署：`http://192.168.31.119:8112`
- 正式部署：`https://navigator.example.com`

### 2. 统一下发链路

平台对 AI 地址的传递采用“后端下发”，不采用“AI 自发现”：

1. Launcher / Spring 读取 `navigator.api.external-url`
2. Java 后端在任务派发时把该地址作为 `navigatorApiBase` 传给 Worker
3. Worker 将其注入 `NAVIGATOR_API_BASE`
4. 所有 AI-facing 技能、模板、脚本统一读取 `NAVIGATOR_API_BASE`

### 3. Sharing Key 返回真实调用地址

建议在 Sharing Key 的创建 / 查询响应中增加：

- `invokeBaseUrl`
- `invokeUrl`

示例：

```json
{
  "sharingKey": "shk-xxxx",
  "invokeBaseUrl": "https://navigator.example.com",
  "invokeUrl": "https://navigator.example.com/api/v1/shared/ask"
}
```

### 4. 启动与运行时校验

建议补两层兜底：

- 启动校验：启用远端 Worker 或共享场景时，若 external URL 为空或仍为 `localhost`，输出明确告警
- 运行时校验：向远端 Worker 派发任务时若仍携带 `localhost`，记录 warning，必要时 fail-fast

## Decision Notes

### 为什么不能靠 AI 自发现地址

因为这是 bootstrap 问题。AI 在调用平台 API 之前，就必须先知道平台地址；如果平台地址本身需要靠另一个平台 API 才能发现，链路就闭环了。

### 为什么要在 Sharing Key DTO 中返回调用地址

Sharing Key 是外部调用入口。对外部系统而言，拿到 key 却拿不到调用地址，仍会逼迫上层继续手工拼接 `localhost` 或额外维护配置。

## Suggested Analysis Checklist

以下清单只是建议技术优先从这些点开始分析，不作为强制拆解要求：

1. 先确认 `navigator.api.external-url` 的最终统一配置入口和部署文档位置
2. 复核 Claude Java -> Worker -> CLI 的 `navigatorApiBase` / `NAVIGATOR_API_BASE` 透传链路
3. 统计当前仍使用 `localhost:8112` 的 AI-facing 模板、示例和脚本
4. 设计 `SharingKeyDTO` 的新字段和兼容返回策略
5. 评估是否需要在创建 / list / get / update 接口全部返回 `invokeBaseUrl` / `invokeUrl`

## Acceptance Criteria

- 远端 AI / Worker 不再依赖 `localhost` 才能调用 `POST /api/v1/shared/ask`
- `SharingKeyDTO` 返回体中可直接获得真实调用地址
- 平台技能和 AI 模板统一使用 `NAVIGATOR_API_BASE`
- 部署文档明确说明 `NAVIGATOR_API_EXTERNAL_URL` / `navigator.api.external-url` 的配置要求
- 对误配为 `localhost` 的远端场景，系统能给出明确提示

## Test Scope

### Unit / Service

- `SharingKeyService` DTO 组装测试
- `ClaudeTaskService` / `WorkerStreamRelay` 透传 `navigatorApiBase` 的现有测试补齐
- `PlatformSkillSyncer` / `PlatformSkillDeployer` 模板替换测试

### Integration / Regression

- 创建 Sharing Key 后，返回体包含 `invokeBaseUrl` / `invokeUrl`
- 派发 Claude 任务时，Worker 实际收到 `navigator_api_base`
- CLI 运行环境中可读取 `NAVIGATOR_API_BASE`

### Frontend / Playwright Validation

建议最小验证路径：

1. 在管理页创建 Sharing Key
2. 读取返回体或列表数据，确认存在可直接调用的 shared ask URL
3. 启动一个通过 Claude Worker 执行的平台技能任务
4. 在任务环境中校验 `NAVIGATOR_API_BASE` 已被注入
5. 使用共享调用脚本从非本机地址完成一次真实请求

## Delivery Assessment

本项已经可以直接交付技术作为需求文档。

原因是：

- 现有实现已经有明确的半成品链路
- 缺口也已经足够具体
- 关联代码范围集中
- 验收与测试路径清晰

## Status

本项作为 `1.0.0-SNAPSHOT` 版本需求记录，可直接交付技术进入方案细化与开发分析。
