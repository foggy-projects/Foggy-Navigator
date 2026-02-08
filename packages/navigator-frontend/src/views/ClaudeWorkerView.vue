<template>
  <div class="worker-layout">
    <!-- Left Panel: Worker List -->
    <aside class="worker-sidebar">
      <div class="sidebar-header">
        <h3>Workers</h3>
        <el-button type="primary" size="small" @click="showAddDialog = true">
          + 添加
        </el-button>
      </div>
      <div class="worker-list">
        <div
          v-for="worker in workerState.workers.value"
          :key="worker.workerId"
          :class="['worker-item', { active: selectedWorkerId === worker.workerId }]"
          @click="selectWorker(worker.workerId)"
        >
          <div class="worker-info">
            <span :class="['status-dot', worker.status?.toLowerCase()]" />
            <span class="worker-name">{{ worker.name }}</span>
          </div>
          <div class="worker-meta">{{ worker.hostname || worker.baseUrl }}</div>
        </div>
        <div v-if="workerState.workers.value.length === 0" class="empty-hint">
          暂无 Worker，点击上方添加
        </div>
      </div>
      <div class="sidebar-footer">
        <el-button text size="small" @click="router.push('/')">
          <el-icon><Back /></el-icon> 返回会话
        </el-button>
      </div>
    </aside>

    <!-- Right Panel -->
    <main class="worker-main">
      <template v-if="selectedWorker">
        <!-- Worker Detail Header -->
        <div class="worker-header">
          <div class="header-info">
            <h2>{{ selectedWorker.name }}</h2>
            <el-tag :type="statusTagType(selectedWorker.status)" size="small">
              {{ selectedWorker.status }}
            </el-tag>
            <span v-if="selectedWorker.workerVersion" class="version-tag">
              v{{ selectedWorker.workerVersion }}
            </span>
          </div>
          <div class="header-actions">
            <el-button size="small" @click="handleRefreshStatus">刷新状态</el-button>
            <el-button size="small" @click="showEditDialog = true">编辑</el-button>
            <el-button size="small" type="danger" @click="handleDelete">删除</el-button>
          </div>
        </div>

        <!-- Task Form -->
        <div class="task-form">
          <h4>新建任务</h4>
          <el-form :model="taskForm" label-position="top" size="default">
            <el-form-item label="Prompt">
              <el-input
                v-model="taskForm.prompt"
                type="textarea"
                :rows="3"
                placeholder="输入任务描述..."
                @keydown.ctrl.enter="handleCreateTask"
              />
            </el-form-item>
            <el-form-item label="工作目录 (cwd)">
              <el-input v-model="taskForm.cwd" placeholder="可选，如 /home/user/project" />
            </el-form-item>
            <el-form-item>
              <el-button
                type="primary"
                :disabled="!taskForm.prompt || selectedWorker.status !== 'ONLINE'"
                @click="handleCreateTask"
              >
                运行任务
              </el-button>
            </el-form-item>
          </el-form>
        </div>

        <!-- Active Task / Chat Panel -->
        <div v-if="activeTask" class="task-panel">
          <div class="task-header">
            <span class="task-label">
              任务: {{ activeTask.taskId }}
              <el-tag :type="taskStatusType(activeTask.status)" size="small">
                {{ activeTask.status }}
              </el-tag>
            </span>
            <div class="task-actions">
              <span v-if="activeTask.costUsd" class="cost-label">
                ${{ activeTask.costUsd?.toFixed(4) }}
              </span>
              <el-button
                v-if="activeTask.status === 'RUNNING'"
                size="small"
                type="danger"
                @click="handleAbort"
              >
                中止
              </el-button>
            </div>
          </div>
          <div class="chat-container">
            <ChatPanel
              :messages="chatStore.sortedMessages"
              :is-thinking="chatStore.isThinking"
              :connection-status="chatStore.connectionStatus"
              :conversation-status="chatStore.conversationStatus"
              :show-input="false"
            >
              <template #empty>
                <div class="waiting-hint">等待 Worker 响应...</div>
              </template>
            </ChatPanel>
          </div>
        </div>

        <!-- Task History -->
        <div v-if="workerTasks.length > 0" class="task-history">
          <h4>历史任务</h4>
          <div class="task-list">
            <div
              v-for="task in workerTasks"
              :key="task.taskId"
              class="task-item"
              @click="viewTask(task)"
            >
              <div class="task-prompt">{{ truncate(task.prompt, 80) }}</div>
              <div class="task-meta">
                <el-tag :type="taskStatusType(task.status)" size="small">
                  {{ task.status }}
                </el-tag>
                <span v-if="task.costUsd">${{ task.costUsd?.toFixed(4) }}</span>
                <span v-if="task.durationMs">{{ (task.durationMs / 1000).toFixed(1) }}s</span>
                <span>{{ formatTime(task.createdAt) }}</span>
              </div>
            </div>
          </div>
        </div>
      </template>

      <template v-else>
        <div class="empty-state">
          <h2>Claude Workers</h2>
          <p>选择一个 Worker 或添加新的 Worker 开始使用</p>
        </div>
      </template>
    </main>

    <!-- Add Worker Dialog -->
    <el-dialog v-model="showAddDialog" title="添加 Worker" width="480px">
      <el-form :model="addForm" label-position="top">
        <el-form-item label="名称" required>
          <el-input v-model="addForm.name" placeholder="如：我的家庭机" />
        </el-form-item>
        <el-form-item label="地址" required>
          <el-input v-model="addForm.baseUrl" placeholder="如：http://192.168.1.100:3001" />
        </el-form-item>
        <el-form-item label="认证令牌" required>
          <el-input
            v-model="addForm.authToken"
            type="password"
            show-password
            placeholder="Worker 预共享令牌"
          />
        </el-form-item>
        <el-form-item label="认证模式">
          <el-select v-model="addForm.authMode" style="width: 100%">
            <el-option label="订阅模式 (Claude Max)" value="SUBSCRIPTION" />
            <el-option label="API Key 模式" value="API_KEY" />
            <el-option label="自定义端点" value="CUSTOM_ENDPOINT" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showAddDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleAdd">添加</el-button>
      </template>
    </el-dialog>

    <!-- Edit Worker Dialog -->
    <el-dialog v-model="showEditDialog" title="编辑 Worker" width="480px">
      <el-form :model="editForm" label-position="top">
        <el-form-item label="名称">
          <el-input v-model="editForm.name" />
        </el-form-item>
        <el-form-item label="地址">
          <el-input v-model="editForm.baseUrl" />
        </el-form-item>
        <el-form-item label="认证令牌">
          <el-input
            v-model="editForm.authToken"
            type="password"
            show-password
            placeholder="留空保持不变"
          />
        </el-form-item>
        <el-form-item label="认证模式">
          <el-select v-model="editForm.authMode" style="width: 100%">
            <el-option label="订阅模式 (Claude Max)" value="SUBSCRIPTION" />
            <el-option label="API Key 模式" value="API_KEY" />
            <el-option label="自定义端点" value="CUSTOM_ENDPOINT" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showEditDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleEdit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Back } from '@element-plus/icons-vue'
