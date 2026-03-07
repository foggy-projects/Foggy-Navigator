<template>
  <div class="setup-container">
    <div class="setup-card">
      <h1 class="setup-title">Foggy Navigator 初始化配置</h1>
      <p class="setup-desc">完成以下配置后即可开始使用</p>

      <!-- Steps -->
      <el-steps :active="currentStep" finish-status="success" align-center class="setup-steps">
        <el-step title="Git 提供者" />
        <el-step title="AI 模型" />
        <el-step title="Workers" />
      </el-steps>

      <!-- Step 1: Git Provider -->
      <div v-show="currentStep === 0" class="step-content">
        <p class="step-hint">配置至少一个 Git 提供者（GitHub / GitLab / Gitee）</p>

        <div v-for="(provider, idx) in gitProviders" :key="idx" class="provider-block">
          <div class="provider-header">
            <el-tag>{{ provider.providerType }}</el-tag>
            <el-button text type="danger" size="small" @click="removeGitProvider(idx)">移除</el-button>
          </div>
          <el-form :model="provider" label-position="top" size="default">
            <el-form-item label="访问令牌" required>
              <el-input v-model="provider.accessToken" type="password" show-password placeholder="Personal Access Token" />
            </el-form-item>
            <el-form-item v-if="provider.providerType === 'GITLAB'" label="服务地址">
              <el-input v-model="provider.baseUrl" placeholder="如 https://gitlab.example.com" />
            </el-form-item>
            <el-form-item label="用户名（可选）">
              <el-input v-model="provider.username" placeholder="Git 用户名" />
            </el-form-item>
          </el-form>
        </div>

        <div class="add-provider">
          <el-dropdown @command="addGitProvider">
            <el-button type="primary" plain>+ 添加 Git 提供者</el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="GITHUB">GitHub</el-dropdown-item>
                <el-dropdown-item command="GITLAB">GitLab</el-dropdown-item>
                <el-dropdown-item command="GITEE">Gitee</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </div>

      <!-- Step 2: LLM Model -->
      <div v-show="currentStep === 1" class="step-content">
        <p class="step-hint">配置至少一个 AI 模型，用于 Agent 对话</p>

        <!-- Presets -->
        <div class="preset-bar">
          <span class="preset-label">快速填充：</span>
          <el-button v-for="preset in llmPresets" :key="preset.name" size="small" @click="applyPreset(preset)">
            {{ preset.name }}
          </el-button>
        </div>

        <div v-for="(model, idx) in llmModels" :key="idx" class="model-block">
          <div class="provider-header">
            <el-tag>{{ model.category }}</el-tag>
            <el-button text type="danger" size="small" @click="removeLlmModel(idx)">移除</el-button>
          </div>
          <el-form :model="model" label-position="top" size="default">
            <el-row :gutter="12">
              <el-col :span="12">
                <el-form-item label="显示名称" required>
                  <el-input v-model="model.name" placeholder="如：通义千问-Max" />
                </el-form-item>
              </el-col>
              <el-col :span="12">
                <el-form-item label="模型类别" required>
                  <el-select v-model="model.category" style="width: 100%">
                    <el-option label="通用" value="GENERAL" />
                    <el-option label="编程" value="CODING" />
                    <el-option label="推理" value="REASONING" />
                    <el-option label="视觉" value="VISION" />
                  </el-select>
                </el-form-item>
              </el-col>
            </el-row>
            <el-form-item label="API Base URL" required>
              <el-input v-model="model.baseUrl" placeholder="如：https://dashscope.aliyuncs.com/compatible-mode/v1" />
            </el-form-item>
            <el-row :gutter="12">
              <el-col :span="12">
                <el-form-item label="模型名称" required>
                  <el-input v-model="model.modelName" placeholder="如：qwen-max" />
                </el-form-item>
              </el-col>
              <el-col :span="12">
                <el-form-item label="API Key" required>
                  <el-input v-model="model.apiKey" type="password" show-password placeholder="API Key" />
                </el-form-item>
              </el-col>
            </el-row>
            <el-form-item>
              <el-checkbox v-model="model.isDefault">设为该类别的默认模型</el-checkbox>
              <el-button
                style="margin-left: 16px"
                :loading="model.testing"
                :disabled="!model.baseUrl || !model.modelName || !model.apiKey"
                @click="handleTestModel(model)"
              >测试连接</el-button>
            </el-form-item>
          </el-form>
        </div>

        <el-button type="primary" plain @click="addLlmModel">+ 添加模型</el-button>
      </div>

      <!-- Step 3: Workers (Claude + Codex, Optional) -->
      <div v-show="currentStep === 2" class="step-content">
        <p class="step-hint">
          配置 Worker 以集成远程编程能力（可选，可稍后在设置中配置）
        </p>

        <div v-for="(worker, idx) in claudeWorkers" :key="idx" class="model-block">
          <div class="provider-header">
            <span>
              <el-tag :type="worker.workerType === 'CODEX' ? 'success' : ''" size="small" style="margin-right: 6px">
                {{ worker.workerType === 'CODEX' ? 'Codex' : 'Claude' }}
              </el-tag>
              Worker #{{ idx + 1 }}
            </span>
            <el-button text type="danger" size="small" @click="claudeWorkers.splice(idx, 1)">移除</el-button>
          </div>
          <el-form :model="worker" label-position="top" size="default">
            <el-form-item label="名称" required>
              <el-input v-model="worker.name" :placeholder="worker.workerType === 'CODEX' ? '如：Codex Worker' : '如：我的家庭机'" />
            </el-form-item>
            <el-form-item label="地址" required>
              <el-input v-model="worker.baseUrl" :placeholder="worker.workerType === 'CODEX' ? '如：http://localhost:3032' : '如：http://192.168.1.100:3031'" />
            </el-form-item>
            <el-form-item label="认证令牌" :required="worker.workerType === 'CLAUDE'">
              <el-input v-model="worker.authToken" type="password" show-password placeholder="Worker 预共享令牌" />
            </el-form-item>
            <template v-if="worker.workerType === 'CLAUDE'">
              <el-form-item label="认证模式">
                <el-select v-model="worker.authMode" style="width: 100%">
                  <el-option label="订阅模式 (Claude Max)" value="SUBSCRIPTION" />
                  <el-option label="API Key 模式" value="API_KEY" />
                  <el-option label="自定义端点" value="CUSTOM_ENDPOINT" />
                </el-select>
              </el-form-item>
            </template>
          </el-form>
        </div>

        <div style="display: flex; gap: 8px">
          <el-button type="primary" plain @click="addClaudeWorker">+ Claude Worker</el-button>
          <el-button type="success" plain @click="addCodexWorker">+ Codex Worker</el-button>
        </div>
      </div>

      <!-- Footer Buttons -->
      <div class="step-footer">
        <el-button v-if="currentStep > 0" @click="currentStep--">上一步</el-button>
        <div class="footer-right">
          <el-button v-if="currentStep === 2" @click="handleSkipAndFinish">跳过并完成</el-button>
          <el-button
            v-if="currentStep < 2"
            type="primary"
            :disabled="!canProceed"
            @click="handleNext"
          >
            下一步
          </el-button>
          <el-button
            v-if="currentStep === 2"
            type="primary"
            :loading="saving"
            @click="handleFinish"
          >
            完成配置
          </el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { resetSetupStatus } from '@/router'
