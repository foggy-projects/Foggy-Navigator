# OpenHands Docker 安装和 Web 端验证指南

> 使用 Docker 安装 OpenHands 并通过 Web 端进行验证

## 📋 前置要求

- ✅ Docker 已安装（推荐 Docker Desktop for Windows）
- ✅ 至少 8GB 可用内存
- ✅ 至少 20GB 可用磁盘空间
- ✅ 稳定的网络连接（用于下载镜像和访问 LLM API）

## 🚀 安装步骤

### 方法 1: 使用 Docker Run（推荐）

#### 1.1 拉取镜像

```bash
# 拉取最新版本的 OpenHands 镜像
docker pull docker.all-hands.dev/all-hands-ai/runtime:latest

# 或者指定版本
docker pull docker.all-hands.dev/all-hands-ai/runtime:0.17
```

#### 1.2 创建工作目录

```bash
# 在 Windows 上
mkdir D:\openhands-workspace
cd D:\openhands-workspace

# 在 Linux/Mac 上
mkdir -p ~/openhands-workspace
cd ~/openhands-workspace
```

#### 1.3 运行容器

```bash
docker run -d \
  --name openhands \
  -p 3000:3000 \
  -v D:\openhands-workspace:/workspace \
  -e OPENAI_API_KEY=your-openai-api-key \
  -e MODEL=gpt-4 \
  docker.all-hands.dev/all-hands-ai/runtime:latest
```

**参数说明：**
- `-d`: 后台运行
- `--name openhands`: 容器名称
- `-p 3000:3000`: 端口映射，将容器的 3000 端口映射到主机的 3000 端口
- `-v D:\openhands-workspace:/workspace`: 挂载工作目录
- `-e OPENAI_API_KEY`: OpenAI API 密钥
- `-e MODEL`: 使用的模型（gpt-4, gpt-3.5-turbo, claude-3 等）

#### 1.4 查看容器状态

```bash
# 查看运行中的容器
docker ps

# 查看容器日志
docker logs openhands

# 实时查看日志
docker logs -f openhands
```

### 方法 2: 使用 Docker Compose（推荐）

#### 2.1 创建 docker-compose.yml 文件

在 `D:\openhands-workspace` 目录下创建 `docker-compose.yml` 文件：

```yaml
version: '3.8'

services:
  openhands:
    image: docker.all-hands.dev/all-hands-ai/runtime:latest
    container_name: openhands
    ports:
      - "3000:3000"
    volumes:
      - ./workspace:/workspace
    environment:
      - OPENAI_API_KEY=${OPENAI_API_KEY}
      - MODEL=gpt-4
      - MAX_ITERATIONS=10
      - SECURITY_ANALYZER=false
    restart: unless-stopped
    networks:
      - openhands-network

networks:
  openhands-network:
    driver: bridge
```

#### 2.2 创建 .env 文件

在同一目录下创建 `.env` 文件：

```env
OPENAI_API_KEY=your-openai-api-key-here
```

#### 2.3 启动服务

```bash
# 启动服务
docker-compose up -d

# 查看日志
docker-compose logs -f

# 停止服务
docker-compose down

# 重启服务
docker-compose restart
```

## 🌐 访问 Web 端

### 1. 打开浏览器

在浏览器中访问：

```
http://localhost:3000
```

### 2. 首次访问

首次访问时，您会看到 OpenHands 的 Web 界面：

```
┌─────────────────────────────────────────────────────────────────┐
│                    OpenHands                              │
│  ┌─────────────────────────────────────────────────────┐    │
│  │  输入您的任务...                             │    │
│  │  [例如：修改 semantic-layer.js 文件]            │    │
│  │                                               │    │
│  │  [执行任务]                                    │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                         │
│  ┌─────────────────────────────────────────────────────┐    │
│  │  工作空间                                       │    │
│  │  📁 semantic-layer.js                          │    │
│  │  📁 package.json                              │    │
│  │  📁 test.js                                   │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                         │
│  ┌─────────────────────────────────────────────────────┐    │
│  │  终端输出                                       │    │
│  │  > 正在执行任务...                             │    │
│  │  > 分析代码结构...                              │    │
│  │  > 修改代码...                                  │    │
│  │  > 运行测试...                                  │    │
│  │  > 任务完成！                                   │    │
│  └─────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────┘
```

