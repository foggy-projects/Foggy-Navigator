# 1.3.3-SNAPSHOT

## 文档作用

- doc_type: version-index
- intended_for: execution-agent | reviewer | signoff-owner
- purpose: 跟踪 `1.3.3-SNAPSHOT` 版本内 Navigator upstream provisioning 授权闭环、dev provisioning credential 边界、WorkerHost 可见性刷新和正式环境审批负担收敛。

## 版本目标

本版本专门处理上游系统在开发与验收阶段反复卡在 `admin-key request/approve`、`worker-host apply`、model grant、Agent sync、workspace binding、readiness / owner-smoke 的问题。目标是在保持 tenant / upstream system / ClientApp 资源隔离的前提下，为 SIM、TMS 等上游提供可复用的 dev provisioning credentials 与正式环境授权策略，减少不必要的人工往返，同时保留生产变更的审计和最小权限边界。

## 版本状态

- status: in-progress
- primary_workitem: [workitems/OPT-001-dev-operator-key-provisioning-boundary.md](./workitems/OPT-001-dev-operator-key-provisioning-boundary.md)
- production_readiness: pending
- acceptance_status: not-started

## 文档清单

- [workitems/OPT-001-dev-operator-key-provisioning-boundary.md](./workitems/OPT-001-dev-operator-key-provisioning-boundary.md) - dev provisioning credentials、WorkerHost apply 授权闭环、跨上游隔离与正式环境审批策略
- [runbooks/navigator-runtime-provisioning-sop.md](./runbooks/navigator-runtime-provisioning-sop.md) - runtime provisioning 经验沉淀、凭据落盘边界、旧数据排障、正式环境审批与恢复 SOP
- [test-records/navigator-provisioning-selftest-20260705.md](./test-records/navigator-provisioning-selftest-20260705.md) - Navigator selftest、SIM/TMS smoke、跨 ClientApp 隔离与敏感扫描记录
