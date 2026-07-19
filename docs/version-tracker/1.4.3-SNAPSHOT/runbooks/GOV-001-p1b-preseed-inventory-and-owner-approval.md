# GOV-001 P1B Pre-seed Inventory / Mapping / Owner-Approval Runbook

- doc_type: security-gate-runbook
- version: `1.4.3-SNAPSHOT`
- related_workitem: [GOV-001 P1B-B0](../workitems/GOV-001-p1b-b0-preseed-inventory-and-owner-approval.md)
- status: offline-gate-only
- scope: synthetic fixture validation and the future real-inventory handoff boundary

## 结论与硬边界

P1B-B0 只交付 `navi.authorization.preseed-inventory.v1` 的纯离线校验合同与
synthetic fixture。它没有文件适配器、CLI、Spring bean、环境变量/profile、数据库、
网络、secret store、seed、migration、credential 签发或 route enforcement。

`VALID` 只表示该脱敏 envelope 的结构、checksum 和已声明事实没有被 B0 隔离；它
**不是** owner approval、真实映射证明、seed 许可、production 许可、Provider ready、
Worker Gateway ready 或 `NAVIGATOR_EXTERNAL_ENABLED` /
`NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED` 的启用依据。

在单独的 real P1B-B delivery spec 获批且四眼审批完成前，任何真实 S1/S2 mapping、
credential、grant、tenant authority、ClientApp binding、Worker 资源或 route 都不得写入或
切换。

## 本地 fixture 验证

仓库只允许运行 synthetic fixture 测试；不要将真实 inventory 粘贴进 shell、Maven、CI
日志、issue、版本文档或 tracked 文件。

```bash
mvn test -pl navigator-common -Dtest=PreseedInventoryValidatorTest
```

期望：三个 fixture 分别得出 `VALID`、`QUARANTINED / PRESEED_OWNER_CONFLICT`、
`INVALID / PRESEED_CHECKSUM_MISMATCH`。fixture 中的 S1/S2 名称、instance、tenant、
ClientApp、owner、approval reference 和 fingerprint edge 全为 `synthetic-*` 占位符。

B0 有意不提供“读取真实文件”的命令。未来若需要 secure-source dry-run，必须先由
单独批准的 P1B-B contract 定义受控运行器、输入保留策略、审计与失败回滚；不能把这份
runbook 或 B0 测试临时扩展为真实数据导入通道。

## 脱敏 envelope 合同

固定 envelope：

```text
schemaVersion = navi.authorization.preseed-inventory.v1
mode          = OFFLINE_VALIDATE_ONLY
deployment    = { navigatorInstanceId, environmentProfile }
records       = non-empty array
checksum      = canonical SHA-256 excluding checksum itself
```

每条 record 仅可包含下列已批准字段：

| 类别 | 字段 |
| --- | --- |
| 安全 alias / 来源 | `recordAlias`, `sourceKind`, `upstreamSystemReference`, `namespaceReference`, `ownerReference` |
| S2 范围 | `tenantReference`, `clientAppReference`, `clientAppConflict`, `tenantAuthorityReference`, `tenantAuthorityState` |
| 事实状态 | `ownerConflict`, `sourceMappingState`, `authorityFactsComplete`, `credentialStatus`, `expiresAt`, `revoked` |
| 脱敏凭据指纹 | `credentialFingerprintPrefix`, `credentialFingerprintSuffix` |
| 拟议分类 | `proposedPrincipalType`, `proposedCredentialLane`, `proposedDisposition`, `quarantineReason`, `approvalReference` |

`approvalReference` 只是非敏感审批/变更单 locator，不证明该审批已通过。checksum 是对对象键
递归排序、保留数组顺序、无空白 UTF-8 JSON（排除 `checksum` 字段）计算的 lowercase
SHA-256。

`expiresAt` 必须是带时区的 UTC instant，且必须严格晚于校验时刻；恰好等于校验时刻即为
`PRESEED_CREDENTIAL_EXPIRED`。repository fixture 固定使用 `2099-12-31T23:59:59Z`，单元测试
使用固定 UTC clock 验证该边界，因此 fixture 不随执行日期漂移；公共默认构造器仍按当前 UTC
时刻执行这个只读、离线分类。

