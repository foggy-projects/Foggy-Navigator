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
4. **查询线上最新 APK 版本**（`latest-native-version`）→ 作为 `--minVersion`
5. `node uni-admin-api.js publish --type wgt ...` → 上传到 uni-admin 并上线

> **重要**：`--minVersion` 必须是线上实际发布的 APK 版本，不能用本地版本号。
> 本地版本号随 wgt 发版不断递增，可能远高于用户实际安装的 APK 版本。
> 如果 `min_uni_version > 用户 APK 版本`，云函数会认为不兼容而跳过 wgt 更新。

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
UNI_ADMIN_PASSWORD=<your-password>
UNICLOUD_SPACE_ID=mp-4af7054d-5a40-4315-8678-df36b44298bb
```

### API 工具 (`scripts/uni-admin-api.js`)

自动化发布流程：匿名认证 → 登录 uni-id → 上传文件到 uniCloud 云存储 → 创建版本记录（clientDB）

**uniCloud 客户端 API 协议要点**（逆向自 uni-admin 前端 SDK）：
- 所有请求发送到 `https://api.next.bspapp.com/client`
- 签名: `HmacMD5(sortedQueryString, clientSecret)`，通过 `x-serverless-sign` 头传递
- clientSecret: `bFl25CiSXhK7K979vXI2rA==`（从 uni-admin 前端提取）
- 云函数调用: 先 `anonymousAuthorize` 获取 accessToken，通过 `x-basement-token` 头传递
- uni-id 登录: 方法名 `login`（非 `loginByUsername`），params 为数组 `[{username, password}]`
- clientDB 操作: 需同时传 accessToken（API 层）+ uniIdToken（在 functionArgs 中）
- OSS 上传: 必须包含 `x-oss-security-token`（STS 临时凭证）
- 版本记录必填字段: `uni_platform: 'android'`

```powershell
# 发布原生安装包
node scripts/uni-admin-api.js publish --type native_app --version 1.0.0 ^
  --title "v1.0.0" --content "首个版本" --file dist/foggy-navigator-1.0.0.apk

# 发布 wgt 热更新（--minVersion 必填，必须是线上实际 APK 版本）
node scripts/uni-admin-api.js publish --type wgt --version 1.0.1 ^
  --title "v1.0.1" --content "Bug fixes" --minVersion 1.0.0 ^
  --file dist/foggy-navigator-1.0.1.wgt [--silent]

# 查询线上最新原生 App 版本（用于确定 --minVersion）
node scripts/uni-admin-api.js latest-native-version
# 输出: {"version":"1.0.0"}

# 查询线上最新版本（任意类型）
node scripts/uni-admin-api.js latest-version
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

**调用协议**：`src/utils/unicloud-client.ts`
- 通过 uniCloud 客户端 API 协议（`https://api.next.bspapp.com/client`）调用云函数
- 纯 JS 实现 MD5/HMAC-MD5 签名（App-Plus 无 Node.js crypto）
- 流程：`anonymousAuthorize` → accessToken → `serverless.function.runtime.invoke(uni-upgrade-center)`
- **不走 HTTP trigger**（`fc-{spaceId}.next.bspapp.com` 返回 404，URL化未开启）

**版本检查**：`src/utils/upgrade.ts`
- 设置页手动调用 `checkUpgradeManual()`（仅 `#ifdef APP-PLUS`）
- **appVersion**: `plus.runtime.version`（原生 APK 版本，wgt 更新后不变）
- **wgtVersion**: `plus.runtime.getProperty()` 获取（wgt 更新后会变）
- **注意**：`plus.runtime.innerVersion` 是引擎版本（如 `4.87`），**不是** wgt 版本，绝对不能传给云函数
- 支持：静默 wgt 更新、交互式 wgt 更新（带进度条）、APK 整包更新

