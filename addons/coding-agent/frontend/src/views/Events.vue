<template>
  <div class="page-container">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>事件日志</span>
          <div class="filter-controls">
            <el-input
              v-model="filters.conversationId"
              placeholder="会话ID筛选"
              style="width: 200px; margin-right: 10px;"
              clearable
            />
            <el-input
              v-model="filters.type"
              placeholder="事件类型筛选"
              style="width: 150px; margin-right: 10px;"
              clearable
            />
            <el-button type="primary" @click="loadData">查询</el-button>
          </div>
        </div>
      </template>

      <el-skeleton v-if="loading" :rows="5" animated />

      <el-table v-else :data="events" stripe max-height="600">
        <el-table-column prop="id" label="事件ID" width="250" />
        <el-table-column prop="conversationId" label="会话ID" width="250" />
        <el-table-column prop="type" label="类型" width="150">
          <template #default="{ row }">
            <el-tag size="small">{{ row.type }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="data" label="数据" show-overflow-tooltip />
        <el-table-column prop="createdAt" label="创建时间" width="180" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { listEvents } from '@/api/event'
import type { Event } from '@/types'

const loading = ref(false)
const events = ref<Event[]>([])
const filters = ref({
  conversationId: '',
  type: ''
})

const loadData = async () => {
  loading.value = true
  try {
    const params: any = { limit: 100 }
    if (filters.value.conversationId) {
      params.conversationId = filters.value.conversationId
    }
    if (filters.value.type) {
      params.type = filters.value.type
    }
    events.value = await listEvents(params)
  } catch (error: any) {
    ElMessage.error(error.message || '加载失败')
  } finally {
    loading.value = false
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

.filter-controls {
  display: flex;
  align-items: center;
}
</style>
