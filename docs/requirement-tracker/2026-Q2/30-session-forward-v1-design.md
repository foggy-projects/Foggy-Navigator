# Worker 会话转发 V1 设计

## 文档作用

- doc_type: requirement + implementation-plan
- intended_for: root-controller + sub-agent + reviewer
- purpose: 定义 Worker 会话“转发当前回复”V1 的范围、关系模型、接口编排、前端交互与 V2 拆分边界

## 1. 背景

当前 Worker 会话里已经具备：

- `parentSessionId` 能表达“新建子会话”
- `providerType`、`currentDirectoryId` 能表达会话绑定的执行上下文
- `milestoneId` 能表达会话所属里程碑，且里程碑严格归属某个工作目录
- 前端已有会话搜索弹窗，可复用“选择已有会话”的能力

但当前仍缺少一条完整能力链：

1. 用户无法从某条 assistant 回复直接发起“转发”
2. `parentSessionId` 只能表达父子会话，不能表达“源回复 -> 目标会话”的精确关系
3. “转发到已有会话”与“转发并创建新会话”在执行上下文约束上并不相同
4. 里程碑跟随规则目前只支持手工设置，不支持在转发时继承或重新选择

结论：

- 会话转发不能只靠前端拼接已有接口完成
- 需要在后端建立显式关系记录，并提供一次性编排入口

## 2. V1 目标

V1 仅交付“转发并创建新会话”：

1. 在 assistant 消息上提供“转发”入口
2. 点击后弹出转发对话框，默认内容为当前回复正文，允许用户编辑
3. 用户可选择目标 Worker、工作目录、目录内路径、模型配置、模型、权限模式、Agent Teams 配置
4. 创建目标新会话时，自动建立与源会话、源回复的关系
5. 同时保留 `parentSessionId = sourceSessionId`，维持现有父子会话语义
6. 支持源会话里程碑带入；跨目录时允许重新选择目标目录下的里程碑
7. 在历史会话区提供最小可用的父子会话可见性，但不重构整棵列表

## 3. 非目标

以下内容不在 V1：

1. 转发到已有会话
2. 允许在已有会话上切换目录、Provider、模型绑定
3. 深层树形会话管理器
4. 将源会话历史自动并入目标会话上下文
5. 通用多关系图谱 UI

## 4. 关键约束

### 4.1 现有会话绑定不可漂移

已有会话的 `providerType`、`currentDirectoryId`、上下文绑定是续聊链路的真相源。

因此：

- V2“转发到已有会话”时，目录、Provider、模型配置均不得修改
- V1“新建会话”才允许重新选择目标执行上下文

### 4.2 里程碑归属工作目录

`milestoneId` 必须属于目标会话的 `currentDirectoryId`。

因此：

- 同目录转发时可默认继承源里程碑
- 跨目录转发时不能盲目沿用原里程碑
- 前端必须给出目标目录下的里程碑选择器

### 4.3 转发来源必须是稳定消息

当前 session message 有稳定 `messageId`，因此关系可精确落到“源 assistant message”。

V1 约束：

- 仅允许转发已落库的 assistant 文本消息
- 对流式生成中的消息不提供转发入口，或置灰直到消息完成

## 5. 核心设计

### 5.1 关系模型

V1 即引入独立关系表，不只依赖 `parentSessionId`。

建议新增：`session_relations`

建议字段：

- `id`
- `user_id`
- `relation_type`，V1 固定为 `FORWARD`
- `source_session_id`
- `source_message_id`
- `target_session_id`
- `target_mode`，V1 固定为 `NEW_SESSION`
- `source_directory_id`
- `target_directory_id`
- `source_milestone_id`
- `target_milestone_id`
- `metadata_json`
- `created_at`

说明：

- `parentSessionId` 继续保留，解决“父子导航”问题
- `session_relations` 解决“这条回复转发到了哪里”问题
- V2 转发到已有会话时，仍复用同一关系表，只需把 `target_mode` 扩展为 `EXISTING_SESSION`

### 5.2 V1 编排接口

