# 10 File Browser PDF Preview Plan

## Date

- 2026-04-05

## Type

- Requirement
- Solution Plan

## Background

文件浏览器已经补上了图片二进制预览能力，但当前预览能力仍然是点状的：

- 文本文件 -> Monaco
- 图片文件 -> 图片预览
- 其他二进制文件 -> “无法预览”

下一步希望讨论 `PDF` 等文件类型的支持方案，但本轮只做方案收敛，不进入实现。

## Goal

优先解决 `PDF` 在文件浏览器中“打开即可预览”的需求，同时保证后续扩展到更多文件类型时，不会继续堆叠零散特判。

## Complexity Assessment

如果范围限定为 `PDF`，复杂度属于**中等**，明显低于 `docx/xlsx/pptx` 这类办公文档。

原因：

- 当前 raw 文件流链路已经存在，可直接复用
- 浏览器对 PDF 有一定原生支持
- 前端只需要新增一种预览组件和对应分发逻辑

真正复杂的是：

- Office 文档高质量预览
- 服务端格式转换
- 跨端一致的文档交互能力（搜索、目录、分页、批注）

## Recommended Direction

建议按“两层目标”推进：

### 1. 第一层：先支持 PDF 预览

目标是尽快让文件浏览器能直接打开 PDF。

### 2. 第二层：把预览架构抽象成 MIME 分发

不要继续沿用“某个扩展名单独写一套逻辑”的模式，改成：

- `text/*` 或文本内容 -> Monaco
- `image/*` -> 图片预览
- `application/pdf` -> PDF 预览
- `video/*` -> 视频预览
- `audio/*` -> 音频预览
- 其他 -> fallback 提示

这样后续新增类型时，只需要增加一个 preview handler，而不是继续侵入主视图流程。

## PDF Options

### Option A: Browser Native PDF Preview

方案：

- 前端继续走 raw 接口获取 `Blob`
- 使用 `iframe` / `object` / 新窗口加载 PDF 预览

优点：

- 实现最快
- 依赖最少
- 基本不需要新增大型前端库

缺点：

- 浏览器之间体验不完全一致
- 缩放、页码、搜索、目录控制能力弱
- iPad / 嵌入式 WebView 的表现可能不稳定

结论：

适合作为 PDF 第一版。

### Option B: PDF.js

方案：

- 前端引入 `pdf.js`
- 自己管理页面渲染、缩放、分页、滚动和加载状态

优点：

- 跨浏览器一致性更好
- 控制力强
- 后续可扩展页码、缩放、搜索、缩略图等能力

缺点：

- 前端实现和维护成本更高
- 包体积增加
- 首次接入需要更多 UI 和性能调优

结论：

适合作为第二阶段，而不是第一阶段起手。

## Suggested Scope Split

建议按下面两步拆：

### Phase 1: PDF Minimum Viable Preview

范围：

- 支持 `application/pdf`
- 文件浏览器主区域可直接预览
- 保留失败兜底提示
- 不做全文搜索、目录、批注、缩略图

技术倾向：

- 优先使用浏览器原生 PDF 预览

### Phase 2: Preview Registry Refactor

范围：

- 统一抽象 preview type / preview renderer
- 由 MIME 而不是单纯扩展名驱动
- 为音视频、Markdown、CSV 等类型预留扩展点

技术倾向：

- 引入 preview registry
- 主视图只负责调度，不负责每种类型的细节渲染

## Required Design Adjustments

即使暂时只做 PDF，也建议提前补两个结构点：

### 1. 返回 MIME Type

当前前端对图片仍主要按扩展名识别。继续扩展 PDF 及更多类型时，建议后端返回明确的 `mime_type`，避免长期依赖扩展名判断。

建议优先级：

- 高

原因：

- 扩展名不可靠
- MIME 更适合做 preview dispatch

### 2. 通用 Tab / Preview 类型

当前标签体系已经从纯文本扩展到了图片，但如果继续新增 PDF、视频、音频，建议把 tab/view model 抽象成通用 preview type，而不是继续堆 `if/else`。

建议优先级：

- 中高

原因：

- 可以控制后续复杂度
- 让主视图逻辑更稳定

## Current Recommendation

当前推荐路线：

1. 先做 `PDF 第一版`
2. PDF 第一版使用浏览器原生预览
3. 同时补 `mime_type` 返回
4. 随后再做 preview registry 抽象

这个路线的优点是：

- 交付快
- 风险低
- 不会把问题一次性扩大成“全格式预览平台”

## Out Of Scope For This Item

本条方案暂不包含：

- `docx/xlsx/pptx` 浏览器内高保真预览
- 服务端转 PDF / 转图片
- 文档全文检索
- PDF 批注和画笔能力
- 多文件预览工作台

## Decision Snapshot

结论先记录为：

- `PDF` 支持可做，复杂度可控
- 第一版应走原生 PDF 预览，而不是直接上 `pdf.js`
- 架构上应开始向 MIME 分发和通用 preview registry 演进
