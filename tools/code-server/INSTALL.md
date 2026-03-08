# Code Server 安装说明

## 快速安装 (macOS)

### 方式1: 使用 Homebrew（推荐）

```bash
# 安装
brew install code-server

# 启动
code-server --bind-addr 127.0.0.1:8443 --auth password --password foggy123

# 或使用提供的脚本
./start-code-server.sh
```

### 方式2: 使用提供的安装脚本

```bash
# 运行安装脚本
bash install-macos.sh

# 启动
~/.local/lib/code-server/start.sh
```

### 方式3: 手动下载

1. 访问 [Code Server Releases](https://github.com/coder/code-server/releases)
2. 下载适合你系统的版本
3. 解压到 `~/.local/lib/code-server/`
4. 使用启动脚本运行

## 目录结构

```
tools/code-server/
├── README.md                # 详细文档
├── INSTALL.md              # 本文件（快速安装指南）
├── .env                    # 环境变量配置
├── config.yaml             # Code Server 配置
├── install-macos.sh        # macOS 安装脚本
├── install-linux.sh        # Linux 安装脚本
├── start-code-server.sh    # 启动脚本
├── stop-code-server.sh     # 停止脚本
├── start.ps1              # Windows PowerShell 启动脚本
└── stop.ps1               # Windows PowerShell 停止脚本
```

## 访问信息

- **地址**: http://127.0.0.1:8443
- **密码**: foggy123

## 常见问题

### 1. Homebrew 下载慢

使用国内镜像：
```bash
export HOMEBREW_BOTTLE_DOMAIN=https://mirrors.aliyun.com/homebrew/homebrew-bottles
brew install code-server
```

### 2. 端口被占用

修改端口：
```bash
CODE_SERVER_PORT=8444 ./start-code-server.sh
```

### 3. 无法访问

检查防火墙和网络设置，确保端口未被阻塞。

## 下一步

安装完成后，查看 [README.md](README.md) 了解详细使用方法和高级配置。