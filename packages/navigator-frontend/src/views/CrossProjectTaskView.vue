<template>
  <div class="cross-project-view">
    <!-- Sidebar: Task List -->
    <aside class="task-sidebar">
      <div class="sidebar-header">
        <h3>跨项目任务</h3>
        <el-button type="primary" size="small" @click="showCreateDialog = true">+ 创建</el-button>
      </div>
      <div class="task-list">
        <div
          v-for="task in tasks"
          :key="task.contextId"
          class="task-item"
          :class="{ active: selectedTask?.contextId === task.contextId }"
          @click="handleSelectTask(task.contextId)"
        >
          <div class="task-item-header">
            <span class="task-title">{{ task.title }}</span>
            <el-tag :type="statusTagType(task.status)" size="small">{{ task.status }}</el-tag>
          </div>
          <div class="task-item-meta">
            {{ task.totalPhases }} 阶段 · {{ formatDate(task.createdAt) }}
          </div>
        </div>
        <div v-if="tasks.length === 0 && !loading" class="empty-hint">
          暂无跨项目任务
        </div>
      </div>
      <div class="sidebar-footer">
        <el-button text size="small" @click="router.push('/workers')">返回 Workers</el-button>
      </div>
    </aside>

    <!-- Main: Task Detail -->
    <main class="task-detail" v-if="selectedTask">
      <div class="detail-header">
        <div>
          <h2>{{ selectedTask.title }}</h2>
          <p v-if="selectedTask.description" class="detail-desc">{{ selectedTask.description }}</p>
        </div>
        <div class="detail-actions">
          <el-tag :type="statusTagType(selectedTask.status)" size="default">
            {{ selectedTask.status }}
          </el-tag>
          <el-button
            v-if="selectedTask.status === 'DRAFT'"
            type="primary"
            size="small"
            @click="handleStartTask"
          >
            开始执行
          </el-button>
          <el-button
            v-if="selectedTask.status === 'PAUSED'"
            type="success"
            size="small"
            @click="handleReview"
          >
            审查当前阶段
          </el-button>
          <el-button
            v-if="['DRAFT', 'RUNNING', 'PAUSED'].includes(selectedTask.status)"
            type="danger"
            size="small"
            plain
            @click="handleCancel"
          >
            取消
          </el-button>
        </div>
      </div>

      <!-- Phase Timeline -->
      <div class="phase-timeline">
        <h3>阶段时间线</h3>
        <div
          v-for="phase in selectedTask.phases"
          :key="phase.phaseId"
          class="phase-card"
          :class="phaseCardClass(phase)"
        >
          <div class="phase-header">
            <span class="phase-index">[{{ phase.phaseIndex }}]</span>
            <span class="phase-name">{{ phase.phaseName }}</span>
            <el-tag :type="phaseStatusTagType(phase.status)" size="small">
              {{ phaseStatusLabel(phase.status) }}
            </el-tag>
          </div>
          <div class="phase-meta">
            <span v-if="phase.directoryName">📁 {{ phase.directoryName }}</span>
            <span v-if="phase.agentName"> · 🤖 {{ phase.agentName }}</span>
            <span v-if="phase.worktreeBranch"> · 🌿 {{ phase.worktreeBranch }}</span>
          </div>

          <!-- Handoff display/edit for AWAITING_REVIEW or COMPLETED -->
          <div
            v-if="phase.handoffArtifact || phase.status === 'AWAITING_REVIEW'"
            class="phase-handoff"
          >
            <div class="handoff-label">交接信息</div>
            <el-input
              v-if="editingHandoff === phase.phaseId"
              v-model="handoffEditText"
              type="textarea"
              :rows="6"
              placeholder="输入交接信息..."
            />
            <pre v-else class="handoff-content">{{ phase.handoffArtifact || '(尚未生成)' }}</pre>
            <div class="handoff-actions">
              <el-button
                v-if="editingHandoff !== phase.phaseId"
                size="small"
                @click="startEditHandoff(phase)"
              >
                编辑交接
              </el-button>
              <template v-if="editingHandoff === phase.phaseId">
                <el-button size="small" type="primary" @click="saveHandoff(phase)">保存</el-button>
                <el-button size="small" @click="editingHandoff = null">取消</el-button>
              </template>
              <el-button
                v-if="phase.status === 'AWAITING_REVIEW' && phase.handoffArtifact"
                size="small"
                type="success"
                @click="handleAdvance"
              >
                发送到下一阶段
              </el-button>
            </div>
          </div>

          <!-- Phase session link -->
          <div v-if="phase.phaseSessionId" class="phase-links">
            <el-button text size="small" @click="router.push(`/c/${phase.phaseSessionId}`)">
              查看 Phase 会话
            </el-button>
            <span v-if="phase.costUsd" class="phase-cost">${{ phase.costUsd?.toFixed(4) }}</span>
            <span v-if="phase.durationMs" class="phase-duration">
              {{ Math.round((phase.durationMs || 0) / 1000) }}s
            </span>
          </div>
        </div>
      </div>

      <!-- Initial session link -->
      <div v-if="selectedTask.initialSessionId" class="initial-session">
        <el-button text @click="router.push(`/c/${selectedTask.initialSessionId}`)">
          返回初始会话
        </el-button>
      </div>

      <!-- Cost summary -->
      <div v-if="selectedTask.totalCostUsd" class="cost-summary">
        总成本: ${{ selectedTask.totalCostUsd.toFixed(4) }}
      </div>
    </main>

    <!-- Empty state -->
    <main class="task-detail empty" v-else>
      <p>选择一个任务查看详情，或点击"+ 创建"新建跨项目任务</p>
    </main>

    <!-- Create Dialog -->
    <el-dialog v-model="showCreateDialog" title="创建跨项目任务" width="700px">
      <el-form :model="createForm" label-width="80px">
        <el-form-item label="标题">
          <el-input v-model="createForm.title" placeholder="任务标题" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="createForm.description" type="textarea" :rows="2" placeholder="任务描述（可选）" />
        </el-form-item>
        <el-divider content-position="left">阶段列表</el-divider>
        <div v-for="(phase, idx) in createForm.phases" :key="idx" class="phase-form-item">
          <div class="phase-form-header">
            <span>阶段 {{ idx }}</span>
            <el-button
              v-if="createForm.phases.length > 1"
              text
              size="small"
              type="danger"
              @click="createForm.phases.splice(idx, 1)"
            >
              删除
            </el-button>
          </div>
          <el-form-item label="名称">
            <el-input v-model="phase.phaseName" placeholder="阶段名称" />
          </el-form-item>
          <el-form-item label="Agent">
            <el-select v-model="phase.agentId" placeholder="选择 Agent（可选）" clearable>
              <el-option
                v-for="agent in agents"
                :key="agent.agentId"
                :label="agent.name"
                :value="agent.agentId"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="目录">
            <el-select v-model="phase.directoryId" placeholder="目标目录（Agent 默认可不填）" clearable>
              <el-option
                v-for="dir in allDirectories"
                :key="dir.directoryId"
                :label="dir.projectName + (dir.path ? ` (${dir.path})` : '')"
                :value="dir.directoryId"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="任务">
            <el-input v-model="phase.prompt" type="textarea" :rows="3" placeholder="本阶段的任务描述" />
          </el-form-item>
          <el-form-item label="分支">
            <el-input v-model="phase.worktreeBranch" placeholder="worktree 分支名（可选）" />
          </el-form-item>
        </div>
        <el-button @click="addPhase" size="small">+ 添加阶段</el-button>
      </el-form>
      <template #footer>
        <el-button @click="showCreateDialog = false">取消</el-button>
        <el-button type="primary" @click="handleCreate" :loading="creating">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useCrossProjectTask } from '@/composables/useCrossProjectTask'
