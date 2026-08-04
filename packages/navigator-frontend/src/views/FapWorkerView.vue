<template>
  <div class="fap-page">
    <div v-if="initializing" class="gate-state">
      <span class="gate-code">FAP_V1 / DISCOVERY</span>
      <el-icon class="is-loading" :size="24"><Loading /></el-icon>
      <p>正在确认个人灰度资格……</p>
    </div>

    <div v-else-if="!isEligible" class="gate-state unavailable">
      <span class="gate-code">FAP_V1 / {{ availabilityState.toUpperCase() }}</span>
      <h1>新版工作台未对当前实例开放</h1>
      <p>{{ unavailableMessage }}</p>
      <el-button type="primary" @click="router.push('/')">返回稳定版 Workers</el-button>
    </div>

    <template v-else>
      <FapConversationRail
        :conversations="conversations"
        :selected-id="selectedConversation?.conversationId"
        :loading="conversationsLoading"
        @create="createDialogOpen = true"
        @refresh="handleRefreshConversations"
        @select="handleSelectConversation"
      />

      <main class="work-area">
        <header class="canary-header">
          <div class="canary-identity">
            <span class="lane-stamp">FAP_V1</span>
            <div>
              <strong>个人灰度工作台</strong>
              <small>新旧并行 · 会话链路创建后不可切换</small>
            </div>
          </div>
          <div class="header-actions">
            <el-button text size="small" @click="router.push('/')">
              <el-icon><Back /></el-icon>
              稳定版 Workers
            </el-button>
          </div>
        </header>

        <template v-if="selectedConversation">
          <section class="conversation-header">
            <div class="conversation-title">
              <span class="state-dot" :class="stateTone" />
              <div>
                <h1>{{ selectedConversation.title }}</h1>
                <p>
                  {{ selectedConversation.displayState || selectedConversation.bindingStatus }}
                  <span>·</span>
                  {{ selectedConversation.taskType || 'TASK' }}
                  <span v-if="selectedConversation.terminalKind">· {{ selectedConversation.terminalKind }}</span>
                </p>
              </div>
            </div>
            <div class="conversation-actions">
              <el-button
                v-if="canCancel"
                text
                size="small"
                :loading="commandPending"
                class="cancel-action"
                @click="handleCancel"
              >
                中止本轮
              </el-button>
              <el-button text size="small" :loading="selectedLoading" @click="handleRefreshSelected">
                <el-icon><Refresh /></el-icon>
                刷新事实
              </el-button>
              <el-button text size="small" :loading="commandPending" @click="handleReattach">
                <el-icon><Connection /></el-icon>
                重连
              </el-button>
            </div>
          </section>

          <div v-if="pollingPaused" class="polling-warning">
            <div>
              <strong>自动同步已在连续 {{ pollingFailureCount }} 次失败后暂停</strong>
              <span>{{ lastPollingError }}</span>
            </div>
            <el-button size="small" @click="resumePolling">手动恢复</el-button>
          </div>

          <div v-if="selectedConversation.bindingStatus === 'START_OUTCOME_UNKNOWN'" class="outcome-warning">
            启动结果不明确。系统不会自动重放 START，也不会回退到旧版；请先核对 Runtime 后再由你决定。
          </div>

          <div class="timeline-area" :class="{ loading: selectedLoading }">
            <FapEventTimeline :events="events" />
          </div>

          <footer class="composer">
            <div v-if="isTaskRunning" class="running-notice">
              <span class="running-pulse" />
              Worker 正在执行。本轮得到明确终态后才能继续发送下一条任务。
            </div>
            <template v-else-if="canContinue">
              <el-input
                v-model="continuationPrompt"
                type="textarea"
                :rows="3"
                resize="none"
                placeholder="继续当前 Worker 会话……"
                @keydown.meta.enter.prevent="handleContinue"
                @keydown.ctrl.enter.prevent="handleContinue"
              />
              <div class="composer-tools">
                <div>
                  <span>Reasoning</span>
                  <el-select v-model="continuationReasoning" size="small">
                    <el-option label="Worker 默认" value="" />
                    <el-option label="Medium" value="medium" />
                    <el-option label="High" value="high" />
                    <el-option label="XHigh" value="xhigh" />
                  </el-select>
                </div>
                <el-button type="primary" :loading="commandPending" @click="handleContinue">
                  继续任务
                  <el-icon><Promotion /></el-icon>
                </el-button>
              </div>
            </template>
            <div v-else class="blocked-notice">
              当前绑定不是可继续状态。不会自动创建替代会话。
            </div>
          </footer>
        </template>

        <div v-else class="workspace-empty">
          <span class="coordinate">00 / FAP</span>
          <h1>选择会话，或从一条新任务开始。</h1>
          <p>此工作台只显示新架构产生的 Worker 事实；旧版 Workers 保持独立运行。</p>
          <el-button type="primary" @click="createDialogOpen = true">建立首个 FAP 会话</el-button>
        </div>
      </main>

      <FapEvidencePanel
        :conversation="selectedConversation"
        :resources="resources"
        :recovery="recovery"
        :loading="evidenceLoading"
        @load="handleLoadEvidence"
      />

      <FapNewConversationDialog
        v-model="createDialogOpen"
        :worker-profiles="workerProfiles"
        :workspaces="workspaces"
        :model-configs="modelConfigs"
        :catalog-loading="catalogLoading"
        :pending="commandPending"
        @submit="handleStart"
      />
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Back, Connection, Loading, Promotion, Refresh } from '@element-plus/icons-vue'
import FapConversationRail from '@/components/workbench-fap/FapConversationRail.vue'
import FapEventTimeline from '@/components/workbench-fap/FapEventTimeline.vue'
import FapEvidencePanel from '@/components/workbench-fap/FapEvidencePanel.vue'
import FapNewConversationDialog from '@/components/workbench-fap/FapNewConversationDialog.vue'
import { useWorkbenchFap } from '@/composables/useWorkbenchFap'

