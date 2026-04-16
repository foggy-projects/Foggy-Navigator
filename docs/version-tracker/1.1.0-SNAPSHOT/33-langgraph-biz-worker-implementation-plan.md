# LangGraph Biz Worker 实现计划

## 文档作用

- doc_type: implementation-plan
- intended_for: java-backend + python-worker + reviewer + task-owner
- purpose: 将 LangGraph Biz Worker 与 Skill Runtime 方案拆解成可执行开发计划、模块责任、阶段交付物、测试基线与风险控制项

## 1. 上游设计输入

本计划以下列文档为上游设计输入：

1. [31-langgraph-biz-worker-skill-runtime-design.md](./31-langgraph-biz-worker-skill-runtime-design.md)
2. [32-langgraph-biz-worker-tms-sequence-and-api-contract.md](./32-langgraph-biz-worker-tms-sequence-and-api-contract.md)

本文件不重复设计原则，只负责把方案拆成可开工项。

## 2. 实现目标

首版实现应满足：

1. 系统中新增 `providerType = langgraph-biz-worker`
2. 可以通过统一任务入口创建业务型 Worker 任务
3. Skill 通过独立 Frame 执行，不污染父任务上下文
4. Skill 只能通过 `submit_skill_result` 完成交卷
5. Runtime 统一提交 `COMPLETED`
6. 支持 TMS 首批 3 个 Skill 的最小链路跑通

## 3. 模块责任划分

### 3.1 Java 侧责任

Java 侧负责：

1. 对接现有统一任务分发体系
2. 管理 Worker 实例、任务实体、会话绑定、SSE relay
3. 将 Python Worker 的事件转为平台统一事件
4. 提供审批/恢复 API
5. 承担统一审计与平台权限边界

不负责：

1. Skill 内部路由
2. Frame 私有状态推进
3. Output contract 业务校验细节

### 3.2 Python 侧责任

Python 侧负责：

1. Root Graph 与 Skill Subgraph 执行
2. Frame 生命周期管理
3. Skill Registry / Manifest 加载
4. `submit_skill_result` / `request_skill_approval`
5. Skill 私有上下文隔离与关闭
6. TMS 工具适配

不负责：

1. 平台统一任务分发
2. 平台级会话绑定模型
3. Java 侧统一任务投影

## 4. 模块归属

- **Java 侧**：`addons/langgraph-biz-worker/` — 职责域参见 Doc 31 §5.1
- **Python 侧**：`tools/langgraph-biz-worker/` — 职责域参见 Doc 31 §5.2

> 具体包结构、类设计和文件骨架由实现阶段的子 agent 自行决定，不在规划层锁定。

## 5. 首版开发阶段

### Phase 1: Provider 接入与最小任务链路

- **depends_on**: 无
- **owner**: Java 侧 + Python 侧（联调）

目标：

1. Java 侧识别 `langgraph-biz-worker`
2. Python Worker 可接收 `/api/v1/query`
3. Root Graph 能返回固定结果
4. Java SSE relay 能打通

交付项：

- **[Java]** `TaskQueryProvider` 实现、`A2aAgentProvider` 适配、Worker Client、SecurityConfig 权限配置
- **[Python]** `/api/v1/query` + `/health` 端点、Root Graph 固定结果返回

验收标准：

1. 可通过统一入口创建 `langgraph-biz-worker` 任务
2. 可收到 `assistant_text` + `result` SSE 事件
3. 统一任务列表可见该任务
4. Java 侧单元测试全部运行通过

### Phase 2: Frame Runtime 与完成协议

- **depends_on**: Phase 1
- **owner**: Python 侧（为主）

目标：

1. 引入 `SkillFrameState`
2. 引入 `submit_skill_result`
3. Skill 只能通过提交工具完成
4. Runtime 控制 `COMPLETED`

交付项：

- **[Python]** Skill Runtime（Frame 生命周期管理）、Frame Store、系统工具（`submit_skill_result`）、Output Contract 校验
- **[Python]** `skill_frame_open` / `skill_frame_close` SSE 事件

