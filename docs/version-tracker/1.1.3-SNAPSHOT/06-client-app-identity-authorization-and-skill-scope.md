# Client App 身份、授权、Worker Pool 与 Skill 作用域契约

## 文档作用

- doc_type: contract-design
- version: 1.1.3-SNAPSHOT
- status: draft
- date: 2026-05-02
- intended_for: platform-owner | upstream-business-owner | java-service-owner | worker-owner | skill-owner | security-reviewer | execution-agent
- purpose: 定义 Client App 与 Navigator Java 用户体系、授权凭证、Biz Worker Pool 和 Skill 作用域之间的边界

## 背景

当前 Navigator 已有两类相关能力：

1. 内部用户体系：`UserEntity`、`CurrentUser`、JWT、API Key 和 tenant/role 字段。
2. Sharing Key：把某个 Navigator 内部用户拥有的 Agent 能力临时开放给外部调用方，外部请求实际映射为 `ownerUserId + agentId`。

这些能力可以复用部分实现经验，但不能直接作为 `1.1.3-SNAPSHOT` 的上游业务系统集成契约。业务系统集成需要同时表达：

1. 哪个 Client App 在调用。
2. 该 App 下哪个上游用户在操作。
3. Java 将其映射成哪个 Navigator 有效用户或服务账号。
4. 当前 App 被授权使用哪些 Skill、Business Function 和 Worker 资源。
5. 调用、审批、审计和回调如何绑定同一业务主体。

## 核心结论

### 交互边界

标准方向固定为：

```text
Client App
  -> Navigator Java Service
      -> Biz Worker Pool
          -> Biz Worker Instance
```

Client App 不直接认识、选择或绑定具体 Biz Worker。Biz Worker 也不直接持有 Client App 的 runtime credential，不直接访问上游 REST，不自行判断上游用户权限。

Navigator Java 服务是唯一控制面，负责：

1. 注册和管理多个 Client App。
2. 注册和管理多个 Biz Worker 或 Biz Worker Pool。
3. 将上游请求路由到合适的 Biz Worker。
4. 管理鉴权、授权、Skill 作用域、函数授权、审批、审计、幂等和凭证注入。
5. 保存 Client App、upstream user、Navigator effective user、task/session、Worker 调度和 Business Function 调用之间的关联。

### 上游多租户收敛规则

Navigator 不继承上游系统自己的多租户层级。对 Navigator 来说：

```text
上游的一个租户 = 一个 Client App
```

因此 1.1.3 不设计：

```text
upstream_system
  -> upstream_tenant
      -> upstream_user
```

而是收敛为：

```text
client_app
  -> upstream_user
```

如果某个上游 SaaS 系统有 100 个租户，则在 Navigator 侧注册为 100 个 `client_app_id`。授权、Skill、函数白名单、审批策略、callback 配置和 Worker 路由策略都挂在 `client_app_id` 上。

### App 与 Worker 不是一对一

禁止把模型设计成：

```text
client_app_id -> biz_worker_id
```

应设计为：

```text
client_app_id
  -> app grant / skill scope / function scope
  -> Java routing policy
  -> biz_worker_pool
  -> biz_worker_instance
```

Java 可以根据 capability domain、Skill、函数作用域、Worker 类型、版本、负载、灰度和故障转移策略动态选择 Biz Worker。

## 主体模型

| 主体 | 说明 | 所有者 | 是否可信 |
| --- | --- | --- | --- |
| `client_app_id` | Navigator 侧注册的上游接入单元；上游一个租户对应一个 App | Navigator Java | 是，来自 Java Registry |
| `upstream_user_id` | App 内部用户 ID，仅在 `client_app_id` 下唯一 | Client App | 需由 Java 校验签名后接受 |
| `upstream_user_display_name` | 上游用户展示名，可用于审批卡片和审计摘要 | Client App | 不作为权限依据 |
| `navigator_effective_user_id` | Java 映射出的 Navigator 内部有效用户或服务账号 | Navigator Java | 是 |
| `navigator_tenant_id` | Navigator 内部租户边界 | Navigator Java | 是 |
| `worker_identity` | Biz Worker 实例身份 | Navigator Java / Worker Registry | 是 |
| `task_scoped_token` | Java 发给 Worker 的短期任务令牌 | Navigator Java | 是 |

约束：

1. `upstream_user_id` 不能直接等同于 `navigator_effective_user_id`。
2. `navigator_effective_user_id` 可以是映射用户，也可以是 App 绑定的服务账号。
3. 审计必须同时记录上游操作者和 Navigator 有效用户，避免把 App 调用误记为普通内部用户操作。
4. Worker 只能看到执行所需的安全上下文摘要，不能信任请求体自行扩权。

