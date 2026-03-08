# Code Server (VS Code in Browser)

在浏览器中运行的 VS Code，用于远程开发和代码编辑。

## 安装

### macOS 安装

#### 方法 1: Homebrew（推荐）

```bash
brew install code-server
```

#### 方法 2: 使用安装脚本

```bash
# 安装到默认位置
bash install-macos.sh

# 自定义端口和路径
bash install-macos.sh --port 8443 --install-dir ~/code-server
```

### Linux 安装

```bash
# 生产环境（端口 18443）
bash install-linux.sh

# 自定义配置
bash install-linux.sh --port 8443 --password yourpassword
```

## 使用

### 快速启动

```bash
# 启动 Code Server
./start-code-server.sh

# 启动并打开特定项目
./start-code-server.sh /path/to/project

# 停止
./stop-code-server.sh
```

### 访问

- **URL**: http://127.0.0.1:8443
- **密码**: foggy123（默认）

### 配置文件

配置文件位置：`~/.local/share/code-server/config.yaml`

```yaml
bind-addr: 127.0.0.1:8443
auth: password
password: foggy123
cert: false
```

## 集成到系统

### macOS - LaunchAgent（开机自启）

```bash
# 安装
cp ~/.local/share/code-server/com.coder.code-server.plist ~/Library/LaunchAgents/
launchctl load ~/Library/LaunchAgents/com.coder.code-server.plist

# 卸载
launchctl unload ~/Library/LaunchAgents/com.coder.code-server.plist
rm ~/Library/LaunchAgents/com.coder.code-server.plist
```

### Linux - Systemd（用户级服务）

```bash
# 安装
mkdir -p ~/.config/systemd/user
cp ~/.local/share/code-server/code-server.service ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now code-server

# 管理
systemctl --user status code-server
systemctl --user stop code-server
systemctl --user restart code-server
```

## 与 Claude Worker Agent 集成

Code Server 可以作为远程开发环境，供 Claude Worker Agent 使用：

1. **启动 Code Server**
   ```bash
   ./start-code-server.sh ~/foggy-projects/
   ```

2. **配置 Worker**
   在 Worker 设置中配置：
   - Code Server URL: `http://localhost:8443`
   - Password: `foggy123`

3. **通过 frp 暴露（可选）**
   如果需要远程访问，在 `frpc.toml` 中添加：
   ```toml
   [[proxies]]
   name = "code-server"
   type = "tcp"
   localIP = "127.0.0.1"
   localPort = 8443
   remotePort = 18443
   ```

## Nginx 反向代理

如果需要通过 Nginx 访问，添加以下配置：

```nginx
location /code/ {
    proxy_pass http://127.0.0.1:8443/;
    proxy_set_header Host $host;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
}
```

## 故障排查

### 端口被占用

```bash
# 查看占用端口的进程
lsof -i :8443

# 更改端口
CODE_SERVER_PORT=8444 ./start-code-server.sh
```

### 无法访问

1. 检查防火墙设置
2. 确认绑定地址（默认 127.0.0.1 仅本地访问）
3. 查看日志：`~/.local/share/code-server/code-server.log`

### 安装失败

如果自动安装失败，可以手动下载：

1. 访问 [Releases](https://github.com/coder/code-server/releases)
2. 下载对应平台的包
3. 解压到 `~/.local/lib/code-server/`
4. 使用启动脚本运行

## 安全建议

1. **生产环境**：始终使用强密码
2. **公网访问**：建议使用 HTTPS + 反向代理
3. **认证方式**：可以配置 OAuth 等更安全的认证方式
4. **网络隔离**：限制访问 IP 范围

## 扩展和插件

Code Server 支持大部分 VS Code 扩展：

1. 通过内置扩展市场安装
2. 手动安装 `.vsix` 文件
3. 同步设置（Settings Sync）

## 相关文档

- [Code Server 官方文档](https://coder.com/docs/code-server)
- [VS Code 文档](https://code.visualstudio.com/docs)
- [Claude Worker Agent 集成](../../docs/claude-worker-integration.md)