---
type: requirement
version: 1.4.2-SNAPSHOT
ticket: REQ-002
priority: high
status: ready-for-verification
decision_status: confirmed
implementation_status: implementation-complete-verification-partial
source: user-confirmed-error-diagnostics-expansion
owner: root-workspace
---

# 结构化错误诊断与临时分享链接

## 文档作用

- doc_type: requirement
- intended_for: project-root-session | reviewer | signoff-owner
- purpose: 冻结 Worker 错误结构化、诊断快照、内部详情页和临时公开分享链接的语义、安全边界与验收标准。

## 背景与问题

当前 Worker 错误链路优先保证稳定错误码和敏感信息不外泄，但也造成诊断信息过度收敛：Codex SDK Worker 能捕获原始失败信息，Java 中继会将非标准错误归并为 `CODEX_WORKER_REMOTE_ERROR`；Codex App Server Worker 更早将原始失败压缩为稳定分类。前端因此只能展示抽象错误码，开发和运维需要回查分散日志，无法直接判断失败阶段、错误类别和建议处理方式。

本需求在不恢复旧 Monitoring、自建通用日志平台或公开原始 Worker 日志的前提下，引入 Provider 无关的错误诊断契约。系统应始终返回安全的结构化错误摘要，并保存可控留存的诊断快照；登录用户可按任务 ownership 查看详情，需要跨账号或临时协作时可主动生成有期限、可撤销的匿名分享链接。

## 已确认口径

以下五项由 Owner 于 `2026-07-15` 确认：

1. 采用“结构化错误 + 诊断快照 + 按需生成临时公开链接”的两阶段方案。
2. 诊断快照默认保存 90 天。
3. 公开链接按需生成，默认有效 7 天、最大 30 天，并支持主动撤销。
4. 匿名页面不展示原始堆栈、Prompt、完整路径、工具输入输出或凭据等敏感内容。
5. 能力采用 Provider 无关的公共协议，Codex 首批落地，后续 Claude、Gemini、LangGraph 可复用。

## 目标

1. 将稳定错误码、人类可读说明、错误类别、运行阶段、可恢复性和诊断引用作为统一错误信封传递。
2. 在原始错误被稳定化之前生成经过脱敏、限长和分级的诊断快照。
3. 让登录用户通过任务归属校验查看内部诊断详情。
4. 让有权限的用户按需签发、复制和撤销临时匿名分享链接。
5. 保持旧 Worker、旧消息和只识别 `error` 字符串的客户端兼容。
6. 对 SDK Worker 与 App Server Worker 的信息可用程度如实建模，不虚构已丢失的原始原因。

## 术语与命名

| 术语 | 定义 | 约束 |
|---|---|---|
| error envelope | 通过 WorkerEvent、AgentMessage、Task DTO 和 SSE 传播的安全错误摘要 | 不携带原始堆栈、Prompt、工具数据或匿名分享令牌 |
| diagnostic snapshot | 错误发生时持久化的 Provider 无关诊断记录 | 默认保存 90 天，按 owner/tenant 隔离，内容必须先脱敏 |
| `diagnosticRef` | 稳定逻辑引用，如 `diagnostic://dg_xxx` | 可持久化到会话和任务，不授予匿名访问权限 |
| share token | 用户主动签发的匿名访问 bearer token | 高熵、不可枚举、只存哈希、有期限、可撤销 |
| diagnostic share URL | 包含或引用 share token 的匿名访问链接 | 不在错误事件中自动生成，不写入长期任务错误字段 |

不得使用 `errorLink`、`publicUrl`、`detail` 等无法表达身份、生命周期或安全语义的泛化名称替代上述契约。

## 功能范围

### 1. 结构化错误信封

统一错误信封至少支持以下字段；除 `errorCode` 外均允许为空，以兼容旧 Worker 和信息受限的 Provider：

