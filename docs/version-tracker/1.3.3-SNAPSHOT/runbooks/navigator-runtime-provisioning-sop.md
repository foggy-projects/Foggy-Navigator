# Navigator Runtime Provisioning SOP

## 文档作用

- doc_type: runbook
- intended_for: execution-agent | reviewer | upstream-operator
- purpose: 固化 Navigator upstream runtime provisioning 的凭据沉淀边界、标准执行顺序、旧数据排障和正式环境审批策略。

## 基本信息

- version: `1.3.3-SNAPSHOT`
- status: draft-complete
- related_workitem: [../workitems/OPT-001-dev-operator-key-provisioning-boundary.md](../workitems/OPT-001-dev-operator-key-provisioning-boundary.md)
- related_skill: `.agents/skills/navigator-runtime-provisioning/SKILL.md`

## 沉淀边界

### 可以提交

- 资源 ID：tenantId、ClientAppId、agentCode、directoryId、modelConfigId、非敏感 workerId。
- 命令类别和执行顺序。
- readiness / owner-smoke / live smoke 的通过状态。
- 失败原因分类：tenant mismatch、owner mismatch、grant 不可见、Biz Worker identity 不可见、route 缺失。
- 示例 profile 中的变量名和占位符。
- 跨 ClientApp / 跨 upstream 的负向 smoke 结论。

### 不能提交

- admin key、operator key、control key、runtime key、runtime access token、claim token。
- LLM API key、provider key、cookie、真实账号、真实密码。
- `.navigator/*.env`、`.navigator/tenants/*.env`、包含本地 token 的 `.navigator/*.json`。
- 真实 TMS 账号、真实 TMS endpoint 访问证据、`accounts/` 内容。

真实凭据只允许进入 gitignored profile 或平台 secret。tracked docs 只能记录 profile 路径和 `profileGitIgnored=true` 这类状态。

## Profile 与 Secret 放置

| 文件 / Secret | 是否提交 | 用途 |
| --- | --- | --- |
| `.navigator/upstream.env` | no | 当前 upstream system 的本地 admin/control/runtime profile |
| `.navigator/*.env` | no | 项目本地临时 profile |
| `.navigator/tenants/*.env` | no | 多 tenant / 多 ClientApp profile |
| `.navigator/*.json` | no | 本地 worker-host / Agent manifest，可能引用 token env |
| `tools/navigator-upstream/fixtures/**/*.example.env` | yes | 变量名和占位符示例 |
| 平台 secret store | no | 生产 / CI credential |

## 标准执行顺序

1. 明确 upstream system、tenant、ClientApp、agentCode、upstreamUserId、modelConfigId、directoryId 和边界。
2. 确认 profile 目标路径被 gitignore 覆盖。
3. `worker-host verify` 本地 manifest。
4. `worker-host apply --write-profile`，仅使用 upstream admin lane。
5. 创建或确认 modelConfig；ClientApp-owned modelConfig 使用 ClientApp control lane。
6. `agent sync` 到当前 ClientApp 或 upstream owner。
7. 绑定 default model、workspace、worker。
8. `ensure-grant` upstream user。
9. `verify-agent-readiness`，显式传入 agent、user、modelConfig、directory。
10. `owner-smoke`。
11. 使用安全 prompt 执行 live smoke。
12. 多 upstream profile 同时存在时，执行跨 ClientApp 负向 smoke。
13. 回填 runtime profile、rehearsal run、version workitem、test record。
14. 对 tracked diff 做 secret-like pattern 扫描后再提交。

## 旧数据排障

