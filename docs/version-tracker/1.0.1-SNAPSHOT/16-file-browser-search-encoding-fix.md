---
type: bug
bug_source: user-report
version: 1.0.1-SNAPSHOT
ticket: BUG-015
severity: major
status: closed
reproduction_status: confirmed
test_strategy: unit-test
automation_decision: required
owner: claude-worker-agent
---

# 16 File Browser Search Encoding Fix

## Date

- 2026-04-07
- 2026-04-08

## Background

用户在 2026-04-07 报告：文件浏览器的“查找文件”链路存在转码问题。

问题集中出现在 Windows Worker + Git 仓库场景：

- 包含中文文件名的仓库文件在搜索结果中可能显示为 Git 转义后的八进制串，而不是原始中文名
- 使用 GBK / GB18030 等本地编码保存的中文文本文件，在文件预览、Git 内容搜索和 fallback 内容搜索中会出现乱码或丢字

这类问题会直接影响：

- `Ctrl+P` 文件名搜索
- 搜索结果点击后打开文件
- 文件预览
- Git 仓库内容搜索
- 非 Git fallback 内容搜索

## Reproduction

### Environment

- worker platform: Windows
- file browser mode: Git-backed file search
- sample files:
  - 中文文件名文件，例如 `中文文件名.txt`
  - 非 UTF-8 中文文本文件，例如 GBK / GB18030 编码文件

### Steps

1. 在 Git 仓库中创建一个中文文件名文件，并保证该文件处于可被 `git ls-files` 枚举的范围内。
2. 打开文件浏览器，使用“查找文件”输入中文关键字搜索该文件。
3. 观察搜索结果中的文件名与相对路径。
4. 再打开一个 GBK / GB18030 编码的中文文本文件，观察文件预览。
5. 在非 Git fallback 内容搜索路径中检索中文内容，观察上下文行文本。

### Observed Result

- `git ls-files` 默认输出的非 ASCII 路径会被 Git quote-path 机制转义为类似 `"\\344\\270\\255..."` 的字面串。
- 前端拿到的不是原始中文路径，因此：
  - 搜索结果显示异常
  - 中文文件名的匹配能力下降，甚至直接搜不到
  - 基于该相对路径的后续打开动作不稳定
- 文件读取和 fallback 搜索原先统一按 UTF-8 解码：
  - `errors="replace"` 会产生乱码
  - `errors="ignore"` 会直接吞掉无法解码的中文字符
- `git grep` 输出原先也被当作单一编码整体解码：
  - 当一次命中同时混合 UTF-8 文件和 GBK 文件时，同一批结果会出现局部乱码
  - 问题并不只存在于 fallback 链路，Git-backed 内容搜索同样会复现

### Resolution Implemented

- Worker `run_git(...)` 统一增加 `-c core.quotepath=false`
- Worker 新增统一文本解码兜底：优先 UTF-8，再回退本机常用编码、`gb18030`、`gbk`
- 文件预览、fallback 内容搜索、工作区 diff 读取全部接入该解码逻辑
- 2026-04-08 再次修复：内容搜索不再直接消费 `git grep` 的整段文本输出，而是改成按文件逐个解码后匹配

## Expected vs Actual

### Expected

- 文件搜索结果应返回真实中文文件名与路径，而不是 Git 的转义串
- 中文文件名应可以被正常搜索、展示和点击打开
- 非 UTF-8 中文文本文件在预览、Git 内容搜索和 fallback 内容搜索中都应尽量按正确编码展示，而不是乱码或丢字

### Actual Before Fix

- Git 路径输出保留 quote-path 转义
- Worker 文本读取固定按 UTF-8 解码
- 文件搜索和文本预览在中文路径 / 本地编码文本场景下表现不正确

### Actual After Fix

- Git 文件路径默认返回真实中文路径
- 文件浏览器对常见中文编码文本具备更稳健的兜底解码能力
- 内容搜索对混合编码仓库改为逐文件解码，不再受 `git grep` 单流解码污染
- 路由级单测已覆盖这次修复的核心故障点

## Impact Scope

直接影响：

- `tools/claude-agent-worker` 文件浏览路由
- `addons/claude-worker-agent` 代理后的文件浏览功能
- `packages/navigator-frontend` 文件搜索结果展示与点击打开链路

具体功能点：

- 文件名搜索
- 文件内容预览
- fallback 内容搜索
- 工作区 diff 预览
- Git 相关路径展示

## Test Strategy

- 主策略：`unit-test`
- 原因：
  - 根因集中在 Worker 端的 Git 调用参数与文本解码逻辑
  - 这次变更不依赖复杂外部环境即可稳定还原与验证
  - 适合用路由层 / 工具层单测先锁死回归