严禁放入任何 credential material、raw verifier/hash、token、key、password、cookie、
profile/env 内容、upstream user token、full request body 或真实 `.navigator`/`accounts/`
内容。未知字段和 secret-like 值会被拒绝；重复 JSON object key 也会在树解析前被当作
`PRESEED_DOCUMENT_MALFORMED` 拒绝，不能依赖“后一个值覆盖前一个值”。校验结果不会回显输入值。

## 分类与隔离规则

| 条件 | B0 结果 | 后续动作 |
| --- | --- | --- |
| 合成或受控输入结构完整、checksum 匹配、S1/S2 候选事实完整 | `VALID / PRESEED_VALID` | 仅保留为待审批 inventory；不得 seed |
| 版本、mode、deployment、records、checksum 或字段形状不符合合同 | `INVALID` | 修复离线文档，不推断任何 authority |
| secret-like 字段/值 | `INVALID / PRESEED_SECRET_LIKE_INPUT` | 停止、移除泄露材料，按安全流程处理 |
| owner/upstream/source mapping/ClientApp 冲突或缺失、credential revoked/expired/no-expiry | `QUARANTINED` | authority owner 提供受控事实，不能降级为 valid |
| duplicate/conflicting tenant authority | `QUARANTINED` | 先确定唯一 tenant authority 与处置结论 |
| legacy upstream-admin/scope/tenant-list | `QUARANTINED / PRESEED_LEGACY_*` | 只能人工 review；不得自动提升为 root/platform/security principal 或 lane |
| `REQUIRES_APPROVAL` / `QUARANTINED` disposition | `QUARANTINED` | 保持隔离，不能因 ticket 存在而自动放行 |

## 真实 inventory 的四眼交接（未来 P1B-B 前置）

1. **Source owner** 在受控系统中确认目标 `navigatorInstanceId` + `environmentProfile`、
   S1 `INSTANCE_ROOT` source mapping、S2 `tms-x3` platform identity、tenant owner、
   ClientApp/owner/operator binding、credential ID/fingerprint/status/lane、effective time 和
   conflict disposition。B0 不读取这些系统。
2. **Preparing operator** 仅从获准 secure source 构造脱敏事实；不复制 secret、token、
   verifier/hash、账号、请求体或 profile 内容。legacy 输入始终标记 review，不得自动提升。
3. **Independent reviewer** 与 source owner 分离，核对 source authority、instance/profile、
   record alias、fingerprint prefix/suffix、status、reason code 和 checksum；批准记录保存在
   受控审批系统，使用非敏感 `approvalReference` 关联。
4. **Release/architecture owner** 批准专门的 P1B-B real-seed delivery spec，明确 verifier/KMS
   owner、seed 幂等性、rollback、审计保留、生命周期/offboarding、跨 ClientApp/tenant
   拒绝预期和执行窗口。没有该批准不得调用任何 seed/cutover。
5. **Implementer** 只在批准范围内执行，并将下列安全摘要回填 durable evidence；不能凭
   `VALID` 或本 runbook 自行扩大权限。

## Durable evidence 模板（仅安全摘要）

```text
delivery_spec: <approved P1B-B reference>
target: <navigatorInstanceId>/<environmentProfile>
inventory_checksum: <sha256>
record_count: <n>
record_aliases: <opaque aliases>
source_kinds: <S1/S2/legacy categories>
credential_fingerprint_edges: <prefix...suffix only>
credential_statuses: <ACTIVE/REVOKED/expiry classification only>
classification_and_reason_codes: <VALID/INVALID/QUARANTINED + codes>
mapping_authority_reference: <non-secret ref>
approval_reference: <non-secret ref>
source_owner: <approved role/identifier>
independent_reviewer: <approved role/identifier>
effective_time_and_conflict_disposition: <safe summary>
```

不得记录 raw credential、token、secret、verifier/hash、password、profile/env 内容、真实
账户、完整 request body 或 source-system export。发现此类内容后，停止处理并按安全事件
流程清理受影响的工作区/日志；不要通过重新计算 checksum 或创建额外 Worker、
BizWorkerIdentity、WorkerPool member 来规避任何路由或权限问题。

## Exit gate

P1B-B0 的 exit 仅是“可独立签核的离线 precondition”。真实 seed、credential issuance、
verifier 接入、management route cutover、Open API、Worker Gateway、external 或 production
enablement 全部保持未启动/未批准，直到后续架构决策和独立 APPROVED work item 满足上述
四眼交接与执行/rollback 义务。
