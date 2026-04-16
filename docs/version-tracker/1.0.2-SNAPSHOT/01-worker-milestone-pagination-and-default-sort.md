# 01 Worker 里程碑分页与默认排序方案

## 文档作用

- doc_type: requirement | implementation-plan
- intended_for: execution-agent | reviewer
- purpose: 为 Worker 里程碑管理补齐开始/结束时间、分页与排序能力，并统一历史会话按里程碑分组的默认顺序。

## 基本信息

- version: `1.0.2-SNAPSHOT`
- date: `2026-04-15`
- source_type: optimization | UX enhancement
- priority: `P1`
- status: `draft`

## 背景

`1.0.1-SNAPSHOT` 已经补齐了目录级里程碑和历史会话按里程碑分组展示，但当前实现仍然停留在“轻量定义 + 全量列表”阶段：

- 里程碑模型只有 `id / name / status / docPath`
- 管理弹窗一次性拉全量列表，没有分页
- 排序语义依赖当前数组顺序，本质上等于“创建/编辑后的当前存储顺序”
- 历史会话分组顺序也跟随该数组顺序，没有明确时间语义

当单个工作目录的里程碑数量继续增加后，现有管理方式会同时暴露两个问题：

1. 难找，列表越来越长，缺少分页和显式排序
2. 难懂，用户无法直接按版本周期查看“最近里程碑”

## 现状评估

### 已确认实现边界

