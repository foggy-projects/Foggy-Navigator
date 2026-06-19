# 历史会话主会话/分支会话分组优化

## 文档目的

记录 Workers 历史会话面板从“里程碑优先 + 扁平会话列表”调整为“主会话任务组 + 分支会话列表”的需求、实现边界和验收标准。

## 基本信息

- 版本：1.3.0-SNAPSHOT
- 类型：优化
- 优先级：P1
- 状态：已实现
- 创建日期：2026-06-19
- 归属模块：`packages/navigator-frontend`、`session-module`

## 背景

当前转发为新会话后，历史会话列表会把新会话和来源会话放在同一层，并通过“上游”“子会话”标签表达关系。实际使用中容易误解为新会话成了主会话，原会话成了子会话。

里程碑分组适合版本阶段管理，但用户的高频使用场景更接近“一个主会话代表一个问题域，多个转发会话代表该问题域下的分支探索”。因此历史列表需要把主子关系提升为主要信息架构，里程碑降级为可选分组和标签。

## 目标

- 历史会话列表以主会话作为根任务组展示。
- 子会话作为主会话下的分支列表展示，不再与主会话同层竞争位置。
- UI 不渲染无限递归树；从子会话继续转发的新会话仍归属到同一个根主会话下。
- 里程碑保留，但不再承担主要任务组织职责。

## 设计规则

1. 普通新建会话是主会话。
2. 从主会话转发创建的新会话是该主会话的分支会话。
3. 从分支会话继续转发创建的新会话，视觉上仍挂在根主会话下面，不继续形成孙会话缩进。
4. 直接来源关系继续由转发关系表和消息来源保存，历史列表只展示两层。
5. 右侧窄栏最多展示“主会话 + 分支列表”；更深来源链路如后续需要，可在详情面板展示。

## 实现范围

- 前端历史会话列表：
  - 将当前过滤结果归并到根主会话。
  - 主会话卡片下内嵌显示分支会话。
  - 移除会造成层级误读的“上游”同层标签作为主要表达。
- 前端转发目标：
  - 转发到已有会话时优先列出同一根主会话下的分支会话。
- 后端转发创建：
  - 从分支会话转发创建新会话时，`parentSessionId` 写入根主会话 ID，而不是直接来源会话 ID。
  - 直接来源仍由 `SessionRelationEntity.sourceSessionId/sourceMessageId` 表达。

## 验收标准

- 从主会话转发创建新会话后，新会话显示在主会话下方的分支列表。
- 从分支会话再次转发创建新会话后，新会话仍显示在同一个主会话下方，不形成第三层缩进。
- 历史列表按状态/来源过滤时，如果命中的是分支会话，仍能显示其所属主会话并在下方看到该分支。
- 里程碑分组仍可使用，但组内计数以主会话为单位。
- 前端构建通过。

## 进度

- 2026-06-19：完成需求落档，开始实现。
- 2026-06-19：完成后端转发根会话归属逻辑、历史列表两层分支展示、已有会话转发目标筛选调整和后端回归测试。
- 2026-06-19：按下一步规划完成 1~3 推进：浏览器可用性验证、会话详情来源链路展示、主会话/分支会话 Playwright E2E 覆盖。

## 测试计划

- 前端类型检查：`pnpm --dir packages/navigator-frontend type-check`
- 前端构建：`pnpm --dir packages/navigator-frontend build`
- 项目构建脚本：`bash scripts/build-frontend.sh`
- 前端组件测试：`pnpm --dir packages/navigator-frontend exec vitest run src/views/__tests__/ClaudeWorkerView.integration.test.ts`
- 前端 E2E：`pnpm --dir packages/navigator-frontend exec playwright test --project=chromium`
- 后端单测：`mvn test -pl session-module -am -Dtest=SessionForwardServiceTest '-Dsurefire.failIfNoSpecifiedTests=false'`
- 手工验证：在 Workers 历史会话中转发主会话和分支会话，确认右侧列表只展示两层。

## 验证记录

- 2026-06-19：`pnpm --dir packages/navigator-frontend type-check` 通过。
- 2026-06-19：`pnpm --dir packages/navigator-frontend build` 通过，保留原有 chunk 体积提示。
- 2026-06-19：`mvn test -pl session-module -am -Dtest=SessionForwardServiceTest '-Dsurefire.failIfNoSpecifiedTests=false'` 初版通过；后续补充入向转发关系查询后扩展到 4 个用例。
- 2026-06-19：`bash scripts/build-frontend.sh` 未通过，原因是当前 WSL/Linux Node 环境缺少 Rollup optional native package `@rollup/rollup-linux-x64-gnu`；Windows 原生前端构建已通过。
- 2026-06-19：补充后端入向转发关系查询后，`SessionForwardServiceTest` 扩展到 4 个用例并通过。
- 2026-06-19：`pnpm --dir packages/navigator-frontend exec vitest run src/views/__tests__/ClaudeWorkerView.integration.test.ts` 通过，11 个用例通过；保留既有 Vue 测试环境 warning。
- 2026-06-19：`pnpm --dir packages/navigator-frontend exec playwright test --project=chromium` 通过，5 个浏览器用例通过，覆盖主会话下两条扁平分支和分支详情直接来源显示。
