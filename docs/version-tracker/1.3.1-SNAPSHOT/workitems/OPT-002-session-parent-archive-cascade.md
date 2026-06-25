# 父会话归档级联子会话

## 基本信息

- 版本：1.3.1-SNAPSHOT
- 类型：优化
- 优先级：P1
- 状态：待手工验收
- 创建日期：2026-06-25
- 归属模块：`session-module`、`packages/navigator-frontend`

## 背景

历史会话已经按“主会话 + 分支会话”两层结构展示。用户对主会话执行归档时，通常表示该任务组已经处理完成；按父子任务语义，父任务完成的前提是子任务也已完成或关闭。因此父会话归档应当把子会话一起归档，避免任务组在列表中出现父会话已收口但分支仍散落可见的状态。

## 设计规则

1. 父会话归档仍使用“归档”文案，不新增菜单名称。
2. 前端在父会话归档确认框中提示：该操作会同时归档所有子会话。
3. 后端收到父会话归档请求时，将父会话和所有未删除子会话一起设为 `ARCHIVED`。
4. 后端归档前检查父会话和子会话是否存在活跃任务；如存在 `PENDING`、`RUNNING`、`AWAITING_PERMISSION`、`AWAITING_INPUT`，拒绝归档。
5. 子会话归档只影响当前子会话，不影响父会话或兄弟会话。
6. 取消归档只取消当前会话，不恢复任何子会话。
7. 删除、搁置暂不调整级联语义，本事项只覆盖归档。

## 执行步骤

1. 为 `SessionRepository` 增加按 `parentSessionId` 查询未删除子会话的方法。
2. 调整 `SessionMetadataService.archiveConversation`：
   - 先加载当前会话。
   - 当前会话为父会话时查询子会话。
   - 对父子会话涉及的 `sessionId` 统一做活跃任务校验。
   - 批量写入 `interactionState = ARCHIVED`。
3. 保持 `unarchiveConversation` 单条会话更新。
4. 调整前端单会话归档确认文案：父会话显示级联提示，子会话保持原提示。
5. 补充后端单元测试和 session API 集成测试。
6. 运行后端、前端和 Playwright 验证，手工验收由用户最后执行。

## 验收标准

- 归档父会话后，父会话和所有未删除子会话都进入 `ARCHIVED`。
- 归档子会话后，父会话和兄弟会话不受影响。
- 取消归档父会话后，子会话仍保持归档状态。
- 父子任一会话存在活跃任务时，父会话归档失败。
- 前端父会话归档确认框能明确提示子会话会被一并归档。

## 验证记录

- 2026-06-25：`mvn test -pl session-module -am -Dtest=SessionMetadataServiceTest -Dsurefire.failIfNoSpecifiedTests=false` 通过，覆盖父会话归档级联、子会话单独归档、子会话活跃任务阻断、取消归档只恢复当前会话。
- 2026-06-25：`pnpm --dir packages/navigator-frontend type-check` 通过。
- 2026-06-25：`npm exec tsc -- --noEmit`（`session-module/integration-tests`）通过，新增 L3 测试代码类型检查通过。
- 2026-06-25：`pnpm --dir packages/navigator-frontend exec playwright test e2e/history-session-branch-grouping.spec.ts --project=chromium` 通过，覆盖父会话归档确认提示和前端隐藏行为。
- 2026-06-25：`npm test -- tests/01-session-crud.test.ts` 连接本机 `localhost:8112` 时新增级联用例失败，原因是当前运行服务仍为旧逻辑；尝试启动新端口服务被 `launcher` jar 文件占用阻断，未强制停止现有服务。
- 2026-06-25：用户确认可停止现有服务后，重新执行 `start-launcher.ps1` 完成 clean build 并启动 `localhost:8112`，健康检查 `UP`；再次运行 `npm test -- tests/01-session-crud.test.ts` 通过，8 个 L3 用例全部成功。