```json
{
  "errorCode": "CODEX_WORKER_REMOTE_ERROR",
  "message": "Codex 执行进程异常退出",
  "category": "RUNTIME",
  "runtimePhase": "TURN_EXECUTION",
  "recoverable": true,
  "diagnosticRef": "diagnostic://dg_xxx",
  "occurredAt": "2026-07-15T10:30:00+08:00",
  "taskId": "task_xxx",
  "providerType": "CODEX",
  "runtimeType": "SDK_EXEC"
}
```

协议要求：

1. 保留现有 `error` 字符串作为兼容字段，其值继续为稳定错误码或安全短消息。
2. 新字段采用可选字段增量扩展；旧 Worker 和旧客户端缺失字段时行为不变。
3. `message` 是面向用户的安全短说明，不等于原始 exception message。
4. `category` 使用稳定枚举，首批至少覆盖 `AUTHENTICATION`、`AUTHORIZATION`、`CONFIGURATION`、`NETWORK`、`RATE_LIMIT`、`RUNTIME`、`TIMEOUT`、`CANCELLED`、`UNKNOWN`。
5. `runtimePhase` 表示失败阶段，不与 Provider 原始 subtype 混为一体。
6. `diagnosticRef` 只在诊断快照成功保存后返回；快照保存失败不得覆盖原始任务终态。

### 2. 诊断快照

诊断快照至少记录：

- 诊断 ID、schema version、redaction version；
- taskId、sessionId、ownerUserId、tenantId；
- providerType、runtimeType、Worker 标识或安全标签；
- errorCode、category、runtimePhase、safeMessage、recoverable；
- Provider 安全状态信息，例如 HTTP status、provider status、重试次数；
- 已脱敏、限长的异常类型和诊断文本；
- occurredAt、createdAt、expiresAt；
- 快照生成或脱敏失败状态，不因诊断辅助能力失败改变任务终态。

诊断快照作为 Session/Task 资源治理的一部分，由平台持久化；不得直接复用 `codex_tasks.errorMessage`、会话消息 payload 或旧执行报告表承载完整诊断内容。

### 3. 登录态详情访问

1. 登录用户通过 `diagnosticRef` 或诊断 ID 查询详情前，必须校验 Task/Session ownership 与 tenant 语义。
2. 管理员或系统主体如需旁路，必须使用项目现有明确主体模型，不得因“内部使用”新增无审计的通用 bypass。
3. 查询接口返回的内部详情仍必须脱敏；是否展示安全堆栈片段由独立配置控制，默认关闭。
4. 不存在、已过期、无权访问的诊断记录使用不可枚举的统一错误反馈，不泄露 owner、tenant 或任务是否存在。

### 4. 临时公开分享链接

公开链接采用独立签发流程，不随错误事件自动返回：

1. 有权访问诊断详情的登录用户可生成分享链接。
2. 默认有效期 7 天，允许选择更短期限，最大 30 天。
3. token 使用密码学安全随机值，服务端只存 token hash，不存明文。
4. 同一诊断允许存在多个分享链接；每个链接独立过期和撤销。
5. 支持主动撤销，并记录 createdBy、createdAt、expiresAt、revokedAt、lastAccessAt 和 accessCount。
6. 匿名访问只授予单个诊断快照的只读权限，不可由 token 横向查询任务、会话、附件或其他诊断。
7. 产品能力默认关闭；内部部署 profile 可显式开启。关闭后不得签发新链接，既有链接的继续有效或统一撤销策略必须由配置语义明确，推荐 fail closed。
8. 分享 URL 不使用可枚举 taskId、sessionId 或 diagnosticId 作为唯一访问凭据。

### 5. 匿名详情页内容与响应安全

匿名页面允许展示：

- 错误码、类别、用户可读说明和建议处理方式；
- 发生时间、运行阶段、Provider/运行时类型；
- 脱敏后的状态码、异常类型和短诊断文本；
- 诊断编号和链接过期时间。

匿名页面禁止展示：

