<template>
  <div class="tool-call-block">
    <div class="tool-header">
      <span class="tool-icon">&#9881;</span>
      <span class="tool-name">{{ props.message.toolName || '工具调用' }}</span>
      <span v-if="props.message.thought" class="tool-thought">{{ props.message.thought }}</span>
    </div>
    <div v-if="props.message.content" class="tool-command">
      <div class="code-label">命令</div>
      <pre class="code-block"><code>{{ props.message.content }}</code></pre>
    </div>
    <div v-if="props.message.toolOutput" class="tool-output">
      <div class="code-label">输出</div>
      <pre class="code-block output"><code>{{ props.message.toolOutput }}</code></pre>
    </div>
    <div v-if="props.message.error" class="tool-error">
      <div class="code-label">错误</div>
      <pre class="code-block error"><code>{{ props.message.error }}</code></pre>
    </div>
  </div>
</template>

<script setup lang="ts">
import type { ChatMessage } from '../types/chat'

const props = defineProps<{
  message: ChatMessage
}>()
</script>

<style scoped>
.tool-call-block {
  margin-bottom: 12px;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  overflow: hidden;
  max-width: 90%;
}

.tool-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background-color: #fafafa;
  border-bottom: 1px solid #e4e7ed;
  font-size: 13px;
}

.tool-icon {
  font-size: 14px;
}

.tool-name {
  font-weight: 600;
  color: #303133;
}

.tool-thought {
  color: #909399;
  font-style: italic;
  font-size: 12px;
  margin-left: auto;
}

.code-label {
  padding: 4px 12px;
  font-size: 11px;
  color: #909399;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.code-block {
  margin: 0;
  padding: 8px 12px;
  background-color: #1e1e1e;
  color: #d4d4d4;
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 13px;
  line-height: 1.5;
  overflow-x: auto;
  max-height: 300px;
  overflow-y: auto;
}

.code-block.output {
  background-color: #1a2332;
  color: #a9d4a0;
}

.code-block.error {
  background-color: #2d1a1a;
  color: #f48771;
}

.tool-command,
.tool-output,
.tool-error {
  border-top: 1px solid #e4e7ed;
}
</style>