**UI 组件**：`src/components/UpgradePopup.vue`
- 挂载在 `pages/settings/index.vue`（设置页）
- 使用原生 `view` + `button` + CSS 进度条（wot-design-uni 组件在 App-Plus 不渲染）
- `code === 101` 可选更新，`code === 102` 强制更新（隐藏"稍后"按钮）
- wgt 安装完先关闭遮罩层（`visible=false`），再弹 `uni.showModal` 重启确认（否则 modal 被 z-index:9999 遮罩挡住无法点击）

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

### 检查更新返回 "已是最新" 但实际有新版本

**最常见原因：`min_uni_version` 高于用户 APK 版本**（已修复）
发布 wgt 时 `--minVersion` 如果设成了本地 manifest 版本号（随 wgt 发版不断递增），
会导致 `min_uni_version`（如 1.0.18）远高于用户实际安装的 APK 版本（如 1.0.8）。
云函数兼容性校验：`用户 APK 版本 < min_uni_version` → 认为 wgt 不兼容 → 跳过。
**解决**：`release.ps1` 现在通过 `latest-native-version` 从服务器查询真实 APK 版本作为 `--minVersion`，
不再使用本地版本号。若服务器上无 APK 记录，会提示先发布 APK 基础版本。

**可能原因 2：wgtVersion 传错**
`plus.runtime.innerVersion` 返回的是 uni-app **引擎版本**（如 `"4.87"`），不是 wgt 资源版本。
字符串比较 `"1.0.15" < "4.87"` 导致云函数认为客户端版本更高。
**解决**：必须用 `plus.runtime.getProperty(appid, cb)` 获取真实 wgt 资源版本。

**可能原因 3：wgt 记录缺少 `min_uni_version`**
当 wgt 版本号 > APK 版本号时，云函数会检查 `min_uni_version` 兼容性字段。
该字段为空 → 兼容性校验失败 → wgt 被跳过。
**解决**：发布 wgt 时必须传 `--minVersion`（线上实际 APK 版本），`release.ps1` 已自动处理。

### wgt 更新下载完成后卡在 100%（重启无响应）

**原因**：`uni.showModal` 重启确认对话框被 UpgradePopup 的 `position:fixed; z-index:9999` 遮罩层挡住。
**解决**：wgt 安装完成后，必须先设置 `visible=false`（关闭遮罩），再调用 `uni.showModal`。

### wot-design-uni 组件在 App-Plus 原生环境不渲染

**现象**：`wd-popup`、`wd-button`、`wd-progress` 等在 H5 正常，但 App-Plus 中渲染为纯文本或空白。
**解决**：UpgradePopup 等需要在 App-Plus 工作的组件，使用原生 `view` + `button` + CSS 自绘。

## 可复用经验（适用于任何 uni-app + uniCloud + uni-admin 项目）

### HBuilderX CLI 打包陷阱

1. **`pack` 命令编译 bug**：CLI 创建的项目执行 `pack` 时，内部 `uni.js build` 不传 `-p app`，导致编译 H5 而非 App。**必须先用 `uni build -p app-android` 预编译**，再让 `pack` 只打包编译产物。
2. **pnpm workspace 不兼容**：HBuilderX 的 `publish --type wgt` 无法解析 pnpm 符号链接下的编译器模块。**解决**：本地 `uni build` + 脚本压缩 wgt，绕过 HBuilderX 的 wgt 发布。
3. **公共测试证书已废弃**：DCloud 不再允许新 App 使用公共测试证书，必须用 `keytool` 生成自定义 keystore（`androidpacktype: 0`）。
4. **CLI 需要 GUI 插件**：`amazon-corretto` 和 `launcher` 插件必须先在 HBuilderX GUI 中安装，CLI 才能执行打包。

### uniCloud 客户端 API 协议（逆向总结）

直接调用 uniCloud HTTP trigger（`https://fc-{spaceId}.next.bspapp.com/{functionName}`）**行不通** — uni-admin 的云函数未配置 HTTP trigger。必须通过客户端 API 协议调用：