### Automation Decision

- `required`

原因：

- 文件浏览器属于高频基础能力
- 问题在 Windows 中文工作区中可稳定出现
- 回归点清晰，自动化成本低，不应依赖人工记忆

## Code Inventory

- `tools/claude-agent-worker/src/agent_worker/routes/utils.py`
- `tools/claude-agent-worker/src/agent_worker/routes/files.py`
- `tools/claude-agent-worker/tests/routes/test_utils.py`
- `tools/claude-agent-worker/tests/routes/test_files.py`

## Root Cause

本次问题有两个独立根因：

1. Git 路径输出问题

- Worker 文件搜索依赖 `git ls-files`
- Git 默认会对非 ASCII 路径应用 quote-path 转义
- Worker 直接消费该输出，导致中文文件名被传成转义字面量，而不是原始中文路径

2. 文本解码策略问题

- 文件预览和 fallback 搜索将文本强行视为 UTF-8
- 对 GBK / GB18030 这类中文编码文件：
  - `replace` 会产生替换字符
  - `ignore` 会直接丢失字符

3. Git 内容搜索输出模型问题

- `git grep` 会把多个命中文件的内容拼成一段 stdout
- Worker 再对整段 stdout 做一次统一解码
- 当同一批命中混合 UTF-8 文件和 GBK / GB18030 文件时，不存在一个单一编码可以同时正确还原所有结果

因此当前故障不是前端渲染问题，而是 Worker 在“路径返回”“文本读取”“Git grep 输出解码粒度”三个位置都做了过强假设。

## Fix Implemented

1. 在 `run_git(...)` 中强制附加 `-c core.quotepath=false`
2. 新增统一文本解码函数，按 UTF-8 -> 系统首选编码 -> `gb18030` -> `gbk` 顺序回退
3. 将文件内容读取接入该解码逻辑
4. 将 fallback 内容搜索接入该解码逻辑
5. 将工作区 diff 的文件读取接入该解码逻辑
6. 将 Git-backed 内容搜索改成“文件枚举 + Worker 逐文件解码匹配”，不再依赖 `git grep` 的整段文本输出
7. 补充 `test_utils.py`，覆盖：
   - UTF-8 解码
   - GBK 回退解码
   - `run_git(...)` 的 quote-path 配置
8. 补充 `test_files.py`，覆盖：
   - Git 文件列表场景下 UTF-8 + GBK 混合编码内容搜索
   - 相对路径文件过滤规则

## Remaining Risk

编码正确性上的主故障已收口，但仍有一个新的权衡需要接受：

- 内容搜索现在优先正确性，改为 Worker 逐文件读取并匹配；在超大仓库中，性能可能低于原先完全依赖 `git grep` 的方案

如果后续需要继续优化，方向应是：

1. 保持“逐文件独立解码”的正确性前提不变
2. 再引入更细粒度的候选文件缩小或缓存策略，而不是回退到整段 `git grep` 输出解码

## Fix Checklist

- [x] 记录问题来源、环境、现象、影响范围
- [x] 通过最小样例确认问题可复现
- [x] 定位 Git quote-path 与 UTF-8 强解码两条根因
- [x] 定位 `git grep` 单流解码导致的混合编码回归根因
- [x] 完成 Worker 侧彻底修复
- [x] 补充自动化测试覆盖回归点
- [x] 执行 routes 单测验证修复未破坏既有 Git / files 路由
- [x] 将 Git-backed 内容搜索改为逐文件独立解码

## Verification

### Automated

已执行：

```bash
pytest tools/claude-agent-worker/tests/routes
```

结果：

- `143` 个测试全部通过
- 其中新增回归覆盖：
  - `test_run_git_disables_quote_path_and_decodes_output`
  - `test_run_git_decodes_legacy_windows_output`
  - `TestDecodeTextBytes`
  - `test_decodes_each_git_file_independently`
  - `test_respects_file_pattern_for_relative_paths`

### Manual

已确认：

1. 2026-04-08 用户在更新部署后再次验证 `/api/v1/file-browser/search`，文件名搜索返回已恢复正常。
2. 此前通过 `curl` 观察到的 `git ls-files` quote-path 转义结果，在更新后不再出现。
3. 当前问题可按“已修复并已线上验证”关闭。

## References

- `docs/version-tracker/1.0.1-SNAPSHOT/09-file-browser-image-preview.md`
- `tools/claude-agent-worker/src/agent_worker/routes/utils.py`
- `tools/claude-agent-worker/src/agent_worker/routes/files.py`
- `tools/claude-agent-worker/tests/routes/test_utils.py`
