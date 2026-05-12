# Navigator Upstream CLI Readiness And Skill Artifact Requirement

## 文档作用

- doc_type: workitem
- version: 1.1.3-SNAPSHOT
- status: recorded
- date: 2026-05-11
- priority: P0
- source_type: github-issue
- source: https://github.com/foggy-projects/Foggy-Navigator/issues/102
- intended_for: navigator-execution-agent | upstream-llm-coding-agent | upstream-backend-developer | skill-owner | reviewer
- purpose: 记录 Navigator Upstream CLI 新增 agent readiness 诊断与已授权 skill artifact 只读浏览能力的设计、边界、验收标准和进度骨架

## 背景

`world-sim` 准备接入首个真实 Navigator-backed actor decision skill：

```text
skill/agent code: world-sim.bug-coordinator.decision.v1
upstream ClientApp: external-llm-agent-dev
upstream user mapping: privateAccountId -> X-Upstream-User-Id
```

该 adapter 使用显式 skill route，要求 OpenAPI URL 中的 `agentId` 与 request metadata 中的 `context.skillId` 一致。正式 actor smoke 前，上游需要一个安全诊断命令，确认当前 Navigator 环境中：

1. 指定 skill/agent 已注册。
2. 当前 ClientApp 可见并可调用该 skill/agent。
3. 目标 upstream user 已授权。
4. 指定或默认 model config 对当前 ClientApp 可用。
5. 路由参数不会因为 `agentId` / `skillId` 不一致导致 ask 失败。
6. 上游 LLM agent 可以按需查看已授权 skill 的交付文件内容，避免只知道 skill code 而无法确认使用说明。

## 设计结论

采用两组能力，职责分离：

```text
agent readiness preflight:
  只回答当前 ClientApp + upstream user + agent/model config 是否可调用

skill artifact tree/slice:
  只读查看当前 ClientApp 已授权 skill 的交付文件结构与文本片段
```

CLI 只负责本地 profile 检查、runtime token 交换、调用 OpenAPI、脱敏输出和非零退出码。权限事实由 Navigator 后端判断，避免 CLI 拼接管理端接口或要求上游持有 admin token。

## 非目标

- 不让 CLI 调用 `/api/v1/open/agents` 管理清单接口来推断注册状态。
- 不要求上游使用 admin token 才能做 readiness 诊断。
- 不在 readiness 中创建或修改 upstream user grant、skill grant 或 model config grant。
- 不把真实 no-op ask 作为首版默认 dry-run；首版只做 resolver、授权、模型配置与路由一致性检查。
- 不暴露服务器物理路径、`manifestJson`、`adapterConfigJson`、token、secret 或业务私有数据。
- 不提供任意文件系统读取能力；只能读取已注册、已发布、已授权的 skill artifact 文本文件。

## Agent Readiness Preflight

### OpenAPI

```http
POST /api/v1/open/agents/{agentId}/preflight
```

认证头：

```text
X-Client-App-Key: <clientAppKey>
X-Client-App-Access-Token: <runtimeAccessToken>
```

请求体：

```json
{
  "upstreamUserId": "<privateAccountId>",
  "modelConfigId": "<modelConfigId>",
  "context": {
    "skillId": "world-sim.bug-coordinator.decision.v1"
  }
}
```

规则：

1. `context.skillId` 可选；为空时默认等于 URL `{agentId}`。
2. `context.skillId` 一旦提供，必须与 URL `{agentId}` 完全一致，否则返回 `ROUTE_SKILL_MISMATCH`。
3. `modelConfigId` 可选；为空时后端按当前 ClientApp 的默认 model config grant 解析。
4. 接口只读，不创建 task、不调用 Worker、不生成 task scoped token。

### 后端检查顺序

1. 使用 `X-Client-App-Key` 与 runtime access token 解析当前 active ClientApp。
2. 校验 URL `{agentId}` 与 request `context.skillId` 一致。
3. 校验 skill/agent 已注册。
4. 校验当前 ClientApp 有该 skill/agent 的访问授权。
5. 校验 `upstreamUserId` 已授权给当前 ClientApp。
6. 校验请求或默认 model config 对当前 ClientApp 可用，并满足 backend 类型要求。
7. 生成稳定、脱敏、可机器解析的 check list。