type StartDraft = {
  title?: string
  workerProfileRef: string
  workspaceRef: string
  modelConfigRef?: string
  allowDefaultModelConfig: boolean
  prompt: string
  reasoningEffort?: string
}

const router = useRouter()
const createDialogOpen = ref(false)
const continuationPrompt = ref('')
const continuationReasoning = ref('high')

const {
  availabilityState,
  conversations,
  selectedConversation,
  events,
  resources,
  recovery,
  workerProfiles,
  workspaces,
  modelConfigs,
  initializing,
  catalogLoading,
  conversationsLoading,
  selectedLoading,
  commandPending,
  evidenceLoading,
  pollingPaused,
  pollingFailureCount,
  lastPollingError,
  isEligible,
  isTaskRunning,
  canContinue,
  canCancel,
  refreshConversations,
  selectConversation,
  refreshSelected,
  startConversation,
  continueConversation,
  cancelCurrent,
  reattachCurrent,
  loadEvidence,
  resumePolling,
} = useWorkbenchFap()

const unavailableMessage = computed(() => {
  if (availabilityState.value === 'not-packaged') return '当前后端未打包个人灰度模块，稳定版不受影响。'
  if (availabilityState.value === 'disabled') return '模块已打包但服务端开关关闭，稳定版不受影响。'
  return '当前用户不在个人灰度白名单中。公司同事继续使用稳定版。'
})

const stateTone = computed(() => {
  const value = selectedConversation.value
  if (value?.bindingStatus === 'START_OUTCOME_UNKNOWN') return 'warning'
  if (value?.bindingStatus === 'START_FAILED' || value?.terminalKind === 'FAILED') return 'danger'
  if (value?.definitiveTerminal) return 'settled'
  return 'running'
})

async function handleStart(draft: StartDraft): Promise<void> {
  try {
    await startConversation(draft)
    createDialogOpen.value = false
    ElMessage.success('FAP 会话已创建')
  } catch {
    // The shared API client already presents the server's typed error.
  }
}

async function handleRefreshConversations(): Promise<void> {
  try {
    await refreshConversations()
  } catch {
    // The shared API client already presents the server's typed error.
  }
}

async function handleSelectConversation(conversationId: string): Promise<void> {
  try {
    await selectConversation(conversationId)
  } catch {
    // The shared API client already presents the server's typed error.
  }
}

async function handleRefreshSelected(): Promise<void> {
  try {
    await refreshSelected(true)
  } catch {
    // The shared API client already presents the server's typed error.
  }
}

async function handleLoadEvidence(): Promise<void> {
  try {
    await loadEvidence()
  } catch {
    // The shared API client already presents the server's typed error.
  }
}

async function handleContinue(): Promise<void> {
  const prompt = continuationPrompt.value.trim()
  if (!prompt) {
    ElMessage.warning('请输入继续任务的内容')
    return
  }
  try {
    await continueConversation(prompt, continuationReasoning.value || undefined)
    continuationPrompt.value = ''
  } catch {
    // The shared API client already presents the server's typed error.
  }
}

async function handleCancel(): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '中止只作用于当前 FAP 任务，不会删除会话或切换到旧版。是否继续？',
      '中止当前任务',
      { type: 'warning', confirmButtonText: '中止', cancelButtonText: '返回' },
    )
  } catch {
    return
  }
  try {
    await cancelCurrent()
  } catch {
    // The shared API client already presents the server's typed error.
  }
}

async function handleReattach(): Promise<void> {
  try {
    await reattachCurrent()
    ElMessage.success('已请求 Runtime 重新加载 Worker 事实')
  } catch {
    // The shared API client already presents the server's typed error.
  }
}

</script>

<style scoped>
.fap-page {
  --fap-ink: #252a26;
  --fap-muted: #7c847c;
  --fap-line: #dfe3dc;
  --fap-green: #175f56;
  height: 100%;
  min-height: 0;
  display: flex;
  overflow: hidden;
  color: var(--fap-ink);
  background:
    linear-gradient(90deg, rgb(38 54 45 / 2%) 1px, transparent 1px) 0 0 / 32px 32px,
    #f5f6f3;
  font-family: "IBM Plex Sans", "Noto Sans SC", sans-serif;
}

