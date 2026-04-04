# 11 Playwright 再复测报告

## Date

- 2026-04-04

## Type

- Validation
- Playwright Smoke
- Retest Report

## Scope

本次针对 `docs/version-tracker/1.0.0-SNAPSHOT` 下前面已记录并声称已修复的问题，再做一轮真实环境复测：

- `08-playwright-experience-report.md`
- `09-claude-worker-rewind-first-turn-session-corruption.md`
- `10-claude-worker-session-model-selection-sync-analysis.md`

验证环境：

- Frontend: `http://localhost:5174`
- Backend: `http://localhost:8112`
- 登录账号: `root / root123`
- 验证方式: `playwright-cli 1.59.0-alpha-1771104257000`

## Summary

本轮重新复测后，结论更新如下：

1. `08` 已修复。同一文件浏览器标签内仅修改 `filePath`，页面已经会切到新文件。
2. `09` 已修复。回退到第一个用户消息后，输入框会正确回填，继续发送也能正常完成，不再出现 `FAILED / ProcessError exit_code=1`。
3. `10` 对新建会话已修复。新建会话在首轮完成后切走、再点回时，顶部模型栏会正确恢复到该会话的用户所选模型；继续发送第 2 轮后，也没有再污染真实执行模型。由于本次修复依赖 `sessionId -> 短模型名` 的前端缓存，修复前已创建的旧会话不在本轮验收范围内。

## Detailed Findings

### Finding 1: 文件浏览器同标签 `filePath` 切换已恢复

本轮在 `LocalDev / TestProject` 文件浏览器里重新走了 `08` 的原始场景：

1. 在同一标签先打开 `.gitignore`
2. 保持标签不关闭
3. 直接把 URL 切到 `filePath=pom.xml`

这次结果已经正确：

- 面包屑从 `.gitignore` 切到 `pom.xml`
- 标签栏同时出现 `.gitignore` 和 `pom.xml`
- 编辑器主视图打开的是 `pom.xml`

因此，`08` 里遗留的“同标签 deeplink 不刷新”问题，本轮应判定为已修复。

### Finding 2: 首轮回退后继续对话已恢复正常

这次重新新建 `TestProject` 会话 `D4R` 做完整闭环复测：

1. 第 1 轮发送 `回退复测D4R：先回复 D4R_OK`
2. 第 2 轮发送 `第二轮：只回复 D4R_2_OK`
3. 对第 1 条用户消息执行“回退到此”
4. 保持默认选项 `仅回退会话 (从该轮继续新对话)`
5. 页面回到“任务已完成”状态，并把输入框回填为首轮 prompt
6. 继续发送 `回退后继续复测D4R：只回复 AFTER_REWIND_D4R_OK`

最终第 6 步成功完成，Agent 正常返回：

```text
AFTER_REWIND_D4R_OK
```

也就是说，`09` 中最关键的“回退到首条消息后继续发送会破坏会话”问题，本轮没有再复现。

### Finding 3: 新建会话重新打开后的模型恢复已正常

这次按修复说明重新新建一条 `TestProject` 会话 `F10R` 作为 `10` 的验收样本：

1. 先把顶部模型手动切到 `Haiku`
2. 新建任务 `模型同步修复复测F10R：只回复 F10R_OK`
3. 首轮完成后，页面同时显示：
   - 顶部模型栏：`Haiku`
   - 当前会话模型标记：`4.7`
4. 然后切到另一条历史会话
5. 再显式点回 `F10R`

点回 `F10R` 后，页面状态保持一致：

- 顶部 `configModelId` 仍是 `test`
- 当前会话模型标记仍是 `4.7`
- 顶部模型栏仍是 `Haiku`

这说明针对新建会话，“显式恢复历史会话时，顶部模型栏没有跟会话一起恢复”的问题本轮未再复现。

### Finding 4: 二次发送后的真实执行模型未再漂移

在 `F10R` 点回成功后，我继续发送第 2 轮：

```text
第二轮：只回复 F10R_2_OK
```

结果这条会话完成后：

- 顶部模型栏仍是 `Haiku`
- 当前会话模型标记仍是 `4.7`
- Agent 正常返回 `F10R_2_OK`

因此，本轮没有再复现“模型栏显示正确但真实执行被错误模型污染”的问题。至少对修复后新建的会话，`10` 应判定为已修复。

## Coverage Matrix

| 项目 | 本次结论 | 说明 |
|------|------|------|
| `TestProject` 目录配置错误 | 已修复 | 页面仍保持 `D:\workspace\fsbi` |
| `TestProject` 文件浏览器打不开 | 已修复 | 本轮可正常进入 |
| `TestProject` 基础任务启动失败 | 已修复 | 本轮基础启动正常 |
| 文件浏览器同标签切换 `filePath` | 已修复 | 已正确从 `.gitignore` 切到 `pom.xml` |
| 首轮回退确认阶段 | 已修复 | 可正常回退并回填 prompt |
| 首轮回退后继续对话 | 已修复 | 本轮继续发送后正常完成 |
| 显式打开历史会话后的模型恢复 | 已修复 | 新建 `F10R` 会话点回后仍保持 `Haiku` |
| 错误模型是否会进入真实执行 | 未复现 | 新建 `F10R` 会话二次发送后仍保持 `4.7` |

## Evidence

本轮新增关键证据如下：

- [vt100-11-filebrowser-reretest-before-restartfix.yml](./evidence/vt100-11-filebrowser-reretest-before-restartfix.yml)
  - 同标签先停在 `.gitignore`
- [vt100-11-filebrowser-same-tab-reretest-after-restartfix.yml](./evidence/vt100-11-filebrowser-same-tab-reretest-after-restartfix.yml)
  - 同标签仅改 `filePath` 后，页面已切到 `pom.xml`
- [vt100-11-d4r-after-first-turn-rewind.yml](./evidence/vt100-11-d4r-after-first-turn-rewind.yml)
  - 首轮回退后，输入框已回填首轮 prompt
- [vt100-11-d4r-after-rewind-send-result.yml](./evidence/vt100-11-d4r-after-rewind-send-result.yml)
  - 首轮回退后继续发送成功，Agent 返回 `AFTER_REWIND_D4R_OK`
- [vt100-11-fix10-before-new-session.yml](./evidence/vt100-11-fix10-before-new-session.yml)
  - 新建验收会话前，顶部模型已手动切到 `Haiku`
- [vt100-11-fix10-f10r-first-complete.yml](./evidence/vt100-11-fix10-f10r-first-complete.yml)
  - `F10R` 首轮完成时，顶部模型栏是 `Haiku`，会话模型标记是 `4.7`
- [vt100-11-fix10-f10r-reopen.yml](./evidence/vt100-11-fix10-f10r-reopen.yml)
  - 切到其他会话再点回 `F10R` 后，顶部模型栏仍保持 `Haiku`
- [vt100-11-fix10-f10r-second-round.yml](./evidence/vt100-11-fix10-f10r-second-round.yml)
  - `F10R` 二次发送完成后，会话模型标记仍是 `4.7`，Agent 返回 `F10R_2_OK`

## Recommendation

当前 `11` 的结论应更新为：

- `08` 已修复
- `09` 已修复
- `10` 对新建会话已修复

建议后续补一条边界说明或回归用例：

1. 明确区分“修复后新建会话”与“修复前旧会话”的行为预期。
2. 保留“非默认模型首轮完成 -> 切走 -> 点回 -> 再发第二轮”的回归链路，作为 `Fix 10` 的稳定验收样例。
