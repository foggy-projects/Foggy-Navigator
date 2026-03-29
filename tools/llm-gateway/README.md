# LLM Gateway (One API)

基于 [One API](https://github.com/songquanpeng/one-api) 的内网 LLM 代理网关。

将云服务商的 API Key 封装为内部 Token，员工只能通过内网访问，避免真实 Key 泄露。

## 架构

```
云厂商 API Key (隐藏)          内部 Token (分发给员工)
┌──────────────────┐          ┌──────────────────┐
│ OpenAI Key       │──┐       │ Token-张三       │──┐
│ Claude Key       │──┤ ┌───────────┐ │ Token-李四       │──┤  员工终端
│ DeepSeek Key     │──┼→│ LLM       │←┼─Token-王五       │──┤  (Claude Code
│ 通义千问 Key      │──┤ │ Gateway   │ │ Token-团队A      │──┤   IDE, 脚本等)
│ 智谱 Key         │──┘ │ (内网)    │ │ ...              │──┘
└──────────────────┘    └───────────┘ └──────────────────┘
                       192.168.x.x:3000
```

## 快速部署

### 1. 启动服务

```powershell
# Windows
powershell -ExecutionPolicy Bypass -File start.ps1

# Linux / Mac
cp .env.example .env
docker compose up -d
```

### 2. 登录管理台

- 地址：`http://<服务器IP>:3000`
- 默认账号：`root` / `123456`
- **首次登录后立即修改密码**

### 3. 添加渠道（配置真实 API Key）

1. 进入「渠道」页面 → 「添加新的渠道」
2. 选择类型（OpenAI / Anthropic / DeepSeek / 通义千问 等）
3. 填入真实 API Key 和 Base URL
4. 选择该渠道支持的模型
5. 测试连通性 → 保存

**示例渠道配置：**

| 渠道类型 | Base URL | 支持模型 |
|---------|----------|---------|
| OpenAI | `https://api.openai.com` | gpt-4o, gpt-4o-mini |
| Anthropic | `https://api.anthropic.com` | claude-sonnet-4-20250514 |
| DeepSeek | `https://api.deepseek.com` | deepseek-chat, deepseek-coder |
| 通义千问 | `https://dashscope.aliyuncs.com/compatible-mode` | qwen-max, qwen-plus |

### 4. 创建内部 Token（分发给员工）

1. 进入「令牌」页面 → 「添加新的令牌」
2. 设置名称（如：张三-开发用）
3. 设置额度限制（可选）
4. 设置过期时间（可选）
5. 选择可用模型范围
6. 复制生成的 `sk-xxx` Token 发给员工

### 5. 员工使用

#### Claude Code

```bash
# 设置环境变量
export ANTHROPIC_BASE_URL=http://<网关IP>:3000
export ANTHROPIC_API_KEY=sk-xxxxx   # 管理台生成的内部 Token

# 启动 Claude Code
claude
```

#### OpenAI 兼容客户端

```bash
export OPENAI_BASE_URL=http://<网关IP>:3000/v1
export OPENAI_API_KEY=sk-xxxxx

# 任何 OpenAI SDK 兼容的工具都可以直接使用
```

#### Python 代码

```python
from openai import OpenAI

client = OpenAI(
    base_url="http://<网关IP>:3000/v1",
    api_key="sk-xxxxx"  # 内部 Token
)

resp = client.chat.completions.create(
    model="gpt-4o",
    messages=[{"role": "user", "content": "Hello"}]
)
```

## 安全建议

1. **内网限制**：网关只监听内网 IP，不暴露到公网
2. **修改默认密码**：首次登录后立即修改 root 密码
3. **Token 额度**：为每个员工/团队设置合理的额度上限
4. **定期轮换**：定期更换云厂商 API Key 和内部 Token
5. **日志审计**：管理台可查看每个 Token 的调用日志

## 文件结构

```
tools/llm-gateway/
├── docker-compose.yml   # Docker Compose 编排
├── .env.example         # 配置模板
├── .env                 # 运行配置（git-ignored）
├── .gitignore
├── start.ps1            # 启动脚本
├── stop.ps1             # 停止脚本
├── data/                # SQLite 数据（git-ignored）
└── README.md
```

## 常用运维

```bash
# 查看日志
docker logs -f llm-gateway

# 更新到最新版
docker compose pull && docker compose up -d

# 备份数据
cp data/one-api.db data/one-api.db.bak

# 完全重置（清除所有数据）
docker compose down
rm -rf data/
docker compose up -d
```
