# 父会话搁置和删除级联子会话

## 基本信息

- 版本：1.3.1-SNAPSHOT
- 类型：优化
- 优先级：P1
- 状态：待手工验收
- 创建日期：2026-07-01
- 归属模块：`session-module`、`packages/navigator-frontend`

## 背景

父会话归档已经按父子任务语义落地为“父会话归档时同时归档所有子会话，取消归档只恢复当前会话”。搁置和删除属于同一类会话收口操作：用户对父会话执行操作时，通常认为整组父子会话已经进入同一处理状态；对子会话执行操作时，则只应影响该子会话本身。

## 设计规则

1. 父会话搁置仍使用“搁置”文案，确认框提示：该操作会同时搁置所有子会话。
2. 后端收到父会话搁置请求时，将父会话和所有未删除子会话一起设为 `ON_HOLD`。
3. 取消搁置只恢复当前会话为 `AWAITING_REPLY`，不恢复任何子会话。
4. 子会话搁置只影响当前子会话，不影响父会话或兄弟会话。
5. 父会话删除仍为软删除，确认框提示：该操作会同时删除所有子会话。
6. 后端收到父会话删除请求时，将父会话和所有未删除子会话一起标记为 `DELETED`，并写入 `deletedAt`。
7. 子会话删除只影响当前子会话，不影响父会话或兄弟会话。
8. 删除保留既有活跃任务阻断规则；父会话删除前会检查父子会话任一任务是否处于活跃状态。

## 执行步骤

1. 复用父会话级联 helper，统一归档、搁置、删除的父子会话选择规则。
2. 调整 `SessionMetadataService.holdConversation`，父会话操作时批量更新父子会话 `interactionState = ON_HOLD`。
3. 调整 `SessionMetadataService.deleteConversation`，已有 session 元数据时按父子会话批量软删除；缺失元数据的 legacy task projection 路径保持兼容。
4. 保持 `unholdConversation` 单条会话更新。
5. 调整前端确认框和 pane 清理逻辑，父会话搁置/删除提示级联影响，父会话删除后关闭所有受影响会话 pane。
6. 补充后端单元测试、session L3 集成测试和 Playwright 历史分支会话测试。

## 验收标准

- 搁置父会话后，父会话和所有未删除子会话都进入 `ON_HOLD`。
- 搁置子会话后，父会话和兄弟会话不受影响。
- 取消搁置父会话后，子会话仍保持搁置状态。
- 删除父会话后，父会话和所有未删除子会话都被软删除，并从默认列表和配置查询中消失。
- 删除子会话后，父会话和兄弟会话不受影响。
- 父子任一会话存在活跃任务时，父会话删除失败。
- 前端父会话搁置/删除确认框能明确提示子会话会被一并处理。

## 验证记录

- 2026-07-01：`mvn test -pl session-module -am -Dtest=SessionMetadataServiceTest -Dsurefire.failIfNoSpecifiedTests=false` 通过，覆盖父会话搁置/删除级联、子会话单独搁置/删除、取消搁置只恢复当前会话、子会话活跃任务阻断父会话删除。
- 2026-07-01：`pnpm --dir packages/navigator-frontend type-check` 通过。
- 2026-07-01：`npm exec tsc -- --noEmit`（`session-module/integration-tests`）通过。
- 2026-07-01：`pnpm --dir packages/navigator-frontend exec playwright test e2e/history-session-branch-grouping.spec.ts --project=chromium` 通过，6 个用例覆盖归档、搁置、删除父会话级联提示和 stale task cache 下的列表隐藏行为。
- 2026-07-01：执行 `start-launcher.ps1` 重新 build 并启动本机 `localhost:8112`，健康检查 `UP`。
- 2026-07-01：`npm test -- tests/01-session-crud.test.ts` 通过，10 个 L3 用例覆盖父会话搁置级联、取消搁置只恢复父会话、父会话删除级联软删。