- API Key、Token、Cookie、Authorization、请求头；
- Prompt、用户消息正文、工具输入输出、文件内容；
- 环境变量、原始请求体、完整 endpoint 和 query string；
- 完整工作目录、本机用户名和绝对路径；
- 未脱敏堆栈、Provider 原始响应和 Worker 原始日志。

匿名响应必须具备 `Cache-Control: no-store`、`Referrer-Policy: no-referrer`、`X-Robots-Tag: noindex, nofollow` 和适当 CSP；页面不得加载第三方统计、脚本、字体或其他会携带访问上下文的资源。

### 6. Provider 首批行为

| Provider/Runtime | 当前信息能力 | 首批要求 |
|---|---|---|
| Codex SDK Worker | 能在 `turn.failed` 和运行异常捕获原始 message | 在稳定化前分类、脱敏并生成安全诊断输入；不得把原始 message 直接写入 SSE |
| Codex App Server Worker | 已主动将原始失败收敛为稳定 code/kind/status | 首批保留 code、kind、provider status、phase 等安全元数据；如增加原始捕获，必须先经过同一脱敏器 |
| Claude/Gemini/LangGraph | 本需求不要求同步完成 | 公共协议和存储不得绑定 Codex；后续按 Provider 单独补适配和测试 |

## 用户体验

错误卡片分三层呈现：

1. 默认显示错误标题、具体说明、错误码和建议动作。
2. 有 `diagnosticRef` 时显示“查看错误详情”和“复制诊断信息”。
3. 部署开启分享能力且当前用户有权限时显示“生成分享链接”；生成后显示有效期、复制和撤销操作。

前端不得在首屏自动签发分享 token，也不得把 token 写入埋点、错误日志、复制诊断文本或持久化 chat message。

## 脱敏要求

脱敏器必须集中维护并带版本号，至少处理：

- Bearer/Basic/API key/token/cookie 等凭据模式；
- Navigator 自有 sharing key、task token、Worker credential；
- URL userinfo、query 和 fragment 中的敏感参数；
- Windows、Linux、macOS 绝对路径和用户目录；
- email、IP 等可能构成身份或环境暴露的信息按配置降级；
- Prompt、工具输入输出和超过限制的任意原始文本。

所有公开内容必须使用 allowlist 组装，不允许先序列化完整对象再依赖字符串替换。脱敏失败时只保存稳定错误码和分类，不回退到原始内容。

## 数据生命周期

1. 诊断快照默认保存 90 天，到期后可批量清理。
2. 分享 token 默认 7 天、最大 30 天，且不得超过快照剩余有效期。
3. 快照删除或过期时，关联分享链接立即失效。
4. 清理任务必须幂等，并记录删除数量和失败数量，不记录明文 token 或诊断正文。
5. 未来如需长期审计，应输出脱敏后的治理事件，不延长原始诊断快照保存期替代审计系统。

## 兼容与迁移

1. `WorkerEvent.error`、AgentMessage `content` 和现有 `errorMessage` 保持兼容。
2. 新字段缺失时前端继续使用错误码本地映射，不要求历史消息回填。
3. 诊断表采用新增 schema，不重写历史 Task 数据；上线 migration 必须提供 rollback 或可验证的 forward-only 说明。
4. Worker 与平台允许滚动升级：新平台接收旧 Worker，新 Worker 对旧平台仍发送兼容 `error` 字段。
5. 不把匿名分享端点并入现有 Sharing Key 能力；Sharing Key 权限范围大于单诊断链接，不适合作为替代 token。

## 非目标

1. 不恢复已退役的 Monitoring 模块或建设通用日志、Tracing、APM 平台。
2. 不公开原始 Worker 日志、Prompt、工具记录或完整异常堆栈。
3. 不让匿名链接执行重试、恢复、取消、审批、文件下载或其他任务动作。
4. 不在首批同时完成所有 Provider 适配。
5. 不以 traceId 代替诊断 ID；现有 HTTP traceId 不是跨异步任务链的持久身份。
6. 不建立永久匿名链接，不允许无限期或不可撤销分享。

## 验收标准