import { useCodingAgent } from '@/composables/useCodingAgent'
import type { CrossProjectPhase, CrossProjectPhaseStatus, CrossProjectTaskStatus, DirectorySummary } from '@/types'

const router = useRouter()
const {
  tasks, selectedTask, loading,
  loadTasks, selectTask, createTask, startTask,
  triggerReview, updateHandoff, advancePhase, cancelTask,
} = useCrossProjectTask()

const { agents, loadAgents } = useCodingAgent()

// Collect all unique directories from agents for the create dialog
const allDirectories = ref<DirectorySummary[]>([])

// Create dialog
const showCreateDialog = ref(false)
const creating = ref(false)
const createForm = ref({
  title: '',
  description: '',
  phases: [{ phaseName: '', prompt: '', agentId: '', directoryId: '', worktreeBranch: '' }],
})

// Handoff editing
const editingHandoff = ref<string | null>(null)
const handoffEditText = ref('')

onMounted(async () => {
  await Promise.all([loadTasks(), loadAgents()])
  // Build directory list from agents' authorized directories
  const dirMap = new Map<string, DirectorySummary>()
  for (const agent of agents.value) {
    for (const dir of agent.authorizedDirectories ?? []) {
      dirMap.set(dir.directoryId, dir)
    }
  }
  allDirectories.value = Array.from(dirMap.values())
})