import { ChatPanel, useChatStore } from '@foggy/chat'
import { useClaudeWorker } from '@/composables/useClaudeWorker'
import { useSession } from '@/composables/useSession'
import type { ClaudeWorker, ClaudeTask } from '@/types'

const router = useRouter()
const chatStore = useChatStore()
const workerState = useClaudeWorker()
const { connectToSession, disconnectSession } = useSession()

const selectedWorkerId = ref<string | null>(null)
const activeTask = ref<ClaudeTask | null>(null)
const showAddDialog = ref(false)
const showEditDialog = ref(false)
const saving = ref(false)

const addForm = ref({
  name: '',
  baseUrl: '',
  authToken: '',
  authMode: 'SUBSCRIPTION',
})

const editForm = ref({
  name: '',
  baseUrl: '',
  authToken: '',
  authMode: 'SUBSCRIPTION',
})

const taskForm = ref({
  prompt: '',
  cwd: '',
})

const selectedWorker = computed(() =>
  workerState.workers.value.find((w) => w.workerId === selectedWorkerId.value),
)

const workerTasks = computed(() =>
  workerState.tasks.value.filter((t) => t.workerId === selectedWorkerId.value),
)

onMounted(async () => {
  await Promise.all([workerState.loadWorkers(), workerState.loadTasks()])
})

function selectWorker(workerId: string) {
  selectedWorkerId.value = workerId
  activeTask.value = null
  disconnectSession()
  chatStore.clearMessages()
}

async function handleAdd() {
  if (!addForm.value.name || !addForm.value.baseUrl || !addForm.value.authToken) {
    ElMessage.warning('请填写完整信息')
    return
  }
  saving.value = true
  try {
    await workerState.registerWorker(addForm.value)
    showAddDialog.value = false
    addForm.value = { name: '', baseUrl: '', authToken: '', authMode: 'SUBSCRIPTION' }
    ElMessage.success('Worker 添加成功')
  } catch {
    ElMessage.error('添加失败')
  } finally {
    saving.value = false
  }
}

async function handleEdit() {
  if (!selectedWorkerId.value) return
  saving.value = true
  try {
    await workerState.updateWorker(selectedWorkerId.value, editForm.value)
    showEditDialog.value = false
    ElMessage.success('更新成功')
  } catch {
    ElMessage.error('更新失败')
  } finally {
    saving.value = false
  }
}

async function handleDelete() {
  if (!selectedWorkerId.value) return
  try {
    await ElMessageBox.confirm('确认删除该 Worker？', '提示', {
      type: 'warning',
      confirmButtonText: '确认',
      cancelButtonText: '取消',
    })
    await workerState.deleteWorker(selectedWorkerId.value)
    selectedWorkerId.value = null
    ElMessage.success('已删除')
  } catch {
    // cancelled
  }
}

async function handleRefreshStatus() {
  if (!selectedWorkerId.value) return
  try {
    await workerState.refreshWorkerStatus(selectedWorkerId.value)
    ElMessage.success('状态已刷新')
  } catch {
    ElMessage.error('刷新失败')
  }
}

