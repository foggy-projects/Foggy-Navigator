---
name: file-browser-dev
description: 文件浏览器全栈开发指导（Python Worker + Java 代理层 + Vue 前端）。当用户需要迭代文件浏览器功能、添加文件操作、修改搜索逻辑、扩展右键菜单、管理 .foggy-ignore 排除规则时使用。触发词：/fb, /file-browser, 提及"文件浏览器"、"FileBrowser"、"文件搜索"、"foggy-ignore"、"搜索排除"。
---

# File Browser Full-Stack Development Guide

文件浏览器功能的全栈开发指导：Python Worker 文件操作 → Java 代理层 → Vue 前端视图。

## 架构概述

```
浏览器 (FileBrowserView.vue)
  ↕ REST（经 Java 代理）
Java 后端 (FileBrowserController — 代理 REST，隐藏 Worker 地址)
  ↕ HTTP（ClaudeWorkerClient）
Python Worker (routes/files.py — 实际文件系统操作)
```

**关键设计**：所有文件操作都经 Java 代理层透传，前端不直接访问 Worker。Java 层负责：
- 通过 `directoryId` 解析 Worker 地址和项目路径
- 用户权限校验（UserContext）
- 路径安全检查（防止 `..` 穿越）

## 核心文件清单

### Python Worker（文件系统操作）

| 文件 | 职责 |
|------|------|
| `tools/claude-agent-worker/src/agent_worker/models.py` | Pydantic 模型：`DirectoryListingResponse`, `FileContentResponse`, `FileSearchResponse`, `ContentSearchResponse`, `FoggyIgnoreRequest/Response` 等 |
| `tools/claude-agent-worker/src/agent_worker/routes/files.py` | 文件浏览/搜索/diff/.foggy-ignore 所有端点 |

### Java 代理层（安全代理 + 路径解析）

| 文件 | 职责 |
|------|------|
| `addons/claude-worker-agent/.../controller/FileBrowserController.java` | 代理所有文件浏览 REST API，`@RequestMapping("/api/v1/file-browser")` |
| `addons/claude-worker-agent/.../client/ClaudeWorkerClient.java` | Worker HTTP 客户端，包含所有文件浏览方法 |
| `addons/claude-worker-agent/.../service/WorkingDirectoryService.java` | 目录实体查询、路径解析 |

### Vue 前端（文件浏览器 UI）

| 文件 | 职责 |
|------|------|
| `packages/navigator-frontend/src/api/fileBrowser.ts` | TypeScript 类型 + API 函数封装 |
| `packages/navigator-frontend/src/views/FileBrowserView.vue` | 主视图：目录树 + Monaco 编辑器 + Git diff + 右键菜单 + 搜索 |
| `packages/navigator-frontend/src/components/file-browser/FileSearchDialog.vue` | 文件/内容搜索弹窗 |

## API 端点总览

### Python Worker 端点

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/api/v1/files?path=&show_hidden=` | 列出目录内容 |
| GET | `/api/v1/files/content?path=` | 读取文件内容（含语言检测） |
| GET | `/api/v1/files/search?path=&query=&max_results=` | 文件名搜索（`git ls-files`） |
| GET | `/api/v1/files/search-content?path=&query=&max_results=&context_lines=&case_sensitive=` | 全文内容搜索（`git grep`） |
| GET | `/api/v1/files/ignore?path=` | 获取 `.foggy-ignore` 排除模式列表 |
| POST | `/api/v1/files/ignore` | 添加排除模式（body: `{path, pattern}`） |
| DELETE | `/api/v1/files/ignore` | 移除排除模式（body: `{path, pattern}`） |
| GET | `/api/v1/git-diff?path=` | Git 变更摘要（`git diff --stat`） |
| GET | `/api/v1/git-diff/file?path=&file=` | 单文件 diff（original + modified） |

### Java 代理端点（`/api/v1/file-browser/`）

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/list?directoryId=&subPath=&showHidden=` | 列出目录 |
| GET | `/content?directoryId=&subPath=` | 读取文件 |
| GET | `/search?directoryId=&query=&maxResults=` | 文件名搜索 |
| GET | `/search-content?directoryId=&query=&maxResults=&contextLines=&caseSensitive=` | 内容搜索 |
| GET | `/ignore?directoryId=` | 获取排除模式 |
| POST | `/ignore` | 添加排除模式（body: `{directoryId, pattern}`） |
| DELETE | `/ignore` | 移除排除模式（body: `{directoryId, pattern}`） |
| GET | `/git-diff?directoryId=` | Git 变更摘要 |
| GET | `/git-diff/file?directoryId=&file=` | 单文件 diff |

