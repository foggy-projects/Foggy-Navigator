---
name: foggy-mobile-build
description: Foggy Mobile APK 云端打包与 wgt 热更新发布指导。当用户需要打包 APK、发布 wgt 热更新、配置签名、或排查 HBuilderX CLI 打包问题时使用。触发词：/mobile-build, /apk, /wgt, 提及"打包"、"APK"、"热更新"、"wgt"、"云打包"。
---

# Foggy Mobile 打包与发布指导

APK 云端打包、wgt 热更新、版本管理、签名配置、uni-admin 升级中心发布。

## 打包架构

```
pnpm build:app-android     → dist/build/app/        (uni-app 编译 App 资源)
HBuilderX CLI pack          → DCloud 云端打包         (上传资源 → 生成 APK)
scripts/pack-wgt.js         → dist/*.wgt              (本地压缩 wgt 热更包)
scripts/uni-admin-api.js    → uni-admin 升级中心       (上传文件 + 创建版本记录)
```

**关键发现**：HBuilderX CLI 的 `pack` 命令对 CLI 创建的 uni-app 项目有 bug — 内部调用 `uni.js build` 时不传 `-p app` 参数，导致编译 H5 而非 App，最终报 "编译成功" 但 "项目编译失败"（`zip not exists`）。

**解决方案**：先用 `pnpm build:app-android` 预编译，再让 HBuilderX 只打包编译产物（跳过编译步骤）。

## 一键命令

```powershell
cd packages/foggy-mobile

# APK 整包打包 + 发布到 uni-admin（原生模块变更时用）
powershell -ExecutionPolicy Bypass -File scripts/build-apk.ps1

# wgt 热更新发版 + 发布到 uni-admin（日常迭代用）
powershell -ExecutionPolicy Bypass -File scripts/release.ps1
```

## APK 云端打包流程

### 前置条件

1. **HBuilderX** 已安装（含 `amazon-corretto` 和 `launcher` 插件）
2. **HBuilderX CLI** 路径：`D:\work\HBuilderX\cli.exe`
3. **DCloud 账号**已登录：`cli user info` 验证
4. **签名文件**：`packages/foggy-mobile/keystore/foggy-navigator.keystore`
5. **配置文件**：`packages/foggy-mobile/.env.release`

### `build-apk.ps1` 流程

1. `pnpm build:app-android` → 预编译到 `dist/build/app/`
2. `cli project open --path dist/build/app` → HBuilderX 导入编译产物
3. `cli pack --platform android ...` → 提交云端打包（2-5 分钟）
4. 自动下载 APK → `dist/foggy-navigator-{version}.apk`
5. `node uni-admin-api.js publish --type native_app ...` → 上传到 uni-admin 并上线

### 手动打包（调试用）

```powershell
# 1. 预编译 App 资源
pnpm build:app-android

# 2. 在 HBuilderX 中导入编译产物
D:\work\HBuilderX\cli.exe project open --path dist/build/app

# 3. 提交云端打包
D:\work\HBuilderX\cli.exe pack --platform android --project dist/build/app ^
  --android.androidpacktype 0 ^
  --android.packagename com.foggy.navigator ^
  --android.certfile keystore/foggy-navigator.keystore ^
  --android.certpassword "@Shundao888" ^
  --android.storepassword "@Shundao888" ^
  --android.certalias foggy-navi
```

### 签名配置

| 参数 | 值 |
|------|-----|
| Keystore | `keystore/foggy-navigator.keystore` |
| Alias | `foggy-navi` |
| Algorithm | RSA 2048, 有效期 36500 天 |
| Package Name | `com.foggy.navigator` |
| androidpacktype | `0`（自定义证书） |

## wgt 热更新发布

### `release.ps1` 流程

1. 提示输入版本号、更新标题、内容、是否静默
2. 更新 `manifest.json` 和 `package.json` 中的版本号
3. `pnpm build:wgt` → 编译 + 打包为 `dist/foggy-navigator-{version}.wgt`
4. `node uni-admin-api.js publish --type wgt ...` → 上传到 uni-admin 并上线

### 手动构建 wgt

```powershell
pnpm build:wgt
# 产物: dist/foggy-navigator-{version}.wgt
```

## uni-admin 升级中心

### 配置（`.env.release`）

```
UNI_ADMIN_URL=https://sd-uni-admin.qlfloor.com
UNI_ADMIN_USERNAME=root
UNI_ADMIN_PASSWORD=@Foggysource888
UNICLOUD_SPACE_ID=mp-4af7054d-5a40-4315-8678-df36b44298bb
```

### API 工具 (`scripts/uni-admin-api.js`)

自动化发布流程：登录 uni-id → 上传文件到 uniCloud 云存储 → 创建版本记录

