<template>
  <div class="worker-layout">
    <!-- Left Panel: Two-level Tree Sidebar -->
    <aside class="worker-sidebar">
      <div class="sidebar-header">
        <h3>Workers</h3>
        <el-dropdown trigger="click" @command="handleAddCommand">
          <el-button type="primary" size="small">+ 添加</el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="worker">添加 Worker</el-dropdown-item>
              <el-dropdown-item command="directory" :disabled="!selectedWorkerId">
                添加工作目录
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
      <div class="worker-list">
        <template v-for="worker in workerState.workers.value" :key="worker.workerId">
          <!-- Worker node (Level 1) -->
          <div
            :class="[
              'worker-item',
              { active: selectedWorkerId === worker.workerId && !selectedDirectoryId },
            ]"
            @click="selectWorker(worker.workerId)"
          >
            <div class="worker-info">
              <span
                class="expand-icon"
                @click.stop="toggleExpand(worker.workerId)"
              >
                {{ expandedWorkerIds.has(worker.workerId) ? '▼' : '▶' }}
              </span>
              <span :class="['status-dot', worker.status?.toLowerCase()]" />
              <span class="worker-name">{{ worker.name }}</span>
            </div>
            <div class="worker-meta">{{ worker.hostname || worker.baseUrl }}</div>
          </div>
          <!-- Directory nodes (Level 2) -->
          <template v-if="expandedWorkerIds.has(worker.workerId)">
            <div
              v-for="dir in directoriesForWorker(worker.workerId)"
              :key="dir.directoryId"
              :class="[
                'directory-item',
                { active: selectedDirectoryId === dir.directoryId },
              ]"
              @click="selectDirectory(worker.workerId, dir.directoryId)"
            >
              <div class="dir-info">
                <span class="dir-name">{{ dir.projectName }}</span>
                <span v-if="dir.gitStatus === 'dirty'" class="dirty-dot" title="Uncommitted changes" />
              </div>
              <div v-if="dir.gitBranch" class="dir-branch">
                <span class="branch-icon">⎇</span> {{ dir.gitBranch }}
              </div>
            </div>
            <div
              v-if="directoriesForWorker(worker.workerId).length === 0"
              class="empty-dir-hint"
            >
              暂无工作目录
            </div>
          </template>
        </template>
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

    <!-- Middle Panel: Main Content Area -->
    <main :class="['worker-main', { 'has-panes': panes.length > 0 }]">
      <!-- Directory selected: show git info + task form scoped to directory -->
      <template v-if="selectedDirectory">
        <!-- Compact directory header -->
        <div class="dir-compact-header">
          <div class="dir-compact-info">
            <h2>{{ selectedDirectory.projectName }}</h2>
            <el-tag v-if="selectedDirectory.gitBranch" size="small">
              ⎇ {{ selectedDirectory.gitBranch }}
            </el-tag>
            <el-tag
              v-if="selectedDirectory.gitStatus"
              :type="selectedDirectory.gitStatus === 'clean' ? 'success' : selectedDirectory.gitStatus === 'dirty' ? 'warning' : 'info'"
              size="small"
            >
              {{ selectedDirectory.gitStatus }}
            </el-tag>
            <code class="dir-path-inline">{{ selectedDirectory.path }}</code>
          </div>
          <div class="header-actions">
            <el-button size="small" :loading="syncing" @click="handleSyncGitInfo">
              同步 Git
            </el-button>
            <el-button size="small" @click="showEditDirectoryDialog = true">编辑</el-button>
            <el-button size="small" type="danger" text @click="handleDeleteDirectory">删除</el-button>
          </div>
        </div>
        <div v-if="parsedAgentTeams.length" class="agent-teams-bar">
          <span class="agent-teams-label">Agent Teams:</span>
          <el-tag v-for="agent in parsedAgentTeams" :key="agent" size="small" type="info">
            {{ agent }}
          </el-tag>
        </div>

        <!-- Task Form: collapse when panes are open -->
        <div v-if="panes.length === 0" class="task-form">
          <el-form :model="taskForm" label-position="top" size="default">
            <el-form-item label="Prompt">
              <SlashCommandInput
                v-model="taskForm.prompt"
                :rows="3"
                placeholder="输入任务描述... (输入 / 触发命令)"
                :skills="directorySkills"
                @submit="handleCreateTask"
                @command="handleSlashCommand"
              />
            </el-form-item>
            <div v-if="taskForm.model || taskForm.maxTurns" class="active-overrides">
              <el-tag v-if="taskForm.model" size="small" closable @close="taskForm.model = ''">
                模型: {{ shortModel(taskForm.model) }}
              </el-tag>
              <el-tag v-if="taskForm.maxTurns" size="small" closable @close="taskForm.maxTurns = null">
                轮次: {{ taskForm.maxTurns }}
              </el-tag>
            </div>
            <el-form-item>
              <el-button
                type="primary"
                :disabled="!taskForm.prompt || selectedWorkerEntity?.status !== 'ONLINE'"
                @click="handleCreateTask"
              >
                运行任务
              </el-button>
            </el-form-item>
          </el-form>
        </div>
        <div v-else class="new-task-mini">
          <SlashCommandInput
            v-model="taskForm.prompt"
            :rows="1"
            size="small"
            placeholder="新建任务..."
            :skills="directorySkills"
            @submit="handleCreateTask"
            @command="handleSlashCommand"
          >
            <template #append>
              <el-button
                :disabled="!taskForm.prompt || selectedWorkerEntity?.status !== 'ONLINE' || panes.length >= MAX_PANES"
                @click="handleCreateTask"
              >
                运行
              </el-button>
            </template>
          </SlashCommandInput>
          <div v-if="taskForm.model || taskForm.maxTurns" class="active-overrides">
            <el-tag v-if="taskForm.model" size="small" closable @close="taskForm.model = ''">
              模型: {{ shortModel(taskForm.model) }}
            </el-tag>
            <el-tag v-if="taskForm.maxTurns" size="small" closable @close="taskForm.maxTurns = null">
              轮次: {{ taskForm.maxTurns }}
            </el-tag>
          </div>
        </div>

        <!-- Empty state when no panes -->
        <div v-if="panes.length === 0" class="pane-empty-hint">
          从右侧历史选择会话查看，或新建任务开始
        </div>

        <!-- Multi-Pane Grid -->
        <TaskPaneGrid
          v-if="panes.length > 0"
          :panes="panes"
          :skills="directorySkills"
          @close="closePane"
          @abort="abortPane"
          @send="handlePaneSend"
          @command="handleSlashCommand"
        />
      </template>

      <!-- Worker selected (no directory): show worker details + all tasks -->
      <template v-else-if="selectedWorkerEntity">
        <div class="worker-header">
          <div class="header-info">
            <h2>{{ selectedWorkerEntity.name }}</h2>
            <el-tag :type="statusTagType(selectedWorkerEntity.status)" size="small">
              {{ selectedWorkerEntity.status }}
            </el-tag>
            <span v-if="selectedWorkerEntity.workerVersion" class="version-tag">
              v{{ selectedWorkerEntity.workerVersion }}
            </span>
          </div>
          <div class="header-actions">
            <el-button size="small" @click="handleRefreshStatus">刷新状态</el-button>
            <el-button size="small" @click="showEditDialog = true">编辑</el-button>
            <el-button size="small" type="danger" text @click="handleDelete">删除</el-button>
          </div>
        </div>

        <!-- Task Form: collapse when panes are open -->
        <div v-if="panes.length === 0" class="task-form">
          <el-form :model="taskForm" label-position="top" size="default">
            <el-form-item label="Prompt">
              <SlashCommandInput
                v-model="taskForm.prompt"
                :rows="3"
                placeholder="输入任务描述... (输入 / 触发命令)"
                :skills="directorySkills"
                @submit="handleCreateTask"
                @command="handleSlashCommand"
              />
            </el-form-item>
            <el-form-item label="工作目录 (cwd)">
              <el-input v-model="taskForm.cwd" placeholder="可选，如 /home/user/project" />
            </el-form-item>
            <div v-if="taskForm.model || taskForm.maxTurns" class="active-overrides">
              <el-tag v-if="taskForm.model" size="small" closable @close="taskForm.model = ''">
                模型: {{ shortModel(taskForm.model) }}
              </el-tag>
              <el-tag v-if="taskForm.maxTurns" size="small" closable @close="taskForm.maxTurns = null">
                轮次: {{ taskForm.maxTurns }}
              </el-tag>
            </div>
            <el-form-item>
              <el-button
                type="primary"
                :disabled="!taskForm.prompt || selectedWorkerEntity.status !== 'ONLINE'"
                @click="handleCreateTask"
              >
                运行任务
              </el-button>
            </el-form-item>
          </el-form>
        </div>
        <div v-else class="new-task-mini">
          <SlashCommandInput
            v-model="taskForm.prompt"
            :rows="1"
            size="small"
            placeholder="新建任务..."
            :skills="directorySkills"
            @submit="handleCreateTask"
            @command="handleSlashCommand"
          >
            <template #append>
              <el-button
                :disabled="!taskForm.prompt || selectedWorkerEntity.status !== 'ONLINE' || panes.length >= MAX_PANES"
                @click="handleCreateTask"
              >
                运行
              </el-button>
            </template>
          </SlashCommandInput>
          <div v-if="taskForm.model || taskForm.maxTurns" class="active-overrides">
            <el-tag v-if="taskForm.model" size="small" closable @close="taskForm.model = ''">
              模型: {{ shortModel(taskForm.model) }}
            </el-tag>
            <el-tag v-if="taskForm.maxTurns" size="small" closable @close="taskForm.maxTurns = null">
              轮次: {{ taskForm.maxTurns }}
            </el-tag>
          </div>
        </div>

        <!-- Empty state when no panes -->
        <div v-if="panes.length === 0" class="pane-empty-hint">
          从右侧历史选择会话查看，或新建任务开始
        </div>

        <!-- Multi-Pane Grid -->
        <TaskPaneGrid
          v-if="panes.length > 0"
          :panes="panes"
          :skills="directorySkills"
          @close="closePane"
          @abort="abortPane"
          @send="handlePaneSend"
          @command="handleSlashCommand"
        />
      </template>

      <template v-else>
        <div class="empty-state">
          <h2>Claude Workers</h2>
          <p>选择一个 Worker 或添加新的 Worker 开始使用</p>
        </div>
      </template>
    </main>

    <!-- Right Panel: Task History -->
    <aside v-if="selectedWorkerId" class="worker-history">
      <div class="history-header">
        <h3>历史会话</h3>
      </div>
      <div class="history-content">
        <!-- Conversation list (shared template for directory / worker-level) -->
        <div
          v-if="activeConversations.length > 0"
          class="conv-list"
        >
          <div
            v-for="conv in activeConversations"
            :key="conv.sessionId"
            class="conv-item"
            @click="viewTask(conv.latestTask)"
          >
            <div class="conv-row-1">
              <span class="conv-prompt" :title="conv.firstPrompt">{{ truncate(conv.firstPrompt, 36) }}</span>
              <span :class="['conv-status-dot', conv.latestTask.status.toLowerCase()]" :title="conv.latestTask.status" />
            </div>
            <div class="conv-row-2">
              <span v-if="conv.tasks.length > 1" class="conv-rounds">{{ conv.tasks.length }}轮</span>
              <span v-if="conv.latestTask.model" class="conv-model">{{ shortModel(conv.latestTask.model) }}</span>
              <span v-if="conv.totalCost > 0" class="conv-cost">${{ conv.totalCost.toFixed(2) }}</span>
              <span class="conv-time">{{ formatTime(conv.latestTask.createdAt) }}</span>
              <span class="conv-hover-actions" @click.stop>
                <el-button
                  v-if="conv.latestTask.status === 'RUNNING'"
                  type="warning"
                  size="small"
                  text
                  @click="handleAbortTask(conv.latestTask.taskId)"
                >
                  中止
                </el-button>
                <el-button
                  v-if="conv.latestTask.status !== 'RUNNING' && conv.claudeSessionId"
                  type="primary"
                  size="small"
                  text
                  @click="handleResumeFromHistory(conv.latestTask)"
                >
                  继续
                </el-button>
                <el-button
                  v-if="conv.latestTask.status !== 'RUNNING'"
                  size="small"
                  text
                  class="delete-btn"
                  @click="handleDeleteConversation(conv)"
                >
                  删除
                </el-button>
              </span>
            </div>
          </div>
        </div>
        <div v-else class="empty-hint">暂无历史会话</div>
        <!-- Pagination -->
        <el-pagination
          v-if="selectedDirectoryId ? dirTaskTotal > dirTaskSize : workerState.taskTotal.value > workerState.taskSize.value"
          class="task-pagination"
          small
          layout="prev, pager, next"
          :total="selectedDirectoryId ? dirTaskTotal : workerState.taskTotal.value"
          :page-size="selectedDirectoryId ? dirTaskSize : workerState.taskSize.value"
          :current-page="(selectedDirectoryId ? dirTaskPage : workerState.taskPage.value) + 1"
          @current-change="selectedDirectoryId ? handleDirPageChange($event) : handlePageChange($event)"
        />
      </div>
    </aside>

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

    <!-- Add Directory Dialog -->
    <el-dialog v-model="showAddDirectoryDialog" title="添加工作目录" width="480px">
      <el-form :model="addDirForm" label-position="top">
        <el-form-item label="项目名称" required>
          <el-input v-model="addDirForm.projectName" placeholder="如：foggy-navigator / feature-branch" />
        </el-form-item>
        <el-form-item label="路径" required>
          <el-input v-model="addDirForm.path" placeholder="如：/home/user/projects/foggy-navigator" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showAddDirectoryDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleAddDirectory">添加</el-button>
      </template>
    </el-dialog>

    <!-- Edit Directory Dialog -->
    <el-dialog v-model="showEditDirectoryDialog" title="编辑工作目录" width="480px">
      <el-form :model="editDirForm" label-position="top">
        <el-form-item label="项目名称">
          <el-input v-model="editDirForm.projectName" />
        </el-form-item>
        <el-form-item label="路径">
          <el-input v-model="editDirForm.path" />
        </el-form-item>
        <el-form-item label="Agent Teams">
          <el-input
            v-model="editDirForm.agentTeamsConfig"
            type="textarea"
            :rows="6"
            placeholder='{"reviewer": {"description": "...", "prompt": "..."}}'
          />
          <div class="form-tip">
            JSON 格式定义子 Agent 团队。Claude 会自动分派子任务给团队成员。
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showEditDirectoryDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleEditDirectory">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, shallowRef, triggerRef, computed, reactive, onMounted, onUnmounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Back } from '@element-plus/icons-vue'
import { useClaudeWorker } from '@/composables/useClaudeWorker'
import { useTaskPane } from '@/composables/useTaskPane'
import type { TaskPaneState } from '@/composables/useTaskPane'
import TaskPaneGrid from '@/components/worker/TaskPaneGrid.vue'
import SlashCommandInput from '@/components/worker/SlashCommandInput.vue'
import * as dirApi from '@/api/claudeWorker'
import type { ClaudeTask, WorkingDirectory, SkillInfo } from '@/types'

