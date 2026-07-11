# 1.4.0-SNAPSHOT

## 文档作用

- doc_type: version-index
- intended_for: root-controller | execution-agent | reviewer | release-owner
- purpose: 管理独立 Codex App Server Worker 的建设、Ultra 首批切流、全档位迁移和旧 SDK Worker 退役。

## 版本状态

- status: implementation
- primary_workitem: `OPT-001`
- implementation_started: yes
- production_routing_changed: no

## 版本目标

新增独立部署的 `codex-app-server-worker`，以 Codex app-server 作为执行引擎并支持全部模型与 reasoning 档位。现有 `codex-agent-worker` 保持 SDK / `codex exec` 稳定路径、最高支持 Max，并拒绝所有 Ultra 请求。平台先完成幂等任务接受、受控 runtime registry、不可变任务/会话 affinity 和能力握手，再将 Ultra 会话灰度到新 Worker；待全模型、全功能、长稳和回滚证据齐备后，使新 Worker 成为默认，最后退役 SDK Worker。

## 已确认决策

1. `codex-app-server-worker` 是独立进程、独立发布物、独立端口、独立日志和独立运行目录。
2. 新 Worker 从第一版起以“全部模型和 reasoning 档位可运行”为能力目标；Ultra 只是首批生产路由范围。
3. 外部逻辑仍使用 `workerBackend=OPENAI_CODEX` 和 `providerType=codex-worker|codex-biz-worker`，不新增第二套 Provider、Task、Session 或 PC 页面。
4. 现有 Worker 不再承担 app-server；没有新 runtime 能力时，Ultra 必须 fail closed，禁止静默降级到 Max/xhigh。
5. 已接受任务和已有会话的 runtime affinity 不可被路由配置重写；回滚只停止新分配。
6. `turn/start` 提交后禁止跨 runtime 重放同一 prompt。

## 成功标准

| Gate | 成功标准 | 当前状态 |
|---|---|---|
| P0 契约 | 幂等 create/accept、capability manifest、runtime affinity 和 rollback 语义评审通过 | completed |
| Dark Worker | 新 Worker 零生产流量运行，全档位核心契约和真实 smoke 通过 | in-progress: 0.1.0 本地确定性发布物/Windows 安装更新通过；最终 provider/crash 复验被账户额度阻塞，POSIX 与运行中 drain/rollback 待验 |
| 双 Runtime 控制面 | runtime registry、能力缓存、任务/会话 affinity、N-1 兼容通过 | in-progress: 分层实现与回归通过，真实全链/N-1/多副本门禁未关闭 |
| Ultra Canary | 新 Ultra 会话小流量运行，零重复执行、零 affinity mismatch，回滚演练通过 | blocked: 等待目标环境、release owner 和 P1/P2 exit |
| Ultra Default | 新 Ultra 100% 路由新 Worker，旧任务/会话在原 runtime drain | blocked: 依赖 P3 生产签收 |
| 全模型迁移 | 非 Ultra 各模型、认证与功能 cohort 均有真实链路证据 | blocked: 依赖 P4 长稳与功能 parity |
| 默认切换 | 所有新 Codex 任务默认使用 app-server Worker | blocked: 依赖 P5 独立签收 |
| SDK 退役 | 无活动或保留期内可续接 SDK 会话、无能力例外，完成独立签收 | blocked: 依赖 P6 drain 数据和退役窗口 |

## 文档清单

- [需求](./workitems/OPT-001-independent-codex-app-server-worker-requirement.md)
- [实施计划与代码清单](./workitems/OPT-001-independent-codex-app-server-worker-plan.md)
- [实施与门禁进度](./workitems/OPT-001-independent-codex-app-server-worker-progress.md)
- [Codex GPT-5.6 模型目录与 Runtime 边界](./workitems/OPT-002-codex-model-catalog-boundary.md)
- [BUG-001 Codex Resume 后 Shell 工具丢失](./workitems/BUG-001-codex-resume-shell-tool-loss.md)
- [P0-P2 实现质量检查](./quality/OPT-001-p0-p2-implementation-quality.md)
- [BUG-001 修复实现质量检查](./quality/BUG-001-codex-resume-shell-fix-quality-review.md)
- [P0-P2 测试证据覆盖审计](./coverage/OPT-001-p0-p2-coverage-audit.md)
- [P0-P7 阶段验收记录](./acceptance/OPT-001-p0-p7-acceptance.md)

## 上游基线

- [1.3.1 OPT-005 Max / Ultra 与原生子任务投影](../1.3.1-SNAPSHOT/workitems/OPT-005-codex-sol-max-ultra-support.md)
- [Codex App Server 官方协议](https://developers.openai.com/codex/app-server)
- [Codex SDK 官方说明](https://developers.openai.com/codex/sdk)
- [Codex app-server 协议与 schema 生成](https://github.com/openai/codex/blob/main/codex-rs/app-server/README.md)

## 当前边界

- P0 已完成；P1/P2 已达到本地实现检查点，但发布物、真实全链、N-1、多副本和生产迁移证据仍未关闭，因此阶段保持 `in-progress`。
- P3-P7 依赖真实生产放量、至少 50 个 terminal Ultra task、连续 72 小时和至少 2 次实例轮换；当前没有目标环境证据，状态为门禁阻塞而不是实现完成。
- 当前工作区中的 1.3.1 未提交实现不得直接提交为最终双 Worker 架构；其迁移分类和重验要求以本版本计划为准。
- Ultra 切流前必须填实 canary 观察窗口、成功率、延迟、资源和错误预算阈值；只有“重复副作用数=0、凭据/原始子线程内容泄漏数=0”可在规划阶段直接固定为零容忍。
