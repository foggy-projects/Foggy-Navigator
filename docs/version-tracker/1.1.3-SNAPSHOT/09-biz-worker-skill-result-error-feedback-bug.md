# Biz Worker 子技能结果错误回传缺陷

## 文档作用

- doc_type: bug-regression
- version: 1.1.3-SNAPSHOT
- status: ready-for-verification
- date: 2026-05-08
- source: user-report
- severity: major
- owner: langgraph-biz-worker
- purpose: 记录 TMS 会话中子技能失败后仅回传 `Skill completed` 的缺陷，并把 allowed-tools 权限方案暂缓到后续版本

## 问题现象

TMS 同一会话中询问“你可以看到目前都有哪些可用的数据模型”时，前端最终展示 `Skill completed`，没有展示真实失败原因。

本地日志中对应任务为：

- `tools/langgraph-biz-worker/data/logs/llm-conversations/lgt_a249124045b44192.jsonl`
- 子技能 frame 输出包含 `error: Tool not allowed: tms.dataset.listModels`
- 子技能 frame 输出也包含面向用户的 `message` 与 `result_summary`

同时，`llm-conversations` 下生成多个 `lgt_*.jsonl` 文件不代表 TMS 创建了多个 Navigator 会话。该日志以 Biz Worker task 为单位落文件；已观察到三次 TMS 消息对应三个 task，但 `session_id` 相同。

## 根因

1. `close_skill_frame` 只读取 `SkillRuntime.close_frame()` 返回的 promoted 字段。
2. 当前 `foggy-query-agent` 未配置 `metadata.promote-to-parent`，导致 frame 中已有的 `result_summary/output` 没有进入父图结果。
3. 结果事件使用 `"Skill completed"` 作为兜底文案，因此用户看不到真实错误。
4. 当前 Python Worker 还存在基于 Skill Manifest `allowed_tools` 的工具拦截，和本阶段“业务函数工具由全局运行时控制”的方向不一致。

## 当前决策

本阶段先不做完整工具调用权限控制：

1. `allowed-tools` 不作为某个 Skill 的局部配置来决定是否允许调用。
2. Biz Worker 先全局暴露业务函数包装工具：`list_business_functions`、`get_business_function_schema`、`invoke_business_function`。
3. 完整权限方案延期到后续版本，届时统一定义全局工具授权来源、TMS 是否每次传 `allowed-tools`、Java Business Function grant、task scoped token、审计与风险等级之间的关系。
4. 无论权限或上游调用失败，子技能提交的 `summary/message/error` 必须能回传给用户，而不是显示 `Skill completed`。

## 修复范围

- `tools/langgraph-biz-worker/src/langgraph_biz_worker/graphs/root_graph.py`
  - 子技能关闭时，在 promoted 字段为空时兜底读取 frame 自身的 `result_summary`。
  - 同步把 frame `output` 作为 `structured_output` 回传。
  - 当没有 summary 时，再从结构化输出的 `message/error` 生成用户可见文案。
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_skill_agent.py`
  - 移除 per-skill `allowed_tools` 的硬拦截。
  - 全局绑定业务函数包装工具。
  - 允许直接形如 `tms.dataset.listModels` 的业务函数 id 走 `invoke_business_function`。
- `tools/langgraph-biz-worker/tests/`
  - 补充无 promote 配置时仍能回传 frame summary/output 的回归测试。
  - 补充无 skill allowlist 时仍暴露全局业务函数工具的回归测试。

## 后续待定项

完整工具权限方案延期，不在本次修复中展开。后续需要单独形成方案并覆盖：

1. `allowed-tools` 的权威来源：TMS 每次传入、Java 控制面下发、或二者合并。
2. Worker 对工具权限的最终判定边界。
3. Business Function grant 与 LLM tool schema 暴露之间的映射。
4. 失败原因、审批拒绝、权限拒绝和上游异常的统一用户反馈格式。
5. 日志中 task 文件、Navigator session、TMS contextId 的关联字段补齐。

## 验证计划

1. 运行 `tools/langgraph-biz-worker` 的相关单元测试。
2. 重新在 TMS 中发送“你可以看到目前都有哪些可用的数据模型”。
3. 确认前端不再展示 `Skill completed`，而是展示子技能给出的错误或提示文案。

## 本地验证结果

- `tools/langgraph-biz-worker`: `$env:PYTHONPATH='src'; .\.venv\Scripts\python.exe -m pytest tests/test_llm_skill_agent.py tests/test_single_skill.py`
  - Result: `10 passed`
- `tools/langgraph-biz-worker`: `$env:PYTHONPATH='src'; .\.venv\Scripts\python.exe -m pytest tests`
  - Result: `314 passed, 6 skipped`