async function handleCreateTask() {
  if (!selectedWorkerId.value || !taskForm.value.prompt) return
  try {
    const task = await workerState.createTask({
      workerId: selectedWorkerId.value,
      prompt: taskForm.value.prompt,
      cwd: taskForm.value.cwd || undefined,
    })
    activeTask.value = task
    taskForm.value.prompt = ''

    // Connect to the session for live streaming
    await connectToSession(task.sessionId)
  } catch (e: unknown) {
    ElMessage.error('创建任务失败: ' + ((e as Error).message || '未知错误'))
  }
}

async function handleAbort() {
  if (!activeTask.value) return
  try {
    await workerState.abortTask(activeTask.value.taskId)
    activeTask.value.status = 'ABORTED'
    ElMessage.info('任务已中止')
  } catch {
    ElMessage.error('中止失败')
  }
}

function viewTask(task: ClaudeTask) {
  activeTask.value = task
  connectToSession(task.sessionId)
}

watch(showEditDialog, (val) => {
  if (val && selectedWorker.value) {
    editForm.value = {
      name: selectedWorker.value.name,
      baseUrl: selectedWorker.value.baseUrl,
      authToken: '',
      authMode: selectedWorker.value.authMode || 'SUBSCRIPTION',
    }
  }
})

function statusTagType(status: string) {
  if (status === 'ONLINE') return 'success'
  if (status === 'OFFLINE') return 'danger'
  return 'info'
}

function taskStatusType(status: string) {
  if (status === 'COMPLETED') return 'success'
  if (status === 'RUNNING') return 'primary'
  if (status === 'FAILED') return 'danger'
  if (status === 'ABORTED') return 'warning'
  return 'info'
}

function truncate(text: string, maxLen: number) {
  return text.length > maxLen ? text.substring(0, maxLen) + '...' : text
}

function formatTime(dateStr: string): string {
  const d = new Date(dateStr)
  const now = new Date()
  if (d.toDateString() === now.toDateString()) {
    return d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  }
  return d.toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' })
}
</script>

<style scoped>
.worker-layout {
  display: flex;
  height: 100vh;
}

.worker-sidebar {
  width: 260px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  background: #f7f8fa;
  border-right: 1px solid #e4e7ed;
}

.sidebar-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  border-bottom: 1px solid #e4e7ed;
}

.sidebar-header h3 {
  margin: 0;
  font-size: 16px;
  color: #303133;
}

.worker-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}

.worker-item {
  padding: 10px 12px;
  margin-bottom: 4px;
  border-radius: 6px;
  cursor: pointer;
  transition: background-color 0.2s;
}

.worker-item:hover {
  background: #ebeef5;
}

.worker-item.active {
  background: #e1eaff;
}

.worker-info {
  display: flex;
  align-items: center;
  gap: 8px;
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}

.status-dot.online {
  background: #67c23a;
}

.status-dot.offline {
  background: #c0c4cc;
}

.status-dot.unknown {
  background: #e6a23c;
}

.worker-name {
  font-size: 14px;
  color: #303133;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.worker-meta {
  font-size: 12px;
  color: #909399;
  margin-top: 2px;
  margin-left: 16px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.sidebar-footer {
  padding: 10px 12px;
  border-top: 1px solid #e4e7ed;
}

.worker-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  overflow-y: auto;
  padding: 20px 24px;
}

.worker-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.header-info {
  display: flex;
  align-items: center;
  gap: 10px;
}

.header-info h2 {
  margin: 0;
  font-size: 20px;
}

.version-tag {
  font-size: 12px;
  color: #909399;
}

.header-actions {
  display: flex;
  gap: 8px;
}

.task-form {
  background: #fafafa;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 16px;
  margin-bottom: 20px;
}

.task-form h4 {
  margin: 0 0 12px 0;
  font-size: 15px;
  color: #303133;
}

.task-panel {
  margin-bottom: 20px;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  overflow: hidden;
}

.task-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 16px;
  background: #fafafa;
  border-bottom: 1px solid #e4e7ed;
}

.task-label {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: #606266;
}

.task-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.cost-label {
  font-size: 13px;
  color: #e6a23c;
  font-weight: 500;
}

.chat-container {
  height: 400px;
}

.chat-container > :deep(.chat-panel) {
  height: 100%;
  border: none;
  border-radius: 0;
}

.waiting-hint {
  color: #909399;
  font-size: 14px;
}

.task-history h4 {
  margin: 0 0 12px 0;
  font-size: 15px;
  color: #303133;
}

.task-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.task-item {
  padding: 10px 14px;
  background: #fafafa;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  cursor: pointer;
  transition: border-color 0.2s;
}

.task-item:hover {
  border-color: #409eff;
}

.task-prompt {
  font-size: 14px;
  color: #303133;
  margin-bottom: 6px;
}

.task-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12px;
  color: #909399;
}

.empty-hint {
  text-align: center;
  color: #909399;
  font-size: 13px;
  padding: 24px 0;
}

.empty-state {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  color: #909399;
}

.empty-state h2 {
  font-size: 24px;
  color: #606266;
  margin-bottom: 8px;
}
</style>