const MAX_PANES = 4

const router = useRouter()
const workerState = useClaudeWorker()

const selectedWorkerId = ref<string | null>(null)
const selectedDirectoryId = ref<string | null>(null)
const expandedWorkerIds = reactive(new Set<string>())
const panes = shallowRef<TaskPaneState[]>([])
const showAddDialog = ref(false)
const showEditDialog = ref(false)
const showAddDirectoryDialog = ref(false)
const showEditDirectoryDialog = ref(false)
const saving = ref(false)
const syncing = ref(false)
const directorySkills = ref<SkillInfo[]>([])

// Directory task pagination (separate from global task pagination)
const directoryTasks = ref<ClaudeTask[]>([])
const dirTaskPage = ref(0)
const dirTaskSize = ref(20)
const dirTaskTotal = ref(0)

let paneCounter = 0

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

const addDirForm = ref({
  projectName: '',
  path: '',
})

const editDirForm = ref({
  projectName: '',
  path: '',
  agentTeamsConfig: '',
})

const taskForm = ref({
  prompt: '',
  cwd: '',
  model: '' as string,
  maxTurns: null as number | null,
})

const selectedWorkerEntity = computed(() =>
  workerState.workers.value.find((w) => w.workerId === selectedWorkerId.value),
)

const selectedDirectory = computed(() =>
  workerState.directories.value.find((d) => d.directoryId === selectedDirectoryId.value),
)