import { ElMessage } from 'element-plus'
import { saveGitProvider, saveModelConfig, testLlmConnection } from '@/api/platform'
import { registerWorker } from '@/api/claudeWorker'
import { registerCodexWorker } from '@/api/codexWorker'
import type { GitProviderType, LlmModelCategory, WorkerType } from '@/types'

const router = useRouter()
const currentStep = ref(0)
const saving = ref(false)

// ===== Step 1: Git =====

interface GitForm {
  providerType: GitProviderType
  baseUrl: string
  accessToken: string
  username: string
}

const gitProviders = ref<GitForm[]>([])

function addGitProvider(type: GitProviderType) {
  gitProviders.value.push({
    providerType: type,
    baseUrl: '',
    accessToken: '',
    username: '',
  })
}

function removeGitProvider(idx: number) {
  gitProviders.value.splice(idx, 1)
}

// ===== Step 2: LLM =====

interface LlmForm {
  name: string
  category: LlmModelCategory
  baseUrl: string
  modelName: string
  apiKey: string
  isDefault: boolean
  testing: boolean
}

const llmModels = ref<LlmForm[]>([])

const llmPresets = [
  {
    name: '阿里通义',
    category: 'GENERAL' as LlmModelCategory,
    displayName: '通义千问-Max',
    baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
    modelName: 'qwen-max',
  },
  {
    name: 'DeepSeek',
    category: 'REASONING' as LlmModelCategory,
    displayName: 'DeepSeek-R1',
    baseUrl: 'https://api.deepseek.com/v1',
    modelName: 'deepseek-reasoner',
  },
  {
    name: 'OpenAI',
    category: 'GENERAL' as LlmModelCategory,
    displayName: 'GPT-4o',
    baseUrl: 'https://api.openai.com/v1',
    modelName: 'gpt-4o',
  },
]

function applyPreset(preset: (typeof llmPresets)[number]) {
  llmModels.value.push({
    name: preset.displayName,
    category: preset.category,
    baseUrl: preset.baseUrl,
    modelName: preset.modelName,
    apiKey: '',
    isDefault: llmModels.value.length === 0,
    testing: false,
  })
}

