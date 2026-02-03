<template>
  <div class="page-container">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>会话管理</span>
          <div>
            <el-button @click="loadData" :loading="loading">
              刷新
            </el-button>
            <el-button type="primary" @click="createDialogVisible = true">
              创建会话
            </el-button>
          </div>
        </div>
      </template>

      <el-skeleton v-if="loading" :rows="5" animated />

      <el-table v-else :data="conversations" stripe>
        <el-table-column prop="conversationId" label="会话ID" width="320" />
        <el-table-column prop="userId" label="用户ID" width="150" />
        <el-table-column label="项目" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.gitProjectPath">{{ row.gitProjectPath }}</span>
            <span v-else-if="row.gitRepoUrl">{{ row.gitRepoUrl }}</span>
            <span v-else style="color: #999">-</span>
          </template>
        </el-table-column>
        <el-table-column label="分支" width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <template v-if="row.workingBranch">
              <el-tooltip :content="`基准: ${row.baseBranch || '-'}`" placement="top">
                <span>{{ row.workingBranch }}</span>
              </el-tooltip>
            </template>
            <span v-else-if="row.branchName">{{ row.branchName }}</span>
            <span v-else style="color: #999">-</span>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="120">
          <template #default="{ row }">
            <StatusBadge :status="row.status" />
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="180" />
        <el-table-column label="操作" width="200">
          <template #default="{ row }">
            <el-button link type="primary" @click="viewDetail(row.conversationId)">
              查看
            </el-button>
            <el-button
              link
              type="warning"
              @click="stopConv(row.conversationId)"
              :disabled="row.status !== 'RUNNING' && row.status !== 'READY'"
            >
              停止
            </el-button>
            <el-button link type="danger" @click="deleteConv(row.conversationId)">
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="createDialogVisible" title="创建会话" width="600px" :close-on-click-modal="!creating" :close-on-press-escape="!creating" :show-close="!creating">
      <el-form :model="createForm" label-width="110px">
        <el-form-item label="用户ID" required>
          <el-input v-model="createForm.userId" placeholder="请输入用户ID" @blur="handleUserIdChange" />
        </el-form-item>

        <!-- Git 凭证选择 -->
        <el-form-item label="Git 凭证">
          <el-select
            v-model="createForm.gitCredentialId"
            placeholder="选择 Git 凭证（可选）"
            clearable
            style="width: 100%"
            @change="handleCredentialChange"
          >
            <el-option
              v-for="cred in credentials"
              :key="cred.credentialId"
              :label="`${cred.displayName} (${cred.provider})`"
              :value="cred.credentialId"
            />
          </el-select>
          <el-button link type="primary" @click="credentialDialogVisible = true" style="margin-left: 8px">
            添加凭证
          </el-button>
        </el-form-item>

        <!-- Git 项目选择（有凭证时显示） -->
        <el-form-item v-if="createForm.gitCredentialId" label="Git 项目">
          <el-select
            v-model="createForm.gitProjectId"
            placeholder="选择项目"
            filterable
            remote
            :remote-method="searchProjects"
            :loading="loadingProjects"
            style="width: 100%"
            @change="handleProjectChange"
          >
            <el-option
              v-for="proj in projects"
              :key="proj.id"
              :label="proj.pathWithNamespace"
              :value="proj.id"
            >
              <span>{{ proj.pathWithNamespace }}</span>
              <span v-if="proj.description" style="color: #999; margin-left: 8px; font-size: 12px">
                {{ proj.description.substring(0, 30) }}
              </span>
            </el-option>
          </el-select>
        </el-form-item>

        <!-- 基准分支选择（有项目时显示） -->
        <el-form-item v-if="createForm.gitProjectId" label="基准分支">
          <el-select
            v-model="createForm.baseBranch"
            placeholder="选择基准分支"
            filterable
            :loading="loadingBranches"
            style="width: 100%"
          >
            <el-option
              v-for="branch in branches"
              :key="branch.name"
              :label="branch.name + (branch.isDefault ? ' (default)' : '')"
              :value="branch.name"
            />
          </el-select>
        </el-form-item>

        <!-- 任务描述（用于生成工作分支名） -->
        <el-form-item v-if="createForm.gitProjectId" label="任务描述">
          <el-input v-model="createForm.taskDescription" placeholder="如: fix-login-bug（用于生成工作分支名）" />
        </el-form-item>

        <!-- 旧模式：直接输入 URL（无凭证时显示） -->
        <template v-if="!createForm.gitCredentialId">
          <el-divider content-position="center">或直接输入仓库信息</el-divider>
          <el-form-item label="Git 仓库 URL">
            <el-input v-model="createForm.gitRepoUrl" placeholder="http://gitlab.example.com/group/repo.git" />
          </el-form-item>
          <el-form-item label="分支名">
            <el-input v-model="createForm.branchName" placeholder="main（可选）" />
          </el-form-item>
        </template>

        <el-divider />

        <el-form-item label="初始消息" required>
          <el-input
            v-model="createForm.initialMessage"
            type="textarea"
            :rows="4"
            placeholder="请输入初始消息"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialogVisible = false" :disabled="creating">取消</el-button>
        <el-button type="primary" @click="handleCreate" :loading="creating">
          {{ creating ? '创建中...' : '创建' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 添加凭证弹窗 -->
    <el-dialog v-model="credentialDialogVisible" title="添加 Git 凭证" width="480px">
      <el-form :model="credentialForm" label-width="100px">
        <el-form-item label="服务类型" required>
          <el-select v-model="credentialForm.provider" style="width: 100%">
            <el-option label="GitLab" value="GITLAB" />
            <el-option label="GitHub" value="GITHUB" />
          </el-select>
        </el-form-item>
        <el-form-item label="服务地址" required>
          <el-input
            v-model="credentialForm.serverUrl"
            :placeholder="credentialForm.provider === 'GITHUB' ? 'https://github.com（或 GitHub Enterprise 地址）' : 'https://gitlab.example.com'"
          />
        </el-form-item>
        <el-form-item label="显示名称" required>
          <el-input v-model="credentialForm.displayName" placeholder="如: 公司GitLab" />
        </el-form-item>
        <el-form-item label="Access Token" required>
          <el-input v-model="credentialForm.accessToken" type="password" show-password placeholder="Personal Access Token" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="credentialDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleCreateCredential" :loading="creatingCredential">
          保存
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import StatusBadge from '@/components/StatusBadge.vue'
import { listConversations, createConversation, deleteConversation, stopConversation } from '@/api/conversation'
import { listGitCredentials, createGitCredential, listGitProjects, listGitBranches } from '@/api/git'
import type { Conversation, GitCredential, GitProject, GitBranch, GitProvider } from '@/types'

const router = useRouter()
const loading = ref(false)
const conversations = ref<Conversation[]>([])
const createDialogVisible = ref(false)
const creating = ref(false)

// Git 相关状态
const credentials = ref<GitCredential[]>([])
const projects = ref<GitProject[]>([])
const branches = ref<GitBranch[]>([])
const loadingProjects = ref(false)
const loadingBranches = ref(false)

// 凭证弹窗
const credentialDialogVisible = ref(false)
const creatingCredential = ref(false)
const credentialForm = ref({
  provider: 'GITLAB' as GitProvider,
  serverUrl: '',
  displayName: '',
  accessToken: '',
})

const createForm = ref({
  userId: '',
  initialMessage: '',
  // 新流程
  gitCredentialId: '',
  gitProjectId: '',
  baseBranch: '',
  taskDescription: '',
  // 旧流程
  gitRepoUrl: '',
  branchName: '',
})

const loadData = async () => {
  loading.value = true
  try {
    conversations.value = await listConversations()
  } catch (error: unknown) {
    const msg = error instanceof Error ? error.message : '加载失败'
    ElMessage.error(msg)
  } finally {
    loading.value = false
  }
}

// 加载凭证列表
const loadCredentials = async () => {
  if (!createForm.value.userId) return
  try {
    credentials.value = await listGitCredentials(createForm.value.userId)
  } catch (error) {
    console.error('加载凭证列表失败', error)
  }
}

// 凭证变更时加载项目
const handleCredentialChange = async (credentialId: string) => {
  createForm.value.gitProjectId = ''
  createForm.value.baseBranch = ''
  projects.value = []
  branches.value = []

  if (credentialId) {
    await searchProjects('')
  }
}

// 搜索项目
const searchProjects = async (query: string) => {
  if (!createForm.value.gitCredentialId || !createForm.value.userId) return

  loadingProjects.value = true
  try {
    projects.value = await listGitProjects(createForm.value.userId, createForm.value.gitCredentialId, query || undefined)
  } catch (error) {
    ElMessage.error('加载项目列表失败')
  } finally {
    loadingProjects.value = false
  }
}

// 项目变更时加载分支
const handleProjectChange = async (projectId: string) => {
  createForm.value.baseBranch = ''
  branches.value = []

  if (projectId && createForm.value.gitCredentialId && createForm.value.userId) {
    loadingBranches.value = true
    try {
      branches.value = await listGitBranches(createForm.value.userId, createForm.value.gitCredentialId, projectId)
      // 自动选择默认分支
      const defaultBranch = branches.value.find(b => b.isDefault)
      if (defaultBranch) {
        createForm.value.baseBranch = defaultBranch.name
      }
    } catch (error) {
      ElMessage.error('加载分支列表失败')
    } finally {
      loadingBranches.value = false
    }
  }
}

// 创建凭证
const handleCreateCredential = async () => {
  const { provider, serverUrl, displayName, accessToken } = credentialForm.value
  if (!createForm.value.userId || !serverUrl || !displayName || !accessToken) {
    ElMessage.warning('请先填写用户ID，并填写完整凭证信息')
    return
  }

  creatingCredential.value = true
  try {
    await createGitCredential(createForm.value.userId, { provider, serverUrl, displayName, accessToken })
    ElMessage.success('凭证创建成功')
    credentialDialogVisible.value = false
    credentialForm.value = { provider: 'GITLAB', serverUrl: '', displayName: '', accessToken: '' }
    await loadCredentials()
  } catch (error) {
    ElMessage.error('创建凭证失败')
  } finally {
    creatingCredential.value = false
  }
}

const handleCreate = async () => {
  if (!createForm.value.userId || !createForm.value.initialMessage) {
    ElMessage.warning('请填写用户ID和初始消息')
    return
  }

  creating.value = true
  try {
    const req: Record<string, string> = {
      userId: createForm.value.userId,
      initialMessage: createForm.value.initialMessage,
    }

    // 新流程：使用凭证+项目+分支
    if (createForm.value.gitCredentialId && createForm.value.gitProjectId) {
      req.gitCredentialId = createForm.value.gitCredentialId
      req.gitProjectId = createForm.value.gitProjectId
      if (createForm.value.baseBranch) req.baseBranch = createForm.value.baseBranch
      if (createForm.value.taskDescription) req.taskDescription = createForm.value.taskDescription
    } else {
      // 旧流程：直接传 URL
      if (createForm.value.gitRepoUrl) req.gitRepoUrl = createForm.value.gitRepoUrl
      if (createForm.value.branchName) req.branchName = createForm.value.branchName
    }

    const conv = await createConversation(req as any)
    ElMessage.success('创建成功')
    createDialogVisible.value = false
    resetCreateForm()
    router.push(`/conversations/${conv.conversationId}`)
  } catch (error: unknown) {
    const msg = error instanceof Error ? error.message : '创建失败'
    ElMessage.error(msg)
  } finally {
    creating.value = false
  }
}

const resetCreateForm = () => {
  createForm.value = {
    userId: '',
    initialMessage: '',
    gitCredentialId: '',
    gitProjectId: '',
    baseBranch: '',
    taskDescription: '',
    gitRepoUrl: '',
    branchName: '',
  }
  projects.value = []
  branches.value = []
}

const viewDetail = (conversationId: string) => {
  router.push(`/conversations/${conversationId}`)
}

const stopConv = async (conversationId: string) => {
  try {
    await stopConversation(conversationId)
    ElMessage.success('已停止会话')
    loadData()
  } catch (error: unknown) {
    const msg = error instanceof Error ? error.message : '操作失败'
    ElMessage.error(msg)
  }
}

const deleteConv = async (conversationId: string) => {
  await ElMessageBox.confirm('确认删除该会话？', '警告', {
    type: 'warning'
  })
  try {
    await deleteConversation(conversationId)
    ElMessage.success('删除成功')
    loadData()
  } catch (error: unknown) {
    const msg = error instanceof Error ? error.message : '删除失败'
    ElMessage.error(msg)
  }
}

// userId 变化时加载凭证
const handleUserIdChange = () => {
  credentials.value = []
  createForm.value.gitCredentialId = ''
  createForm.value.gitProjectId = ''
  createForm.value.baseBranch = ''
  projects.value = []
  branches.value = []
  if (createForm.value.userId) {
    loadCredentials()
  }
}

onMounted(() => {
  loadData()
})
</script>

<style scoped>
.page-container {
  padding: 20px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
</style>
