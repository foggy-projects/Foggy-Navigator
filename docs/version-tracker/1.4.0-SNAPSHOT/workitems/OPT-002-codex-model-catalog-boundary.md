# OPT-002 Codex GPT-5.6 模型目录与 Runtime 边界

## 需求

- 从有效模型目录移除 `codex-mini`。
- 增加 `codex-terra -> gpt-5.6-terra` 与 `codex-luna -> gpt-5.6-luna`；两者默认使用 Medium，并保留 `alias:reasoning` 后缀能力。
- 保留 `codex-max -> gpt-5.6-sol:max`，由现有 SDK Worker 执行。
- `codex-agent-worker` 不执行任何 Ultra 请求；`codex-ultra -> gpt-5.6-sol:ultra` 只由 `codex-app-server-worker` 执行。
- 平台继续使用统一的 `OPENAI_CODEX` backend；App Server 是独立 runtime，不新增第二套业务 Provider。

## 映射

| Alias | 实际模型 | 默认/固定档位 | 执行 Runtime |
|---|---|---|---|
| `codex-latest` | `gpt-5.6-sol` | 模型默认 | SDK 或 App Server |
| `codex-terra` | `gpt-5.6-terra` | Medium | SDK 或 App Server |
| `codex-luna` | `gpt-5.6-luna` | Medium | SDK 或 App Server |
| `codex-fast` | `gpt-5.6-sol:low` | Low | SDK 或 App Server |
| `codex-deep` | `gpt-5.6-sol:high` | High | SDK 或 App Server |
| `codex-xhigh` | `gpt-5.6-sol:xhigh` | Extra high | SDK 或 App Server |
| `codex-max` | `gpt-5.6-sol:max` | Max | SDK 或 App Server |
| `codex-ultra` | `gpt-5.6-sol:ultra` | Ultra | 仅 App Server |

Terra/Luna 的非默认档位不扩展成大量固定 alias；协议层使用 `codex-terra:low|medium|high|xhigh|max` 和 `codex-luna:low|medium|high|xhigh|max`。Ultra 是否可用于某个模型族，以 App Server capability manifest 的逐模型矩阵为准，禁止跨模型族推断。

## 实现决策

1. PC 与移动端只展示稳定 alias；移除 Mini，新增 Terra、Luna、Max，保留 Ultra。
2. Max/Ultra 继续要求模型配置显式授权，旧真实模型名单的兼容兜底不会自动开放高权限档位。
3. SDK Worker 默认 alias 中删除 Mini 和 Ultra，reasoning 校验只到 Max。
4. SDK Worker 在通用请求校验前识别所有 Ultra 请求并返回 `409 CODEX_ULTRA_APP_SERVER_REQUIRED`，包括带 SDK `session_id` 的历史续接请求。
5. App Server Worker 保留 Ultra alias，删除 Mini alias，并发布 Terra/Luna alias。
6. Java runtime registry 保留 Ultra alias 用于 App Server 选路，但不再发布 Mini alias。
7. Mini 完全下线：SDK Worker 与 App Server Worker 都在 alias 解析后拒绝 `gpt-5.4-mini[:effort]`，统一返回 `UNSUPPORTED_CODEX_MODEL`；App Server capability manifest 不再声明该模型。

## 兼容性与风险

- 已绑定到 SDK runtime 的历史 Ultra thread 无法原 runtime 续接；请求会明确失败，不能静默降级为 Max/xhigh，也不能跨 runtime 重放原 prompt。
- 当前运行中的旧版 Worker/前端不会因源码修改自动更新，需要重新构建、发布和重启后才生效。
- `gpt-5.4-mini` 不再提供 alias、目录、动态透传或续接兼容；历史请求和自定义 alias 都会被稳定拒绝，禁止静默切换到其他模型。

## 验证

- SDK Worker：`npm test`、`npm run typecheck`、`npm run build` 通过；覆盖 alias 后 Mini 拒绝、reasoning 与新建/续接 Ultra 409。
- App Server Worker：Node 测试、`npm run typecheck`、`npm run build` 通过；覆盖 capability 移除及直接/alias Mini 拒绝。
- Java：`CodexRuntimeRegistryServiceTest` 连同模块依赖共 71 项测试通过。
- PC：模型目录定向单测 14 项通过，`scripts/build-frontend.sh` 全量构建通过。
- Mobile：H5 构建通过，薄壁镜像与 PC 的 alias 集合一致。