### 响应

```json
{
  "overallStatus": "OK",
  "baseUrl": "https://navigator.example.com",
  "clientAppId": "capp_xxx",
  "clientAppName": "external-llm-agent-dev",
  "agentCode": "world-sim.bug-coordinator.decision.v1",
  "upstreamUserId": "<privateAccountId>",
  "requestedModelConfigId": "<modelConfigId>",
  "effectiveModelConfigId": "<modelConfigId>",
  "checks": [
    {
      "code": "AGENT_REGISTERED",
      "status": "OK"
    },
    {
      "code": "CLIENT_APP_SKILL_GRANT",
      "status": "OK"
    },
    {
      "code": "UPSTREAM_USER_GRANT",
      "status": "OK"
    },
    {
      "code": "MODEL_CONFIG_GRANT",
      "status": "OK"
    }
  ],
  "skillArtifact": {
    "available": true,
    "treeUrl": "/api/v1/open/skills/world-sim.bug-coordinator.decision.v1/files/tree",
    "sliceUrlTemplate": "/api/v1/open/skills/world-sim.bug-coordinator.decision.v1/files/slice?path={path}&startLine={startLine}&startColumn={startColumn}&maxChars={maxChars}"
  }
}
```

`skillArtifact` 只返回入口摘要，不内联完整文件树。

## Skill Artifact Tree And Slice

### 文件树接口

```http
GET /api/v1/open/skills/{skillId}/files/tree
```

认证头同 readiness。后端必须先校验当前 ClientApp 对 `{skillId}` 有 skill grant。

响应示例：

```json
{
  "skillId": "world-sim.bug-coordinator.decision.v1",
  "artifactVersion": "v1",
  "files": [
    {
      "path": "SKILL.md",
      "type": "file",
      "size": 8421,
      "lineCount": 180,
      "sliceUrl": "/api/v1/open/skills/world-sim.bug-coordinator.decision.v1/files/slice?path=SKILL.md&startLine=1&startColumn=1&maxChars=8000"
    },
    {
      "path": "references/runtime-contract.md",
      "type": "file",
      "size": 3210,
      "lineCount": 86,
      "sliceUrl": "/api/v1/open/skills/world-sim.bug-coordinator.decision.v1/files/slice?path=references%2Fruntime-contract.md&startLine=1&startColumn=1&maxChars=8000"
    }
  ]
}
```

文件树不得返回服务器物理路径，只返回 skill artifact 内的相对路径。

### 文件切片接口

```http
GET /api/v1/open/skills/{skillId}/files/slice?path=SKILL.md&startLine=1&startColumn=1&maxChars=8000
```

响应示例：

```json
{
  "skillId": "world-sim.bug-coordinator.decision.v1",
  "path": "SKILL.md",
  "encoding": "UTF-8",
  "lineEnding": "LF_NORMALIZED",
  "startLine": 1,
  "startColumn": 1,
  "endLine": 83,
  "endColumn": 17,
  "nextLine": 83,
  "nextColumn": 18,
  "maxChars": 8000,
  "truncated": true,
  "totalLines": 180,
  "content": "..."
}
```

切片规则：

1. 服务端按 UTF-8 完整解码文件，解码失败返回 `SKILL_ARTIFACT_UNSUPPORTED_ENCODING`。
2. 换行统一规范化为 `\n`，响应标记 `lineEnding=LF_NORMALIZED`。
3. `startLine` 与 `startColumn` 均为 1-based。
4. `startColumn` 是当前行内 Unicode code point 位置，不是 byte offset，也不是 UTF-16 code unit。
5. `maxChars` 是 Unicode code point 数量，不是 byte 数。
6. 服务端切片不得截断 UTF-8 字节，也不得截断 UTF-16 surrogate pair。
7. 如果单行特别长且未读完，`nextLine` 保持当前行，只推进 `nextColumn`。
8. 默认 `maxChars=8000`，单次最大值建议限制为 `20000`。
9. 首版不承诺 grapheme cluster 级别切片；Markdown、JSON、YAML、脚本类 skill 文档按 code point 切片即可满足不乱码要求。

