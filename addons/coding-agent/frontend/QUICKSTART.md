# 快速开始

## 前置条件

1. Node.js 18+ 已安装
2. 后端服务已启动（http://localhost:8112）

## 启动步骤

### 方式一：使用启动脚本（Windows）

双击运行 `start-dev.bat`

### 方式二：命令行

```bash
# 进入项目目录
cd addons/coding-agent/frontend

# 首次运行需要安装依赖
npm install

# 启动开发服务器
npm run dev
```

## 访问应用

打开浏览器访问：http://localhost:5173

## 可用页面

- **系统监控**: http://localhost:5173/#/dashboard
- **会话管理**: http://localhost:5173/#/conversations
- **容器管理**: http://localhost:5173/#/containers
- **事件日志**: http://localhost:5173/#/events

## 构建生产版本

```bash
npm run build
```

构建产物将输出到 `../src/main/resources/static`，可直接被 Spring Boot 托管。

## 故障排查

### 端口冲突

如果 5173 端口被占用，修改 `vite.config.ts` 中的 `server.port`。

### API 请求失败

1. 检查后端服务是否运行在 http://localhost:8112
2. 检查 `vite.config.ts` 中的 proxy 配置
3. 打开浏览器控制台查看具体错误

### 类型错误

运行类型检查：
```bash
npm run type-check
```
