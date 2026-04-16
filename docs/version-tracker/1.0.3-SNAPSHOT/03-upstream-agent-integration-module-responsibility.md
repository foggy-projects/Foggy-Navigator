# 上游 Agent 接入模块职责划分

## 文档作用

- doc_type: module-responsibility
- intended_for: root-controller | execution-agent | reviewer
- purpose: 明确 1.0.3-SNAPSHOT 上游接入首版在各模块之间的职责边界、依赖关系和可开工范围，避免后续开发时职责漂移

## Version

- `1.0.3-SNAPSHOT`

## Status

- Draft
- 2026-04-15

## 1. 文档适用范围

本文件服务于 `1.0.3-SNAPSHOT` 版本目标：

- 让上游系统能够真实接入平台 Agent
- 让上游能够加载会话列表、读取消息、轮询任务进行中的多条消息
- 交付一个可运行的上游接入 Demo

本文件不负责决定最终接口字段细节，只负责回答：

1. 哪个模块负责什么
2. 哪些模块可以直接开工
3. 哪些模块依赖前置契约收口

## 2. Root 控制职责

当前 root 控制和版本基线位于：

- [01-upstream-agent-integration-requirement.md](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/docs/version-tracker/1.0.3-SNAPSHOT/01-upstream-agent-integration-requirement.md)
- [02-upstream-agent-integration-current-state-analysis.md](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/docs/version-tracker/1.0.3-SNAPSHOT/02-upstream-agent-integration-current-state-analysis.md)

root 层负责：

1. 统一版本目标和验收标准
2. 统一接口边界口径
3. 决定首版最小范围
4. 决定 Demo 交付形态

root 层不负责：

1. 直接指定每个类的最终命名
2. 替各模块写局部实现方案
3. 在未收敛契约前提前锁定所有底层存储改造

## 3. 模块职责划分

### 3.1 `addons/claude-worker-agent`

负责：

1. 面向第三方的 Open API 主入口
2. Claude Worker 任务视图投影
3. 平台 `taskId` 与 Worker `workerTaskId` 的关联维护
4. 对外任务轮询协议扩展
5. 与 Worker 持续消息回流的对接

本次版本建议承担：

1. 对外 `task polling` 协议扩展
2. 按 `taskId` 获取进行中增量消息的主入口设计
3. Open API 返回结构与文档收口

不建议在本模块承担：

1. 平台统一会话域的通用列表聚合逻辑
2. 前端 Demo 页面本体

### 3.2 `session-module`

负责：

1. 统一会话管理
2. 会话消息持久化
3. 统一任务抽象
4. SSE 推送
5. 会话与任务的统一视图

本次版本建议承担：

1. 上游会话列表与会话消息模型的正式化
2. 任务增量消息查询所需的统一读模型支撑
3. 消息 `taskId` 归属规则的统一输出
4. 分页 / cursor / 增量读取语义的稳定化

不建议在本模块承担：

1. 对外 Open SDK 封装
2. Demo 业务展示层

### 3.3 `navigator-open-sdk`

负责：

1. 对外 Java SDK 封装
2. 将平台正式开放能力转换为稳定的 Java 调用接口

本次版本建议承担：

1. 补齐会话列表 API 封装
2. 补齐会话消息 API 封装
3. 补齐任务增量消息轮询 API 封装
4. 输出最小 Java 示例调用路径

不建议在本模块承担：

1. 协议本身的首轮设计
2. Worker 内部模型兼容逻辑

### 3.4 `packages/foggy-chat` 或独立示例工程

负责：

1. 示范上游如何消费开放接口
2. 提供最小可运行 Demo

本次版本建议承担：

1. 演示会话列表读取
2. 演示会话消息读取
3. 演示创建任务
4. 演示轮询任务状态
5. 演示轮询任务进行中新增消息

如果使用 `packages/foggy-chat`：

- 更适合作为前端示例能力承载

如果新建独立 sample：

- 更适合作为对外接入演示

### 3.5 `docs/version-tracker/1.0.3-SNAPSHOT`

负责：

1. 版本目标记录
2. 接口边界和实施规划文档
3. 后续进度与验收材料的落盘基线

## 4. 依赖关系

### 4.1 先行依赖

后续执行前必须先收口以下问题：

1. 进行中消息轮询的正式协议选型
2. 上游会话接口是复用 Open API 扩展还是新增独立入口
3. Demo 的形态选择

### 4.2 开工顺序建议

建议顺序如下：

1. 先确定接口合同和首版最小字段集
2. 再落 `session-module` 的读模型输出
3. 再落 `addons/claude-worker-agent` 的 Open API 扩展
4. 再补 `navigator-open-sdk`
5. 最后完成 Demo

## 5. 当前可直接开工的部分

在不等待更深层设计的前提下，可以直接开工的部分有：

1. 版本内接口合同初稿文档
2. 代码触点盘点
3. Demo 交付形态决策
4. Java SDK 现有能力盘点与补齐点清单

## 6. 当前不建议直接编码的部分

以下内容在契约未收敛前不建议直接开工：

1. 消息表结构性改造
2. 大范围 Open API 重命名
3. 过早引入 WebSocket 或额外实时通道
4. 把内部 UI 查询接口直接作为上游正式接口暴露

## 7. 完成定义

模块职责文档完成的标准是：

1. 后续执行 agent 能基于本文判断自己负责的模块边界
2. 不会把“会话域能力”和“Open API 出口能力”混在同一个模块里处理
3. Demo、SDK、平台后端三条工作线的关系清晰