const parsedAgentTeams = computed(() => {
  const dir = selectedDirectory.value
  if (!dir?.agentTeamsConfig) return []
  try { return Object.keys(JSON.parse(dir.agentTeamsConfig)) }
  catch { return [] }
})

const workerTasks = computed(() =>
  workerState.tasks.value.filter((t) => t.workerId === selectedWorkerId.value),
)

// Per-conversation grouping for history panel
interface ConversationGroup {
  sessionId: string
  claudeSessionId: string
  latestTask: ClaudeTask
  tasks: ClaudeTask[]
  totalCost: number
  firstPrompt: string
}

function groupTasksToConversations(taskList: ClaudeTask[]): ConversationGroup[] {
  const groups = new Map<string, ClaudeTask[]>()
  for (const task of taskList) {
    const key = task.sessionId
    const existing = groups.get(key)
    if (existing) {
      existing.push(task)
    } else {
      groups.set(key, [task])
    }
  }
  return Array.from(groups.values())
    .map((tasks) => ({
      sessionId: tasks[0]!.sessionId,
      claudeSessionId: tasks.find((t) => t.claudeSessionId)?.claudeSessionId || '',
      latestTask: tasks[0]!,
      tasks,
      totalCost: tasks.reduce((s, t) => s + (t.costUsd || 0), 0),
      firstPrompt: tasks[tasks.length - 1]!.prompt,
    }))
    .sort((a, b) => new Date(b.latestTask.createdAt).getTime() - new Date(a.latestTask.createdAt).getTime())
}