### 3. 界面功能

#### 3.1 任务输入区

在顶部的输入框中输入您的任务，例如：

```
修改 semantic-layer.js 文件，在 Customer 实体中添加一个 address 字段
```

#### 3.2 工作空间

左侧显示工作空间中的文件，您可以：

- 📂 浏览文件
- 📝 编辑文件
- 📤 上传文件
- 📥 下载文件

#### 3.3 终端输出

右侧显示终端输出，包括：

- 🔧 执行的命令
- 📊 任务进度
- ✅ 执行结果
- ❌ 错误信息

## 📝 验证步骤

### Phase 1: 基础功能验证

#### 1.1 创建测试项目

**步骤：**

1. 在工作空间目录中创建测试文件：

```bash
# 在 D:\openhands-workspace\workspace 目录下
echo 'function hello() { return "Hello World"; }' > test.js
```

2. 在 Web 端查看文件

3. 在任务输入框中输入：

```
在 test.js 中添加一个 goodbye 函数，返回 "Goodbye World"
```

4. 点击"执行任务"

#### 1.2 观察执行过程

**观察要点：**

1. **终端输出**
   - 查看任务分解步骤
   - 查看代码修改过程
   - 查看测试执行结果

2. **文件变化**
   - 刷新工作空间
   - 查看 test.js 文件是否被修改
   - 检查代码是否正确

3. **Docker 日志**

```bash
# 在另一个终端窗口中
docker logs -f openhands
```

**预期输出：**
```
[INFO] Starting OpenHands...
[INFO] Task received: 在 test.js 中添加一个 goodbye 函数
[INFO] Analyzing task...
[INFO] Planning execution steps...
[INFO] Step 1: Read file test.js
[INFO] Step 2: Add goodbye function
[INFO] Step 3: Write file test.js
[INFO] Step 4: Verify changes
[INFO] Task completed successfully
```

#### 1.3 验证结果

**检查清单：**

- [ ] 任务成功执行
- [ ] 代码修改正确
- [ ] 文件已更新
- [ ] Docker 日志无错误
- [ ] Web 端显示成功

### Phase 2: 语义层验证

#### 2.1 创建语义层测试项目

**步骤：**

1. 在工作空间目录中创建语义层文件：

```bash
# 在 D:\openhands-workspace\workspace 目录下
cd D:\openhands-workspace\workspace

# 创建 semantic-layer.js
cat > semantic-layer.js << 'EOF'
const SemanticLayer = {
  version: "1.0.0",
  
  entities: {
    Customer: {
      description: "客户信息",
      table: "customers",
      fields: {
        id: {
          type: "integer",
          description: "客户ID",
          primaryKey: true
        },
        name: {
          type: "string",
          description: "客户姓名",
          searchable: true
        },
        email: {
          type: "string",
          description: "客户邮箱"
        }
      }
    }
  },
  
  functions: {
    getCustomerById: function(id) {
      return this.entities.Customer.fields;
    }
  }
};

module.exports = SemanticLayer;
EOF

# 创建 test.js
cat > test.js << 'EOF'
const SemanticLayer = require('./semantic-layer');

console.log('Semantic Layer Version:', SemanticLayer.version);
console.log('Customer Fields:', SemanticLayer.entities.Customer.fields);
console.log('getCustomerById:', SemanticLayer.functions.getCustomerById(1));
EOF

# 创建 package.json
cat > package.json << 'EOF'
{
  "name": "semantic-layer-test",
  "version": "1.0.0",
  "scripts": {
    "test": "node test.js"
  }
}
EOF
```

2. 在 Web 端刷新工作空间，查看创建的文件

#### 2.2 修改实体定义

**任务 1: 添加新字段**

在任务输入框中输入：

```
在 Customer 实体中添加一个 phone 字段，类型为 string，描述为'客户电话'，设置为 searchable: true
```

**观察要点：**

1. **终端输出**
   - 查看任务分解
   - 查看代码修改过程
   - 查看验证步骤

2. **文件变化**
   - 刷新工作空间
   - 查看 semantic-layer.js 文件
   - 检查 phone 字段是否正确添加