function addPhase() {
  createForm.value.phases.push({ phaseName: '', prompt: '', agentId: '', directoryId: '', worktreeBranch: '' })
}

async function handleCreate() {
  if (!createForm.value.title.trim()) {
    ElMessage.warning('请输入任务标题')
    return
  }
  if (createForm.value.phases.some(p => !p.phaseName.trim() || !p.prompt.trim())) {
    ElMessage.warning('每个阶段需要填写名称和任务描述')
    return
  }
  creating.value = true
  try {
    await createTask({
      title: createForm.value.title,
      description: createForm.value.description || undefined,
      phases: createForm.value.phases.map(p => ({
        phaseName: p.phaseName,
        prompt: p.prompt,
        agentId: p.agentId || undefined,
        directoryId: p.directoryId || undefined,
        worktreeBranch: p.worktreeBranch || undefined,
      })),
    })
    showCreateDialog.value = false
    createForm.value = {
      title: '',
      description: '',
      phases: [{ phaseName: '', prompt: '', agentId: '', directoryId: '', worktreeBranch: '' }],
    }
    ElMessage.success('跨项目任务已创建')
  } catch (e: any) {
    ElMessage.error(e.message || '创建失败')
  } finally {
    creating.value = false
  }
}

async function handleSelectTask(contextId: string) {
  await selectTask(contextId)
}

async function handleStartTask() {
  if (!selectedTask.value) return
  try {
    await startTask(selectedTask.value.contextId)
    ElMessage.success('任务已启动')
  } catch (e: any) {
    ElMessage.error(e.message || '启动失败')
  }
}

async function handleReview() {
  if (!selectedTask.value) return
  try {
    await triggerReview(selectedTask.value.contextId)
    ElMessage.success('已恢复初始会话进行审查')
    // Navigate to the initial session
    if (selectedTask.value.initialSessionId) {
      router.push(`/c/${selectedTask.value.initialSessionId}`)
    }
  } catch (e: any) {
    ElMessage.error(e.message || '审查触发失败')
  }
}

async function handleAdvance() {
  if (!selectedTask.value) return
  try {
    await ElMessageBox.confirm('确认将交接信息发送到下一阶段？', '推进阶段')
    await advancePhase(selectedTask.value.contextId)
    ElMessage.success('已推进到下一阶段')
  } catch (e: any) {
    if (e !== 'cancel') {
      ElMessage.error(e.message || '推进失败')
    }
  }
}

async function handleCancel() {
  if (!selectedTask.value) return
  try {
    await ElMessageBox.confirm('确认取消此跨项目任务？', '取消任务', { type: 'warning' })
    await cancelTask(selectedTask.value.contextId)
    ElMessage.success('任务已取消')
  } catch (e: any) {
    if (e !== 'cancel') {
      ElMessage.error(e.message || '取消失败')
    }
  }
}

function startEditHandoff(phase: CrossProjectPhase) {
  editingHandoff.value = phase.phaseId
  handoffEditText.value = phase.handoffArtifact || ''
}

async function saveHandoff(phase: CrossProjectPhase) {
  if (!selectedTask.value) return
  try {
    await updateHandoff(selectedTask.value.contextId, phase.phaseId, handoffEditText.value)
    editingHandoff.value = null
    ElMessage.success('交接信息已保存')
  } catch (e: any) {
    ElMessage.error(e.message || '保存失败')
  }
}

function statusTagType(status: CrossProjectTaskStatus): '' | 'success' | 'warning' | 'danger' | 'info' {
  const map: Record<string, '' | 'success' | 'warning' | 'danger' | 'info'> = {
    DRAFT: 'info', RUNNING: '', PAUSED: 'warning',
    COMPLETED: 'success', FAILED: 'danger', CANCELLED: 'info',
  }
  return map[status] || 'info'
}

function phaseStatusTagType(status: CrossProjectPhaseStatus): '' | 'success' | 'warning' | 'danger' | 'info' {
  const map: Record<string, '' | 'success' | 'warning' | 'danger' | 'info'> = {
    PENDING: 'info', RUNNING: '', AWAITING_REVIEW: 'warning',
    COMPLETED: 'success', FAILED: 'danger', SKIPPED: 'info',
  }
  return map[status] || 'info'
}