不建议前端串联“创建任务 + 单独改里程碑 + 单独落关系”三个已有接口。

原因：

- 现有统一任务接口没有 `parentSessionId`
- 现有统一任务接口也不负责落转发关系
- 分三次调用会放大失败回滚和前端状态收口复杂度

建议新增专用接口：

`POST /api/v1/session-relations/forward`

请求体建议：

- `sourceSessionId`
- `sourceMessageId`
- `prompt`
- `workerId`
- `directoryId`
- `cwd`
- `modelConfigId`
- `model`
- `permissionMode`
- `agentId`
- `agentTeamsConfigId`
- `agentTeamsJson`
- `milestoneId`

返回体建议：

- `relationId`
- `targetSessionId`
- `targetTaskId`
- `providerType`
- `directoryId`
- `milestoneId`

### 5.3 后端执行顺序

建议由 `session-module` 提供单点编排服务：

1. 校验源会话归属当前用户
2. 校验 `sourceMessageId` 属于 `sourceSessionId`
3. 校验源消息角色为 assistant，且内容非空
4. 根据目标目录和模型配置解析目标执行上下文
5. 创建目标新会话，并写入 `parentSessionId = sourceSessionId`
6. 若请求携带 `milestoneId`，先校验该里程碑属于目标目录
7. 启动首个任务，将编辑后的 `prompt` 作为新会话第一条用户输入
8. 持久化 `session_relations` 关系记录
9. 返回目标会话与任务信息

收口规则：

- 只有目标会话与首任务创建成功后，才写入关系记录
- 若里程碑校验失败，整次转发失败，不创建目标会话
- 若关系记录持久化失败，接口返回失败并打错误日志，避免前端误以为已建立关联

## 6. 前端交互

### 6.1 入口位置

V1 在 assistant `MessageBubble` 上新增一个轻量操作：

- `转发`

不挂在整条会话的下拉菜单里。

原因：

- 用户要表达的是“转发这条回复”，不是“基于整个会话做复制”
- 后续 V2 也需要以消息为入口，避免再次迁移交互心智

### 6.2 转发弹窗

V1 弹窗字段建议：

- 源消息摘要，只读
- 转发内容，多行文本，默认填入当前回复正文，可编辑
- 目标 Worker
- 目标工作目录
- 目标路径 `cwd`
- 模型配置
- 模型
- 权限模式
- Agent Teams 配置
- 里程碑

V1 不提供“转发到已有会话”模式切换；弹窗标题可直接写成：

- `转发并创建新会话`

### 6.3 里程碑默认规则

建议规则如下：

1. 若源会话无里程碑，则默认空
2. 若目标目录与源目录相同，则默认选中源里程碑
3. 若目标目录不同，则默认空，但展示目标目录下里程碑选择器
4. 若目标目录没有任何里程碑，则选择器禁用并提示“目标目录暂无可选里程碑”

这样处理最稳妥：

- 同目录转发符合“带上原里程碑”的直觉
- 跨目录转发不假设里程碑可以跨目录复用
- UI 仍然给用户保留明确选择权

## 7. 历史会话展示

V1 不建议直接改成树形列表。

原因：

- 当前历史区已经按里程碑分组
- 树形缩进会同时改变分组、排序、收起逻辑和键盘导航
- V2 还会引入“转发到已有会话”的多源关系，树结构不再天然成立

V1 建议采用轻量展示：

1. 父会话行显示 `子会话 N` 徽标，可点击查看或跳转
2. 子会话行显示 `来自 xxx` 徽标，可点击回到父会话
3. 初版只展示直接父级，不展示深层层级树

这样可以先满足可见性与可跳转，不破坏当前会话列表主体结构。

## 8. V1 / V2 拆分

### 8.1 V1

交付内容：

- 消息级“转发”入口
- 新建会话转发弹窗
- 后端转发编排接口
- `session_relations` 关系表
- `parentSessionId` 与关系表双写
- 里程碑默认与选择逻辑
- 历史列表轻量父子标识

### 8.2 V2

在 V1 基础上增加：

