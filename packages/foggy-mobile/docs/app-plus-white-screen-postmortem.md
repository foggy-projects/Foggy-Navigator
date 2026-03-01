# App-Plus 原生 App 白屏问题复盘

> 影响版本：v1.0.3 ~ v1.0.9 | 排查耗时：约 2 天 | 解决日期：2026-03-01

## 现象

安装 APK 后打开 App，只显示底部原生 tabBar，页面区域完全白屏。
H5 模式运行完全正常，问题仅出现在 App-Plus 原生环境。

## 根因

### 1. `@uni-helper/axios-adapter` 访问不存在的浏览器全局对象（白屏直接原因）

`@uni-helper/axios-adapter@1.0.0` 内部打包了一段来自 axios 的工具代码：

```js
const en = $t("object" == typeof self ? self.FormData : window.FormData);
```

在 uni-app App-Plus 原生运行环境中：
- `typeof self` → `"undefined"`（非 `"object"`），走 else 分支
- `window` → `undefined`（原生 JS 引擎没有 `window` 全局对象）
- `window.FormData` → **`Cannot read property 'FormData' of undefined`**

这个错误发生在 **模块初始化阶段**（ES module evaluation），早于任何业务代码执行。
整个 JS 上下文创建失败（`createInstanceContext failed`），导致页面完全白屏。

### 2. `manifest.json` 缺少 `vueVersion` 字段（HBuilderX 真机调试编译失败）

排查白屏过程中尝试使用 HBuilderX "运行到手机" 真机调试，但始终报：

```
node_modules缺少编译器模块，请执行npm install后重试
```

通过逆向分析 `HBuilderX/plugins/uniapp-extension/out/index.js`（900KB 压缩文件），
发现 HBuilderX 的 Vue 版本检测逻辑：

```js
getVueVersion = async (projectDir) => {
  const vueVersion = await getProjectManifestInfo(projectDir, "vueVersion") || V2;  // 默认 V2!
  if (getIsCli(projectDir)) {
    // CLI 项目: 需要 vueVersion === V3 且 vite.config 存在
    return (vueVersion === V3 && viteConfigExists(projectDir)) ? V3 : V2;
  }
  // ...
};
```

当检测为 Vue 2 时：
- `getCompilePlugin()` 返回 `node_modules/@vue/cli-service`（Vue 2 webpack 编译器）
- 我们的项目是 Vue 3 + Vite，没有 `@vue/cli-service` → 报错

## 修复

### 修复 1：替换 axios 适配器

移除 `@uni-helper/axios-adapter`，用自定义适配器替代：

```
src/utils/uni-axios-adapter.ts  — 基于 uni.request，无浏览器 API 依赖
src/api/client.ts               — import 改为 @/utils/uni-axios-adapter
src/api/auth.ts                 — import 改为 @/utils/uni-axios-adapter
```

自定义适配器直接调用 `uni.request`，不依赖任何浏览器全局对象（`window`、`self`、`FormData`、`Blob`）。

### 修复 2：manifest.json 添加 vueVersion

```json
{
  "vueVersion": "3",
  // ...
}
```

## 排查过程中的其他发现

### HBuilderX CLI `pack` 编译 bug

HBuilderX CLI 的 `pack` 命令对 CLI 创建的 uni-app 项目有 bug：
内部调用 `uni.js build` 时不传 `-p app` 参数，编译 H5 而非 App。

**解决**：先 `pnpm build:app-android` 预编译，再将编译产物复制到项目树外的临时目录，
让 HBuilderX 使用内置编译器。详见 `scripts/build-apk.ps1`。

### pnpm workspace 与 HBuilderX 不兼容

HBuilderX "运行到手机" 无法处理 pnpm 的符号链接 node_modules。
真机调试需要在独立目录中使用 npm 安装的真实 node_modules。

### App-Plus 原生环境缺失的浏览器 API

| API | 是否存在 | 说明 |
|-----|---------|------|
| `window` | ❌ | 原生 JS 引擎，非浏览器 |
| `self` | ❌ | 同上 |
| `document` | ❌ | 无 DOM |
| `FormData` | ❌ | 浏览器专属 |
| `Blob` | ❌ | 浏览器专属 |
| `XMLHttpRequest` | ❌ | 用 `uni.request` 替代 |
| `fetch` | ❌ | 用 `uni.request` 替代 |
| `globalThis` | ✅ | V8/JSCore 标准 |
| `plus.*` | ✅ | 5+ Runtime API |
| `uni.*` | ✅ | uni-app API |

### 诊断技巧

- `plus.nativeUI.alert()` 直接调用 Android AlertDialog，**不依赖 webview 渲染**，
  适合在 JS 执行早期（onLaunch、onError）输出诊断信息
- HBuilderX 真机调试控制台可显示 `reportJSException` 错误，包含具体 JS 堆栈

## 教训

1. **uni-app App-Plus ≠ 浏览器**：任何第三方库如果在模块初始化时访问浏览器全局对象
   （`window`、`self`、`document`、`FormData` 等），都会导致白屏崩溃。
   引入新依赖时必须检查其是否有 App-Plus 兼容问题。

2. **`typeof` 是安全的，直接属性访问不安全**：
   - ✅ `typeof window !== 'undefined'` — 安全，不会抛错
   - ❌ `window.FormData` — 如果 `window` 未定义则崩溃
   - axios 自身的浏览器检测代码用 `typeof` 是安全的；`@uni-helper/axios-adapter` 的打包代码没有这个防护

3. **manifest.json 的 `vueVersion` 字段不可省略**：HBuilderX 对 CLI 项目默认假设 Vue 2，
   必须显式声明 `"vueVersion": "3"` 才能正确识别编译器。

4. **HBuilderX 的错误信息具有误导性**：
   - "编译成功" + "项目编译失败" → 实际是编译了 H5 而非 App
   - "缺少编译器模块" → 实际是 Vue 版本检测错误，不是 npm install 的问题