function phaseStatusLabel(status: CrossProjectPhaseStatus): string {
  const map: Record<string, string> = {
    PENDING: '待执行', RUNNING: '执行中', AWAITING_REVIEW: '待审查',
    COMPLETED: '已完成', FAILED: '失败', SKIPPED: '已跳过',
  }
  return map[status] || status
}

function phaseCardClass(phase: CrossProjectPhase): string {
  return `phase-${phase.status.toLowerCase().replace('_', '-')}`
}

function formatDate(dateStr: string): string {
  if (!dateStr) return ''
  const d = new Date(dateStr)
  return `${d.getMonth() + 1}/${d.getDate()} ${d.getHours()}:${String(d.getMinutes()).padStart(2, '0')}`
}
</script>

<style scoped>
.cross-project-view {
  display: flex;
  height: 100vh;
  background: #f5f7fa;
}

.task-sidebar {
  width: 280px;
  background: #fff;
  border-right: 1px solid #e4e7ed;
  display: flex;
  flex-direction: column;
}

.sidebar-header {
  padding: 16px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  border-bottom: 1px solid #e4e7ed;
}

.sidebar-header h3 {
  margin: 0;
  font-size: 16px;
}

.task-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}

.task-item {
  padding: 12px;
  border-radius: 6px;
  cursor: pointer;
  margin-bottom: 4px;
  transition: background 0.2s;
}

.task-item:hover {
  background: #f0f2f5;
}

.task-item.active {
  background: #e1eaff;
}

.task-item-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.task-title {
  font-weight: 500;
  font-size: 14px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
  margin-right: 8px;
}

.task-item-meta {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}

.sidebar-footer {
  padding: 12px 16px;
  border-top: 1px solid #e4e7ed;
}

.empty-hint {
  text-align: center;
  color: #909399;
  padding: 40px 16px;
  font-size: 14px;
}

/* Main detail area */

.task-detail {
  flex: 1;
  padding: 24px;
  overflow-y: auto;
}

.task-detail.empty {
  display: flex;
  align-items: center;
  justify-content: center;
  color: #909399;
}

.detail-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 24px;
}

.detail-header h2 {
  margin: 0 0 4px;
  font-size: 20px;
}

.detail-desc {
  color: #606266;
  margin: 0;
}

.detail-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

/* Phase timeline */

.phase-timeline h3 {
  font-size: 16px;
  margin-bottom: 16px;
}

.phase-card {
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 16px;
  margin-bottom: 12px;
  border-left: 4px solid #dcdfe6;
}

.phase-card.phase-completed {
  border-left-color: #67c23a;
}

.phase-card.phase-running {
  border-left-color: #409eff;
}

.phase-card.phase-awaiting-review {
  border-left-color: #e6a23c;
}

.phase-card.phase-failed {
  border-left-color: #f56c6c;
}

.phase-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.phase-index {
  font-weight: 600;
  color: #909399;
  font-size: 13px;
}

.phase-name {
  font-weight: 500;
  flex: 1;
}

.phase-meta {
  font-size: 13px;
  color: #909399;
  margin-bottom: 8px;
}

.phase-handoff {
  background: #f5f7fa;
  border-radius: 6px;
  padding: 12px;
  margin-top: 8px;
}

.handoff-label {
  font-size: 12px;
  font-weight: 600;
  color: #606266;
  margin-bottom: 8px;
}

.handoff-content {
  white-space: pre-wrap;
  font-size: 13px;
  color: #303133;
  margin: 0;
  font-family: 'SF Mono', Monaco, Consolas, monospace;
  background: #fff;
  padding: 8px;
  border-radius: 4px;
  border: 1px solid #e4e7ed;
}

.handoff-actions {
  margin-top: 8px;
  display: flex;
  gap: 8px;
}

.phase-links {
  margin-top: 8px;
  display: flex;
  align-items: center;
  gap: 12px;
}

.phase-cost, .phase-duration {
  font-size: 12px;
  color: #909399;
}

.initial-session {
  margin-top: 24px;
  padding-top: 16px;
  border-top: 1px solid #e4e7ed;
}

.cost-summary {
  margin-top: 12px;
  font-size: 14px;
  color: #606266;
}

/* Create dialog */

.phase-form-item {
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  padding: 16px;
  margin-bottom: 12px;
}

.phase-form-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
  font-weight: 500;
}
</style>
