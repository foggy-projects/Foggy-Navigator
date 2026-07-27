# Claude Worker 接入 GPT-5.6 Sol 网关的 ModelConfig 配置

状态：`READY_FOR_USE`

更新时间：2026-07-27

适用范围：

- Foggy Navigator `CLAUDE_CODE` Worker；
- Anthropic Messages 兼容网关 `https://codex2.qlfloor.com:9443`；
- 网关提供以下稳定模型别名：
  - `gpt-5.6-sol-max`
  - `gpt-5.6-sol-high`
  - `gpt-5.6-sol-low`

本文不包含任何 API Key。凭据必须通过 Navigator 加密模型凭据或部署环境安全注入，
不得写入 tracked 文件、日志、截图或验证输出。

## 1. 结论

OpenAI 官方当前公布的 `gpt-5.6-sol` 限额是：

| 项目 | 官方值 |
|---|---:|
| Context window | 1,050,000 tokens |
| Max output | 128,000 tokens |
| 长上下文计价分界 | 输入超过 272,000 tokens |

官方资料：

- [GPT-5.6 Sol model](https://developers.openai.com/api/docs/models/gpt-5.6-sol)
- [GPT-5.6 model guidance](https://developers.openai.com/api/docs/guides/latest-model)

`1,050,000` 是输入、输出、reasoning 和协议内容共享的硬上下文上限，不是建议把输入
堆到 1,050,000。若要为最大 128,000 输出和 Claude Code 压缩过程留出空间，理论输入
上限不能超过约 922,000 tokens；实际运行还必须为 system prompt、工具定义、工具结果、
压缩摘要和估算误差保留余量。

本项目推荐按以下运行预算配置：

```text
Claude Code 自动压缩计算窗口：270,000 tokens
自动压缩触发比例：85%
预计压缩触发点：约 229,500 tokens
相对配置窗口剩余：约 40,500 tokens
```

选择 270,000 而不是直接使用 1,050,000，原因是：

1. 当前先按最大 270K 的保守运行窗口验证 Claude Code、Anthropic Messages 网关和
   GPT-5.6 Sol 的长会话稳定性；
2. 自动压缩会在约 229.5K 触发，给 Claude Code 当前实测的 32K `max_tokens`、压缩摘要、
   system/tool 协议内容和 tokenizer 差异留下约 40.5K 缓冲；
3. 配置窗口低于 OpenAI 的 `>272K` 长上下文计价分界，可避免正常情况下进入整次请求
   的长上下文高计价档；
4. 避免 Claude Code 默认约 95% 才压缩导致摘要请求本身空间不足。

超过 272K 输入会进入 OpenAI 官方公布的长上下文计价档。270K 是 Claude Code 的
压缩计算窗口，不改变 GPT-5.6 Sol 的 1.05M 官方硬能力；若单次请求因估算误差或协议
开销接近边界，仍应以网关记录的实际 input tokens 为准。

## 2. 推荐 ModelConfig

### 2.1 推荐方案：稳定网关别名

在 Navigator 管理台的模型配置中创建或更新一个模型：

| 字段 | 推荐值 |
|---|---|
| 名称 | `Claude GPT-5.6 Sol Gateway` |
| Worker Backend | `CLAUDE_CODE` |
| Model Name | `gpt-5.6-sol-high` |
| Base URL | `https://codex2.qlfloor.com:9443` |
| Available Models | 见下方列表 |
| API Key / Auth Token | 通过加密凭据或安全环境注入 |

`Available Models`：

```text
gpt-5.6-sol-max
gpt-5.6-sol-high
gpt-5.6-sol-low
```

`envVars`：

```json
{
  "ANTHROPIC_MODEL": "gpt-5.6-sol-high",
  "ANTHROPIC_DEFAULT_OPUS_MODEL": "gpt-5.6-sol-max",
  "ANTHROPIC_DEFAULT_SONNET_MODEL": "gpt-5.6-sol-high",
  "ANTHROPIC_DEFAULT_HAIKU_MODEL": "gpt-5.6-sol-low",
  "CLAUDE_CODE_AUTO_COMPACT_WINDOW": "270000",
  "CLAUDE_AUTOCOMPACT_PCT_OVERRIDE": "85",
  "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC": "1"
}
```

该方案的运行结果：

| Navigator/Claude Code 选择 | `/v1/messages` 的 `model` |
|---|---|
| 默认 | `gpt-5.6-sol-high` |
| Max | `gpt-5.6-sol-max` |
| High | `gpt-5.6-sol-high` |
| Low | `gpt-5.6-sol-low` |

这是首选方案，因为发给网关的是固定别名，不依赖 Claude Code 将来把 `opus`、`sonnet`
或 `haiku` 更新到新的 Anthropic 官方模型 ID。

### 2.2 可选方案：界面使用 Opus / Sonnet / Haiku 语义

只有在界面必须显示 Claude 家族名称时，才使用：

```text
Model Name: sonnet
Available Models:
  opus
  sonnet
  haiku
```

并保留 2.1 中的 `ANTHROPIC_DEFAULT_*_MODEL` 映射。Claude Code 会展开为：

```text
opus   -> gpt-5.6-sol-max
sonnet -> gpt-5.6-sol-high
haiku  -> gpt-5.6-sol-low
```

不建议把 Anthropic 完整版本 ID，例如 `claude-opus-4-8` 或未来版本，保存进
ModelConfig。它们会随 Claude Code 升级变化。

## 3. 配置优先级

Navigator 当前先解析有效模型：

1. 请求显式 `model`；
2. Coding Agent 的 `defaultModel`；
3. ModelConfig 的 `modelName`；
4. Python Worker fallback `opus[1m]`。

Python Worker 把解析结果写入 `ClaudeAgentOptions.model`，等价于 Claude Code
`--model`。Claude Code 的模型选择优先级是：

```text
--model > ANTHROPIC_MODEL > settings.json 的 model
```

因此必须给 ModelConfig 设置非空 `modelName=gpt-5.6-sol-high`。不能只设置
`ANTHROPIC_MODEL`，否则没有 Navigator 模型时，Worker 当前的 `opus[1m]` fallback
会覆盖它。

`ANTHROPIC_DEFAULT_OPUS_MODEL`、`ANTHROPIC_DEFAULT_SONNET_MODEL` 和
`ANTHROPIC_DEFAULT_HAIKU_MODEL` 只负责展开相应家族别名；当 `--model` 已经是
`gpt-5.6-sol-high` 这样的完整网关别名时，Claude Code 会原样传递。

## 4. Token 与压缩边界

### 4.1 不要混淆三个数值

| 数值 | 含义 |
|---|---|
| 1,050,000 | GPT-5.6 Sol 官方硬上下文窗口 |
| 270,000 | 当前推荐给 Claude Code 的自动压缩计算窗口 |
| 229,500 | 按 85% 计算的预计自动压缩触发点 |
| 40,500 | 触发点到配置窗口之间的运行缓冲 |

`CLAUDE_CODE_AUTO_COMPACT_WINDOW` 不改变上游模型的真实容量，只改变 Claude Code
何时认为需要压缩。网关仍必须真实支持 GPT-5.6 Sol 的 1,050,000 context window。

`CLAUDE_AUTOCOMPACT_PCT_OVERRIDE` 只能把压缩提前；高于 Claude Code 默认阈值的值
不会扩大真实窗口。

### 4.2 最大输出

OpenAI 官方最大输出是 128,000 tokens，但 Claude Code 仍会根据自身运行策略设置
每次 Anthropic Messages 请求的 `max_tokens`。本机 Claude Code 2.1.202 的最小请求
捕获中使用了 `max_tokens=32000`。

不要在 ModelConfig 中伪造一个“128K 输出环境变量”。128K 是 provider 能力上限，
不是每次 Claude Code 调用都应请求的输出长度。若未来需要改变 Claude Code 单次输出
预算，应单独做兼容性验证。

### 4.3 Reasoning effort

网关固定别名已经承担 effort 路由：

```text
gpt-5.6-sol-max  -> gpt-5.6-sol + reasoning.effort=max
gpt-5.6-sol-high -> gpt-5.6-sol + reasoning.effort=high
gpt-5.6-sol-low  -> gpt-5.6-sol + reasoning.effort=low
```

因此 ModelConfig 不再额外设置 Claude Code `--effort` 或
`CLAUDE_CODE_EFFORT_LEVEL`，避免模型别名和请求 effort 出现两个竞争来源。

OpenAI 官方建议 `max` 只用于最困难、质量优先的任务；日常默认使用 High，简单或
延迟敏感任务使用 Low。

## 5. 凭据配置

优先使用 Navigator ModelConfig 的加密 credential 字段。若部署使用 Worker 本机
配置，则只允许在未跟踪的 `tools/claude-agent-worker/.env` 或部署 secret 中设置：

```dotenv
AGENT_WORKER_ANTHROPIC_BASE_URL=https://codex2.qlfloor.com:9443
AGENT_WORKER_ANTHROPIC_AUTH_TOKEN=<由安全环境注入>
```

不要把凭据放入：

- `.claude/settings.json`；
- ModelConfig `envVars`；
- `.env.example`；
- Shell、PowerShell、Dockerfile 或 CI tracked 配置；
- 验证命令参数、日志或交付证据。

ModelConfig 的 per-request 加密凭据优先于 Worker 全局配置。

## 6. Claude Worker 版本判断

审计时环境：

```text
claude-agent-sdk: 0.2.111
bundled Claude Code: 2.1.202
PyPI 最新 claude-agent-sdk: 0.2.128
```

当前版本已经确认支持：

- `ANTHROPIC_DEFAULT_OPUS_MODEL`
- `ANTHROPIC_DEFAULT_SONNET_MODEL`
- `ANTHROPIC_DEFAULT_HAIKU_MODEL`
- `CLAUDE_CODE_AUTO_COMPACT_WINDOW`
- `CLAUDE_AUTOCOMPACT_PCT_OVERRIDE`
- 自定义模型名称原样发送到 `/v1/messages`

所以本次配置不要求升级 Worker。为了这次接入而升级 SDK 会额外改变 Claude Code
别名和运行行为，收益不足，暂不升级。

仓库当前依赖声明为 `claude-agent-sdk>=0.1.37`，不是可复现锁定。后续如要升级，应作为
独立 Worker 发布执行：固定目标 SDK/CLI 版本，运行 Worker 全量测试和真实网关 smoke，
再发布 Worker，而不是在生产机器上临时 `pip install -U`。

## 7. 配置后验证

### 7.1 直接验证 Claude Code 到网关

先在当前 Shell 中安全设置 `ANTHROPIC_AUTH_TOKEN`，再执行：

```bash
CLI=tools/claude-agent-worker/.venv/lib/python3.12/site-packages/claude_agent_sdk/_bundled/claude; \
test -n "${ANTHROPIC_AUTH_TOKEN:-}" || { echo "ANTHROPIC_AUTH_TOKEN is not set" >&2; exit 1; }; \
for model in gpt-5.6-sol-max gpt-5.6-sol-high gpt-5.6-sol-low; do \
  env \
    ANTHROPIC_BASE_URL=https://codex2.qlfloor.com:9443 \
    ANTHROPIC_AUTH_TOKEN="$ANTHROPIC_AUTH_TOKEN" \
    CLAUDE_CODE_AUTO_COMPACT_WINDOW=270000 \
    CLAUDE_AUTOCOMPACT_PCT_OVERRIDE=85 \
    CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1 \
    "$CLI" -p "仅回复 OK" --model "$model" --max-turns 1 \
      --tools "" --output-format stream-json --verbose \
  | jq -r --arg requested "$model" \
      'select(.type=="assistant") | "\($requested) -> \(.message.model)"' \
  | head -1; \
done
```

验证命令不会打印 Token，但会产生三次真实网关请求。预期：

```text
gpt-5.6-sol-max  -> gpt-5.6-sol-max
gpt-5.6-sol-high -> gpt-5.6-sol-high
gpt-5.6-sol-low  -> gpt-5.6-sol-low
```

### 7.2 Navigator 验证

1. 保存 ModelConfig；
2. 将其授权给目标 ClientApp/Coding Agent，并设为默认模型配置；
3. 新建会话，避免旧会话冻结的模型选择影响结果；
4. 分别选择 Max、High、Low 发起最小请求；
5. 检查 Worker 事件返回的 model，不检查或输出任何鉴权 header；
6. 在网关侧确认请求路径为 `/v1/messages`，模型分别为三个固定别名；
7. 长会话用 Claude Code `/context` 或等价状态信息确认压缩窗口；不需要用真实
   270K 内容做破坏性压力测试。

## 8. 回滚

若网关在 270K 运行窗口下仍不稳定，先只调整 ModelConfig `envVars`：

```json
{
  "CLAUDE_CODE_AUTO_COMPACT_WINDOW": "200000",
  "CLAUDE_AUTOCOMPACT_PCT_OVERRIDE": "85"
}
```

这会在约 170K tokens 提前压缩，不需要回滚模型别名或凭据。若模型请求本身失败，
将 Agent/ClientApp 默认 ModelConfig 切回原配置；不要删除原配置或覆盖其凭据，以便
保留可恢复路径。
