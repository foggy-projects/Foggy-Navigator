---
name: claude-proxy-deploy
description: Claude Code Proxy 打包发版指导（跨平台打包、OBS 上传、版本管理）。仅限开发者使用。当用户需要打包 Proxy、上传到 OBS、管理版本号时使用。触发词：/proxy-deploy, 提及"发版proxy"、"打包proxy"、"OBS上传proxy"、"package proxy"。
---

# Claude Code Proxy 打包发版指导（开发者专用）

为 `tools/claude-code-proxy` 提供跨平台打包和华为云 OBS 发版指导。

> **目标机器安装/升级/CLI 使用** → 参见 `claude-proxy-install` skill（随安装包分发）

## 使用场景

当用户需要以下操作时激活：
- 打包 Proxy 发行版（Windows/Linux/macOS）
- 上传到华为云 OBS 并生成下载链接
- 配置 OBS 发版环境
- 修改版本号并发布新版
- 排查打包/上传问题

## 架构概览

```
开发者机器 (Windows)
  │  dist/package.ps1 -OS all -Upload
  │    1. 读取版本号 ← src/__init__.py
  │    2. 构建 3 个安装包 → dist/output/
  │    3. 调用 upload.ps1 → 华为云 OBS
  ▼
华为云 OBS Bucket
  ├── latest.json           ← 版本发现清单
  ├── install.sh            ← Linux/Mac 引导脚本
  ├── install.ps1           ← Windows 引导脚本
  └── 1.0.0/
      ├── claude-code-proxy-1.0.0-linux.tar.gz
      ├── claude-code-proxy-1.0.0-macos.tar.gz
      └── claude-code-proxy-1.0.0-windows.zip
```

## 关键文件

| 文件 | 说明 |
|------|------|
| `dist/package.ps1` | PowerShell 打包脚本，`-OS all` 全平台，`-Upload` 打包后自动上传 |
| `dist/package.sh` | Bash 打包脚本，`all` 全平台，`--upload` 打包后自动上传 |
| `dist/upload.ps1` | OBS 上传脚本（PowerShell），上传安装包 + latest.json + 引导脚本 |
| `dist/upload.sh` | OBS 上传脚本（Bash） |
| `dist/remote-install.sh` | 远程引导安装脚本模板（Linux/Mac），upload 时注入 BASE_URL |
| `dist/remote-install.ps1` | 远程引导安装脚本模板（Windows），upload 时注入 BASE_URL |

### 版本源

```
src/__init__.py   →  __version__ = "X.Y.Z"  （唯一版本源）
```

打包时从此文件读取版本号，写入 `VERSION` 文件随安装包分发。

## 操作手册

### 1. 首次配置 OBS 发版环境（仅需一次）

**a. 安装 obsutil：**
```powershell
# 下载: https://support.huaweicloud.com/utiltg-obs/obs_11_0003.html
# 配置 AK/SK:
obsutil config -i=你的AK -k=你的SK -e=obs.cn-north-4.myhuaweicloud.com
```

**b. 配置 `.env`（proxy 根目录）：**
```env
# 在 tools/claude-code-proxy/.env 中添加:
RELEASE_OBS_BUCKET=obs://你的桶名/claude-code-proxy
RELEASE_BASE_URL=https://你的桶名.obs.cn-north-4.myhuaweicloud.com/claude-code-proxy
```

### 2. 发版（打包 + 上传）

```powershell
cd tools/claude-code-proxy

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

### 3. 升级版本号

```python
# 编辑 src/__init__.py
__version__ = "1.1.0"    # 修改此处
```
然后重新执行发版命令。

## OBS Bucket 结构

```
obs://桶名/claude-code-proxy/
  ├── latest.json                                    ← 版本发现清单
  ├── install.sh                                     ← 远程引导脚本 (curl | bash)
  ├── install.ps1                                    ← 远程引导脚本 (irm | iex)
  ├── 1.0.0/
  │   ├── claude-code-proxy-1.0.0-linux.tar.gz
  │   ├── claude-code-proxy-1.0.0-macos.tar.gz
  │   └── claude-code-proxy-1.0.0-windows.zip
  └── 1.1.0/
      ├── claude-code-proxy-1.1.0-linux.tar.gz
      └── ...
```

**latest.json 格式：**
```json
{
  "version": "1.0.0",
  "released": "2026-03-12",
  "files": {
    "linux": "1.0.0/claude-code-proxy-1.0.0-linux.tar.gz",
    "macos": "1.0.0/claude-code-proxy-1.0.0-macos.tar.gz",
    "windows": "1.0.0/claude-code-proxy-1.0.0-windows.zip"
  }
}
```

## 开发者 `.env` 配置

```env
# OBS 上传目标
RELEASE_OBS_BUCKET=obs://桶名/claude-code-proxy
# 公网下载地址
RELEASE_BASE_URL=https://桶名.obs.cn-north-4.myhuaweicloud.com/claude-code-proxy
```

## 常见问题（打包/上传）

### obsutil 找不到

**原因**：PowerShell 的 `Get-Command` 在某些环境下找不到 `C:\Windows\obsutil.exe`。
**解决**：`upload.ps1` 已内置回退路径查找（`C:\Windows\obsutil.exe`、`$HOME\obsutil\obsutil.exe`）。

### tar.gz 打包失败（Windows）

**原因**：Git 的 `/usr/bin/tar` 不支持 Windows 盘符路径。
**解决**：`package.ps1` 已优先使用 `C:\Windows\System32\tar.exe`。

## 注意事项

1. **版本号单一源**：只在 `src/__init__.py` 中修改 `__version__`，其他地方（VERSION 文件、latest.json）由脚本自动生成
2. **`.env` 不进 git**：`.env` 在 `.gitignore` 中，只有 `.env.example` 会提交
3. **跨平台行尾**：打包 linux/macos 时，所有 `.sh`、`.py`、CLI wrapper 会自动转 LF（`ConvertTo-UnixLineEndings`）
4. **obsutil AK/SK 安全**：存储在 `~/.obsutilconfig`，不进代码仓库
5. **安装 skill 随包分发**：`dist/docs/SKILL.md` 会被打包进 archive，安装后位于 `~/.claude-code-proxy/docs/SKILL.md`