| ID | 验收标准 |
|---|---|
| ED-AC-01 | WorkerEvent、AgentMessage、Task/SSE 在保留旧 `error` 兼容字段的同时，可传递统一结构化错误摘要。 |
| ED-AC-02 | Codex SDK 原始失败在稳定化前经过分类、限长和脱敏，客户端不会收到未脱敏原始 message。 |
| ED-AC-03 | Codex App Server 至少返回稳定 code、kind/status 和 runtime phase，不虚构已丢失的原始原因。 |
| ED-AC-04 | 诊断快照按 userId+tenantId+task/session ownership 隔离，无权主体不能通过诊断 ID 判断资源是否存在。 |
| ED-AC-05 | 快照默认 90 天过期，清理幂等，过期后内部详情和所有分享链接均不可访问。 |
| ED-AC-06 | 分享链接只能由有权登录用户按需签发，默认 7 天、最大 30 天，并可单独撤销。 |
| ED-AC-07 | 服务端只保存 share token hash；token 不出现在会话消息、任务 errorMessage、应用日志或分析埋点中。 |
| ED-AC-08 | 匿名链接只能读取单个脱敏诊断快照，不能查询或操作 Task、Session、附件和其他诊断。 |
| ED-AC-09 | 匿名页面不包含凭据、Prompt、工具数据、完整路径、原始请求或未脱敏堆栈，并返回 no-store/no-referrer/noindex/CSP。 |
| ED-AC-10 | 分享能力默认关闭，内部部署显式开启；关闭和撤销均 fail closed。 |
| ED-AC-11 | 旧 Worker、旧消息与缺少新字段的错误仍可显示，不发生协议或 UI 崩溃。 |
| ED-AC-12 | 错误卡片提供具体说明、详情、复制诊断信息及按权限显示的生成/撤销分享链接操作。 |
| ED-AC-13 | Java、Codex 两类 Worker、chat-core/chat 和主前端的适用测试、类型检查与构建实际通过并记录证据。 |
| ED-AC-14 | 浏览器验证覆盖登录态详情、无权拒绝、生成/访问/过期/撤销链接和公开页面敏感信息扫描。 |

## 测试与证据要求

至少覆盖：

- Java：诊断持久化、ownership、过期清理、签发、token hash、撤销、统一不可枚举反馈和安全响应头。
- Codex SDK/App Server Worker：分类、稳定 code、脱敏、限长、异常降级和旧协议兼容。
- 前端：新旧错误 payload、详情按钮、复制内容、分享开关、过期/撤销反馈和无 token 持久化。
- 安全负向：凭据、URL、路径、Prompt、工具数据、堆栈和控制字符注入不得进入匿名输出。
- 体验：真实浏览器验证错误卡片、登录态详情和匿名分享页面；证据写入当前版本目录。

## 风险与约束

1. 错误详情最主要风险是凭据和业务内容泄露；公开内容必须 allowlist 组装，不能依赖单一正则兜底。
2. App Server 当前已丢弃部分原始信息，首批详情完整度低于 SDK Worker属于预期兼容事实。
3. 诊断快照属于用户任务数据，必须复用现有 ownership 语义，不能因项目对内使用而放宽。
4. 匿名链接是 bearer capability；一旦转发即视为授权转移，因此必须短期、可撤销、不可横向扩权。
5. 诊断写入失败不能阻塞任务终态；应降级为无 `diagnosticRef` 的稳定错误摘要并记录安全平台日志。

## 实施与后置门禁

- 实施阶段：本版本 [Implementation Plan](../implementation-plan.md) 的 P8。
- 进度回写：[Progress](../progress.md)。
- 实现完成后执行 implementation self-check；因涉及跨 Worker 协议、持久化、匿名访问和 UI，必须进入正式 implementation quality gate、test coverage audit 和 acceptance signoff。
- `2026-07-14` 的既有版本签收结论只覆盖当时范围；REQ-002 实施后必须重新执行门禁，不得复用旧 `rejected` 或历史通过证据作为本需求结论。
