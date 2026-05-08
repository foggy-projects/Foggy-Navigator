# Biz Worker Artifact Externalization 设计

## 文档作用

- doc_type: requirement + architecture-design
- intended_for: biz-worker-runtime | architect | reviewer | signoff-owner
- purpose: 定义业务型 Worker 中长内容外部化、`artifact` 抽象、`create_artifact/read_artifact` 工具边界，以及基于账号目录的首版文件实现方案

## Version

- `1.1.1-SNAPSHOT`

## Status

- Ready for execution planning
- 2026-04-18

## 1.1.1 执行口径

本设计在 `1.1.1-SNAPSHOT` 首版按以下口径落地：

1. 只实现 `create_artifact` 和 `read_artifact`。
2. 只支持文本和 JSON 内容。
3. 只支持 `task` 与 `account` scope。
4. `account_id`、`task_id`、`created_by` 由 Runtime 注入，不接受模型传入。
5. 对模型只暴露 `artifact_id`、`name`、`scope`、`mime_type`、`size`、`summary`，不暴露 `content_ref`。
6. `read_artifact` 默认读取 summary，全文读取必须显式传 `mode=content`。
7. 长内容外部化后，活跃上下文默认只保留轻量引用，不继续携带原始 `content`。
8. Worker 自己持久化或继续传给 LLM 的历史消息中，`create_artifact(content=...)` 的原始 `content` 必须替换为轻量占位；外部 LLM provider 请求日志不纳入本轮治理范围。
9. 首版 `create_artifact` 单次内容大小上限为 1MB。

## 1. 背景

在业务型 Worker 中，LLM 经常会发起带长参数的工具调用，例如：

1. 大段 JSON payload
2. 长文本说明
3. DSL / SQL / 筛选表达式
4. 长列表或批量对象集合

这些内容在执行工具时可能是必须的，但在后续推理中未必还需要继续保留在活跃上下文。

如果每轮都将这类长参数及其原始结果继续放回上下文，会带来：

1. token 成本快速膨胀
2. 上下文噪音增大
3. 后续推理难以聚焦
4. 对话与运行态审计耦合在一起

因此需要一套“先外部化，再传引用”的通用机制。

## 2. 设计结论

本设计建议引入 `artifact` 作为业务型 Worker 的基础外部化对象，并仅首版提供两个基础能力：

1. `create_artifact`
2. `read_artifact`

结论如下：

1. LLM 不直接长期携带长内容，而是先调用 `create_artifact`
2. Worker 返回 `artifact_id`
3. 后续业务工具优先接收 `*_ref` 或 `artifact_id`
4. 活跃上下文默认只保留 `artifact_id + summary`
5. 原始内容保存在 Worker 管理下的外部存储中
6. 首版物理实现直接使用账号目录下的文件系统
7. 对模型暴露的是逻辑对象 `artifact`，不是实际文件路径

## 3. 非目标

首版不包含以下能力：

1. `delete_artifact`
2. `list_artifacts`
3. 多后端存储切换 UI
4. 跨账号共享 artifact
5. 自动向量索引与相似检索
6. artifact 生命周期全自动归档系统

## 4. 设计原则

### 4.1 对模型暴露逻辑对象，不暴露物理路径

模型侧只看到：

1. `artifact_id`
2. `summary`
3. 必要 metadata

模型不应直接操作：

1. 本地文件路径
2. 对象存储 key
3. 数据库存储主键

### 4.2 原始内容默认不进入长期活跃上下文

artifact 创建成功后，活跃上下文中只保留：

1. `artifact_id`
2. `name`
3. `mime_type`
4. `summary`

原始 `content` 默认不继续驻留。治理边界为 Worker 自己持久化或继续传给 LLM 的上下文，包括 tool message、private message、Frame state 或后续 provider message history；这些位置中的原始 `content` 必须替换为轻量占位，例如：

```json
{
  "content": "[externalized: art_01JZ8Y6Y8M7C4H2K9P1D, size=8342, summary=异常订单分析原始载荷]"
}
```

外部 LLM provider 的请求日志、网关日志或第三方审计日志不纳入 `1.1.1-SNAPSHOT` 的上下文治理范围。

### 4.3 物理实现可先用文件系统，但上层协议不得绑定文件

首版允许直接在账号目录下落文件，但：

1. 业务工具不以文件路径作为输入协议
2. LLM 不以路径为主要引用方式
3. 未来可以无缝迁移到对象存储或数据库

