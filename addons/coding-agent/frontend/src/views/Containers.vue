<template>
  <div class="page-container">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>容器管理</span>
          <el-button type="primary" @click="loadData">
            刷新
          </el-button>
        </div>
      </template>

      <el-skeleton v-if="loading" :rows="5" animated />

      <el-table v-else :data="containers" stripe>
        <el-table-column prop="id" label="容器ID" width="250" />
        <el-table-column prop="imageTag" label="镜像标签" width="200" />
        <el-table-column prop="status" label="状态" width="120">
          <template #default="{ row }">
            <StatusBadge :status="row.status" />
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="180" />
        <el-table-column prop="stoppedAt" label="停止时间" width="180" />
        <el-table-column prop="errorMessage" label="错误信息" show-overflow-tooltip />
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <el-button link type="danger" @click="deleteContainer(row.id)">
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import StatusBadge from '@/components/StatusBadge.vue'
import { listContainers, deleteContainer as apiDeleteContainer } from '@/api/container'
import type { Container } from '@/types'

const loading = ref(false)
const containers = ref<Container[]>([])

const loadData = async () => {
  loading.value = true
  try {
    containers.value = await listContainers()
  } catch (error: any) {
    ElMessage.error(error.message || '加载失败')
  } finally {
    loading.value = false
  }
}

const deleteContainer = async (id: string) => {
  await ElMessageBox.confirm('确认删除该容器？此操作不可恢复', '警告', {
    type: 'warning'
  })
  try {
    await apiDeleteContainer(id)
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
