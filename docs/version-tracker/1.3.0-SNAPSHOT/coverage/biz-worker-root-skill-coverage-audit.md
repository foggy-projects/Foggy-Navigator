---
audit_scope: feature
audit_mode: pre-acceptance-check
version: 1.3.0-SNAPSHOT
target: biz-worker-root-skill-context-and-business-function
status: reviewed
conclusion: ready-with-gaps
reviewed_by: Codex
reviewed_at: 2026-05-16
follow_up_required: yes
---

# Test Coverage Audit

## Background

本次审计覆盖 BizWorker root skill、业务函数虚拟 frame、审批恢复、上下文可见性、控制面/SDK/CLI `contextVisibility` 透传。

## Audit Basis

- `docs/version-tracker/1.3.0-SNAPSHOT/13-biz-worker-root-skill-context-design.md`
- Worker pytest full suite.
- Java service/listener targeted Maven suite.
- Open SDK API/CLI targeted Maven suite.

## Coverage Matrix

| Requirement / Acceptance Item | Risk | Evidence Layer | Evidence | Conclusion |
| --- | --- | --- | --- | --- |
| root skill 作为常驻特殊 skill，业务函数只在 skill loop 内出现 | critical | unit-test / e2e-test | `test_root_graph.py`, `test_llm_skill_agent.py`, `test_e2e_scripted_tool_call_streaming.py` | covered |
| `FUNCTION_CALL` frame 作为业务函数暂停/恢复边界 | critical | unit-test | `test_frame_lifecycle.py`, `test_llm_skill_agent.py`, `test_resume.py` | covered |
| 审批后确定性投递 `post_approval_message`，不交给 LLM 续写 | critical | unit-test / integration-style Java test | `test_resume.py`, `LanggraphWorkerResumeEventListenerTest`, `BusinessFunctionApprovalResumeFlowTest` | covered |
| Java 执行业务 adapter 后投递 `business_function_result_message` | critical | unit-test / integration-style Java test | `BusinessFunctionSuspensionServiceTest`, `BusinessFunctionApprovalResumeFlowTest` | covered |
| runtime 函数权限不再以 SkillFunctionAllowlist 为硬门禁 | major | unit-test | `BusinessFunctionAuthorizationServiceTest`, `WorkerGatewayServiceTest` | covered |
| root context summary 收敛，child skill 默认隔离，可选 summary | major | unit-test | `test_frame_lifecycle.py`, `test_llm_skill_agent.py` | covered |
| 普通 skill 的 `passthrough` 降级，系统 skill 保留 passthrough | major | unit-test | `test_llm_skill_agent.py`, `test_skill_git_sync.py` | covered |
| Worker materialize / Java control plane / SDK CLI 透传 `contextVisibility` | major | unit-test | `test_skill_git_sync.py`, `SkillRegistryServiceTest`, `BusinessAgentBundleServiceTest`, `BusinessAgentApiSmokeTest`, `UpstreamCliTest` | covered |
| 前端管理 UI 暴露 context visibility | minor | manual review | 当前前端未发现 skill/agent bundle 注册表单，实际入口是 SDK/CLI | not-covered |
| 上游真实项目重新同步并 smoke | major | manual/e2e | 尚未通知上游执行 | not-covered |

## Evidence Summary

- `tools/langgraph-biz-worker`: `.\.venv\Scripts\python -m pytest tests` -> 362 passed, 6 skipped, 10 warnings.
- Java/SDK targeted closure: `mvn test -pl business-agent-module,addons/langgraph-biz-worker,navigator-open-sdk -am "-Dtest=SkillRegistryServiceTest,BusinessAgentBundleServiceTest,BusinessFunctionAuthorizationServiceTest,WorkerGatewayServiceTest,BusinessFunctionSuspensionServiceTest,LanggraphWorkerResumeEventListenerTest,BusinessFunctionApprovalResumeFlowTest,BusinessAgentApiSmokeTest,UpstreamCliTest" "-Dsurefire.failIfNoSpecifiedTests=false"` -> BUILD SUCCESS.
- SDK focused rerun earlier: `mvn test -pl navigator-open-sdk "-Dtest=BusinessAgentApiSmokeTest,UpstreamCliTest"` -> 56 tests passed.

## Gaps

- 尚未跑真实上游项目的 `skill sync` / `agent sync` / `ask` smoke。
- 尚未做前端 UI 改造，因为当前管理前端没有对应注册表单；如果后续新增 UI，需要补组件测试或手工 evidence。
- 尚未做整仓全量 Maven/前端测试；当前结论基于本次改动面目标验证。

## Recommended Next Skills

- `foggy-acceptance-signoff`：正式签收前使用。
- `coding-agent-frontend`：如果决定新增或整改前端管理 UI。
- `navigator-upstream-cli`：如果要整理上游通知和 smoke 操作清单。

## Conclusion

`ready-with-gaps`。核心运行链路和权限/暂停/上下文策略已有自动化证据，可以进入上游联调规划；缺口集中在真实上游 smoke 与前端管理面是否需要新增入口。
