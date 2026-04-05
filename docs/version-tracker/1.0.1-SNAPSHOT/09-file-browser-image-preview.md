# 09 File Browser Image Preview

## Date

- 2026-04-05

## Type

- Requirement
- UX Fix

## Background

文件浏览器页面当前只能用 Monaco 预览文本文件。用户在下列页面打开 PNG 截图时，会落入“二进制文件，无法预览”的占位提示：

- `http://dev-kvm-jdk17.foggysource.com/#/files?directoryId=20260305-fe66&workerId=9bb67974`

本次反馈的具体现象来自截图目录中的：

- `docs/8.1.10.beta/P1-QM前端组件体系/screenshots/06-data-viewer-final-accept.png`

期望是：点击图片文件后，右侧主区域直接可预览，而不是只能看到二进制提示。

## Problem

当前文件浏览器的读取链路只有一条：

1. 前端调用 `/api/v1/file-browser/content`
2. Java 代理透传到 Worker `/api/v1/files/content`
3. Worker 只返回“文本内容 + 二进制标记 + 语言信息”

因此：

- 文本文件可以进入 Monaco
- 图片、PDF、压缩包等二进制文件都会被统一拦截为“无法预览”
- 前端没有可用的原始文件流地址，也没有图片预览视图

## Decision

先按最小闭环支持“图片打开即预览”，不在本次扩展为通用二进制预览平台。

本次收敛方案：

1. Worker 新增 `/api/v1/files/raw`，返回原始文件字节流和 MIME 类型
2. Java 文件浏览器代理新增 `/api/v1/file-browser/raw`
3. 前端对常见图片扩展名走 raw 接口读取 `Blob`
4. 文件浏览器主区域新增图片预览视图，并继续保留文本标签页能力
5. 非图片二进制文件仍保持“无法预览”

## Scope

涉及模块：

- `tools/claude-agent-worker`
- `addons/claude-worker-agent`
- `packages/navigator-frontend`

本次不包含：

- PDF 内嵌预览
- 音视频播放
- Office 文档预览
- 通用下载中心/附件服务抽象

## Notes

- Java `WebClient` 原有 `maxInMemorySize=4MB` 无法稳定承接较大的图片预览，本次需要一并上调，保证 raw 预览链路可用。
- raw 预览只用于当前已登录用户的文件浏览器访问，仍保持经过 Java 代理，不让前端直连 Worker。
