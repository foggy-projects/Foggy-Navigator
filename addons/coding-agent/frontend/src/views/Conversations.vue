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
        <el-table-column prop="projectId" label="项目ID" />
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

    <el-dialog v-model="createDialogVisible" title="创建会话" width="500px">
      <el-form :model="createForm" label-width="100px">
        <el-form-item label="用户ID">
          <el-input v-model="createForm.userId" placeholder="请输入用户ID" />
        </el-form-item>
        <el-form-item label="初始消息">
          <el-input
            v-model="createForm.initialMessage"
            type="textarea"
            :rows="4"
            placeholder="请输入初始消息"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleCreate">创建</el-button>
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
import type { Conversation } from '@/types'

const router = useRouter()
const loading = ref(false)
const conversations = ref<Conversation[]>([])
const createDialogVisible = ref(false)
const createForm = ref({
  userId: '',
  initialMessage: ''
})

const loadData = async () => {
  loading.value = true
  try {
    conversations.value = await listConversations()
  } catch (error: any) {
    ElMessage.error(error.message || '加载失败')
  } finally {
    loading.value = false
  }
}

const handleCreate = async () => {
  if (!createForm.value.userId || !createForm.value.initialMessage) {
    ElMessage.warning('请填写完整信息')
    return
  }

  try {
    await createConversation(createForm.value)
    ElMessage.success('创建成功')
    createDialogVisible.value = false
    createForm.value = { userId: '', initialMessage: '' }
    loadData()
  } catch (error: any) {
    ElMessage.error(error.message || '创建失败')
  }
}

const viewDetail = (conversationId: string) => {
  router.push(`/conversations/${conversationId}`)
}

const stopConv = async (conversationId: string) => {
  try {
    await stopConversation(conversationId)
    ElMessage.success('已停止会话')
    loadData()
  } catch (error: any) {
    ElMessage.error(error.message || '操作失败')
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
  } catch (error: any) {
    ElMessage.error(error.message || '删除失败')
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