const workerConversations = computed(() => groupTasksToConversations(workerTasks.value))
const directoryConversations = computed(() => groupTasksToConversations(directoryTasks.value))
const activeConversations = computed(() =>
  selectedDirectoryId.value ? directoryConversations.value : workerConversations.value,
)

function directoriesForWorker(workerId: string): WorkingDirectory[] {
  return workerState.directories.value.filter((d) => d.workerId === workerId)
}

onMounted(async () => {
  await Promise.all([workerState.loadWorkers(), workerState.loadTasks()])
})

onUnmounted(() => {
  disposeAllPanes()
})

/** Called when any pane's task reaches a terminal state */
function handleTaskFinished(_paneId: string) {
  workerState.loadTasks()
  if (selectedDirectoryId.value) {
    loadDirectoryTasks()
  }
}

function disposeAllPanes() {
  for (const pane of panes.value) {
    pane.dispose()
  }
  panes.value = []
}

function toggleExpand(workerId: string) {
  if (expandedWorkerIds.has(workerId)) {
    expandedWorkerIds.delete(workerId)
  } else {
    expandedWorkerIds.add(workerId)
    workerState.loadDirectories(workerId)
  }
}

function selectWorker(workerId: string) {
  selectedWorkerId.value = workerId
  selectedDirectoryId.value = null
  directorySkills.value = []
  disposeAllPanes()
  // Auto-expand
  if (!expandedWorkerIds.has(workerId)) {
    expandedWorkerIds.add(workerId)
    workerState.loadDirectories(workerId)
  }
}