.gate-state {
  margin: auto;
  max-width: 520px;
  padding: 48px;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 16px;
  background: #fff;
  border: 1px solid var(--fap-line);
  box-shadow: 0 18px 50px rgb(35 45 39 / 8%);
}

.gate-code {
  color: #317c71;
  font: 600 10px/1.3 "IBM Plex Mono", monospace;
  letter-spacing: 0.12em;
}

.gate-state h1 { margin: 0; font-size: 24px; }
.gate-state p { margin: 0; color: var(--fap-muted); line-height: 1.7; }

.work-area {
  min-width: 0;
  min-height: 0;
  flex: 1;
  display: grid;
  grid-template-rows: 54px auto auto minmax(0, 1fr) auto;
  background: #fafbf9;
}

.canary-header {
  padding: 0 18px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #26332f;
  border-bottom: 1px solid #18231f;
}

.canary-identity {
  display: flex;
  align-items: center;
  gap: 12px;
  color: #f5f8f5;
}

.lane-stamp {
  padding: 4px 7px;
  color: #c4e6de;
  border: 1px solid #527a70;
  font: 600 10px/1.2 "IBM Plex Mono", monospace;
  letter-spacing: 0.08em;
}

.canary-identity div { display: flex; flex-direction: column; gap: 2px; }
.canary-identity strong { font-size: 13px; }
.canary-identity small { color: #aebbb6; font-size: 10px; }
.header-actions :deep(.el-button) { color: #d7dfdb; }

.conversation-header {
  min-height: 66px;
  padding: 10px 18px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  background: #fff;
  border-bottom: 1px solid var(--fap-line);
}

.conversation-title {
  min-width: 0;
  display: flex;
  align-items: flex-start;
  gap: 10px;
}

.state-dot {
  width: 8px;
  height: 8px;
  margin-top: 7px;
  flex: 0 0 auto;
  border-radius: 50%;
  background: #818a81;
}

.state-dot.running { background: #188b7b; box-shadow: 0 0 0 4px rgb(24 139 123 / 12%); }
.state-dot.warning { background: #d28e21; }
.state-dot.danger { background: #c34c43; }

.conversation-title h1 {
  margin: 0;
  overflow: hidden;
  font-size: 15px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.conversation-title p {
  margin: 4px 0 0;
  color: #8b928b;
  font: 10px/1.3 "IBM Plex Mono", monospace;
}

.conversation-actions { flex: 0 0 auto; display: flex; }
.cancel-action { color: #a4423b; }

.polling-warning,
.outcome-warning {
  padding: 8px 18px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: #765312;
  background: #fff8e8;
  border-bottom: 1px solid #ead8ad;
  font-size: 12px;
}

.polling-warning div { display: flex; flex-direction: column; gap: 2px; }
.polling-warning span { color: #9a7b40; font-size: 11px; }
.outcome-warning { display: block; }

.timeline-area { min-height: 0; position: relative; }
.timeline-area.loading { opacity: 0.72; }

.composer {
  padding: 12px 16px;
  background: #fff;
  border-top: 1px solid var(--fap-line);
}

.running-notice,
.blocked-notice {
  min-height: 56px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 9px;
  color: #697269;
  font-size: 12px;
}

.running-pulse {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #1b8b7b;
  animation: fap-pulse 1.7s ease-out infinite;
}

@keyframes fap-pulse {
  0% { box-shadow: 0 0 0 0 rgb(27 139 123 / 35%); }
  70%, 100% { box-shadow: 0 0 0 7px rgb(27 139 123 / 0%); }
}

.composer :deep(.el-textarea__inner) {
  border-radius: 3px;
  box-shadow: 0 0 0 1px #cfd5ce inset;
  font: 13px/1.55 "IBM Plex Sans", "Noto Sans SC", sans-serif;
}

.composer-tools {
  margin-top: 8px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.composer-tools > div { display: flex; align-items: center; gap: 8px; }
.composer-tools span { color: #8b928a; font: 10px/1.3 "IBM Plex Mono", monospace; }
.composer-tools :deep(.el-select) { width: 130px; }
.composer-tools :deep(.el-button--primary) { background: var(--fap-green); border-color: var(--fap-green); }

.workspace-empty {
  grid-row: 2 / -1;
  max-width: 560px;
  margin: auto;
  padding: 54px;
  background: rgb(255 255 255 / 88%);
  border: 1px solid var(--fap-line);
}

.coordinate {
  color: #8ca198;
  font: 500 11px/1.3 "IBM Plex Mono", monospace;
  letter-spacing: 0.12em;
}

.workspace-empty h1 { margin: 16px 0 8px; font-size: 24px; line-height: 1.35; }
.workspace-empty p { margin: 0 0 22px; color: var(--fap-muted); line-height: 1.65; }

</style>