### 文件安全规则

1. `path` 必须规范化为 skill artifact 内相对路径。
2. 禁止 `..`、绝对路径、Windows drive path、UNC path、反斜杠逃逸。
3. 默认只允许文本文件：`.md`、`.txt`、`.json`、`.yaml`、`.yml`、`.sh`、`.ps1`、`.properties`。
4. 二进制或超大文件返回明确错误，不返回内容。
5. 不返回 `manifestJson`、`adapterConfigJson`、secret、token、内部 worker 配置和服务器物理路径。

## CLI 命令设计

主命令：

```powershell
.\tools\navigator-upstream\navi.ps1 upstream verify-agent-readiness `
  --agent-code world-sim.bug-coordinator.decision.v1 `
  --upstream-user-id <privateAccountId> `
  --model-config-id <modelConfigId>
```

兼容别名：

```powershell
.\tools\navigator-upstream\navi.ps1 upstream verify-agent-grant `
  --agent-code world-sim.bug-coordinator.decision.v1 `
  --upstream-user-id <privateAccountId> `
  --model-config-id <modelConfigId>
```

`verify-agent-grant` 是兼容 GitHub issue 原始命令草案的 alias，帮助文案应提示推荐使用 `verify-agent-readiness`。

Skill artifact 浏览命令：

```powershell
.\tools\navigator-upstream\navi.ps1 upstream skill tree `
  --agent-code world-sim.bug-coordinator.decision.v1

.\tools\navigator-upstream\navi.ps1 upstream skill read `
  --agent-code world-sim.bug-coordinator.decision.v1 `
  --path SKILL.md `
  --start-line 1 `
  --start-column 1 `
  --max-chars 8000
```

参数默认：

1. `--agent-code` 为空时读取 `NAVI_AGENT_CODE`。
2. `--model-config-id` 为空时读取 `NAVI_MODEL_CONFIG_ID`；仍为空时由后端解析默认 model config grant。
3. `skill read` 的 `--start-line` 默认 `1`。
4. `skill read` 的 `--start-column` 默认 `1`。
5. `skill read` 的 `--max-chars` 默认 `8000`。

Runtime token：

1. `runtime-token --write-profile` 会把完整 `NAVI_CLIENT_APP_ACCESS_TOKEN` 写入当前项目 gitignored profile，不打印完整 token。
2. `verify-agent-readiness`、`ask`、`messages`、`sessions`、`session-messages`、`skill tree`、`skill read` 在存在 `NAVI_CLIENT_APP_KEY` 与 `NAVI_CLIENT_APP_SECRET` 时，会在内存中自动交换 fresh runtime access token。
3. 自动交换得到的 token 纳入 CLI 敏感值脱敏集合，不写入文档或 stdout/stderr。

CLI 输出规则：

1. readiness 每个 check 输出 `OK` / `FAIL`。
2. 任一 required check 失败时返回非零退出码。
3. `--json` 输出机器可读 JSON，但仍不得包含敏感字段。
4. `skill read` 若 `truncated=true`，输出下一段读取命令建议，使用响应中的 `nextLine` 与 `nextColumn`。

## 错误码

readiness 至少区分：

```text
CONFIG_ENV_MISSING
CONFIG_ENV_NOT_GITIGNORED
RUNTIME_TOKEN_FAILED
ROUTE_SKILL_MISMATCH
AGENT_NOT_FOUND
CLIENT_APP_SKILL_GRANT_MISSING
UPSTREAM_USER_GRANT_MISSING
MODEL_CONFIG_GRANT_MISSING
DEFAULT_MODEL_CONFIG_GRANT_MISSING
MODEL_CONFIG_NOT_FOUND
MODEL_CONFIG_TENANT_MISMATCH
MODEL_CONFIG_BACKEND_UNSUPPORTED
```

skill artifact 至少区分：

```text
SKILL_ARTIFACT_NOT_FOUND
SKILL_ARTIFACT_ACCESS_DENIED
SKILL_ARTIFACT_PATH_INVALID
SKILL_ARTIFACT_FILE_NOT_FOUND
SKILL_ARTIFACT_BINARY_FILE
SKILL_ARTIFACT_UNSUPPORTED_ENCODING
SKILL_ARTIFACT_SLICE_RANGE_INVALID
SKILL_ARTIFACT_SLICE_TOO_LARGE
```