## 5. Artifact 抽象模型

artifact 是一个由 Worker 托管的外部化内容对象，用于保存：

1. 长工具入参
2. 长工具结果快照
3. 业务过程中的临时材料
4. 需要跨多轮引用但不适合长期放入上下文的内容

建议最小属性：

1. `artifact_id`
2. `account_id`
3. `scope`
4. `task_id`
5. `name`
6. `mime_type`
7. `encoding`
8. `size`
9. `summary`
10. `created_by`
11. `created_at`
12. `content_ref`
13. `sha256`

其中：

1. `artifact_id` 对外唯一引用
2. `content_ref` 为 Worker 内部使用，不对模型暴露
3. `summary` 用于后续对话中引用 artifact 时的轻量描述

## 6. Scope 设计

首版只支持两种 scope：

1. `task`
2. `account`

含义：

1. `task`：仅与当前任务绑定，适合大部分临时长参数
2. `account`：账号级长期内容，适合后续可复用材料

首版不引入：

1. `turn`
2. `frame`
3. `workspace`

这些可在后续版本按需要补充。

## 7. 账号目录下的首版实现

每个业务账号在 Worker 所在服务器上有独立根目录。

在账号目录下新增：

```text
<data_root>/
  accounts/
    <account-id>/
      artifacts/
        task/
          <task-id>/
            meta/
              art_xxx.json
            content/
              art_xxx.bin
        account/
          meta/
            art_xxx.json
          content/
            art_xxx.bin
```

说明：

1. `meta/` 保存 artifact metadata
2. `content/` 保存 artifact 实际内容
3. `task/` 用于任务级 artifact
4. `account/` 用于账号级 artifact

这样做的优点：

1. 结构简单，首版实现成本低
2. 与账号私有目录天然对齐
3. 便于排障、审计和后续迁移

## 8. Metadata 文件格式

每个 artifact 对应一个 metadata JSON 文件。

建议示例：

```json
{
  "artifact_id": "art_01JZ8Y6Y8M7C4H2K9P1D",
  "account_id": "acct_001",
  "scope": "task",
  "task_id": "task_001",
  "name": "exception-payload",
  "mime_type": "application/json",
  "encoding": "utf-8",
  "size": 8342,
  "summary": "异常订单分析原始载荷",
  "created_by": "llm",
  "created_at": "2026-04-18T10:20:00Z",
  "content_ref": "accounts/acct_001/artifacts/task/task_001/content/art_01JZ8Y6Y8M7C4H2K9P1D.bin",
  "sha256": "..."
}
```

约束：

1. `content_ref` 是内部字段，不对模型暴露
2. `artifact_id` 建议使用 ULID
3. `sha256` 用于内容完整性校验

## 9. `create_artifact`

### 9.1 作用

用于将长文本或大对象外部化，并返回一个可引用的 `artifact_id`。

### 9.2 输入建议

```json
{
  "name": "exception-payload",
  "content": "{...}",
  "mime_type": "application/json",
  "encoding": "utf-8",
  "scope": "task",
  "summary": "异常订单分析原始载荷"
}
```

说明：

1. `content` 为原始文本内容
2. `scope` 首版只允许 `task | account`
3. `summary` 可由模型提供，也可由 Runtime 在缺失时自动生成
4. `account_id`、`task_id` 不由模型提供，由 Runtime 注入
5. 首版单次 `content` 最大 1MB，超出时返回 `content_too_large`

### 9.3 输出建议

```json
{
  "artifact_id": "art_01JZ8Y6Y8M7C4H2K9P1D",
  "name": "exception-payload",
  "scope": "task",
  "mime_type": "application/json",
  "size": 8342,
  "summary": "异常订单分析原始载荷"
}
```

### 9.4 运行时行为

1. 生成 `artifact_id`
2. 计算 metadata
3. 将 `content` 写入账号目录中的 `content/`
4. 将 metadata 写入 `meta/`
5. 将 `artifact_id + summary` 返回给模型
6. 默认不把原始 `content` 继续留在后续活跃上下文

### 9.5 错误边界

首版至少需要返回可区分的错误：

1. `invalid_scope`：scope 不是 `task | account`
2. `invalid_content_type`：非文本或 JSON 内容
3. `missing_task_context`：创建 task-scope artifact 时缺少 `task_id`
4. `account_context_required`：Runtime 未取得当前账号
5. `content_too_large`：超过首版 1MB 内容上限
6. `storage_write_failed`：metadata 或 content 写入失败

