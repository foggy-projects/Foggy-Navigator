<template>
  <div class="dashboard">
    <el-row :gutter="20">
      <el-col :span="6">
        <el-card shadow="hover">
          <template #header>
            <span>会话统计</span>
          </template>
          <div class="stat-content">
            <div class="stat-number">{{ stats.conversations }}</div>
            <div class="stat-label">总会话数</div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover">
          <template #header>
            <span>活跃会话</span>
          </template>
          <div class="stat-content">
            <div class="stat-number">{{ stats.activeConversations }}</div>
            <div class="stat-label">正在进行</div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover">
          <template #header>
            <span>容器统计</span>
          </template>
          <div class="stat-content">
            <div class="stat-number">{{ stats.containers }}</div>
            <div class="stat-label">运行中容器</div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover">
          <template #header>
            <span>今日消息</span>
          </template>
          <div class="stat-content">
            <div class="stat-number">{{ stats.todayMessages }}</div>
            <div class="stat-label">消息数量</div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="20" style="margin-top: 20px;">
      <el-col :span="12">
        <el-card>
          <template #header>
            <span>最近会话</span>
          </template>
          <el-skeleton v-if="loading" :rows="5" animated />
          <el-table v-else :data="recentConversations" stripe max-height="400">
            <el-table-column prop="conversationId" label="ID" width="320" />
            <el-table-column prop="projectId" label="项目" />
            <el-table-column prop="status" label="状态" width="100">
              <template #default="{ row }">
                <StatusBadge :status="row.status" />
              </template>
            </el-table-column>
            <el-table-column label="操作" width="100">
              <template #default="{ row }">
                <el-button link type="primary" @click="viewConversation(row.conversationId)">
                  查看
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card>
          <template #header>
            <span>最近事件</span>
          </template>
          <el-skeleton v-if="loading" :rows="5" animated />
          <el-table v-else :data="recentEvents" stripe max-height="400">
            <el-table-column prop="type" label="类型" width="150" />
            <el-table-column prop="data" label="内容" show-overflow-tooltip />
            <el-table-column prop="createdAt" label="时间" width="180" />
          </el-table>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import StatusBadge from '@/components/StatusBadge.vue'
import { listConversations } from '@/api/conversation'
import { listContainers } from '@/api/container'
import { listEvents } from '@/api/event'
import type { Conversation, Event } from '@/types'

const router = useRouter()
const loading = ref(false)

const stats = ref({
  conversations: 0,
  activeConversations: 0,
  containers: 0,
  todayMessages: 0
})

const recentConversations = ref<Conversation[]>([])
const recentEvents = ref<Event[]>([])

const loadData = async () => {
  loading.value = true
  try {
    const [conversations, containers, events] = await Promise.all([
      listConversations(),
      listContainers(),
      listEvents({ limit: 10 })
    ])

    stats.value.conversations = conversations.length
    stats.value.activeConversations = conversations.filter(c => c.status === 'RUNNING' || c.status === 'READY').length
    stats.value.containers = containers.filter(c => c.status === 'RUNNING').length
    stats.value.todayMessages = 0

    recentConversations.value = conversations.slice(0, 5)
    recentEvents.value = events
  } catch (error: any) {
    ElMessage.error(error.message || '加载数据失败')
  } finally {
    loading.value = false
  }
}

const viewConversation = (id: string) => {
  router.push(`/conversations/${id}`)
}

onMounted(() => {
  loadData()
})
</script>

<style scoped>
.dashboard {
  padding: 20px;
}

.stat-content {
  text-align: center;
  padding: 20px 0;
}

.stat-number {
  font-size: 32px;
  font-weight: bold;
  color: #409eff;
}

.stat-label {
  margin-top: 10px;
  color: #909399;
  font-size: 14px;
}
</style>
