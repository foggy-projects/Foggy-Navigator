<template>
  <div class="settings-layout">
    <div class="settings-header">
      <h2>系统设置</h2>
      <el-button text @click="router.push('/')">
        <el-icon><Back /></el-icon> 返回会话
      </el-button>
    </div>

    <el-tabs v-model="activeTab" class="settings-tabs">
      <!-- Tab 1: Git Providers -->
      <el-tab-pane label="Git 提供者" name="git">
        <div class="tab-toolbar">
          <el-dropdown @command="handleAddGit">
            <el-button type="primary" size="small">+ 添加</el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="GITHUB">GitHub</el-dropdown-item>
                <el-dropdown-item command="GITLAB">GitLab</el-dropdown-item>
                <el-dropdown-item command="GITEE">Gitee</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>

        <el-table :data="gitProviders" v-loading="loadingGit" stripe>
          <el-table-column prop="providerType" label="类型" width="120" />
          <el-table-column prop="baseUrl" label="服务地址" min-width="200">
            <template #default="{ row }">{{ row.baseUrl || '默认' }}</template>
          </el-table-column>
          <el-table-column prop="username" label="用户名" width="150">
            <template #default="{ row }">{{ row.username || '-' }}</template>
          </el-table-column>
          <el-table-column label="Token" width="80" align="center">
            <template #default="{ row }">
              <el-tag :type="row.hasToken ? 'success' : 'danger'" size="small">
                {{ row.hasToken ? '已配置' : '未配置' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="80" align="center">
            <template #default="{ row }">
              <el-tag :type="row.isActive ? 'success' : 'info'" size="small">
                {{ row.isActive ? '启用' : '停用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="140" align="center">
            <template #default="{ row }">
              <el-button text size="small" @click="editGitProvider(row)">编辑</el-button>
              <el-button text type="danger" size="small" @click="deleteGit(row.id)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- Tab 2: AI Models -->
      <el-tab-pane label="AI 模型" name="llm">
        <div class="tab-toolbar">
          <div class="toolbar-left">
            <el-button type="primary" size="small" @click="showLlmDialog('add')">+ 添加</el-button>
            <span class="preset-label">快速填充：</span>
            <el-button v-for="preset in llmPresets" :key="preset.name" size="small" @click="applyPreset(preset)">
              {{ preset.name }}
            </el-button>
          </div>
        </div>

        <el-table :data="llmModels" v-loading="loadingLlm" stripe>
          <el-table-column prop="name" label="名称" min-width="140" />
          <el-table-column prop="category" label="类别" width="100">
            <template #default="{ row }">
              <el-tag size="small">{{ categoryLabel(row.category) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="modelName" label="模型" width="160" />
          <el-table-column prop="baseUrl" label="API 地址" min-width="200" show-overflow-tooltip />
          <el-table-column label="默认" width="70" align="center">
            <template #default="{ row }">
              <span v-if="row.isDefault" style="color: #67c23a">&#10003;</span>
            </template>
          </el-table-column>
          <el-table-column label="API Key" width="80" align="center">
            <template #default="{ row }">
              <el-tag :type="row.hasApiKey ? 'success' : 'danger'" size="small">
                {{ row.hasApiKey ? '已配置' : '未配置' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="200" align="center">
            <template #default="{ row }">
              <el-button text size="small" :loading="row._testing" @click="handleTestSaved(row)">测试</el-button>
              <el-button text size="small" @click="editLlmModel(row)">编辑</el-button>
              <el-button text type="danger" size="small" @click="deleteLlm(row.id)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- Tab 3: Agent Model Overrides -->
      <el-tab-pane label="Agent 模型" name="agent-model">
        <div class="tab-toolbar">
          <el-button type="primary" size="small" @click="showOverrideDialog = true">+ 添加覆盖</el-button>
        </div>

        <el-table :data="agentOverrides" v-loading="loadingOverrides" stripe>
          <el-table-column prop="agentId" label="Agent ID" min-width="200" />
          <el-table-column label="使用模型" min-width="200">
            <template #default="{ row }">
              {{ resolveModelName(row.modelConfigId) }}
            </template>
          </el-table-column>
          <el-table-column label="操作" width="100" align="center">
            <template #default="{ row }">
              <el-button text type="danger" size="small" @click="deleteOverride(row.agentId)">移除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- Tab 4: Claude Workers -->
      <el-tab-pane label="Claude Workers" name="workers">
        <div class="tab-toolbar">
          <el-button type="primary" size="small" @click="showWorkerAddDialog = true">+ 添加 Worker</el-button>
        </div>

        <el-table :data="workers" v-loading="loadingWorkers" stripe>
          <el-table-column label="状态" width="60" align="center">
            <template #default="{ row }">
              <span :class="['status-dot', row.status?.toLowerCase()]" />
            </template>
          </el-table-column>
          <el-table-column prop="name" label="名称" min-width="140" />
          <el-table-column prop="baseUrl" label="地址" min-width="220" show-overflow-tooltip />
          <el-table-column prop="authMode" label="模式" width="140" />
          <el-table-column label="操作" width="200" align="center">
            <template #default="{ row }">
              <el-button text size="small" @click="handleWorkerHealthCheck(row.workerId)">检测</el-button>
              <el-button text size="small" @click="editWorker(row)">编辑</el-button>
              <el-button text type="danger" size="small" @click="deleteWorkerById(row.workerId)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <!-- Git Provider Dialog -->
    <el-dialog v-model="showGitDialog" :title="gitDialogMode === 'add' ? '添加 Git 提供者' : '编辑 Git 提供者'" width="480px">
      <el-form :model="gitForm" label-position="top">
        <el-form-item label="类型">
          <el-select v-model="gitForm.providerType" :disabled="gitDialogMode === 'edit'" style="width: 100%">
            <el-option label="GitHub" value="GITHUB" />
            <el-option label="GitLab" value="GITLAB" />
            <el-option label="Gitee" value="GITEE" />
          </el-select>
        </el-form-item>
        <el-form-item label="访问令牌" required>
          <el-input v-model="gitForm.accessToken" type="password" show-password :placeholder="gitDialogMode === 'edit' ? '留空保持不变' : 'Personal Access Token'" />
        </el-form-item>
        <el-form-item v-if="gitForm.providerType === 'GITLAB'" label="服务地址">
          <el-input v-model="gitForm.baseUrl" placeholder="如 https://gitlab.example.com" />
        </el-form-item>
        <el-form-item label="用户名（可选）">
          <el-input v-model="gitForm.username" placeholder="Git 用户名" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showGitDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveGit">保存</el-button>
      </template>
    </el-dialog>

    <!-- LLM Model Dialog -->
    <el-dialog v-model="showLlmDialog_" :title="llmDialogMode === 'add' ? '添加 AI 模型' : '编辑 AI 模型'" width="560px">
      <el-form :model="llmForm" label-position="top">
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="显示名称" required>
              <el-input v-model="llmForm.name" placeholder="如：通义千问-Max" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="模型类别" required>
              <el-select v-model="llmForm.category" style="width: 100%">
                <el-option label="通用" value="GENERAL" />
                <el-option label="编程" value="CODING" />
                <el-option label="推理" value="REASONING" />
                <el-option label="视觉" value="VISION" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="API Base URL" required>
          <el-input v-model="llmForm.baseUrl" placeholder="如：https://dashscope.aliyuncs.com/compatible-mode/v1" />
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="模型名称" required>
              <el-input v-model="llmForm.modelName" placeholder="如：qwen-max" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="API Key" :required="llmDialogMode === 'add'">
              <el-input v-model="llmForm.apiKey" type="password" show-password :placeholder="llmDialogMode === 'edit' ? '留空保持不变' : 'API Key'" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item>
          <el-checkbox v-model="llmForm.isDefault">设为该类别的默认模型</el-checkbox>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showLlmDialog_ = false">取消</el-button>
        <el-button :loading="testingLlm" @click="handleTestLlm">测试连接</el-button>
        <el-button type="primary" :loading="saving" @click="saveLlm">保存</el-button>
      </template>
    </el-dialog>

    <!-- Agent Override Dialog -->
    <el-dialog v-model="showOverrideDialog" title="添加 Agent 模型覆盖" width="460px">
      <el-form :model="overrideForm" label-position="top">
        <el-form-item label="Agent ID" required>
          <el-input v-model="overrideForm.agentId" placeholder="如：coding-agent" />
        </el-form-item>
        <el-form-item label="使用模型" required>
          <el-select v-model="overrideForm.modelConfigId" style="width: 100%">
            <el-option
              v-for="m in llmModels"
              :key="m.id"
              :label="`${m.name} (${categoryLabel(m.category)})`"
              :value="m.id"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showOverrideDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveOverride">保存</el-button>
      </template>
    </el-dialog>

    <!-- Worker Add/Edit Dialog -->
    <el-dialog v-model="showWorkerAddDialog" :title="workerDialogMode === 'add' ? '添加 Worker' : '编辑 Worker'" width="480px">
      <el-form :model="workerForm" label-position="top">
        <el-form-item label="名称" required>
          <el-input v-model="workerForm.name" placeholder="如：我的家庭机" />
        </el-form-item>
        <el-form-item label="地址" required>
          <el-input v-model="workerForm.baseUrl" placeholder="如：http://192.168.1.100:3031" />
        </el-form-item>
        <el-form-item label="认证令牌" :required="workerDialogMode === 'add'">
          <el-input v-model="workerForm.authToken" type="password" show-password :placeholder="workerDialogMode === 'edit' ? '留空保持不变' : 'Worker 预共享令牌'" />
        </el-form-item>
        <el-form-item label="认证模式">
          <el-select v-model="workerForm.authMode" style="width: 100%">
            <el-option label="订阅模式 (Claude Max)" value="SUBSCRIPTION" />
            <el-option label="API Key 模式" value="API_KEY" />
            <el-option label="自定义端点" value="CUSTOM_ENDPOINT" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showWorkerAddDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveWorkerForm">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { resetSetupStatus } from '@/router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Back } from '@element-plus/icons-vue'
import {
  listGitProviders as apiListGit,
  saveGitProvider as apiSaveGit,
  updateGitProvider as apiUpdateGit,
  deleteGitProvider as apiDeleteGit,
  listModelConfigs as apiListLlm,
  saveModelConfig as apiSaveLlm,
  updateModelConfig as apiUpdateLlm,
  deleteModelConfig as apiDeleteLlm,
  listAgentModelOverrides as apiListOverrides,
  setAgentModelOverride as apiSetOverride,
  removeAgentModelOverride as apiRemoveOverride,
  testLlmConnection as apiTestLlm,
  testSavedLlmConnection as apiTestSavedLlm,
} from '@/api/platform'
import {
  listWorkers as apiListWorkers,
  registerWorker as apiRegisterWorker,
  updateWorker as apiUpdateWorker,
  deleteWorker as apiDeleteWorker,
  triggerHealthCheck as apiHealthCheck,
} from '@/api/claudeWorker'
import type { GitProviderConfig, LlmModelConfig, AgentModelOverride, ClaudeWorker, GitProviderType, LlmModelCategory } from '@/types'

const router = useRouter()
const activeTab = ref('git')
const saving = ref(false)

// ===== Loading states =====
const loadingGit = ref(false)
const loadingLlm = ref(false)
const loadingOverrides = ref(false)
const loadingWorkers = ref(false)

// ===== Data =====
const gitProviders = ref<GitProviderConfig[]>([])
const llmModels = ref<LlmModelConfig[]>([])
const agentOverrides = ref<AgentModelOverride[]>([])
const workers = ref<ClaudeWorker[]>([])

// ===== Git Dialog =====
const showGitDialog = ref(false)
const gitDialogMode = ref<'add' | 'edit'>('add')
const editingGitId = ref('')
const gitForm = ref({
  providerType: 'GITHUB' as GitProviderType,
  baseUrl: '',
  accessToken: '',
  username: '',
})

function handleAddGit(type: GitProviderType) {
  gitDialogMode.value = 'add'
  editingGitId.value = ''
  gitForm.value = { providerType: type, baseUrl: '', accessToken: '', username: '' }
  showGitDialog.value = true
}

function editGitProvider(row: GitProviderConfig) {
  gitDialogMode.value = 'edit'
  editingGitId.value = row.id
  gitForm.value = {
    providerType: row.providerType,
    baseUrl: row.baseUrl || '',
    accessToken: '',
    username: row.username || '',
  }
  showGitDialog.value = true
}

async function saveGit() {
  if (!gitForm.value.accessToken && gitDialogMode.value === 'add') {
    ElMessage.warning('请填写访问令牌')
    return
  }
  saving.value = true
  try {
    if (gitDialogMode.value === 'add') {
      await apiSaveGit({
        providerType: gitForm.value.providerType,
        baseUrl: gitForm.value.baseUrl || undefined,
        accessToken: gitForm.value.accessToken,
        username: gitForm.value.username || undefined,
      })
    } else {
      const form: Record<string, unknown> = {}
      if (gitForm.value.accessToken) form.accessToken = gitForm.value.accessToken
      if (gitForm.value.baseUrl) form.baseUrl = gitForm.value.baseUrl
      if (gitForm.value.username) form.username = gitForm.value.username
      await apiUpdateGit(editingGitId.value, form)
    }
    showGitDialog.value = false
    ElMessage.success('保存成功')
    await loadGitProviders()
  } catch {
    ElMessage.error('保存失败')
  } finally {
    saving.value = false
  }
}

async function deleteGit(id: string) {
  try {
    await ElMessageBox.confirm('确认删除该 Git 提供者？', '提示', { type: 'warning' })
    await apiDeleteGit(id)
    resetSetupStatus()
    ElMessage.success('已删除')
    await loadGitProviders()
  } catch { /* cancelled */ }
}

// ===== LLM Dialog =====
const showLlmDialog_ = ref(false)
const llmDialogMode = ref<'add' | 'edit'>('add')
const editingLlmId = ref('')
const llmForm = ref({
  name: '',
  category: 'GENERAL' as LlmModelCategory,
  baseUrl: '',
  modelName: '',
  apiKey: '',
  isDefault: false,
})

const llmPresets = [
  { name: '阿里通义', displayName: '通义千问-Max', category: 'GENERAL' as LlmModelCategory, baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', modelName: 'qwen-max' },
  { name: 'DeepSeek', displayName: 'DeepSeek-R1', category: 'REASONING' as LlmModelCategory, baseUrl: 'https://api.deepseek.com/v1', modelName: 'deepseek-reasoner' },
  { name: 'OpenAI', displayName: 'GPT-4o', category: 'GENERAL' as LlmModelCategory, baseUrl: 'https://api.openai.com/v1', modelName: 'gpt-4o' },
]

function showLlmDialog(mode: 'add' | 'edit') {
  llmDialogMode.value = mode
  if (mode === 'add') {
    editingLlmId.value = ''
    llmForm.value = { name: '', category: 'GENERAL', baseUrl: '', modelName: '', apiKey: '', isDefault: false }
  }
  showLlmDialog_.value = true
}

function applyPreset(preset: (typeof llmPresets)[number]) {
  llmDialogMode.value = 'add'
  editingLlmId.value = ''
  llmForm.value = {
    name: preset.displayName,
    category: preset.category,
    baseUrl: preset.baseUrl,
    modelName: preset.modelName,
    apiKey: '',
    isDefault: false,
  }
  showLlmDialog_.value = true
}

function editLlmModel(row: LlmModelConfig) {
  llmDialogMode.value = 'edit'
  editingLlmId.value = row.id
  llmForm.value = {
    name: row.name,
    category: row.category,
    baseUrl: row.baseUrl,
    modelName: row.modelName,
    apiKey: '',
    isDefault: row.isDefault,
  }
  showLlmDialog_.value = true
}

async function saveLlm() {
  if (!llmForm.value.name || !llmForm.value.baseUrl || !llmForm.value.modelName) {
    ElMessage.warning('请填写必填项')
    return
  }
  if (!llmForm.value.apiKey && llmDialogMode.value === 'add') {
    ElMessage.warning('请填写 API Key')
    return
  }
  saving.value = true
  try {
    if (llmDialogMode.value === 'add') {
      await apiSaveLlm({
        name: llmForm.value.name,
        category: llmForm.value.category,
        baseUrl: llmForm.value.baseUrl,
        modelName: llmForm.value.modelName,
        apiKey: llmForm.value.apiKey,
        isDefault: llmForm.value.isDefault,
      })
    } else {
      const form: Record<string, unknown> = {
        name: llmForm.value.name,
        category: llmForm.value.category,
        baseUrl: llmForm.value.baseUrl,
        modelName: llmForm.value.modelName,
        isDefault: llmForm.value.isDefault,
      }
      if (llmForm.value.apiKey) form.apiKey = llmForm.value.apiKey
      await apiUpdateLlm(editingLlmId.value, form)
    }
    showLlmDialog_.value = false
    ElMessage.success('保存成功')
    await loadLlmModels()
  } catch {
    ElMessage.error('保存失败')
  } finally {
    saving.value = false
  }
}

const testingLlm = ref(false)

async function handleTestLlm() {
  if (!llmForm.value.baseUrl || !llmForm.value.modelName || !llmForm.value.apiKey) {
    ElMessage.warning('请先填写 Base URL、模型名称和 API Key')
    return
  }
  testingLlm.value = true
  try {
    const reply = await apiTestLlm({
      baseUrl: llmForm.value.baseUrl,
      apiKey: llmForm.value.apiKey,
      modelName: llmForm.value.modelName,
    })
    ElMessage.success('连接成功: ' + (reply || 'OK'))
  } catch (e: any) {
    ElMessage.error('连接失败: ' + (e?.response?.data?.msg || e?.message || '未知错误'))
  } finally {
    testingLlm.value = false
  }
}

async function handleTestSaved(row: LlmModelConfig & { _testing?: boolean }) {
  row._testing = true
  try {
    const reply = await apiTestSavedLlm(row.id)
    ElMessage.success('连接成功: ' + (reply || 'OK'))
  } catch (e: any) {
    ElMessage.error('连接失败: ' + (e?.response?.data?.msg || e?.message || '未知错误'))
  } finally {
    row._testing = false
  }
}

async function deleteLlm(id: string) {
  try {
    await ElMessageBox.confirm('确认删除该模型配置？', '提示', { type: 'warning' })
    await apiDeleteLlm(id)
    resetSetupStatus()
    ElMessage.success('已删除')
    await loadLlmModels()
  } catch { /* cancelled */ }
}

function categoryLabel(c: string): string {
  const map: Record<string, string> = { GENERAL: '通用', CODING: '编程', REASONING: '推理', VISION: '视觉' }
  return map[c] || c
}

// ===== Agent Override =====
const showOverrideDialog = ref(false)
const overrideForm = ref({ agentId: '', modelConfigId: '' })

async function saveOverride() {
  if (!overrideForm.value.agentId || !overrideForm.value.modelConfigId) {
    ElMessage.warning('请填写完整')
    return
  }
  saving.value = true
  try {
    await apiSetOverride(overrideForm.value)
    showOverrideDialog.value = false
    overrideForm.value = { agentId: '', modelConfigId: '' }
    ElMessage.success('保存成功')
    await loadOverrides()
  } catch {
    ElMessage.error('保存失败')
  } finally {
    saving.value = false
  }
}

async function deleteOverride(agentId: string) {
  try {
    await ElMessageBox.confirm('确认移除该 Agent 的模型覆盖？', '提示', { type: 'warning' })
    await apiRemoveOverride(agentId)
    ElMessage.success('已移除')
    await loadOverrides()
  } catch { /* cancelled */ }
}

function resolveModelName(modelConfigId: string): string {
  const m = llmModels.value.find((m) => m.id === modelConfigId)
  return m ? `${m.name} (${m.modelName})` : modelConfigId
}

// ===== Workers =====
const showWorkerAddDialog = ref(false)
const workerDialogMode = ref<'add' | 'edit'>('add')
const editingWorkerId = ref('')
const workerForm = ref({ name: '', baseUrl: '', authToken: '', authMode: 'SUBSCRIPTION' })

function editWorker(row: ClaudeWorker) {
  workerDialogMode.value = 'edit'
  editingWorkerId.value = row.workerId
  workerForm.value = {
    name: row.name,
    baseUrl: row.baseUrl,
    authToken: '',
    authMode: row.authMode || 'SUBSCRIPTION',
  }
  showWorkerAddDialog.value = true
}

async function saveWorkerForm() {
  if (!workerForm.value.name || !workerForm.value.baseUrl) {
    ElMessage.warning('请填写名称和地址')
    return
  }
  saving.value = true
  try {
    if (workerDialogMode.value === 'add') {
      if (!workerForm.value.authToken) {
        ElMessage.warning('请填写认证令牌')
        saving.value = false
        return
      }
      await apiRegisterWorker(workerForm.value)
    } else {
      const form: Record<string, string> = { name: workerForm.value.name, baseUrl: workerForm.value.baseUrl }
      if (workerForm.value.authToken) form.authToken = workerForm.value.authToken
      if (workerForm.value.authMode) form.authMode = workerForm.value.authMode
      await apiUpdateWorker(editingWorkerId.value, form)
    }
    showWorkerAddDialog.value = false
    workerForm.value = { name: '', baseUrl: '', authToken: '', authMode: 'SUBSCRIPTION' }
    ElMessage.success('保存成功')
    await loadWorkers()
  } catch {
    ElMessage.error('保存失败')
  } finally {
    saving.value = false
  }
}

async function deleteWorkerById(workerId: string) {
  try {
    await ElMessageBox.confirm('确认删除该 Worker？', '提示', { type: 'warning' })
    await apiDeleteWorker(workerId)
    ElMessage.success('已删除')
    await loadWorkers()
  } catch { /* cancelled */ }
}

async function handleWorkerHealthCheck(workerId: string) {
  try {
    await apiHealthCheck(workerId)
    ElMessage.success('状态已刷新')
    await loadWorkers()
  } catch {
    ElMessage.error('健康检查失败')
  }
}

// ===== Data Loading =====
async function loadGitProviders() {
  loadingGit.value = true
  try { gitProviders.value = await apiListGit() } catch { /* handled by interceptor */ }
  finally { loadingGit.value = false }
}

async function loadLlmModels() {
  loadingLlm.value = true
  try { llmModels.value = await apiListLlm() } catch { /* handled by interceptor */ }
  finally { loadingLlm.value = false }
}

async function loadOverrides() {
  loadingOverrides.value = true
  try { agentOverrides.value = await apiListOverrides() } catch { /* handled by interceptor */ }
  finally { loadingOverrides.value = false }
}

async function loadWorkers() {
  loadingWorkers.value = true
  try { workers.value = await apiListWorkers() } catch { /* handled by interceptor */ }
  finally { loadingWorkers.value = false }
}

onMounted(() => {
  loadGitProviders()
  loadLlmModels()
  loadOverrides()
  loadWorkers()
})
</script>

<style scoped>
.settings-layout {
  max-width: 960px;
  margin: 0 auto;
  padding: 24px;
}

.settings-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.settings-header h2 {
  margin: 0;
  font-size: 22px;
  color: #303133;
}

.settings-tabs {
  background: #fff;
  border-radius: 8px;
  padding: 16px 20px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
}

.tab-toolbar {
  display: flex;
  align-items: center;
  margin-bottom: 16px;
}

.toolbar-left {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.preset-label {
  font-size: 13px;
  color: #909399;
  margin-left: 12px;
}

.status-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.status-dot.online { background: #67c23a; }
.status-dot.offline { background: #c0c4cc; }
.status-dot.unknown { background: #e6a23c; }
</style>