function selectDirectory(workerId: string, directoryId: string) {
  selectedWorkerId.value = workerId
  selectedDirectoryId.value = directoryId
  disposeAllPanes()
  taskForm.value.prompt = ''
  taskForm.value.cwd = ''
  dirTaskPage.value = 0
  loadDirectoryTasks()
  loadDirectorySkills()
}

async function loadDirectoryTasks() {
  if (!selectedDirectoryId.value) return
  try {
    const result = await dirApi.listTasksByDirectoryPaged(
      selectedDirectoryId.value,
      dirTaskPage.value,
      dirTaskSize.value,
    )
    directoryTasks.value = result.content
    dirTaskTotal.value = result.totalElements
  } catch {
    try {
      directoryTasks.value = await dirApi.listTasksByDirectory(selectedDirectoryId.value)
      dirTaskTotal.value = directoryTasks.value.length
    } catch {
      directoryTasks.value = []
      dirTaskTotal.value = 0
    }
  }
}

function handleAddCommand(command: string) {
  if (command === 'worker') {
    showAddDialog.value = true
  } else if (command === 'directory') {
    if (!selectedWorkerId.value) {
      ElMessage.warning('请先选择一个 Worker')
      return
    }
    addDirForm.value = { projectName: '', path: '' }
    showAddDirectoryDialog.value = true
  }
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
    disposeAllPanes()
    await workerState.deleteWorker(selectedWorkerId.value)
    selectedWorkerId.value = null
    selectedDirectoryId.value = null
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

async function handleAddDirectory() {
  if (!selectedWorkerId.value || !addDirForm.value.projectName || !addDirForm.value.path) {
    ElMessage.warning('请填写完整信息')
    return
  }
  saving.value = true
  try {
    await workerState.createDirectory({
      workerId: selectedWorkerId.value,
      projectName: addDirForm.value.projectName,
      path: addDirForm.value.path,
    })
    showAddDirectoryDialog.value = false
    ElMessage.success('工作目录添加成功')
  } catch (e: unknown) {
    ElMessage.error('添加失败: ' + ((e as Error).message || '未知错误'))
  } finally {
    saving.value = false
  }
}

async function handleEditDirectory() {
  if (!selectedDirectoryId.value) return
  saving.value = true
  try {
    const updated = await dirApi.updateDirectory(selectedDirectoryId.value, editDirForm.value)
    const idx = workerState.directories.value.findIndex(
      (d) => d.directoryId === selectedDirectoryId.value,
    )
    if (idx >= 0) workerState.directories.value[idx] = updated
    showEditDirectoryDialog.value = false
    ElMessage.success('更新成功')
  } catch {
    ElMessage.error('更新失败')
  } finally {
    saving.value = false
  }
}

async function handleDeleteDirectory() {
  if (!selectedDirectoryId.value) return
  try {
    await ElMessageBox.confirm('确认删除该工作目录？', '提示', {
      type: 'warning',
      confirmButtonText: '确认',
      cancelButtonText: '取消',
    })
    disposeAllPanes()
    await workerState.deleteDirectory(selectedDirectoryId.value)
    selectedDirectoryId.value = null
    ElMessage.success('已删除')
  } catch {
    // cancelled
  }
}

async function handleSyncGitInfo() {
  if (!selectedDirectoryId.value) return
  syncing.value = true
  try {
    await workerState.syncGitInfo(selectedDirectoryId.value)
    ElMessage.success('Git 信息已同步')
  } catch {
    ElMessage.error('同步失败')
  } finally {
    syncing.value = false
  }
}

function handleSlashCommand(payload: { command: string; value: string | number }) {
  if (payload.command === 'model') {
    taskForm.value.model = payload.value as string
    ElMessage.success(payload.value ? `模型已设为 ${shortModel(payload.value as string)}` : '已恢复默认模型')
  } else if (payload.command === 'turns') {
    taskForm.value.maxTurns = payload.value as number
    ElMessage.success(`最大轮次已设为 ${payload.value}`)
  }
}

async function loadDirectorySkills() {
  if (!selectedDirectoryId.value) {
    directorySkills.value = []
    return
  }
  try {
    directorySkills.value = await dirApi.listSkills(selectedDirectoryId.value)
  } catch {
    directorySkills.value = []
  }
}

async function handleCreateTask() {
  if (!selectedWorkerId.value || !taskForm.value.prompt) return
  // Strip leading "/" to prevent Claude Code CLI from interpreting it as a slash command
  let prompt = taskForm.value.prompt
  if (prompt.startsWith('/')) {
    prompt = prompt.slice(1)
  }
  if (!prompt.trim()) return
  if (panes.value.length >= MAX_PANES) {
    ElMessage.warning(`最多同时打开 ${MAX_PANES} 个面板，请先关闭一个`)
    return
  }
  try {
    const form: {
      workerId: string; prompt: string; cwd?: string; directoryId?: string
      model?: string; maxTurns?: number; agentTeamsJson?: string
    } = {
      workerId: selectedWorkerId.value,
      prompt,
    }

    if (selectedDirectoryId.value) {
      form.directoryId = selectedDirectoryId.value
    } else if (taskForm.value.cwd) {
      form.cwd = taskForm.value.cwd
    }
    if (taskForm.value.model) {
      form.model = taskForm.value.model
    }
    if (taskForm.value.maxTurns != null) {
      form.maxTurns = taskForm.value.maxTurns
    }
    if (selectedDirectory.value?.agentTeamsConfig) {
      form.agentTeamsJson = selectedDirectory.value.agentTeamsConfig
    }

    const task = await workerState.createTask(form)
    taskForm.value.prompt = ''

    const pane = createPane(task)
    await pane.connect(task.sessionId)
  } catch (e: unknown) {
    ElMessage.error('创建任务失败: ' + ((e as Error).message || '未知错误'))
  }
}

function createPane(task: ClaudeTask): TaskPaneState {
  const paneId = `pane-${++paneCounter}`
  const pane = useTaskPane(paneId, { onTaskFinished: handleTaskFinished })
  pane.task.value = task
  panes.value = [...panes.value, pane]
  return pane
}

function closePane(paneId: string) {
  const idx = panes.value.findIndex((p) => p.paneId === paneId)
  if (idx >= 0) {
    panes.value[idx]!.dispose()
    panes.value = panes.value.filter((_, i) => i !== idx)
  }
}

async function abortPane(paneId: string) {
  const pane = panes.value.find((p) => p.paneId === paneId)
  if (!pane?.task.value) return
  try {
    await workerState.abortTask(pane.task.value.taskId)
    pane.task.value.status = 'ABORTED'
    triggerRef(panes)
    ElMessage.info('任务已中止')
  } catch {
    ElMessage.error('中止失败')
  }
}

/** Handle inline send from pane input (replaces dialog-based resume) */
async function handlePaneSend(paneId: string, content: string) {
  const pane = panes.value.find((p) => p.paneId === paneId)
  const oldTask = pane?.task.value
  if (!pane || !oldTask?.claudeSessionId || !selectedWorkerId.value) return

  try {
    const resumeForm: Parameters<typeof workerState.resumeTask>[0] = {
      workerId: selectedWorkerId.value,
      claudeSessionId: oldTask.claudeSessionId,
      prompt: content,
      cwd: oldTask.cwd,
      directoryId: oldTask.directoryId,
      sessionId: oldTask.sessionId,
    }
    if (taskForm.value.model) {
      resumeForm.model = taskForm.value.model
    }
    if (taskForm.value.maxTurns != null) {
      resumeForm.maxTurns = taskForm.value.maxTurns
    }
    const newTask = await workerState.resumeTask(resumeForm)

    pane.resumeInPlace(newTask)

    workerState.loadTasks()
    if (selectedDirectoryId.value) {
      loadDirectoryTasks()
    }
  } catch {
    ElMessage.error('发送失败')
  }
}

function handlePageChange(page: number) {
  workerState.loadTasksPage(page - 1)
}

function handleDirPageChange(page: number) {
  dirTaskPage.value = page - 1
  loadDirectoryTasks()
}

function viewTask(task: ClaudeTask) {
  // Per-conversation: match by sessionId (same conversation = same pane)
  const existing = panes.value.find((p) => p.task.value?.sessionId === task.sessionId)
  if (existing) return

  if (panes.value.length >= MAX_PANES) {
    ElMessage.warning(`最多同时打开 ${MAX_PANES} 个面板，请先关闭一个`)
    return
  }

  const pane = createPane(task)
  pane.connect(task.sessionId)
}

async function handleAbortTask(taskId: string) {
  try {
    await ElMessageBox.confirm('确认中止该任务？', '提示', {
      type: 'warning',
      confirmButtonText: '确认',
      cancelButtonText: '取消',
    })
    await workerState.abortTask(taskId)
    ElMessage.success('任务已中止')
    // Refresh task lists
    workerState.loadTasks()
    if (selectedDirectoryId.value) {
      loadDirectoryTasks()
    }
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error('中止失败')
    }
  }
}

