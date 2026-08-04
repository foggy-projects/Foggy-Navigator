<template>
  <el-dialog
    :model-value="modelValue"
    width="640px"
    class="fap-start-dialog"
    :close-on-click-modal="false"
    destroy-on-close
    @update:model-value="$emit('update:modelValue', $event)"
  >
    <template #header>
      <div class="dialog-title">
        <span>FAP_V1 · NEW</span>
        <h2>建立新架构会话</h2>
        <p>资源由 Internal Access 授权；创建后不会切回旧版链路。</p>
      </div>
    </template>

    <el-form label-position="top" class="start-form">
      <div class="resource-grid">
        <el-form-item label="Worker Profile" required>
          <el-select
            v-model="form.workerProfileRef"
            filterable
            :loading="catalogLoading"
            placeholder="选择 Worker"
          >
            <el-option
              v-for="entry in workerProfiles"
              :key="entry.resourceRef"
              :label="entry.displayName"
              :value="entry.resourceRef"
              :disabled="!entry.available"
            >
              <span>{{ entry.displayName }}</span>
              <small v-if="!entry.available">{{ entry.reasonCode || '不可用' }}</small>
            </el-option>
          </el-select>
        </el-form-item>

        <el-form-item label="Workspace" required>
          <el-select
            v-model="form.workspaceRef"
            filterable
            :loading="catalogLoading"
            placeholder="选择工作目录"
          >
            <el-option
              v-for="entry in workspaces"
              :key="entry.resourceRef"
              :label="entry.displayName"
              :value="entry.resourceRef"
              :disabled="!entry.available"
            />
          </el-select>
        </el-form-item>
      </div>

      <div class="model-row">
        <el-form-item label="Model Config" :required="!form.allowDefaultModelConfig">
          <el-select
            v-model="form.modelConfigRef"
            clearable
            filterable
            :loading="catalogLoading"
            :placeholder="form.allowDefaultModelConfig ? '可留空，使用默认配置' : '必须指定模型配置'"
          >
            <el-option
              v-for="entry in modelConfigs"
              :key="entry.resourceRef"
              :label="entry.displayName"
              :value="entry.resourceRef"
              :disabled="!entry.available"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="Reasoning">
          <el-select v-model="form.reasoningEffort" placeholder="Worker 默认">
            <el-option label="Worker 默认" value="" />
            <el-option label="Medium" value="medium" />
            <el-option label="High" value="high" />
            <el-option label="XHigh" value="xhigh" />
          </el-select>
        </el-form-item>
      </div>

      <div class="default-policy">
        <el-switch v-model="form.allowDefaultModelConfig" />
        <div>
          <strong>允许默认 ModelConfig</strong>
          <span>关闭后，调用必须显式携带上方模型配置。</span>
        </div>
      </div>

      <el-form-item label="标题（可选）">
        <el-input v-model="form.title" maxlength="256" placeholder="留空时从首条任务生成" />
      </el-form-item>

      <el-form-item label="首条任务" required>
        <el-input
          v-model="form.prompt"
          type="textarea"
          :rows="7"
          resize="vertical"
          maxlength="200000"
          show-word-limit
          placeholder="描述需要 Worker 执行的具体工作……"
          @keydown.meta.enter.prevent="submit"
          @keydown.ctrl.enter.prevent="submit"
        />
      </el-form-item>
    </el-form>

    <template #footer>
      <div class="dialog-footer">
        <span>Ctrl / ⌘ + Enter 创建</span>
        <div>
          <el-button @click="$emit('update:modelValue', false)">取消</el-button>
          <el-button type="primary" :loading="pending" @click="submit">创建并运行</el-button>
        </div>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { reactive, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { FapCatalogEntry } from '@/api/workbenchFap'

const props = defineProps<{
  modelValue: boolean
  workerProfiles: FapCatalogEntry[]
  workspaces: FapCatalogEntry[]
  modelConfigs: FapCatalogEntry[]
  catalogLoading?: boolean
  pending?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  submit: [value: {
    title?: string
    workerProfileRef: string
    workspaceRef: string
    modelConfigRef?: string
    allowDefaultModelConfig: boolean
    prompt: string
    reasoningEffort?: string
  }]
}>()

const form = reactive({
  title: '',
  workerProfileRef: '',
  workspaceRef: '',
  modelConfigRef: '',
  allowDefaultModelConfig: true,
  prompt: '',
  reasoningEffort: 'high',
})

watch(
  () => props.modelValue,
  (open) => {
    if (!open) return
    if (!form.workerProfileRef) form.workerProfileRef = singleAvailable(props.workerProfiles)
    if (!form.workspaceRef) form.workspaceRef = singleAvailable(props.workspaces)
  },
)

function submit(): void {
  if (!form.workerProfileRef) {
    ElMessage.warning('请选择 Worker Profile')
    return
  }
  if (!form.workspaceRef) {
    ElMessage.warning('请选择 Workspace')
    return
  }
  if (!form.allowDefaultModelConfig && !form.modelConfigRef) {
    ElMessage.warning('关闭默认模型后，必须指定 ModelConfig')
    return
  }
  if (!form.prompt.trim()) {
    ElMessage.warning('请输入首条任务')
    return
  }
  emit('submit', {
    title: form.title.trim() || undefined,
    workerProfileRef: form.workerProfileRef,
    workspaceRef: form.workspaceRef,
    modelConfigRef: form.modelConfigRef || undefined,
    allowDefaultModelConfig: form.allowDefaultModelConfig,
    prompt: form.prompt.trim(),
    reasoningEffort: form.reasoningEffort || undefined,
  })
}

function singleAvailable(entries: FapCatalogEntry[]): string {
  const available = entries.filter((entry) => entry.available)
  return available.length === 1 ? available[0]?.resourceRef ?? '' : ''
}
</script>

<style scoped>
.dialog-title span {
  display: block;
  margin-bottom: 6px;
  color: #27766b;
  font: 600 10px/1.3 "IBM Plex Mono", "Noto Sans Mono", monospace;
  letter-spacing: 0.12em;
}

.dialog-title h2 {
  margin: 0;
  color: #252a26;
  font: 650 21px/1.3 "IBM Plex Sans", "Noto Sans SC", sans-serif;
}

.dialog-title p {
  margin: 5px 0 0;
  color: #818780;
  font-size: 12px;
}

.start-form { padding-top: 4px; }

.resource-grid,
.model-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}

.start-form :deep(.el-select) { width: 100%; }

.start-form :deep(.el-form-item__label) {
  padding-bottom: 6px;
  color: #515851;
  font: 600 11px/1.3 "IBM Plex Mono", "Noto Sans Mono", monospace;
}

.default-policy {
  margin: -2px 0 18px;
  padding: 10px 12px;
  display: flex;
  align-items: center;
  gap: 10px;
  background: #f4f6f2;
  border: 1px solid #e0e4de;
  border-radius: 4px;
}

.default-policy div {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.default-policy strong {
  color: #404740;
  font-size: 12px;
}

.default-policy span {
  color: #878e86;
  font-size: 11px;
}

.dialog-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.dialog-footer > span {
  color: #999f98;
  font: 10px/1.3 "IBM Plex Mono", monospace;
}

@media (max-width: 680px) {
  .resource-grid,
  .model-row { grid-template-columns: 1fr; gap: 0; }
}
</style>