3. **Docker 日志**

```bash
docker logs -f openhands
```

**预期输出：**
```
[INFO] Task received: 在 Customer 实体中添加一个 phone 字段
[INFO] Analyzing semantic-layer.js...
[INFO] Found Customer entity
[INFO] Adding phone field...
[INFO] Updating file...
[INFO] Verifying changes...
[INFO] Task completed successfully
```

**任务 2: 添加新实体**

在任务输入框中输入：

```
添加一个 Order 实体，包含以下字段：
- id: integer, 主键
- customerId: integer, 外键关联到 Customer.id
- amount: decimal, 订单金额
- status: string, 订单状态，枚举值为 pending, paid, shipped, completed
```

**观察要点：**

1. 查看实体是否正确创建
2. 查看字段定义是否正确
3. 查看外键关系是否正确
4. 查看 Docker 日志是否有错误

#### 2.3 修改业务逻辑

**任务 3: 添加新函数**

在任务输入框中输入：

```
在 semantic-layer.js 中添加一个 getCustomerByEmail 函数，根据邮箱查询客户信息
```

**观察要点：**

1. 查看函数是否正确添加
2. 查看函数逻辑是否合理
3. 查看参数是否正确
4. 查看 Docker 日志

**任务 4: 运行测试**

在任务输入框中输入：

```
运行 npm test 命令，验证 semantic-layer.js 是否正确
```

**观察要点：**

1. 查看测试是否通过
2. 查看输出是否正确
3. 查看是否有错误

#### 2.4 验证结果

**检查清单：**

- [ ] Customer 实体添加了 phone 字段
- [ ] Order 实体正确创建
- [ ] getCustomerByEmail 函数正确添加
- [ ] 测试运行成功
- [ ] 代码风格一致
- [ ] Docker 日志无严重错误

### Phase 3: Git 集成验证

#### 3.1 初始化 Git 仓库

**步骤：**

1. 在 Web 端的终端中执行：

```bash
cd /workspace
git init
git config user.name "Your Name"
git config user.email "your.email@example.com"
```

2. 在任务输入框中输入：

```
初始化 Git 仓库，添加所有文件，提交代码，提交信息为 "Initial commit"
```

**观察要点：**

1. 查看 Git 命令是否正确执行
2. 查看提交是否成功
3. 查看 Docker 日志

#### 3.2 创建分支

**任务：**

在任务输入框中输入：

```
创建一个名为 feature/add-phone-field 的新分支，并切换到该分支
```

**观察要点：**

1. 查看分支是否创建成功
2. 查看是否切换到新分支
3. 查看 Docker 日志

#### 3.3 提交代码

**任务：**

在任务输入框中输入：

```
将当前修改提交到 Git，提交信息为 "Add phone field to Customer entity"
```

**观察要点：**

1. 查看文件是否被添加
2. 查看提交信息是否正确
3. 查看提交是否成功

#### 3.4 验证结果

**检查清单：**

- [ ] Git 仓库初始化成功
- [ ] 分支创建和切换成功
- [ ] 代码提交成功
- [ ] 提交信息正确
- [ ] Docker 日志无错误

## 📊 日志分析

### Docker 日志级别

| 级别 | 说明 | 示例 |
|------|------|------|
| **INFO** | 一般信息 | `[INFO] Task received` |
| **DEBUG** | 调试信息 | `[DEBUG] Analyzing code` |
| **WARN** | 警告信息 | `[WARN] Potential issue found` |
| **ERROR** | 错误信息 | `[ERROR] Failed to execute` |

### 常见日志输出

#### 正常执行

```
[INFO] Starting OpenHands...
[INFO] Listening on port 3000
[INFO] Task received: 修改 semantic-layer.js
[INFO] Analyzing task...
[INFO] Planning execution steps...
[INFO] Step 1: Read file
[INFO] Step 2: Modify code
[INFO] Step 3: Write file
[INFO] Step 4: Verify
[INFO] Task completed successfully
```

#### 执行失败

```
[INFO] Task received: 修改 semantic-layer.js
[INFO] Analyzing task...
[ERROR] File not found: semantic-layer.js
[ERROR] Task failed
```

