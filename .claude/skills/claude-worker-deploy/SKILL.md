---
name: claude-worker-deploy
description: Claude Agent Worker 部署运维指导（打包发版、OBS 分发、远程安装、升级）。当用户需要打包 Worker、上传到 OBS、在目标机器安装/升级 Worker、排查部署问题时使用。触发词：/cw-deploy, /worker-deploy, 提及"部署worker"、"安装worker"、"发版"、"worker升级"、"OBS上传"。
---

# Claude Agent Worker 部署运维指导

为 `tools/claude-agent-worker` 提供跨平台打包、华为云 OBS 分发、远程一键安装和自动升级的完整运维指导。

## 使用场景

当用户需要以下操作时激活：
- 打包 Worker 发行版（Windows/Linux/macOS）
- 上传到华为云 OBS 并生成下载链接
- 在新机器上一键安装 Worker
- 执行 Worker 升级
- 配置 OBS 发版环境
- 排查安装/升级/编码问题

## 架构概览

```
开发者机器 (Windows)
  │  dist/package.ps1 -OS all -Upload
  │    1. 读取版本号 ← src/agent_worker/__init__.py
  │    2. 构建 3 个安装包 → dist/output/
  │    3. 调用 upload.ps1 → 华为云 OBS
  ▼
华为云 OBS Bucket
  ├── latest.json           ← 版本发现清单
  ├── install.sh            ← Linux/Mac 引导脚本
  ├── install.ps1           ← Windows 引导脚本
  └── 0.1.0/
      ├── claude-worker-0.1.0-linux.tar.gz
      ├── claude-worker-0.1.0-macos.tar.gz
      └── claude-worker-0.1.0-windows.zip
  ▼
目标机器
  │  curl -sSL <OBS_URL>/install.sh | bash   (Linux/Mac)
  │  irm <OBS_URL>/install.ps1 | iex         (Windows)
  │    1. 下载 latest.json → 获取最新版本号和下载路径
  │    2. 下载对应 OS 的安装包 → /tmp
  │    3. 解压 → 运行 install.sh/install.ps1
  │    4. 创建 venv、安装依赖、写入 CLAUDE_WORKER_URL
  ▼
~/.claude-worker/
  ├── bin/claude-worker      ← CLI 入口
  ├── src/agent_worker/      ← Python 源码
  ├── .venv/                 ← Python 虚拟环境
  ├── .env                   ← 运行配置 + CLAUDE_WORKER_URL
  ├── VERSION                ← 版本文件
  └── logs/                  ← 运行日志
```

## 关键文件

### 打包与上传

| 文件 | 说明 |
|------|------|
| `dist/package.ps1` | PowerShell 打包脚本，`-OS all` 全平台，`-Upload` 打包后自动上传 |
| `dist/package.sh` | Bash 打包脚本，`all` 全平台，`--upload` 打包后自动上传 |
| `dist/upload.ps1` | OBS 上传脚本（PowerShell），上传安装包 + latest.json + 引导脚本 |
| `dist/upload.sh` | OBS 上传脚本（Bash） |
| `dist/remote-install.sh` | 远程引导安装脚本模板（Linux/Mac），upload 时注入 BASE_URL |
| `dist/remote-install.ps1` | 远程引导安装脚本模板（Windows），upload 时注入 BASE_URL |

### 安装与 CLI

| 文件 | 说明 |
|------|------|
| `dist/install.sh` | 本地安装脚本（Linux/Mac），创�� venv、安装依赖、配置 PATH |
| `dist/install.ps1` | 本地安装脚本（Windows），同上 + 生成 .cmd shim |
| `dist/bin/claude-worker` | Bash CLI 入口（start/stop/status/version/logs/upgrade/help）|
| `dist/bin/claude-worker.ps1` | PowerShell CLI 入口 |

### 版本源

```
src/agent_worker/__init__.py   →  __version__ = "X.Y.Z"  （唯一版本源）
```

打包时从此文件读取版本号，写入 `VERSION` 文件随安装包分发。

## 操作手册

### 1. 首次配置 OBS 发版环境（开发者机器，仅需一次）

**a. 安装 obsutil：**
```powershell
# 下载: https://support.huaweicloud.com/utiltg-obs/obs_11_0003.html
# 配置 AK/SK:
obsutil config -i=你的AK -k=你的SK -e=obs.cn-north-4.myhuaweicloud.com
```

**b. 配置 `.env`（worker 根目录）：**
```env
# 在 tools/claude-agent-worker/.env 中添加:
RELEASE_OBS_BUCKET=obs://你的桶名/claude-worker
RELEASE_BASE_URL=https://你的桶名.obs.cn-north-4.myhuaweicloud.com/claude-worker
```

### 2. 发版（打包 + 上传）

```powershell
cd tools/claude-agent-worker

# 一键：打包全平台 + 上传 OBS
powershell -ExecutionPolicy Bypass -File dist/package.ps1 -OS all -Upload

# 或分步：
powershell -ExecutionPolicy Bypass -File dist/package.ps1 -OS all     # 仅打包
powershell -ExecutionPolicy Bypass -File dist/upload.ps1               # 仅上传
```

**Bash 环境：**
```bash
bash dist/package.sh all --upload
# 或分步：
bash dist/package.sh all
bash dist/upload.sh
```

**升级版本号：**
```python
# 编辑 src/agent_worker/__init__.py
__version__ = "0.2.0"    # 修改此处
```
然后重新执行发版命令。

### 3. 目标机器安装

**Linux/Mac：**
```bash
curl -sSL https://你的桶名.obs.cn-north-4.myhuaweicloud.com/claude-worker/install.sh | bash
```

**Windows：**
```powershell
irm https://你的桶名.obs.cn-north-4.myhuaweicloud.com/claude-worker/install.ps1 | iex
```

