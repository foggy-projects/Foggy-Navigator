# OpenHands Demo Workspace

> OpenHands Docker 部署和验证环境

## 📁 文件说明

- `.env` - 环境变量配置文件（包含 API Key，已添加到 .gitignore）
- `.gitignore` - Git 忽略文件配置
- `docker-compose.yml` - Docker Compose 配置文件
- `demo-workspace/` - OpenHands 工作目录（自动创建）

## 🚀 快速开始

### 1. 启动 OpenHands

```bash
# 在当前目录（addons/openhands）执行
docker-compose up -d
```

### 2. 查看日志

```bash
# 实时查看日志
docker-compose logs -f

# 查看最近 100 行
docker-compose logs --tail 100
```

### 3. 访问 Web 端

在浏览器中打开：

```
http://localhost:3000
```

### 4. 停止服务

```bash
docker-compose down
```

## ⚙️ 配置说明

### .env 文件

```env
OPENAI_API_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
OPENAI_API_KEY=sk-40590e5709aa4a779c93c89c5c8c70d4
MODEL=glm-4
MAX_ITERATIONS=10
SECURITY_ANALYZER=false
```

**参数说明：**
- `OPENAI_API_BASE_URL`: API 基础 URL（阿里云 DashScope）
- `OPENAI_API_KEY`: API 密钥（已配置）
- `MODEL`: 使用的模型（glm-4）
- `MAX_ITERATIONS`: 最大迭代次数
- `SECURITY_ANALYZER`: 安全分析器开关

### docker-compose.yml

```yaml
version: '3.8'

services:
  openhands:
    image: docker.all-hands.dev/all-hands-ai/runtime:latest
    container_name: openhands
    ports:
      - "3000:3000"
    volumes:
      - ./demo-workspace:/workspace
    environment:
      - OPENAI_API_BASE_URL=${OPENAI_API_BASE_URL}
      - OPENAI_API_KEY=${OPENAI_API_KEY}
      - MODEL=${MODEL}
      - MAX_ITERATIONS=${MAX_ITERATIONS}
      - SECURITY_ANALYZER=${SECURITY_ANALYZER}
    restart: unless-stopped
    networks:
      - openhands-network

networks:
  openhands-network:
    driver: bridge
```

**参数说明：**
- `image`: OpenHands Docker 镜像
- `container_name`: 容器名称
- `ports`: 端口映射（主机 3000 -> 容器 3000）
- `volumes`: 工作目录挂载（./demo-workspace -> /workspace）
- `environment`: 环境变量（从 .env 文件读取）
- `restart`: 重启策略（除非手动停止）
- `networks`: 网络配置

## 📝 验证步骤

### Phase 1: 基础验证

1. **启动服务**
   ```bash
   docker-compose up -d
   ```

2. **查看日志**
   ```bash
   docker-compose logs -f
   ```

3. **访问 Web 端**
   ```
   http://localhost:3000
   ```

4. **测试简单任务**
   - 在 Web 端输入：`打印 Hello World`
   - 观察执行过程
   - 查看日志输出

### Phase 2: 语义层验证

1. **在 demo-workspace 中创建测试文件**
   ```bash
   cd demo-workspace
   echo 'function hello() { return "Hello World"; }' > test.js
   ```

2. **在 Web 端执行任务**
   - 输入：`在 test.js 中添加一个 goodbye 函数`
   - 观察执行过程
   - 查看文件变化

3. **验证结果**
   ```bash
   cat test.js
   ```

### Phase 3: Git 集成验证

1. **初始化 Git 仓库**
   ```bash
   cd demo-workspace
   git init
   git config user.name "Your Name"
   git config user.email "your.email@example.com"
   ```

2. **在 Web 端执行 Git 任务**
   - 输入：`初始化 Git 仓库，添加所有文件，提交代码`
   - 观察执行过程
   - 查看提交历史

## 🔧 故障排查

### 问题 1: 容器无法启动

```bash
# 查看容器日志
docker-compose logs

# 检查端口是否被占用
netstat -ano | findstr :3000
```

### 问题 2: API 密钥错误

```bash
# 检查 .env 文件
cat .env

# 重新启动服务
docker-compose down
docker-compose up -d
```

### 问题 3: 无法访问 Web 端

```bash
# 检查容器状态
docker ps

# 检查端口映射
docker port openhands
```

## 📊 日志监控

### 实时查看日志

```bash
docker-compose logs -f openhands
```

### 查看特定时间的日志

```bash
docker-compose logs --since 2024-01-21T10:00:00
```

### 查看包含特定关键词的日志

```bash
docker-compose logs | grep "ERROR"
```

## 🔗 相关链接

- [OpenHands GitHub](https://github.com/All-Hands-AI/OpenHands)
- [OpenHands 官方文档](https://docs.openhands.ai/)
- [阿里云 DashScope](https://dashscope.aliyuncs.com/)
- [GLM-4 模型文档](https://help.aliyun.com/zh/dashscope/developer-reference/quick-start)

---

**文档版本：** 1.0.0
**创建日期：** 2026-01-21
**作者：** Foggy Navigator Team