| 现象 | 常见原因 | 处理方式 |
| --- | --- | --- |
| modelConfig tenant mismatch | 复用旧 tenant / sandbox modelConfig | 新建当前 ClientApp-owned modelConfig，或通过正式迁移修正 owner |
| model config not visible | grant 指向旧 ClientApp 或 model variant 不匹配 | 刷新 grant/default，确认 Agent defaultModel 与 modelConfig allowed model 一致 |
| directory not visible | directory tenant / ownerId / clientAppId 属于旧 sandbox | 走正式 directory 迁移或本地 dev data repair，记录原因 |
| Biz worker fallback 到 workspace binding | upstream-owned Biz Worker identity 未 apply 或 ClientApp upstream_system_id 缺失 | `worker-host apply`，确认 `BIZ_WORKER_IDENTITY` 可见 |
| route readiness 失败 | business function upstream route 缺失 | local smoke 用 mock route；正式环境配置真实 route |
| live smoke provider 失败 | modelName 不是 provider 支持的模型 | 查询 provider models，更新 modelConfig 与 Agent defaultModel |

## 正式环境审批策略

日常不应大量 request/approve。正式环境按四层分离：

1. `bootstrap operator`：创建 namespace、ClientApp、首个 control/runtime credential、首个 worker-host identity。
2. `provisioning operator`：绑定单一 upstream system，处理 Agent sync、grant refresh、workspace/worker binding、worker-host refresh。
3. `runtime credential`：只做 runtime-token exchange、readiness、owner-smoke、ask、live smoke。
4. `break-glass admin`：短 TTL、强审计，只做跨 owner repair、迁移、误授权清理和紧急撤销。

需要审批的动作：

- 新 upstream system / namespace bootstrap。
- 扩大 scopes、跨 upstream 共享资源、platform-owned worker/model 创建。
- 跨 owner repair、旧数据迁移、生产 route 指向真实系统。
- break-glass 操作。

不应反复审批的动作：

- 已授权 upstream 内的 worker-host apply/update。
- 当前 ClientApp 内的 Agent sync、model grant/default、workspace binding、upstream user grant。
- runtime-token exchange、readiness、owner-smoke、live smoke。

## 到期与停运恢复

- runtime access token 允许短 TTL；服务恢复后应使用 ClientApp key-secret 自动换取新 token。
- upstream admin / provisioning operator 过期只应阻断 provisioning，不应影响已有 runtime ask / readiness / owner-smoke。
- `upstream admin-key inspect` 会输出 `credential expiryStatus`、`credential expiresInDays` 和必要的 `credential expiryAction`，用于人工或计划任务提前发现 provisioning credential 到期风险。
- `upstream runtime-token` 会输出 `runtimeToken.expiryStatus` 和 `runtimeToken.refresh=automatic when NAVI_CLIENT_APP_SECRET is present`，用于确认短期 runtime token 可从 ClientApp key-secret 恢复。
- production 仍建议接入 scheduled check：定期执行 inspect/status，采集 `EXPIRING_SOON` / `EXPIRED` 并记录轮换窗口。
- 轮换 provisioning credential 不应要求重发业务 runtime key，除非 ClientApp runtime key-secret 本身被轮换。

停运后恢复 smoke：

```powershell
.\tools\navigator-upstream\navi.ps1 upstream runtime-token --profile .navigator\tenants\<tenant>.env --write-profile
.\tools\navigator-upstream\navi.ps1 upstream verify-agent-readiness --profile .navigator\tenants\<tenant>.env --agent-code <agentCode> --upstream-user-id <upstreamUserId> --model-config-id <modelConfigId> --directory-id <directoryId>
.\tools\navigator-upstream\navi.ps1 upstream owner-smoke --profile .navigator\tenants\<tenant>.env --agent-code <agentCode> --upstream-user-id <upstreamUserId> --model-config-id <modelConfigId> --directory-id <directoryId>
```

期望：`runtimeToken.expiryStatus=OK`，readiness / owner-smoke 不需要 upstream admin key 或 operator key。

## 提交前检查

```powershell
git diff --check
git diff --cached | rg -n "sk-[A-Za-z0-9_-]{8,}|naa_[A-Za-z0-9_-]+|cac_[A-Za-z0-9_-]+|cak_[A-Za-z0-9_-]+|cas_[A-Za-z0-9_-]+|nabr_[A-Za-z0-9_-]+|nabt_[A-Za-z0-9_-]+"
git status --ignored --short .navigator
```

`rg` 无命中才可提交；如果命中示例占位符，必须确认不是实际 credential。