## 10. `read_artifact`

### 10.1 作用

用于按需重新读取已外部化的内容。

### 10.2 输入建议

```json
{
  "artifact_id": "art_01JZ8Y6Y8M7C4H2K9P1D",
  "mode": "summary"
}
```

建议支持的 `mode`：

1. `metadata`
2. `summary`
3. `content`

### 10.3 输出建议

```json
{
  "artifact_id": "art_01JZ8Y6Y8M7C4H2K9P1D",
  "name": "exception-payload",
  "scope": "task",
  "mime_type": "application/json",
  "size": 8342,
  "summary": "异常订单分析原始载荷",
  "content": null
}
```

当 `mode = content` 时，再返回 `content`。

### 10.4 默认策略

1. 默认优先使用 `summary`
2. 全文读取必须显式指定 `mode = content`
3. Runtime 应校验当前账号是否有权访问该 artifact

### 10.5 读取权限

首版读取权限规则：

1. account-scope artifact：只允许同一 `account_id` 读取。
2. task-scope artifact：只允许同一 `account_id + task_id` 读取。
3. `content_ref` 为内部字段，任何 `mode` 都不返回给模型。
4. 找不到 artifact 或无权读取时，不泄漏真实物理路径。

## 11. 与工具调用的关系

业务工具应尽量支持两种输入模式：

1. 直接值
2. 引用值

推荐优先支持：

1. `payload_ref`
2. `input_ref`
3. `document_ref`

而不是强制模型始终传大段原文。

示例：

```json
{
  "order_id": "O001",
  "payload_ref": "art_01JZ8Y6Y8M7C4H2K9P1D"
}
```

## 12. 上下文治理规则

首版建议直接定以下规则：

1. 超过阈值的长参数，优先先 `create_artifact`
2. 某些工具参数可直接定义为只接受 `*_ref`
3. artifact 创建成功后，活跃上下文默认只保留 `artifact_id + summary`
4. 如需重新查看原文，模型必须显式调用 `read_artifact`
5. 对 Worker 后续持久化或继续传给 LLM 的历史消息，必须 scrub `create_artifact` 的原始 `content`，保留轻量占位和可审计 metadata

这样可以避免：

1. 工具入参在上下文中长期膨胀
2. 每轮重复携带无关长文本
3. 原始数据和推理上下文强耦合

## 13. 为什么不直接暴露文件读写

虽然首版底层直接用文件系统落地，但不建议对模型直接暴露“按路径写文件/读文件”作为 artifact 主协议。

原因：

1. 文件路径会把上层协议与物理实现耦合
2. 路径权限和可见范围难以稳定收口
3. 后续迁移到对象存储时会破坏协议
4. 业务型 Worker 的内容对象不一定天然等价于用户文件

因此建议：

1. 底层用文件
2. 上层用 `artifact_id`

## 14. 与 Coding Agent 的差异

Claude Code、Codex 这类 coding agent 经常直接以文件系统作为主要外部化介质，因为其工作对象天然就是代码、配置和文档文件。

但业务型 Worker 中：

1. 长参数未必天然属于“用户文件”
2. 它更像临时业务材料或推理依赖对象
3. 更适合抽象为逻辑对象 `artifact`

因此本设计不直接复用 coding agent 的文件协议，而是在内部借用文件系统作为首版实现。

## 15. 首版约束

1. 首版仅支持文本与 JSON 内容
2. 首版不支持二进制大文件上传
3. 首版不支持 `delete_artifact`
4. 首版不支持 `list_artifacts`
5. 首版不支持跨账号访问
6. 首版不支持跨任务共享 task-scope artifact

## 16. 后续演进方向

后续可逐步扩展：

1. `list_artifacts`
2. `delete_artifact`
3. `workspace` scope
4. `frame` scope
5. 对象存储后端
6. 数据库存 metadata + 对象存储 content
7. artifact 与 skill/frame 结果对象联动

## 17. 结论

本设计建议将 artifact 作为业务型 Worker 的基础外部化对象，并采用：

1. `create_artifact`
2. `read_artifact`

两个最小工具能力先落地。

其核心思想是：

1. 对模型暴露逻辑对象 `artifact_id`
2. 对 Worker 内部使用账号目录下的文件系统做首版实现
3. 活跃上下文只保留 `artifact_id + summary`
4. 长内容通过“先外部化，再传引用”方式参与后续工具调用

这样既能控制上下文膨胀，又能保留后续迁移与扩展空间。