验收标准：

1. 单 Skill 能创建 Frame
2. `submit_skill_result` 合格时写入 `COMPLETED`
3. 关闭后不再注入私有上下文
4. Python 侧单元测试全部运行通过

### Phase 3: 单 Skill 示例

- **depends_on**: Phase 2
- **owner**: Python 侧

目标：

1. 跑通一个示例 Skill（如异常分诊）
2. 使用 Mock 业务工具（不引入真实外部系统）
3. 支持输出契约校验

交付项：

- **[Python]** 示例 Skill Manifest + Subgraph、Mock 业务工具

验收标准：

1. 能产出结构化结果
2. 不合格提交会被拒绝并允许有限重试
3. Python 侧单元测试 + 集成测试全部运行通过

### Phase 4: 子 Skill 调用

- **depends_on**: Phase 3
- **owner**: Python 侧

目标：

1. 父 Skill 调用子 Skill
2. 支持 `WAITING_CHILD` 状态
3. child frame 结果回写 parent private state

交付项：

- **[Python]** 子 Skill Manifest + Subgraph、child frame 生命周期管理

验收标准：

1. child frame 完成后立即关闭
2. 父 frame 可以继续执行
3. child scratchpad 不进入 parent public state
4. Python 侧单元测试 + 集成测试全部运行通过

### Phase 5: 多子 Skill 聚合

- **depends_on**: Phase 4
- **owner**: Python 侧

目标：

1. 引入第二个子 Skill
2. 父 Skill 聚合多个子 Skill 结果

交付项：

- **[Python]** 第二个子 Skill Manifest + Subgraph、parent 聚合逻辑

验收标准：

1. Root 仅看到父 Skill 聚合结果
2. 所有 child 的 scratchpad 不进入 parent public state
3. Python 侧单元测试 + 集成测试全部运行通过

### Phase 6: 审批与恢复

- **depends_on**: Phase 3（审批可并行于 Phase 4/5 开发）
- **owner**: Java 侧 + Python 侧（联调）

目标：

1. 审批型 Skill 可中断
2. Java 可调用审批/恢复接口
3. Python Worker 可恢复中断 Frame

交付项：

- **[Python]** `request_skill_approval` 系统工具、审批挂起/恢复逻辑
- **[Java]** 审批/恢复 API 端点、SecurityConfig 权限补充

验收标准：

1. Skill 可停在 `AWAITING_APPROVAL`
2. 审批后可恢复并继续执行
3. Java 侧 + Python 侧单元测试 + 集成测试全部运行通过

### 质量门与验收链路

每个 Phase 完成后必须依次通过：

1. **单元测试 + 集成测试全部运行通过**
2. **`foggy-implementation-quality-gate`** — 实现质量闸门检查
3. 最终 Phase 完成后执行 **`foggy-test-coverage-audit`** + **`foggy-acceptance-signoff`**

## 6. 分工职责边界

Java 侧和 Python 侧的具体类设计由实现阶段决定，此处只定义职责边界：

### 6.1 Java 侧职责域

参见 §3.1，核心是对接统一任务分发、SSE Relay、审批/恢复 API。

### 6.2 Python 侧职责域

参见 §3.2，核心是 Frame Runtime、Skill Registry、Output Contract、Graph 执行。

> 具体的类命名、方法签名、包结构由子 agent 在开工时自行设计。

## 8. 状态存储建议

### 8.1 Task 级持久化

建议由 Java 侧持久化：

1. Worker 任务主状态
2. Session 绑定
3. 最终结果投影

### 8.2 Frame 级持久化

建议由 Python 侧持久化：

1. `SkillFrameState`
2. `approval_state`
3. `artifact_refs`
4. 调试痕迹

首版可以使用：

1. SQLite / 本地文件存储
2. 或复用现有数据库连接

但必须满足：