async function handleDeleteConversation(conv: ConversationGroup) {
  try {
    await ElMessageBox.confirm(
      `确认删除该会话？包含 ${conv.tasks.length} 个任务，此操作不可恢复。`,
      '提示',
      { type: 'warning', confirmButtonText: '确认', cancelButtonText: '取消' },
    )
    // Delete all non-running tasks in the conversation
    for (const task of conv.tasks) {
      if (task.status !== 'RUNNING') {
        await workerState.deleteTask(task.taskId)
      }
    }
    ElMessage.success('会话已删除')
    workerState.loadTasks()
    if (selectedDirectoryId.value) {
      loadDirectoryTasks()
    }
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error('删除失败')
    }
  }
}

async function handleResumeFromHistory(task: ClaudeTask) {
  if (!task.claudeSessionId || !selectedWorkerId.value) return

  try {
    const { value: prompt } = await ElMessageBox.prompt('输入后续指令', '继续对话', {
      confirmButtonText: '发送',
      cancelButtonText: '取消',
      inputType: 'textarea',
      inputPlaceholder: '请输入后续任务描述...',
    })
    if (!prompt) return

    const newTask = await workerState.resumeTask({
      workerId: selectedWorkerId.value,
      claudeSessionId: task.claudeSessionId,
      prompt,
      cwd: task.cwd,
      directoryId: task.directoryId,
      sessionId: task.sessionId,  // per-conversation: reuse session
    })

    // Per-conversation: find existing pane by sessionId
    const existingPane = panes.value.find((p) => p.task.value?.sessionId === task.sessionId)
    if (existingPane) {
      existingPane.resumeInPlace(newTask)
    } else {
      if (panes.value.length >= MAX_PANES) {
        ElMessage.warning(`最多同时打开 ${MAX_PANES} 个面板，请先关闭一个`)
        return
      }
      const newPane = createPane(newTask)
      await newPane.connect(newTask.sessionId)  // load full history
    }

    workerState.loadTasks()
    if (selectedDirectoryId.value) {
      loadDirectoryTasks()
    }
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error('创建任务失败')
    }
  }
}