## 技术落点

后端：

1. `OpenApiController` 只做 OpenAPI facade 和请求转换。
2. 业务校验下沉到 business-agent module 的只读 service，例如 `ClientAppAgentReadinessService`。
3. readiness 复用现有 ClientApp runtime credential、skill grant、upstream user grant、model config grant 与 agent resolver 逻辑。
4. skill artifact 浏览应读取注册后的 artifact 存储模型，不读取任意本地文件系统路径。

SDK：

1. `AgentApi` 增加 readiness preflight wrapper。
2. 增加 skill artifact tree/slice wrapper。
3. SDK 不返回 secret/token，不暴露 internal worker gateway。

CLI：

1. `UpstreamCli` 增加 `verify-agent-readiness` 与 `verify-agent-grant` alias。
2. `UpstreamCli` 增加 `skill tree` 与 `skill read`。
3. 继续使用项目本地 `.navigator/upstream.env`。
4. 输出脱敏与 forbidden term 扫描纳入测试。

Skill 文档：

1. `navigator-upstream-cli` skill 增加 readiness 诊断和 skill artifact 浏览说明。
2. `navigator-upstream-llm-integration` 继续作为综合入口，链接到 CLI 专用 skill。
3. 安装更新细节继续链接独立文档，不内嵌到 `SKILL.md`。

## 验收标准

1. 上游项目根目录可执行 `verify-agent-readiness`，不需要 admin token。
2. 缺少 agent 注册、ClientApp skill grant、upstream user grant、model config grant 时，返回不同错误码和非敏感错误摘要。
3. URL `{agentId}` 与 request `context.skillId` 不一致时 fail-closed。
4. readiness 不创建 task、不调用 Worker、不签发 task scoped token。
5. `skill tree` 只返回当前 ClientApp 已授权 skill 的 artifact 相对路径和 slice URL。
6. `skill read` 支持 `startLine + startColumn + maxChars`，中文和长行不乱码。
7. `skill read` 返回 `nextLine + nextColumn`，CLI 可据此继续读取下一段。
8. 不输出 ClientApp secret、app key、runtime token、upstream user token、admin token、prompt body、`adapterConfigJson`、`manifestJson`、业务私有数据和服务器物理路径。
9. 单元测试覆盖 OK、缺少各类 grant、route mismatch、slice range、非法 path、UTF-8 长行和中文切片。
10. 配套 CLI skill 更新，指导上游 agent 在 live actor smoke 前先执行 readiness，再按需读取 skill 文档。

## Progress Tracking

### Development Progress

| Item | Status | Notes |
| --- | --- | --- |
| 需求落版 | done | 记录 GitHub issue #102 的 readiness 与 skill artifact 浏览设计 |
| 后端 readiness API | done | `POST /api/v1/open/agents/{agentId}/preflight`，只读检查 agent、skill grant、upstream user grant、model config grant 与 route skill match |
| 后端 skill artifact tree/slice API | done | `GET /api/v1/open/skills/{skillId}/files/tree` 与 `files/slice`，按已授权 skill artifact 相对路径只读浏览 |
| SDK wrapper | done | `AgentApi` 补 readiness、skill tree、skill slice wrapper |
| CLI 命令 | done | 补 `verify-agent-readiness`、`verify-agent-grant` alias、`skill tree`、`skill read` |
| runtime token profile writeback | done | GitHub issue #104：补 `runtime-token --write-profile`，runtime 命令有 key/secret 时自动换 token，`NAVI_MODEL_CONFIG_ID` 可从 env/profile 读取 |
| skill 更新 | done | 已更新 `navigator-upstream-cli` 与 `navigator-upstream-llm-integration`，安装更新细节仍链接独立文档 |
| 发布更新 | done | 已刷新 OBS `navigator-upstream-cli/1.0.0-SNAPSHOT`；安装入口 `https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/navigator-upstream-cli/install.ps1` |

### Testing Progress