## Client App 注册与凭证

### Provisioning Credential

上游创建 App 前，需要先获得 Java 发放的接入授权码或 provisioning credential。该凭证只用于创建或初始化 Client App，不用于业务运行时调用。

用途：

1. 允许创建 Client App。
2. 限制可创建 App 数量。
3. 绑定 Navigator tenant、默认 owner 或 service account。
4. 限制可申请的 capability domain。
5. 指定是否需要管理员审批。
6. 设置过期时间、使用次数和审计标签。

示例字段：

```yaml
provisioning_credential:
  code_id: pc_001
  navigator_tenant_id: nav-tenant-a
  issued_to: upstream-provider-a
  allowed_capability_domains:
    - order
    - dispatch
  max_apps: 10
  expires_at: 2026-06-01T00:00:00+08:00
  require_admin_approval: true
  status: active
```

### Runtime Credential

App 创建成功后，Java 为该 App 颁发 runtime credential。它只代表该 `client_app_id`，不能创建新 App。

首版可选形态：

1. `app_key + app_secret` HMAC 签名。
2. client credential JWT。
3. 后续扩展 mTLS。

运行时请求至少绑定：

```json
{
  "client_app_id": "app_tms_tenant_a",
  "upstream_user": {
    "id": "u_001",
    "display_name": "张三"
  },
  "request_id": "req_001",
  "timestamp": "2026-05-02T16:20:00+08:00",
  "signature": "..."
}
```

Java 必须校验 credential、签名、时间戳、重放保护和 App 状态，再进入业务任务创建或审批确认。

## 用户映射与授权

### 映射模式

首版允许三种模式，具体 App 只能选择一种默认模式，也可以按接口灰度切换：

| 模式 | 说明 | 适用场景 |
| --- | --- | --- |
| `service_account_with_actor` | App 绑定一个 Navigator 服务账号，上游用户只作为 actor 进入审计和审批 | 推荐首版默认 |
| `mapped_user` | `client_app_id + upstream_user_id` 映射到具体 Navigator 用户 | 需要内部用户级隔离时 |
| `fixed_owner` | 类似 Sharing Key，所有调用映射为 App owner | 仅兼容低风险试点，不推荐承载正式业务 |

推荐首版默认使用 `service_account_with_actor`。原因是 Client App 的授权边界应由 App Grant 决定，而不是由某个内部用户角色间接扩大。

### 授权层次

运行时必须同时通过：

```text
runtime credential valid
  -> client_app enabled
  -> upstream_user allowed
  -> app skill scope allowed
  -> app function grant allowed
  -> Java Registry function enabled
  -> approval/idempotency/audit gate
```

`navigator_effective_user_id` 只解决 Navigator 内部资源归属和 Worker/session/task 创建问题，不自动授予 Business Function 权限。

## Biz Worker Pool

当前 Worker/Agent 与用户绑定的实现可以保留。1.1.3 在 Java 设计上引入逻辑 `Biz Worker Pool`，用于隔离业务集成调度模型和底层用户归属模型。

建议字段：

```yaml
biz_worker_pool:
  pool_id: bwp_order_default
  worker_type: LANGGRAPH_BIZ
  owner_user_id: service-user-biz-worker
  navigator_tenant_id: nav-tenant-a
  capability_domains:
    - order
  supported_skill_scopes:
    - app_skill:order-close-apply
  supported_function_scopes:
    - order:close_apply:*
  routing:
    strategy: weighted
    failover_enabled: true
  status: enabled
```

约束：

1. Client App 授权的是业务能力，不是具体 Worker。
2. Worker 实例可以继续物理绑定 Navigator 用户，但 App 不直接依赖该用户。
3. Java 根据 App Grant 和路由策略选择 Pool，再选择 Worker。
4. task scoped token 必须绑定 `client_app_id`、`task_id`、`session_id`、`skill_id`、函数作用域和有效期。

## Skill 作用域

### 1.1.3 支持的 Skill 来源

1. `account_skill`：Navigator 内部人类用户个人维护的账号技能。
2. `client_app_skill`：某个 Client App 自己维护的 App 作用域技能。
3. `builtin_public_skill`：Navigator Java / Biz Worker 侧提供的平台内置公共技能。

`role_skill` 本版本不考虑，不参与 1.1.3 的 Skill 暴露和授权计算。

### Client App Skill 规则