**完整调用链**：
```
1. anonymousAuthorize → accessToken（600s TTL）
2. serverless.function.runtime.invoke(uni-id-co, login) → uniIdToken (JWT)
3. serverless.file.resource.generateProximalSign → OSS 上传凭证
4. POST OSS（含 x-oss-security-token） → 文件上传
5. serverless.file.resource.report → 上报上传完成
6. serverless.function.runtime.invoke(DCloud-clientDB, add) → 创建版本记录
```

**关键细节**：
- **签名**：`HmacMD5(sortedQueryString, clientSecret)`，clientSecret 从前端 JS bundle 提取
- **双 token 机制**：云函数调用只需 accessToken；clientDB 操作还需在 `functionArgs` 中传 `uniIdToken`
- **uni-id 登录**：方法名是 `login`（非 `loginByUsername`），params 是**数组** `[{username, password}]`（非对象），必须传 `clientInfo.uniPlatform`
- **OSS 上传**：STS 临时凭证要求 form 中必须包含 `x-oss-security-token` 字段
- **clientDB 权限**：`x-client-info` 头需 `encodeURIComponent(JSON.stringify({PLATFORM, OS, APPID, uniPlatform}))`，其中 APPID 是 **uni-admin 自身的 appid**（非目标 App 的 appid）
- **版本记录必填**：`uni_platform: 'android'`，`create_env: 'upgrade-center'`；不要手动设 `create_date`（服务端自动生成）

### 客户端热更新架构

- **不依赖 `uni-upgrade-center-app` 插件**：该插件要求项目内集成 uniCloud SDK
- **不走 HTTP trigger**：`fc-{spaceId}.next.bspapp.com/uni-upgrade-center` 返回 404（URL化未开启）
- **客户端 API 协议**：`src/utils/unicloud-client.ts` 直接调用 `api.next.bspapp.com/client`，纯 JS 实现 MD5/HMAC-MD5 签名
- **wgt 优先策略**：日常迭代走 wgt 热更新（免安装、秒级生效），仅原生模块/SDK 变更时发 APK
- **静默更新**：`is_silently: true` 时后台下载安装，下次启动生效；非静默弹窗带进度条
- **强制更新**：云函数返回 `code === 102` 时隐藏"稍后"按钮（`101` = 可选更新）
- **版本号获取**：`plus.runtime.version` = 原生 APK 版本（不变），`plus.runtime.getProperty().version` = wgt 资源版本（更新后变）

### .env.release 文件模板

新项目可参考此模板创建 `.env.release`（加入 `.gitignore`）：
```
# DCloud 账号（HBuilderX CLI 打包需要）
DCLOUD_USERNAME=<dcloud-email>
DCLOUD_PASSWORD=<dcloud-password>

# Android 签名
ANDROID_KEYSTORE=keystore/<app-name>.keystore
ANDROID_CERT_ALIAS=<alias>
ANDROID_CERT_PASSWORD=<password>
ANDROID_STORE_PASSWORD=<password>

# uni-admin 升级中心
UNI_ADMIN_URL=<uni-admin-url>
UNI_ADMIN_USERNAME=root
UNI_ADMIN_PASSWORD=<password>
UNICLOUD_SPACE_ID=<space-id>
```

## 文件清单

| 文件 | 用途 |
|------|------|
| `scripts/build-apk.ps1` | APK 云端打包 + 发布到 uni-admin |
| `scripts/release.ps1` | wgt 热更新发版 + 发布到 uni-admin |
| `scripts/uni-admin-api.js` | uni-admin 升级中心 API（登录 + 上传 + 创建版本） |
| `scripts/pack-wgt.js` | wgt 压缩打包（PowerShell Compress-Archive） |
| `src/utils/upgrade.ts` | 客户端版本检查 + 更新逻辑 |
| `src/utils/unicloud-client.ts` | uniCloud 客户端 API 协议（MD5/HMAC-MD5 签名 + 云函数调用） |
| `src/components/UpgradePopup.vue` | 更新弹窗 UI（原生组件，非 wot-design-uni） |
| `.env.release` | DCloud 账号、签名密码、uni-admin 凭据（不提交 git） |
| `keystore/foggy-navigator.keystore` | Android 签名文件（不提交 git） |