### 前端 API 函数（`fileBrowser.ts`）

| 函数 | 说明 |
|------|------|
| `listDirectory(id, subPath?, showHidden?)` | 列出目录 |
| `readFileContent(id, subPath)` | 读取文件 |
| `searchFiles(id, query, maxResults?)` | 文件名搜索 |
| `searchContent(id, query, maxResults?, contextLines?, caseSensitive?)` | 内容搜索 |
| `getFoggyIgnore(id)` | 获取排除模式 |
| `addFoggyIgnore(id, pattern)` | 添加排除模式 |
| `removeFoggyIgnore(id, pattern)` | 移除排除模式 |
| `getGitDiffSummary(id)` | Git 变更摘要 |
| `getFileDiff(id, file)` | 单文件 diff |

## 关键模式与决策规则

### 1. 新增文件浏览功能的传播路径

当需要添加新的文件操作端点时，按此顺序修改：

1. **Python `models.py`** — 添加 Request/Response Pydantic 模型
2. **Python `routes/files.py`** — 实现端点逻辑（实际文件系统操作）
3. **Java `ClaudeWorkerClient.java`** — 添加 HTTP 调用方法
4. **Java `FileBrowserController.java`** — 添加代理端点（解析 directoryId → path）
5. **Frontend `fileBrowser.ts`** — 添加 TypeScript 接口 + API 函数
6. **Frontend `FileBrowserView.vue`** — UI 集成（按钮/菜单/交互）

### 2. Java 代理层的通用模式

FileBrowserController 中每个代理方法遵循相同模式：

```java
@GetMapping("/xxx")
public RX<Map<String, Object>> xxx(@RequestParam String directoryId, ...) {
    ClaudeWorkerClient client = resolveClient(directoryId);
    WorkingDirectoryEntity entity = getEntity(directoryId);
    try {
        Map<String, Object> result = client.xxx(entity.getPath(), ...).block(TIMEOUT);
        return RX.ok(result);
    } catch (Exception e) {
        return RX.failA("操作失败: " + e.getMessage());
    }
}
```

关键 helper 方法：
- `resolveClient(directoryId)` — 查找目录所属 Worker → 创建 HTTP 客户端
- `getEntity(directoryId)` — 查找 WorkingDirectoryEntity（含用户权限校验）
- `buildPath(basePath, subPath)` — 安全拼接路径（防 `..` 穿越）

### 3. .foggy-ignore 机制

**文件格式**：项目根目录下的 `.foggy-ignore`，每行一个 glob 模式（类似 `.gitignore`）。

**用途**：控制文件名搜索和内容搜索的排除范围。

**Python Worker 实现**：
- `_load_foggy_ignore(root)` — 读取并解析 `.foggy-ignore`，过滤注释和空行
- 搜索时通过 `pathspec` 库匹配排除模式（file search + content search 共用）
- 内置默认排除：`node_modules/`, `.git/`, `__pycache__/`, `*.pyc` 等