function addLlmModel() {
  llmModels.value.push({
    name: '',
    category: 'GENERAL',
    baseUrl: '',
    modelName: '',
    apiKey: '',
    isDefault: llmModels.value.length === 0,
    testing: false,
  })
}

function removeLlmModel(idx: number) {
  llmModels.value.splice(idx, 1)
}

async function handleTestModel(model: LlmForm) {
  model.testing = true
  try {
    const reply = await testLlmConnection({
      baseUrl: model.baseUrl,
      apiKey: model.apiKey,
      modelName: model.modelName,
    })
    ElMessage.success('连接成功: ' + (reply || 'OK'))
  } catch (e: any) {
    ElMessage.error('连接失败: ' + (e?.response?.data?.msg || e?.message || '未知错误'))
  } finally {
    model.testing = false
  }
}

// ===== Step 3: Workers (Claude + Codex) =====

interface WorkerForm {
  workerType: WorkerType
  name: string
  baseUrl: string
  authToken: string
  authMode: string
}

const claudeWorkers = ref<WorkerForm[]>([])

function addClaudeWorker() {
  claudeWorkers.value.push({
    workerType: 'CLAUDE',
    name: '',
    baseUrl: '',
    authToken: '',
    authMode: 'SUBSCRIPTION',
  })
}

function addCodexWorker() {
  claudeWorkers.value.push({
    workerType: 'CODEX',
    name: '',
    baseUrl: 'http://localhost:3032',
    authToken: '',
    authMode: '',
  })
}

// ===== Navigation =====

const canProceed = computed(() => {
  if (currentStep.value === 0) {
    return gitProviders.value.length > 0 && gitProviders.value.every((p) => p.accessToken)
  }
  if (currentStep.value === 1) {
    return (
      llmModels.value.length > 0 &&
      llmModels.value.every((m) => m.name && m.baseUrl && m.modelName && m.apiKey)
    )
  }
  return true
})

async function handleNext() {
  if (currentStep.value === 0) {
    // Save Git providers
    saving.value = true
    try {
      for (const p of gitProviders.value) {
        await saveGitProvider({
          providerType: p.providerType,
          baseUrl: p.baseUrl || undefined,
          accessToken: p.accessToken,
          username: p.username || undefined,
        })
      }
      currentStep.value++
    } catch {
      ElMessage.error('Git 配置保存失败')
    } finally {
      saving.value = false
    }
  } else if (currentStep.value === 1) {
    // Save LLM models
    saving.value = true
    try {
      for (const m of llmModels.value) {
        await saveModelConfig({
          name: m.name,
          category: m.category,
          baseUrl: m.baseUrl,
          modelName: m.modelName,
          apiKey: m.apiKey,
          isDefault: m.isDefault,
        })
      }
      currentStep.value++
    } catch {
      ElMessage.error('AI 模型配置保存失败')
    } finally {
      saving.value = false
    }
  }
}

async function handleFinish() {
  saving.value = true
  try {
    for (const w of claudeWorkers.value) {
      if (w.workerType === 'CODEX') {
        if (w.name && w.baseUrl) {
          await registerCodexWorker({ name: w.name, baseUrl: w.baseUrl, authToken: w.authToken || undefined })
        }
      } else {
        if (w.name && w.baseUrl && w.authToken) {
          await registerWorker(w)
        }
      }
    }
    ElMessage.success('配置完成！')
    resetSetupStatus()
    router.push('/')
  } catch {
    ElMessage.error('Worker 保存失败')
  } finally {
    saving.value = false
  }
}

function handleSkipAndFinish() {
  ElMessage.success('配置完成！')
  resetSetupStatus()
  router.push('/')
}
</script>

<style scoped>
.setup-container {
  min-height: 100vh;
  display: flex;
  justify-content: center;
  align-items: flex-start;
  padding: 40px 20px;
  background: #f5f7fa;
}

.setup-card {
  width: 720px;
  background: #fff;
  border-radius: 12px;
  padding: 32px 40px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.08);
}

.setup-title {
  text-align: center;
  font-size: 24px;
  margin: 0 0 4px;
  color: #303133;
}

.setup-desc {
  text-align: center;
  color: #909399;
  margin: 0 0 24px;
  font-size: 14px;
}

.setup-steps {
  margin-bottom: 28px;
}

.step-content {
  min-height: 200px;
}

.step-hint {
  color: #606266;
  font-size: 14px;
  margin: 0 0 16px;
}

.provider-block,
.model-block {
  background: #fafafa;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 16px;
  margin-bottom: 12px;
}

.provider-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.add-provider {
  margin-top: 8px;
}

.preset-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}

.preset-label {
  font-size: 13px;
  color: #909399;
}

.step-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 24px;
  padding-top: 20px;
  border-top: 1px solid #ebeef5;
}

.footer-right {
  display: flex;
  gap: 8px;
  margin-left: auto;
}
</style>