| Test Area | Status | Expected Evidence |
| --- | --- | --- |
| readiness 成功路径 | done | `OpenApiAgentReadinessServiceTest` 与 `UpstreamCliTest.verifyAgentReadinessPrintsChecksAndUsesClientAppRuntimeHeaders` |
| readiness 失败区分 | partial | 覆盖 upstream user grant 缺失与 CLI 非零退出；agent/skill/model grant 的细分可继续补矩阵测试 |
| route mismatch | done | `OpenApiAgentReadinessServiceTest` 覆盖 URL agentId 与 context.skillId 不一致时 fail-closed |
| artifact access control | done | service/controller 复用 `checkClientAppSkillAccess`；`SkillArtifactServiceTest` 验证调用授权检查 |
| artifact path safety | done | `SkillArtifactServiceTest.slice_rejectsPathTraversal` 覆盖 `..` 拒绝 |
| slice unicode safety | done | `SkillArtifactServiceTest` 覆盖中文 code point 切片与超长单行续读 |
| CLI sanitized output | done | `UpstreamCliTest` 覆盖 runtime token 不进入 stdout/stderr；SDK/CLI forbidden scan 待发布前再跑 |
| runtime token writeback | done | `UpstreamCliTest.runtimeTokenWriteProfileStoresAccessTokenWithoutPrintingIt` 覆盖只写 gitignored profile 且 stdout 不打印完整 token |
| runtime token auto exchange | done | `UpstreamCliTest.verifyAgentReadinessAutoExchangesRuntimeTokenAndUsesModelConfigFromEnv` 覆盖 readiness 自动换 token 与 `NAVI_MODEL_CONFIG_ID` profile/env 默认 |

### Experience Progress

| Check | Status | Notes |
| --- | --- | --- |
| world-sim actor smoke 前置诊断 | done | `verify-agent-readiness --agent-code world-sim.bug-coordinator.decision.v1 --upstream-user-id <privateAccountId>` |
| 上游 LLM 可读 skill 交付内容 | done | `skill tree` 与 `skill read` 支持 SKILL.md/references/assets 文本切片读取 |
| 错误提示可指导补授权 | partial | CLI 输出 check code 与 message；后续可按真实 world-sim smoke 反馈继续优化文案 |

### Release Evidence

```text
package: navigator-upstream-cli-1.0.0-SNAPSHOT-windows.zip
sha256: d1977cd7ae945c8e2341a1efbce6bb84266671904ae113760b6c8de2b239826e
obs: obs://obs-fe55/navigator-upstream-cli/1.0.0-SNAPSHOT/navigator-upstream-cli-1.0.0-SNAPSHOT-windows.zip
installer: https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/navigator-upstream-cli/install.ps1
released: 2026-05-11
```

### Verification Evidence

```text
mvn -q -pl business-agent-module '-Dmaven.test.skip=true' install
  PASS: installed updated business-agent-module main artifact for downstream module tests

mvn -q -pl addons/claude-worker-agent test
  PASS: 233 tests, 0 failures, 0 errors, 0 skipped

mvn -q -pl navigator-open-sdk test
  PASS: 30 tests, 0 failures, 0 errors, 0 skipped

powershell -ExecutionPolicy Bypass -File tools\navigator-upstream-cli\dist\package.ps1
  PASS: generated navigator-upstream-cli-1.0.0-SNAPSHOT-windows.zip, SHA256 d1977cd7ae945c8e2341a1efbce6bb84266671904ae113760b6c8de2b239826e

powershell -ExecutionPolicy Bypass -File tools\navigator-upstream-cli\dist\upload.ps1 -Version 1.0.0-SNAPSHOT -AllowSameVersion
  PASS: refreshed OBS package, latest.json, and install.ps1 for issue #104 fix

remote install smoke from temp upstream project
  PASS: version/config check succeeded; generated .navigator/upstream.env includes NAVI_MODEL_CONFIG_ID and remains profileGitIgnored=true

mvn -q -pl business-agent-module -DskipTests compile
  PASS

mvn -q -pl addons/claude-worker-agent -am -DskipTests compile
  PASS
```

Coverage note: no Jacoco/coverage plugin configuration or generated coverage report was found in the current Maven poms, so this work item records test pass evidence only and does not claim coverage threshold sign-off.