```powershell
# 发布原生安装包
node scripts/uni-admin-api.js publish --type native_app --version 1.0.0 ^
  --title "v1.0.0" --content "首个版本" --file dist/foggy-navigator-1.0.0.apk

# 发布 wgt 热更新
node scripts/uni-admin-api.js publish --type wgt --version 1.0.1 ^
  --title "v1.0.1" --content "Bug fixes" --file dist/foggy-navigator-1.0.1.wgt [--silent]
```

若 API 调用失败，脚本会打印手动操作步骤并自动打开浏览器到升级中心页面。

### 手动操作（备用）

1. 打开 `https://sd-uni-admin.qlfloor.com/admin/`
2. 登录 → 系统管理 → App升级中心
3. 切换"当前应用"为 Foggy Navigator（下拉列表最后一项，需滚动）
4. 发布新版 → 选择类型（原生App安装包 / wgt资源包）
5. 填写版本号、更新内容、选择平台（安卓）
6. 点"选择文件"上传 APK/wgt → 自动填充 CDN 下载链接
7. 开启"上线发行"开关 → 点击"发布"

**注意**：uni-picker 下拉框选择 Foggy Navigator 时可能需要在 picker 内滚动到底部。

### 客户端更新机制

**文件**：`src/utils/upgrade.ts`

- App 启动时 `onShow` 调用 `checkUpgrade()`（仅 `#ifdef APP-PLUS`）
- HTTP POST 调用 uni-admin 云函数 `uni-upgrade-center`
- API: `https://fc-mp-4af7054d-5a40-4315-8678-df36b44298bb.next.bspapp.com/uni-upgrade-center`
- 支持：静默 wgt 更新、交互式 wgt 更新（带进度条）、APK 整包更新

**UI 组件**：`src/components/UpgradePopup.vue`
- 挂载在 `pages/chat/index.vue`（主页）
- wot-design-uni 的 `wd-popup` + `wd-progress`
- `code === 2` 表示强制更新（无"稍后"按钮）

## 版本管理

### 版本号规则

| 文件 | 字段 | 示例 |
|------|------|------|
| `src/manifest.json` | `versionName` + `versionCode` | `"1.0.0"` / `"100"` |
| `package.json` | `version` | `"1.0.0"` |

- `versionCode` 由 `release.ps1` 自动递增
- wgt 更新只能升版本号，不能降级

### 更新策略

| 场景 | 方式 | 脚本 |
|------|------|------|
| JS/CSS/页面变更 | wgt 热更新 | `release.ps1` |
| 原生模块/SDK 变更 | APK 整包更新 | `build-apk.ps1` |
| 首次发布 | APK | `build-apk.ps1` |

## 常见问题排查

### "编译成功" 但 "项目编译失败"

**原因**：HBuilderX CLI `pack` 内部编译了 H5 而非 App（bug）。
**解决**：必须先 `pnpm build:app-android` 预编译，再 `pack` 编译产物目录。
**日志**：`%APPDATA%\HBuilder X\.log` — 看 `(generatepackageresource) manifest  false`

### "当前操作依赖插件 amazon-corretto / launcher"

**解决**：在 HBuilderX GUI 的插件管理中安装这两个插件。

### "公共测试证书" 不可用

**说明**：DCloud 已禁止新 App 使用公共测试证书。必须使用自定义证书（`androidpacktype: 0`）。

### "node_modules 缺少编译器模块"（publish --type wgt）

**原因**：pnpm workspace 符号链接与 HBuilderX 模块解析不兼容。
**解决**：用 `pnpm build:wgt`（本地编译 + 本地压缩）替代 HBuilderX 的 `publish --type wgt`。

### uni-admin 自动发布失败

**可能原因**：uniCloud HTTP API 格式变化、uni-id 登录方式变化、或网络问题。
**解决**：脚本会自动 fallback 到手动模式，打开浏览器到升级中心页面。

## 文件清单

| 文件 | 用途 |
|------|------|
| `scripts/build-apk.ps1` | APK 云端打包 + 发布到 uni-admin |
| `scripts/release.ps1` | wgt 热更新发版 + 发布到 uni-admin |
| `scripts/uni-admin-api.js` | uni-admin 升级中心 API（登录 + 上传 + 创建版本） |
| `scripts/pack-wgt.js` | wgt 压缩打包（PowerShell Compress-Archive） |
| `src/utils/upgrade.ts` | 客户端版本检查 + 更新逻辑 |
| `src/components/UpgradePopup.vue` | 更新弹窗 UI |
| `.env.release` | DCloud 账号、签名密码、uni-admin 凭据（不提交 git） |
| `keystore/foggy-navigator.keystore` | Android 签名文件（不提交 git） |