Client App 创建后，可以维护自己的 App 作用域 Skill。该 Skill：

1. 只在当前 `client_app_id` 作用域内可见。
2. 可以授权给该 App 下的特定 `upstream_user_id` 或用户组使用。
3. 不能发布、升级或复制为平台公共技能。
4. 不能越过 Java Registry 调用未授权 Business Function。
5. 不能包含 upstream base_url、credential、curl 或任意 HTTP 能力。

### Navigator 内部人类用户会话

内部人类用户会话可见技能：

```text
account_skill
  + authorized client_app_skill
  + builtin_public_skill
```

其中 `authorized client_app_skill` 必须由 Java 授权，例如用户是 App owner、管理员、协作者或被显式授予调试/调用权限。角色技能不在本版本参与。

### Client App 发起调用

Client App 调用可见技能：

```text
client_app_skill
  intersect upstream_user_skill_grant
  intersect business_function_grant
  intersect Java Registry enabled functions
```

也就是说，App 可以维护技能，但 App 下的用户是否能使用某个 Skill，仍由 Java 侧记录的 App 用户授权决定。Skill 告诉 LLM 业务语义和边界，Java Registry/Grant 决定最终能否执行。

## 任务上下文

Java 创建 task/session 时，应形成统一上下文：

```json
{
  "client_app": {
    "id": "app_tms_tenant_a",
    "display_name": "TMS A 租户"
  },
  "upstream_user": {
    "id": "u_001",
    "display_name": "张三"
  },
  "navigator_effective_subject": {
    "user_id": "svc_app_tms_tenant_a",
    "tenant_id": "nav-tenant-a",
    "mapping_mode": "service_account_with_actor"
  },
  "skill_scope": {
    "skill_id": "order-close-apply",
    "source": "client_app_skill"
  },
  "worker_routing": {
    "pool_id": "bwp_order_default",
    "worker_type": "LANGGRAPH_BIZ"
  }
}
```

Worker 可接收 `client_app_id`、脱敏后的 `upstream_user` 摘要、`skill_id` 和 task scoped token 绑定信息，但不能接收 runtime credential、App secret、上游用户权限明细或 Worker 路由决策密钥。

## 审计要求

业务函数、脚本、审批和 callback 审计至少记录：

1. `client_app_id`
2. `upstream_user_id`
3. `navigator_effective_user_id`
4. `navigator_tenant_id`
5. `mapping_mode`
6. `skill_id`
7. `skill_source`
8. `function_id`
9. `function_version`
10. `task_id`
11. `session_id`
12. `worker_pool_id`
13. `worker_id`
14. `approval_id` / `suspend_id`
15. `request_id`
16. `idempotency_key`

审计中应能区分：

1. 哪个 App 被授权。
2. 哪个上游用户触发。
3. Navigator 用哪个有效用户承载执行。
4. Java 最终调用了哪个 Business Function。
5. Worker 只是执行编排，不是业务授权来源。

## 与 Sharing Key 的关系

Sharing Key 保持现有语义：把某个内部用户拥有的 Agent 能力通过轻量 key 暴露给外部调用方。它可复用的经验包括启停、过期、调用次数、operation allowlist 和外部入口形态。

但 1.1.3 的正式业务集成不能直接以 Sharing Key 作为完整授权模型，原因是：

1. Sharing Key 没有 `client_app_id`。
2. Sharing Key 没有上游用户身份映射。
3. Sharing Key 的 allowlist 是接口操作级，不是 Business Function scope。
4. Sharing Key 的上下文实际归属 `ownerUserId + agentId`，不适合多个 Client App 共享。
5. Sharing Key 审计不足以回答业务审批和上游用户责任归属。

## 当前验收口径

- status: draft
- verified: no
- implementation_required: yes
- development_progress: Client App 身份、授权、Worker Pool 与 Skill 作用域契约已拆出
- testing_progress: 文档自检，未执行代码测试
- experience_progress: N/A，本文不涉及 UI 交互

## 待决策

1. Provisioning Credential 首版采用一次性授权码、管理员发放 invite code，还是平台 API Key 派生。
2. Runtime Credential 首版采用 HMAC `app_key + app_secret`，还是 client credential JWT。
3. Upstream user grant 首版由 Java 保存，还是先从上游签名 claims 中读取并缓存。
4. Biz Worker Pool 首版落 DB 表，还是先复用现有 Agent/Worker 绑定并增加逻辑配置层。
5. `service_account_with_actor` 是否作为所有正式 Client App 的默认映射模式。
