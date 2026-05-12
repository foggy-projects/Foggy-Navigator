<template>
  <details class="nc-skill-frame" open>
    <summary class="nc-frame-summary">
      <span class="nc-frame-title">
        <el-icon v-if="frame.status === 'running'" class="is-loading"><Loading /></el-icon>
        <span>技能 {{ frame.displayName }}</span>
      </span>
      <span :class="['nc-frame-status', `nc-frame-status--${frame.status}`]">
        {{ frameStatusLabel(frame.status) }}
      </span>
      <span v-if="frame.durationMs != null" class="nc-frame-duration">
        {{ (frame.durationMs / 1000).toFixed(1) }}s
      </span>
    </summary>

    <div class="nc-frame-body">
      <div v-if="frame.openContent || frame.closeContent" class="nc-frame-note">
        {{ frame.closeContent || frame.openContent }}
      </div>

      <div v-if="frame.toolExecutions.length" class="nc-frame-tools">
        <details
          v-for="tool in frame.toolExecutions"
          :key="tool.toolCallId"
          class="nc-frame-tool"
          :open="tool.status === 'running'"
        >
          <summary class="nc-tool-summary-row">
            <span class="nc-tool-title">
              <el-icon v-if="tool.status === 'running'" class="is-loading"><Loading /></el-icon>
              <span>工具 {{ tool.displayName }}</span>
            </span>
            <span :class="['nc-tool-status', `nc-tool-status--${tool.status}`]">
              {{ toolStatusLabel(tool.status) }}
            </span>
            <span v-if="tool.durationMs != null" class="nc-tool-duration">
              {{ (tool.durationMs / 1000).toFixed(1) }}s
            </span>
          </summary>
          <div class="nc-tool-body">
            <div class="nc-tool-summary-text">
              摘要：{{ tool.summary.join(', ') }}
            </div>
            <details class="nc-nested">
              <summary>参数</summary>
              <pre>{{ formatJson(tool.args) }}</pre>
            </details>
            <details class="nc-nested">
              <summary>结果</summary>
              <pre>{{ formatJson(tool.result ?? tool.error) }}</pre>
            </details>
            <details class="nc-nested">
              <summary>排障字段</summary>
              <pre>{{ formatJson(tool.trace) }}</pre>
            </details>
            <details class="nc-nested">
              <summary>原始 JSON</summary>
              <pre>{{ formatJson({ call: tool.rawCall, result: tool.rawResult }) }}</pre>
            </details>
          </div>
        </details>
      </div>

      <div v-if="frame.children.length" class="nc-frame-children">
        <SkillFrameBlockView
          v-for="child in frame.children"
          :key="child.frameId"
          :frame="child"
        />
      </div>

      <details class="nc-nested">
        <summary>Frame 原始 JSON</summary>
        <pre>{{ formatJson({ open: frame.rawOpen, close: frame.rawClose, trace: frame.trace }) }}</pre>
      </details>
    </div>
  </details>
</template>

<script setup lang="ts">
import { ElIcon } from 'element-plus'
import { Loading } from '@element-plus/icons-vue'
import type { SkillFrameBlock, ToolExecutionBlock } from '../types'

defineOptions({ name: 'SkillFrameBlockView' })

defineProps<{
  frame: SkillFrameBlock
}>()

function frameStatusLabel(status: SkillFrameBlock['status']): string {
  switch (status) {
    case 'running': return '执行中'
    case 'success': return '完成'
    case 'failed': return '失败'
  }
}

function toolStatusLabel(status: ToolExecutionBlock['status']): string {
  switch (status) {
    case 'running': return '执行中'
    case 'success': return '成功'
    case 'failed': return '失败'
    case 'skipped': return '已跳过'
    case 'cancelled': return '已取消'
  }
}

function formatJson(value: unknown): string {
  if (value == null || value === '') return '无'
  if (typeof value === 'string') return value
  return JSON.stringify(value, null, 2)
}
</script>

<style scoped>
.nc-skill-frame {
  width: 100%;
  padding: 8px 10px;
  border: 1px solid var(--el-border-color-lighter, #ebeef5);
  border-radius: 6px;
  background: var(--el-fill-color-blank, #fff);
  color: var(--el-text-color-secondary, #909399);
  font-size: 12px;
}

.nc-frame-summary,
.nc-tool-summary-row {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  user-select: none;
}

.nc-frame-title,
.nc-tool-title {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  color: var(--el-text-color-regular, #606266);
  font-weight: 600;
}

.nc-frame-status,
.nc-tool-status {
  flex-shrink: 0;
  padding: 1px 6px;
  border-radius: 4px;
  font-size: 11px;
  background: var(--el-fill-color-light, #f4f4f5);
  color: var(--el-text-color-secondary, #909399);
}

.nc-frame-status--success,
.nc-tool-status--success {
  background: var(--el-color-success-light-9, #f0f9eb);
  color: var(--el-color-success, #67c23a);
}

.nc-frame-status--failed,
.nc-tool-status--failed {
  background: var(--el-color-danger-light-9, #fef0f0);
  color: var(--el-color-danger, #f56c6c);
}

.nc-frame-status--running,
.nc-tool-status--running {
  background: var(--el-color-primary-light-9, #ecf5ff);
  color: var(--el-color-primary, #409eff);
}

.nc-tool-status--skipped,
.nc-tool-status--cancelled {
  background: var(--el-fill-color-light, #f4f4f5);
  color: var(--el-text-color-secondary, #909399);
}

.nc-frame-duration,
.nc-tool-duration {
  flex-shrink: 0;
  color: var(--el-text-color-placeholder, #a8abb2);
}

.nc-frame-body,
.nc-tool-body {
  margin-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.nc-frame-note,
.nc-tool-summary-text {
  color: var(--el-text-color-regular, #606266);
}

.nc-frame-tools,
.nc-frame-children {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.nc-frame-tool {
  padding: 6px 8px;
  border: 1px solid var(--el-border-color-extra-light, #f2f6fc);
  border-radius: 6px;
  background: var(--el-fill-color-extra-light, #fafafa);
}

.nc-frame-children {
  padding-left: 10px;
  border-left: 2px solid var(--el-border-color-extra-light, #f2f6fc);
}

.nc-nested {
  border-top: 1px solid var(--el-border-color-extra-light, #f2f6fc);
  padding-top: 6px;
}

.nc-nested summary {
  cursor: pointer;
  user-select: none;
  font-weight: 500;
  color: var(--el-text-color-secondary, #909399);
}

pre {
  margin: 8px 0 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: 'Fira Code', Consolas, monospace;
  font-size: 12px;
}
</style>