**前端右键菜单集成**：
- `ignoredPatterns: ref<Set<string>>` — 页面加载时从 `getFoggyIgnore()` 获取
- 目录 pattern = `relativePath/`（带尾斜杠），文件 pattern = `relativePath`
- `isNodeIgnored` computed — 检查当前右键节点是否已排除
- `toggleIgnore()` — 调用 `addFoggyIgnore` 或 `removeFoggyIgnore`，操作后更新 Set

### 4. 搜索功能实现

**文件名搜索**（`/files/search`）：
- 使用 `git ls-files` 获取版本控制下的文件列表
- 对每个文件名做子串匹配（大小写不敏感）
- 受 `.foggy-ignore` 排除规则过滤

**内容搜索**（`/files/search-content`）：
- 使用 `git grep -F` 进行全文检索
- 支持参数：`context_lines`（上下文行数）、`case_sensitive`
- 受 `.foggy-ignore` 排除规则过滤

**前端搜索弹窗**（`FileSearchDialog.vue`）：
- `Teleport` 到 body 的模态弹窗
- 300ms debounce 输入
- 键盘导航：↑↓ 选择，Enter 打开，Esc 关闭
- 内容模式额外显示「区分大小写」复选框

### 5. 右键菜单扩展模式

在 `FileBrowserView.vue` 中扩展右键菜单的步骤：

1. 在 `<Teleport>` 内的 `.ctx-menu` 中添加菜单项（用 `.ctx-divider` 分组）
2. 添加 handler 函数处理点击
3. 最后调用 `closeContextMenu()` 关闭菜单

```vue
<!-- 模板 -->
<div class="ctx-divider"></div>
<div class="ctx-item" @click="myNewAction()">菜单文字</div>

<!-- 脚本 -->
function myNewAction() {
  const node = ctxMenu.value.node
  if (!node) return
  // ... 操作逻辑
  closeContextMenu()
}
```

### 6. Monaco 编辑器集成

- 懒加载：`import('@/utils/monacoSetup')` 在 `onMounted` 中
- 只读模式：`readOnly: true`
- 两个编辑器实例：`editorInstance`（普通查看）+ `diffEditorInstance`（diff 视图）
- `diffMode` ref 控制显示哪个编辑器
- 语言自动检测由 Python Worker `_detect_language()` 完成

## 常见操作

### 添加新的文件操作端点

例如添加「写入文件」功能：

1. `models.py` — 添加 `WriteFileRequest(path, content)` 模型
2. `files.py` — 添加 `POST /api/v1/files/content` 端点
3. `ClaudeWorkerClient.java` — 添加 `writeFile(path, content)` 方法
4. `FileBrowserController.java` — 添加 `POST /content` 代理
5. `fileBrowser.ts` — 添加 `writeFile(id, subPath, content)` 函数
6. `FileBrowserView.vue` — 添加保存按钮 + 切换为可编辑模式

### 扩展搜索排除规则

如果需要支持更复杂的排除规则（如正则、否定模式）：

1. Python `_load_foggy_ignore()` — 修改解析逻辑
2. Python 搜索端点中的 `pathspec` 匹配 — 调整匹配方式
3. 前端 `isNodeIgnored` — 相应调整判断逻辑

### 添加目录树右键操作

1. `FileBrowserView.vue` 模板 — `.ctx-menu` 中添加 `<div class="ctx-item">`
2. 脚本 — 添加 handler 函数
3. 如需后端支持 — 按「传播路径」逐层添加

## 约束条件

- 所有文件操作都是 **只读** 的（查看、搜索、diff），不支持写入/删除
- 文件大小限制由 Python Worker 的 `MAX_FILE_SIZE` 控制（默认 1MB）
- 二进制文件不读取内容，前端显示「二进制文件，无法预览」
- `.foggy-ignore` 文件存储在项目根目录，由 Worker 直接读写
- 搜索依赖 Git（`git ls-files`、`git grep`），非 Git 仓库有 fallback
- 目录树使用 `el-tree-v2`（虚拟滚动），适合大型项目