- 后端仍将里程碑序列化到 [`WorkingDirectoryEntity.java`](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/navigator-common/src/main/java/com/foggy/navigator/common/entity/WorkingDirectoryEntity.java) 的 `milestonesJson` 中，而不是独立表
- 里程碑 CRUD 由 [`WorkingDirectoryService.java`](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/WorkingDirectoryService.java) 和 [`MilestoneController.java`](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/controller/MilestoneController.java) 提供
- PC 端里程碑管理弹窗在 [`ClaudeWorkerView.vue`](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/packages/navigator-frontend/src/views/ClaudeWorkerView.vue#L2050) 中直接渲染全量列表
- 历史会话按里程碑分组的排序逻辑目前按 `selectedDirectory.milestones` 的数组下标排序，见 [`ClaudeWorkerView.vue`](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/packages/navigator-frontend/src/views/ClaudeWorkerView.vue#L3851)

### 关键判断

- 现阶段先不建议把里程碑拆成独立数据库表。当前里程碑仍然是“单目录内的轻量元数据”，需求核心是管理体验，而不是跨目录检索或统计分析
- 但分页不能只做前端假分页。既然用户已经明确要求“之后多了”，分页和排序应放在 API 层统一处理，确保当前页、总数、排序条件一致
- 由于底层还是单字段 JSON，本次分页属于“目录级内存分页/服务端分页”，不是 SQL 级分页。这对几十到低数百个里程碑是可接受的；若未来单目录里程碑长期上升到数百甚至上千，再评估独立表迁移

## 目标

为 Worker 里程碑管理补齐一套一致的顺序语义和基础可扩展管理能力：

1. 里程碑新增 `开始时间`、`结束时间`
2. 里程碑管理支持分页和排序
3. 默认排序统一为“按开始时间倒序；没有开始时间时按名称倒序”
4. 历史会话按里程碑分组时复用同一默认排序规则
5. 里程碑相关选择器和列表入口尽量复用同一排序规则，避免不同入口顺序不一致

## 范围

### In Scope

- 里程碑 DTO / Form / 前端类型增加开始时间、结束时间
- 里程碑新增、编辑时可维护开始时间、结束时间
- 里程碑管理弹窗支持分页
- 里程碑管理弹窗支持排序切换
- 历史会话中“按里程碑分组”的分组顺序改为统一默认排序
- 会话里程碑选择器、转发会话中的里程碑选择器默认使用相同排序结果
- 保持历史旧数据兼容，旧里程碑没有开始/结束时间时仍可正常展示和编辑

### Out Of Scope

- 里程碑独立数据库表
- 跨目录统一里程碑池
- 基于里程碑的报表、统计、权限控制
- 自动扫描 `docs/version-tracker/...` 反向生成里程碑
- 历史会话本身按里程碑分组后的“组内会话排序规则”重构

## 数据模型方案

## 里程碑字段

在现有字段基础上新增：

- `startAt`: 里程碑开始时间，允许为空
- `endAt`: 里程碑结束时间，允许为空

建议字段命名统一使用 `startAt / endAt`，接口传输使用 ISO-8601 字符串，后端落为 `LocalDateTime` 语义对应的 JSON 文本。这样既能兼容“日期级”使用，也保留后续扩展到小时级的空间。

最终模型：

- `id`
- `name`
- `status`
- `docPath`
- `startAt`
- `endAt`

## 校验规则

- `name` 必填
- `startAt`、`endAt` 可为空
- 当 `startAt`、`endAt` 同时存在时，`endAt` 不能早于 `startAt`
- 旧数据未带时间字段时，按空值处理，不做迁移阻塞

## 默认排序规则

统一定义 `MilestoneDefaultOrder`：

1. 有 `startAt` 的里程碑优先于无 `startAt` 的里程碑
2. 都有 `startAt` 时，按 `startAt DESC`
3. 都没有 `startAt` 时，按 `name DESC`
4. 若 `startAt` 相同，再按 `name DESC`
5. `未设置里程碑` 分组始终排最后

这个规则要在以下位置保持一致：

- 里程碑管理弹窗初始列表
- 历史会话按里程碑分组的组顺序
- 会话“设置里程碑”下拉/弹窗
- 转发会话中的里程碑选项

## 交互方案

## 里程碑管理弹窗

当前弹窗是“行内编辑”。加上 `开始时间`、`结束时间` 后，继续沿用单行内联编辑会过于拥挤，建议改成：

- 弹窗上部为筛选/排序区
- 中部为分页列表
- 新增/编辑改为二级表单弹层，或弹窗内切换为独立表单区

建议列表列：

- 名称
- 状态
- 开始时间
- 结束时间
- 文档路径
- 操作

建议默认分页：

- `pageSize = 10`
- 可切换 `10 / 20 / 50`

建议默认排序：

- 字段：`开始时间`
- 方向：`倒序`

排序切换至少支持：

- 开始时间
- 名称
- 状态
- 结束时间

排序变化后应重置到第 1 页。

## 历史会话按里程碑分组

当前历史会话区不需要增加单独分页逻辑，但需要把组顺序改成复用统一 comparator，而不是依赖当前数组下标。

预期行为：

- 最近开始的里程碑组显示在上方
- 没有开始时间的里程碑组按名称倒序排列
- `未设置里程碑` 始终在最后

## API 方案

## 保持兼容的接口策略

为避免打破现有前端调用，建议保留现有全量接口：

- `GET /api/v1/working-directories/{directoryId}/milestones`

其职责调整为：

- 返回“已按默认规则排好序”的全量列表
- 供历史会话分组、会话选择器、轻量下拉场景复用

新增分页接口，专供管理弹窗：

- `GET /api/v1/working-directories/{directoryId}/milestones/paged`

建议参数：

- `page`
- `size`
- `sortBy`
- `sortDir`

建议返回：

```json
{
  "items": [],
  "page": 1,
  "size": 10,
  "total": 42
}
```

这样可以避免一个路径在“有无分页参数”时返回两种完全不同的结构。

## 写接口

现有接口继续沿用，但入参与返回值补齐时间字段：

- `POST /api/v1/working-directories/{directoryId}/milestones`
- `PUT /api/v1/working-directories/{directoryId}/milestones/{milestoneId}`

删除接口无需改协议，但删除成功后前端要考虑：

- 若当前页删空且不是第一页，则回退上一页
- 删除后保留当前排序条件并刷新列表

## 实施方案

### Stage 1: 契约与排序内核

- 扩展后端 `DirectoryMilestoneDTO`、`DirectoryMilestoneForm`
- 扩展前端 `DirectoryMilestone` 类型
- 在后端沉淀统一排序 comparator，避免控制器和前端各写一份默认规则
- 更新 `WorkingDirectoryService` 的 JSON 解析、归一化、校验逻辑

### Stage 2: 分页查询接口

- 新增分页响应 DTO
- 在 `WorkingDirectoryService` 中先完成排序，再做分页切片
- `listMilestones` 默认返回全量有序列表
- 新增 `listMilestonesPaged` 供管理弹窗使用

### Stage 3: PC 端里程碑管理 UI 重构

- 改造 [`ClaudeWorkerView.vue`](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/packages/navigator-frontend/src/views/ClaudeWorkerView.vue) 的里程碑管理区域
- 管理列表改为“分页表格 + 新增/编辑表单”
- 新增开始/结束时间输入组件
- 默认按开始时间倒序加载
- 切换排序、翻页、保存、删除后保持当前管理上下文

### Stage 4: 默认顺序统一

- 历史会话分组逻辑改为使用统一里程碑默认排序
- “设置里程碑”弹窗/下拉复用排序后的列表
- 转发会话里程碑选择器复用排序后的列表
- 如移动端也直接使用相同类型定义，同步补齐字段，避免契约漂移

### Stage 5: 测试与回归

- 后端单测覆盖排序、分页、时间校验、旧数据兼容
- 前端单测覆盖排序 helper 或视图层分页状态切换
- Playwright 回归覆盖里程碑新增/编辑/排序/分页/历史分组顺序

## 代码触点清单

| repo/module | path | role | expected_change | notes |
| --- | --- | --- | --- | --- |
| `navigator-common` | `navigator-common/src/main/java/com/foggy/navigator/common/dto/DirectoryMilestoneDTO.java` | 里程碑共享 DTO | update | 增加 `startAt/endAt` |
| `navigator-common` | `navigator-common/src/main/java/com/foggy/navigator/common/entity/WorkingDirectoryEntity.java` | 目录里程碑 JSON 容器 | update | 注释与兼容说明更新，无需新列 |
| `claude-worker-agent` | `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/model/form/DirectoryMilestoneForm.java` | 里程碑写接口 Form | update | 增加时间字段 |
| `claude-worker-agent` | `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/controller/MilestoneController.java` | 里程碑 CRUD / 分页接口 | update | 保留旧列表接口并新增 paged |
| `claude-worker-agent` | `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/WorkingDirectoryService.java` | 里程碑排序、分页、校验核心 | update | 增加 comparator 和分页切片 |
| `claude-worker-agent` | `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/service/WorkingDirectoryServiceTest.java` | 后端单测 | update | 覆盖分页、排序、时间校验 |
| `navigator-frontend` | `packages/navigator-frontend/src/types/index.ts` | 前端类型 | update | 增加 `startAt/endAt` |
| `navigator-frontend` | `packages/navigator-frontend/src/api/claudeWorker.ts` | 前端 API | update | 新增 paged 查询接口 |
| `navigator-frontend` | `packages/navigator-frontend/src/views/ClaudeWorkerView.vue` | 里程碑管理与历史分组 UI | update | 管理弹窗改分页排序，历史分组改默认顺序 |
| `navigator-frontend` | `packages/navigator-frontend/src/...milestone helper...` | 排序复用 helper | create or update | 建议抽成独立 helper，避免在 view 内重复比较逻辑 |
| `foggy-mobile` | `packages/foggy-mobile/src/api/types.ts` | 移动端契约同步 | update | 如果本次发布面向统一契约，需同步新增字段 |
| `foggy-mobile` | `packages/foggy-mobile/src/composables/useMilestoneGroups.ts` | 移动端分组顺序 | update | 如要求移动端也统一排序，则同步 comparator |

## 验收标准

1. 用户可以为里程碑维护 `开始时间`、`结束时间`
2. 管理弹窗默认按 `开始时间倒序` 展示里程碑
3. 对于没有 `开始时间` 的里程碑，默认按 `名称倒序` 排序
4. 管理弹窗支持分页，翻页后列表内容与总数正确
5. 管理弹窗支持切换排序字段和排序方向
6. 历史会话按里程碑分组时，组顺序与默认排序规则一致
7. `未设置里程碑` 分组始终排在最后
8. 旧里程碑数据即使没有时间字段，也不会报错，且可正常编辑
9. 当用户输入 `结束时间 < 开始时间` 时，前后端至少一侧给出明确校验错误
10. 会话里程碑选择器的默认选项顺序与管理弹窗默认顺序一致

## 风险与决策

### 风险 1: JSON 存储下的分页不是真正数据库分页

- 影响：单目录里程碑极大时，仍需先解析完整 JSON 再排序分页
- 当前结论：先接受。以当前里程碑能力定位，这个复杂度和改造成本更平衡

### 风险 2: 当前行内编辑布局无法自然承载更多字段

- 影响：如果仍坚持单行编辑，界面会拥挤且移动焦点容易混乱
- 当前结论：管理弹窗建议改成表格 + 独立新增/编辑表单，不继续堆叠单行输入框

### 风险 3: 多端契约可能漂移

- 影响：PC 端新增字段后，移动端若仍引用旧类型，后续容易出现类型和排序行为不一致
- 当前结论：至少同步类型定义；是否同步移动端 UI 行为，可根据本次发布范围决定

## 非目标

- 本次不引入复杂项目管理属性，例如负责人、描述、进度百分比
- 本次不重做历史会话整体分页机制
- 本次不要求自动根据版本目录推导开始/结束时间

## Progress Tracking

### Development Progress

- `not started` 已完成需求与方案评估，尚未开始代码实现
- 待执行项：
  - DTO / Form / API 契约扩展
  - 统一排序 comparator
  - 分页接口
  - PC 管理弹窗改造
  - 历史会话分组顺序统一

### Testing Progress

- `not started`
- 计划补充：
  - `WorkingDirectoryServiceTest` 排序/分页/时间校验用例
  - 前端类型检查与构建
  - Playwright 管理弹窗与历史分组回归

### Experience Progress

- `not started`
- 体验检查清单：
  - 管理弹窗可达性和加载状态
  - 新增/编辑开始时间与结束时间
  - 排序切换后的列表稳定性
  - 分页翻页、删除后回页行为
  - 历史会话按里程碑分组顺序正确

## 执行建议

推荐按“后端契约与排序核心 → 管理弹窗 UI → 历史会话分组回归”的顺序实施。这样可以先把默认顺序规则固化到服务端，再让管理端和展示端消费同一套结果，避免前端先做一套临时排序，后面再回头对齐。