watch(showEditDialog, (val) => {
  if (val && selectedWorkerEntity.value) {
    editForm.value = {
      name: selectedWorkerEntity.value.name,
      baseUrl: selectedWorkerEntity.value.baseUrl,
      authToken: '',
      authMode: selectedWorkerEntity.value.authMode || 'SUBSCRIPTION',
    }
  }
})

watch(showEditDirectoryDialog, (val) => {
  if (val && selectedDirectory.value) {
    editDirForm.value = {
      projectName: selectedDirectory.value.projectName,
      path: selectedDirectory.value.path,
      agentTeamsConfig: selectedDirectory.value.agentTeamsConfig || '',
    }
  }
})

function statusTagType(status: string) {
  if (status === 'ONLINE') return 'success'
  if (status === 'OFFLINE') return 'danger'
  return 'info'
}

function truncate(text: string, maxLen: number) {
  return text.length > maxLen ? text.substring(0, maxLen) + '...' : text
}

function shortModel(model: string): string {
  const match = model.match(/(sonnet|opus|haiku)[\w-]*/i)
  return match ? match[0] : model.split('-').slice(1, 3).join('-')
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
  margin-bottom: 2px;
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
  gap: 6px;
}

.expand-icon {
  font-size: 10px;
  width: 14px;
  color: #909399;
  cursor: pointer;
  user-select: none;
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
  margin-left: 28px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* Directory items (Level 2) */
.directory-item {
  padding: 8px 12px 8px 40px;
  margin-bottom: 2px;
  border-radius: 6px;
  cursor: pointer;
  transition: background-color 0.2s;
}

.directory-item:hover {
  background: #ebeef5;
}

.directory-item.active {
  background: #d4e5ff;
}

.dir-info {
  display: flex;
  align-items: center;
  gap: 6px;
}

.dir-name {
  font-size: 13px;
  color: #303133;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.dirty-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #e6a23c;
  flex-shrink: 0;
}

.dir-branch {
  font-size: 11px;
  color: #909399;
  margin-top: 2px;
  margin-left: 0;
}

.branch-icon {
  font-size: 12px;
}

.empty-dir-hint {
  padding: 8px 12px 8px 40px;
  font-size: 12px;
  color: #c0c4cc;
}

.sidebar-footer {
  padding: 10px 12px;
  border-top: 1px solid #e4e7ed;
}

/* Main content area (middle column) */
.worker-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  overflow-y: auto;
  padding: 20px 24px;
}