1. 支持按 `taskId/frameId` 恢复
2. 支持 `AWAITING_APPROVAL` 后恢复

## 9. 事件与可观测性

建议新增 Worker 事件：

1. `skill_frame_open`
2. `skill_frame_close`
3. `skill_result_submit`
4. `skill_result_reject`
5. `approval_request`
6. `approval_resume`

建议日志字段：

1. `taskId`
2. `sessionId`
3. `frameId`
4. `parentFrameId`
5. `skillId`
6. `status`
7. `approvalType`

## 10. 测试计划

**核心要求：所有测试必须运行通过，才算对应 Phase 完成。**

### 10.1 Java 侧单元测试

至少覆盖以下场景（具体测试类名由实现阶段决定）：

1. TaskQueryProvider 任务创建与持久化
2. SSE Relay 事件映射
3. A2aAgentProvider 适配

### 10.2 Python 侧单元测试

至少覆盖以下场景：

1. Frame 状态机流转（全部合法转换 + 非法转换拒绝）
2. `submit_skill_result` 合格/不合格校验
3. `close_frame()` 后不保留私有上下文
4. child frame 结果回写 parent private state

### 10.3 集成测试

至少覆盖以下场景（使用 Mock 业务工具）：

1. 创建 `langgraph-biz-worker` 任务（Java → Python 链路）
2. 单 Skill 完整生命周期（创建 Frame → 提交结果 → 关闭 Frame）
3. 子 Skill 嵌套完成（child 关闭 → 结果回写 → parent 继续）
4. 提交不合格结果被拒绝并允许重试
5. 审批挂起与恢复

### 10.4 手工验证

至少验证：

1. 任务 UI 可见 skill frame 打开/关闭过程
2. 最终任务结果正确
3. 关闭 frame 后历史消息中不暴露 skill 私有 scratchpad

## 11. 风险与控制项

### 11.1 风险：模型不按协议交卷

控制：

1. 只认 `submit_skill_result`
2. 有限重试
3. 超限失败

### 11.2 风险：Skill 资产泄漏到父上下文

控制：

1. 只允许 `promote_to_parent` 白名单字段上浮
2. frame 关闭时主动清空 private state

### 11.3 风险：审批恢复后状态漂移

控制：

1. 以 `frameId` 为恢复主键
2. 恢复前再次校验当前 frame 状态

### 11.4 风险：首版范围过大

控制：

1. 先做单 Skill
2. 再做子 Skill
3. 最后做审批恢复
4. 首版不引入外部业务工具，全部使用 Mock/Stub 实现
5. 外部工具适配层延迟到业务系统集成阶段

## 12. 建议开发顺序

建议实际排期顺序（标注侧归属）：

1. **[Java]** Provider 接入 + SecurityConfig 权限
2. **[Python]** Worker 基础服务（Query / Health）
3. **[Java+Python]** 最小链路联调
4. **[Python]** Frame Runtime + `submit_skill_result`
5. **[Python]** 单 Skill 示例（Mock 工具）
6. **[Python]** 子 Skill 嵌套
7. **[Java+Python]** 审批恢复
8. **[Java+Python]** 完整链路联调 + 质量门 + 验收

## 13. 首版约束总结

1. **首版不引入外部业务工具**：Skill 子图中的业务工具一律使用 Mock/Stub 实现
2. **外部工具延迟到集成阶段**：等真实业务系统对接时再设计工具适配层
3. **Java/Python 分工明确**：每个 Phase 的交付项按侧标注归属
4. **质量门前置**：每个 Phase 测试必须运行通过 → quality-gate → 下一 Phase

## 14. 当前结论

当前方案已满足开工前设计收口要求：

1. 架构边界明确
2. Skill 完成协议明确
3. Frame 生命周期明确
4. Java/Python 分工明确
5. 阶段依赖关系明确
6. 质量门与验收链路明确

下一步可进入：

1. 代码骨架搭建（Java 侧 + Python 侧分别开工）
2. 状态模型定义
3. 首批测试样例编写