- 转发到已有会话
- 复用 `SessionSearchDialog` 选择目标会话
- 目标会话执行上下文只读展示，不允许修改目录、Provider、模型配置
- 一个目标会话可接收多次转发，关系表记录多条来源
- 更细的历史关系浏览，例如来源列表或关系侧板

## 9. 模块职责

### 9.1 `packages/foggy-chat`

负责：

- 在 `MessageBubble` / `MessageList` 暴露消息级转发事件

不负责：

- 目标会话选择业务
- 里程碑规则
- 转发接口编排

### 9.2 `packages/navigator-frontend`

负责：

- 转发弹窗
- 目标目录、模型配置、里程碑联动
- 调用后端转发接口
- 历史区父子标识展示

### 9.3 `session-module`

负责：

- 转发编排接口
- 关系表持久化
- 源消息校验
- 目标会话创建与关系落库
- 里程碑归属校验

### 9.4 `agent-framework`

负责：

- 继续承接 `parentSessionId` 语义

V1 不要求：

- 改动通用 delegation router 语义

## 10. Code Inventory

| repo/module | path | role | expected change | notes |
| --- | --- | --- | --- | --- |
| navigator-frontend | `packages/foggy-chat/src/components/MessageBubble.vue` | 消息操作入口 | update | 增加转发按钮或转发动作插槽 |
| navigator-frontend | `packages/foggy-chat/src/components/MessageList.vue` | 消息事件透传 | update | 把转发事件向上抛出 |
| navigator-frontend | `packages/foggy-chat/src/components/ChatPanel.vue` | 聊天面板事件透传 | update | 向宿主页面暴露 `forward` |
| navigator-frontend | `packages/navigator-frontend/src/components/worker` | 新增转发弹窗 | create | 复用现有目录、模型、里程碑数据源 |
| navigator-frontend | `packages/navigator-frontend/src/views/ClaudeWorkerView.vue` | Worker 主页面 | update | 接入转发弹窗、提交接口、展示父子标识 |
| navigator-frontend | `packages/navigator-frontend/src/api/unifiedTask.ts` or new API file | 前端 API 封装 | update | 新增转发接口调用，不混入现有 create/resume |
| session-module | `session-module/src/main/java/.../controller` | 转发入口 Controller | create | 专用接口，不污染普通 task create/resume |
| session-module | `session-module/src/main/java/.../service` | 转发编排 Service | create | 统一处理校验、建会话、发任务、落关系 |
| session-module | `session-module/src/main/java/.../entity` | 关系实体 | create | `SessionRelationEntity` |
| session-module | `session-module/src/main/java/.../repository` | 关系查询与落库 | create | 支撑父子标识和后续 V2 |
| session-module | `session-module/src/main/java/.../dto` | 请求响应 DTO | create | Forward request / result |
| session-module | `session-module/src/test` + integration-tests | 回归测试 | update | 覆盖成功、跨目录里程碑、非法消息、权限校验 |

## 11. 验收标准

V1 视为完成，至少满足：

1. 用户能从一条 assistant 回复点击“转发”
2. 转发弹窗默认带入该回复正文，并允许编辑
3. 提交后成功创建一个新会话和首个任务
4. 新会话 `parentSessionId` 指向源会话
5. 数据库能查到一条 `FORWARD` 关系，且含 `sourceMessageId`
6. 同目录转发时，源里程碑可默认带入
7. 跨目录转发时，用户可选择目标目录下里程碑；非法里程碑会被拒绝
8. 历史列表能看到父子关系入口并可互相跳转

## 12. 待确认项

当前只需要你拍一个 UI 决策：

1. 历史列表里，子会话展示更偏向哪种？
2. 方案 A：父会话显示 `子会话 N` 徽标，点击后弹出小面板列出子会话
3. 方案 B：父会话下方直接缩进展示直接子会话

我的建议是先做方案 A：

- 改动面更小
- 不破坏当前按里程碑分组的列表结构
- 到 V2 引入“转发到已有会话”后，也更容易兼容多来源关系