/* When panes are open: no scroll, pane grid fills remaining space */
.worker-main.has-panes {
  overflow: hidden;
}

/* History panel (right column) */
.worker-history {
  width: 320px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  background: #fafafa;
  border-left: 1px solid #e4e7ed;
}

.history-header {
  padding: 12px 16px;
  border-bottom: 1px solid #e4e7ed;
  background: #fff;
}

.history-header h3 {
  margin: 0;
  font-size: 16px;
  color: #303133;
}

.history-content {
  flex: 1;
  overflow-y: auto;
  padding: 12px;
}

.worker-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
  flex-shrink: 0;
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

.remote-url {
  font-size: 12px;
  color: #909399;
  max-width: 300px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
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
  margin-bottom: 12px;
  flex-shrink: 0;
}

.active-overrides {
  display: flex;
  gap: 6px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}


/* Compact directory header */
.dir-compact-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
  flex-shrink: 0;
}

.dir-compact-info {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.dir-compact-info h2 {
  margin: 0;
  font-size: 18px;
  white-space: nowrap;
}

.dir-path-inline {
  font-size: 12px;
  color: #909399;
  font-family: 'Cascadia Code', 'Fira Code', monospace;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 260px;
}

.agent-teams-bar {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 8px;
  flex-shrink: 0;
}

.agent-teams-label {
  font-size: 12px;
  color: #909399;
}

.form-tip {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
  line-height: 1.4;
}

/* Mini task form (when panes are open) */
.new-task-mini {
  flex-shrink: 0;
  margin-bottom: 8px;
}

/* Empty pane hint */
.pane-empty-hint {
  flex: 1;
  display: flex;
  justify-content: center;
  align-items: center;
  color: #c0c4cc;
  font-size: 14px;
}

/* Conversation list (compact history panel) */
.conv-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.conv-item {
  padding: 8px 10px;
  border-radius: 6px;
  cursor: pointer;
  transition: background-color 0.15s;
}

.conv-item:hover {
  background: #ebeef5;
}

.conv-item:hover .conv-hover-actions {
  opacity: 1;
}

.conv-row-1 {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.conv-prompt {
  font-size: 13px;
  color: #303133;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  flex: 1;
  min-width: 0;
}

.conv-status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}

.conv-status-dot.completed { background: #67c23a; }
.conv-status-dot.running { background: #409eff; animation: convPulse 1.5s infinite; }
.conv-status-dot.failed { background: #f56c6c; }
.conv-status-dot.aborted { background: #e6a23c; }

@keyframes convPulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.3; }
}

.conv-row-2 {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 2px;
  font-size: 12px;
  color: #909399;
}

.conv-rounds {
  color: #409eff;
  font-weight: 500;
}

.conv-model {
  font-size: 11px;
  color: #909399;
  background: #f0f2f5;
  padding: 0 4px;
  border-radius: 3px;
  font-family: 'Cascadia Code', 'Fira Code', monospace;
}

.conv-cost {
  color: #67c23a;
}

.conv-time {
  flex-shrink: 0;
}

.conv-hover-actions {
  margin-left: auto;
  opacity: 0;
  transition: opacity 0.15s;
  display: flex;
  gap: 0;
}

.delete-btn {
  color: #c0c4cc !important;
}

.delete-btn:hover {
  color: #f56c6c !important;
}

.task-pagination {
  margin-top: 12px;
  justify-content: center;
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
