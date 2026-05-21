# system.root 退场与 Conversation Root Frame 设计

版本：`1.1.6-SNAPSHOT`
状态：已进入实现
类型：runtime context governance / optimization

## 背景

在 Phase 2-5 的运行时上下文重构中，BizWorker 需要一个会话级执行载体来维护 `ContextRuntimeMemory`、active focus、中断恢复和 root loop 状态。早期实现将这个载体建模为一个名为 `system.root` 的持久 Skill frame，便于复用已有 Skill 执行循环。

随着 root loop 已经承担普通消息、业务 Skill 调用和运行时记忆治理，`system.root` 不应继续作为业务 Skill 身份出现在前端事件、LLM 可见上下文或用户可见调试信息中。它应退回为兼容性内部标识；业务上真正存在的是 conversation root frame。

## 设计结论

1. 引入 `FrameKind.ROOT`，将会话级 root frame 从业务 `FrameKind.SKILL` 中区分出来。
2. root frame 继续负责：
   - `ContextRuntimeMemory`
   - 同一 `contextId` 的运行时排他与 pending user input
   - active focus / recoverable child / `AWAITING_USER` 恢复
   - root 级 LLM loop 的提交与结果 commit
3. `system.root` 不再作为业务 Skill 暴露：
   - root frame open 事件文案改为 `Opening/Reusing conversation root frame`
   - root frame open 事件不再携带 `skill_id=system.root`
   - root loop 工具事件不再携带 `skill_id=system.root`
   - LLM submission 日志的 root meta 使用 `conversation.root`
   - LLM prompt 中继续只表达 root orchestration agent，不表达 `system.root`
   - runtime memory 投影不再写入 `rootSkillId=system.root`
4. 业务 Skill、BusinessFunction frame、frame report refs、execution report refs 继续保留并可见。这些信息对 LLM 回溯业务执行结果有价值，不属于本次退场范围。

## 兼容策略

Phase 1 不做历史数据迁移。

新建 root frame 使用 `frame_kind=ROOT`，但 `skill_id` 暂时仍保存为 legacy internal id `system.root`，用于兼容：

- 已冻结的 root manifest
- 旧 `session.json.rootSkillId`
- 旧 frame 文件的读取与恢复
- 现有测试夹具和恢复路径

root 判断统一改为：

```text
parent_frame_id 为空，并且 frame_kind == ROOT
或 parent_frame_id 为空，并且 skill_id == system.root
```

后续若要彻底移除 legacy id，需要单独做存储迁移和 manifest/agent loop 解耦。

## 不采用固定 frm_root.json

`frame_id` 当前仍是全局唯一执行帧身份，FrameStore、report、tool log、resume、child relation 都依赖真实 `frame_id`。直接把 root frame 改成固定 `frm_root` 会破坏 frame id 的全局唯一语义，也会让多 root history / 旧恢复逻辑更难处理。

当前继续使用 `session.json.rootFrameId` 作为 O(1) root 定位入口。需要人类更容易定位时，后续可以增加只读别名文件或索引字段，而不是改写真实 `frame_id`。

## 实施清单

- [x] 新增 `FrameKind.ROOT`
- [x] 新建 root frame 时写入 `frame_kind=ROOT`
- [x] root frame 判断兼容 `FrameKind.ROOT` 与 legacy `system.root`
- [x] root frame open 事件不再暴露 `system.root`
- [x] root loop 工具事件和 LLM submission meta 不再暴露 `system.root`
- [x] root-only tools 改为依赖 `persistent_frame`，不依赖 manifest id
- [x] runtime memory root metadata 去除 `rootSkillId=system.root`
- [x] 完成测试更新与回归

## 测试记录

2026-05-21：

```text
tools/langgraph-biz-worker
$env:PYTHONPATH='src'; .\.venv\Scripts\python.exe -m pytest -q
584 passed, 6 skipped, 11 warnings
```

## 验收标准

1. 新会话 root frame 的 JSON 中 `frame_kind` 为 `ROOT`。
2. 简洁模式和事件流中不再出现 `Opening frame for skill: system.root`。
3. LLM submission `body.messages` 中不出现 `system.root`。
4. 业务 Skill 的 frame/report refs 仍能进入必要的 root context 投影。
5. 旧 `skill_id=system.root` 的 root frame 仍能被 `session.json` 和 journal 恢复逻辑读取。
