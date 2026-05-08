# 业务对象与函数注册

## 文档作用

- doc_type: integration-guide
- version: 1.1.3-SNAPSHOT
- status: draft
- date: 2026-05-04
- intended_for: upstream-backend-developer | platform-admin
- purpose: 说明 BusinessObject、BusinessFunction、Function Version、Manifest 和 Function Grant 的关系与注册流程

## 核心概念

### BusinessObject

**BusinessObject 是业务对象 / 资源对象**，用来组织一组相关的 Business Function。它提供逻辑分组，但**不是授权主体**。

> **⚠️ 重要**：授权控制在 ClientApp、upstreamUser、Skill 和 Function Grant 层面进行，不在 BusinessObject 层面。BusinessObject disabled 时不允许继续挂载新的函数版本，但不影响已有函数的授权状态。

### BusinessFunction / BusinessFunctionVersion

- **BusinessFunction**：一个稳定的业务函数标识，如 `tms.order.get`。
- **BusinessFunctionVersion**：函数的具体版本，包含完整的 Manifest（schema、adapter、风险等级、审批策略等）。
- 同一 `function_id` 可以有多个版本（`v1`、`v2`...），运行时使用 enabled 版本。

### Manifest

Business Function Manifest 是上游 REST API 进入 Navigator 的受控声明。详细字段定义请参考 [02-business-function-manifest-schema.md](../02-business-function-manifest-schema.md)。

### Function Grant

**Function Grant 是授权主体之一**。它表示某个 ClientApp 被允许使用某个 Business Function。

## 关系模型

```text
BusinessObject (e.g., Order)
  └── BusinessFunction (e.g., tms.order.get)
        └── BusinessFunctionVersion (e.g., v1)
              └── Manifest (schema, adapter, risk, approval)

ClientApp
  └── Function Grant → BusinessFunction
  └── Skill Grant → Skill
        └── Skill Function allowlist → BusinessFunction
```

## 示例：Order 业务对象

以一个订单（Order）业务对象为例：

```text
BusinessObject: Order
  ├── tms.order.get          - 查询订单详情（readonly）
  ├── tms.order.cancel_order - 取消订单（state_change，需审批）
  └── tms.order.refund_order - 退款（external_side_effect，需审批）
```

## 注册流程

### Step 1：创建 BusinessObject

```text
POST /api/v1/business-agent/business-objects
```

请求体示例：

```json
{
  "objectId": "order",
  "name": "订单",
  "description": "TMS 订单资源对象",
  "domain": "order",
  "status": "ENABLED"
}
```

### Step 2：导入 BusinessFunction（含 Manifest）

```text
POST /api/v1/business-agent/functions/import
```

请求体应包含业务函数元数据、schema 和 Manifest JSON。当前 REST Form 使用 camelCase 字段；Manifest 内部字段可继续保持 manifest schema 约定。

请求体示例字段：

```yaml
functionId: tms.order.get
businessObjectId: order
version: v1
domain: order
name: 查询订单详情
description: 查询指定订单详情
exposure: LLM
riskLevel: readonly
approvalRequired: false
idempotencyRequired: true
inputSchemaJson: "{...}"
outputSchemaJson: "{...}"
llmVisibleSummary: 查询订单详情
schemaVisibleSummary: 输入 orderId，返回订单摘要
manifestJson: "{...}"
adapterConfigJson: "{...}"
status: ENABLED
```

Manifest 关键字段：

| 字段 | 可见性 | 说明 |
| --- | --- | --- |
| `function_id` | LLM 可见 | 稳定业务函数标识 |
| `business_object_id` | Registry 内部 | 关联的 BusinessObject |
| `version` | Registry 内部 | Manifest 版本 |
| `description_for_llm` | LLM 可见 | 业务语义说明，**不含** HTTP path、URL 或 credential |
| `risk_level` | LLM 可见 | `readonly` / `draft` / `state_change` / `external_side_effect` / `delete` |
| `input_schema` | Schema 可见 | JSON Schema，Worker 只能看到脱敏后的业务字段 |
| `output_schema` | Schema 可见 | JSON Schema |
| `transport` | **Java-only** | 底层调用方式（REST/RPC 等） |
| `adapter` | **Java-only** | 参数映射、header 注入、响应裁剪 |
| `approval_policy` | 部分 LLM 可见 | 是否需要审批 |
| `skill_allowlist` | Registry 内部 | 允许哪些 Skill 使用该函数 |

> 完整 Manifest 字段定义请参考 [02-business-function-manifest-schema.md](../02-business-function-manifest-schema.md)，此处不重复完整 schema。

### Step 3：查询或更新 BusinessObject

```text
GET /api/v1/business-agent/business-objects/{objectId}
PUT /api/v1/business-agent/business-objects/{objectId}
```

### Step 4：授权函数给 ClientApp（Function Grant）

```text
POST /api/v1/business-agent/client-apps/{clientAppId}/function-grants
```

请求体应包含目标 `functionId`。

请求体示例字段：

```yaml
functionId: tms.order.get
version: v1
status: ENABLED
```

### Step 5：启停 Function Grant

```text
PUT /api/v1/business-agent/client-apps/{clientAppId}/function-grants/{grantId}/status
```

### Step 6：查询 ClientApp 可见函数

```text
GET /api/v1/business-agent/client-apps/{clientAppId}/visible-functions
```

返回当前 ClientApp 被授权、且通过 Skill allowlist 可见的函数摘要列表。

## Manifest 安全边界

- `transport` 和 `adapter` 仅 Java 侧可见，不进入 LLM 上下文。
- `description_for_llm` 描述业务能力，不描述底层 REST 调用细节。
- `adapterConfigJson` 和 `manifestJson` 不在 Worker schema 响应中返回。
- 上游 REST 地址通过 `upstream_ref`（Java 配置引用）解析，不是 LLM 可见的 URL。

## SDK 状态

> **SDK 待补齐**：BusinessObject、BusinessFunction 和 Function Grant 的管理 API 当前 `navigator-open-sdk` 尚未封装。请使用 REST API。