安装完成后需要：
1. 添加 PATH（安装脚本会提示具体命令）
2. 编辑 `~/.claude-worker/.env` 配置 token、端口、允许目录等
3. `claude-worker start` 启动

### 4. 升级已安装的 Worker

```bash
claude-worker upgrade
```

自动流程：
1. 从 `.env` 读取 `CLAUDE_WORKER_URL`
2. 请求 `<URL>/latest.json` 获取最新版本
3. 对比本地 `VERSION`，有新版则下载对应 OS 安装包
4. 解压后运行 `install.sh --upgrade`（保留 `.env`，重建 venv）

**手动升级（离线）：**
```bash
claude-worker upgrade /path/to/claude-worker-X.Y.Z-linux.tar.gz
```

### 5. CLI 命令参考

```bash
claude-worker start      # 启动 Worker 服务
claude-worker stop       # 停止 Worker 服务
claude-worker status     # 查看状态（版本、端口、PID、健康检查）
claude-worker version    # 显示版本号
claude-worker logs       # 实时查看日志
claude-worker upgrade    # 自动升级（需配置 CLAUDE_WORKER_URL）
claude-worker help       # 帮助信息
```

## OBS Bucket 结构

```
obs://桶名/claude-worker/
  ├── latest.json                              ← 版本发现清单
  ├── install.sh                               ← 远程引导脚本 (curl | bash)
  ├── install.ps1                              ← 远程引导脚本 (irm | iex)
  ├── 0.1.0/
  │   ├── claude-worker-0.1.0-linux.tar.gz
  │   ├── claude-worker-0.1.0-macos.tar.gz
  │   └── claude-worker-0.1.0-windows.zip
  └── 0.2.0/
      ├── claude-worker-0.2.0-linux.tar.gz
      └── ...
```

**latest.json 格式：**
```json
{
  "version": "0.1.0",
  "released": "2026-03-10",
  "files": {
    "linux": "0.1.0/claude-worker-0.1.0-linux.tar.gz",
    "macos": "0.1.0/claude-worker-0.1.0-macos.tar.gz",
    "windows": "0.1.0/claude-worker-0.1.0-windows.zip"
  }
}
```

## 升级优先级

CLI `upgrade` 命令按以下优先级检测升级源：

1. **本地安装包**（命令行参数传入路径）
2. **CLAUDE_WORKER_URL**（OBS/HTTP，从 `.env` 或环境变量读取）
3. **CLAUDE_WORKER_REPO**（GitHub Releases，需配合 GITHUB_TOKEN）

## 配置汇总

### 开发者机器 `.env`（打包上传用）

```env
# OBS 上传目标
RELEASE_OBS_BUCKET=obs://桶名/claude-worker
# 公网下载地址
RELEASE_BASE_URL=https://桶名.obs.cn-north-4.myhuaweicloud.com/claude-worker
```

### 目标机器 `.env`（运行 + 升级用）

```env
# Worker 运行配置
AGENT_WORKER_PORT=3031
AGENT_WORKER_WORKER_NAME=dev-machine-01
AGENT_WORKER_WORKER_TOKEN=your-secret-token
AGENT_WORKER_ALLOWED_CWDS=["/home/user/projects"]

# 自动升级 URL（远程安装时自动写入）
CLAUDE_WORKER_URL=https://桶名.obs.cn-north-4.myhuaweicloud.com/claude-worker
```

## 常见问题

### 安装时 bash 脚本报错 `\r` 或 BOM

**原因**：打包脚本未正确转换行尾。
**解决**：`package.ps1` 的 `ConvertTo-UnixLineEndings` 函数会自动处理。如果仍有问题，手动清理：
```bash
sed -i 's/\r$//' install.sh
# 或去 BOM:
sed -i '1s/^\xEF\xBB\xBF//' install.sh
```

### 安装时 python3 venv 创建失败

**原因**：缺少 `python3.x-venv` 包（Ubuntu/Debian）。
**解决**：
```bash
sudo apt install python3.10-venv   # 或对应版本
rm -rf ~/.claude-worker/.venv      # 清理残留
# 重新安装
curl -sSL <OBS_URL>/install.sh | bash
```

### obsutil 找不到

**原因**：PowerShell 的 `Get-Command` 在某些环境下找不到 `C:\Windows\obsutil.exe`。
**解决**：`upload.ps1` 已内置回退路径查找（`C:\Windows\obsutil.exe`、`$HOME\obsutil\obsutil.exe`）。

### tar.gz 打包失败（Windows）

**原因**：Git 的 `/usr/bin/tar` 不支持 Windows 盘符路径。
**解决**：`package.ps1` 已优先使用 `C:\Windows\System32\tar.exe`。

### upgrade 提示 "No source configured"

**原因**：`.env` 中没有 `CLAUDE_WORKER_URL`。
**解决**：
```bash
echo 'CLAUDE_WORKER_URL=https://桶名.obs.cn-north-4.myhuaweicloud.com/claude-worker' >> ~/.claude-worker/.env
```

## 注意事项

1. **版本号单一源**：只在 `src/agent_worker/__init__.py` 中修改 `__version__`，其他地方（VERSION 文件、latest.json）由脚本自动生成
2. **`.env` 不进 git**：`.env` 在 `.gitignore` 中，只有 `.env.example` 会提交
3. **跨平台行尾**：打包 linux/macos 时，所有 `.sh`、`.py`、CLI wrapper 会自动转 LF
4. **升级保留配置**：`install.sh --upgrade` 会备份并恢复 `.env`，不会丢失用户配置
5. **obsutil AK/SK 安全**：存储在 `~/.obsutilconfig`，不进代码仓库