#### API 错误

```
[ERROR] Failed to connect to LLM API
[ERROR] API key invalid or expired
[ERROR] Please check your OPENAI_API_KEY
```

### 日志监控命令

```bash
# 实时查看日志
docker logs -f openhands

# 查看最近 100 行日志
docker logs --tail 100 openhands

# 查看特定时间的日志
docker logs --since 2024-01-21T10:00:00 openhands

# 查看包含特定关键词的日志
docker logs openhands | grep "ERROR"
```

## 🔧 故障排查

### 问题 1: 容器无法启动

**症状：**
```bash
docker ps
# openhands 容器不在列表中
```

**解决方案：**

```bash
# 查看容器日志
docker logs openhands

# 检查端口是否被占用
netstat -ano | findstr :3000

# 修改端口映射
docker run -d --name openhands -p 3001:3000 ...
```

### 问题 2: 无法访问 Web 端

**症状：**
```
浏览器访问 http://localhost:3000 显示 "无法访问此网站"
```

**解决方案：**

```bash
# 检查容器是否运行
docker ps

# 检查端口映射
docker port openhands

# 检查防火墙设置
# Windows: 控制面板 -> 系统和安全 -> Windows Defender 防火墙
```

### 问题 3: API 密钥错误

**症状：**
```
[ERROR] API key invalid or expired
```

**解决方案：**

```bash
# 停止容器
docker stop openhands

# 删除容器
docker rm openhands

# 重新运行，使用正确的 API 密钥
docker run -d --name openhands -p 3000:3000 -e OPENAI_API_KEY=correct-key ...
```

### 问题 4: 文件权限错误

**症状：**
```
[ERROR] Permission denied: /workspace/test.js
```

**解决方案：**

```bash
# 检查工作目录权限
ls -la D:\openhands-workspace

# 修改权限（Linux/Mac）
chmod -R 755 ~/openhands-workspace

# Windows: 右键文件夹 -> 属性 -> 安全 -> 编辑权限
```

## 📝 验证记录

### 验证检查清单

| 阶段 | 任务 | 状态 | 备注 |
|------|------|------|------|
| Phase 1 | 基础功能验证 | ⬜ | |
| Phase 1 | 创建测试项目 | ⬜ | |
| Phase 1 | 观察执行过程 | ⬜ | |
| Phase 1 | 验证结果 | ⬜ | |
| Phase 2 | 语义层验证 | ⬜ | |
| Phase 2 | 创建语义层测试项目 | ⬜ | |
| Phase 2 | 修改实体定义 | ⬜ | |
| Phase 2 | 修改业务逻辑 | ⬜ | |
| Phase 2 | 验证结果 | ⬜ | |
| Phase 3 | Git 集成验证 | ⬜ | |
| Phase 3 | 初始化 Git 仓库 | ⬜ | |
| Phase 3 | 创建分支 | ⬜ | |
| Phase 3 | 提交代码 | ⬜ | |
| Phase 3 | 验证结果 | ⬜ | |

### 验证结果总结

**总体评价：** ⬜ 通过 / ⬜ 不通过 / ⬜ 部分通过

**优势：**
- ⬜
- ⬜
- ⬜

**劣势：**
- ⬜
- ⬜
- ⬜

**建议：**
- ⬜
- ⬜
- ⬜

**最终决策：** ⬜ 使用 OpenHands / ⬜ 不使用 OpenHands

## 🎯 下一步

验证完成后，根据结果决定：

### 如果验证通过

1. 创建集成方案文档
2. 设计 API 接口
3. 实现系统集成
4. 编写测试用例
5. 部署到生产环境

### 如果验证不通过

1. 分析失败原因
2. 考虑其他方案
3. 重新评估需求
4. 寻找替代工具

## 🔗 相关链接

- [OpenHands GitHub](https://github.com/All-Hands-AI/OpenHands)
- [OpenHands 官方文档](https://docs.openhands.ai/)
- [Docker 官方文档](https://docs.docker.com/)
- [Docker Compose 文档](https://docs.docker.com/compose/)

---

**文档版本：** 1.0.0
**创建日期：** 2026-01-21
**作者：** Foggy Navigator Team
