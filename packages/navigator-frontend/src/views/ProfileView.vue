<template>
  <div class="profile-view">
    <h2>个人设置</h2>

    <!-- 个人信息 -->
    <el-card class="profile-card" v-loading="loadingProfile">
      <template #header>
        <span>个人信息</span>
      </template>
      <el-form :model="profileForm" label-width="100px" style="max-width: 500px">
        <el-form-item label="用户名">
          <el-input :model-value="profile?.username" disabled />
        </el-form-item>
        <el-form-item label="角色">
          <div>
            <el-tag
              v-for="role in parseRoles(profile?.roles)"
              :key="role"
              :type="roleTagType(role)"
              size="small"
              style="margin-right: 4px"
            >
              {{ roleLabel(role) }}
            </el-tag>
          </div>
        </el-form-item>
        <el-form-item label="显示名称">
          <el-input v-model="profileForm.displayName" placeholder="请输入显示名称" />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="profileForm.email" placeholder="请输入邮箱" />
        </el-form-item>

        <el-divider content-position="left">修改密码</el-divider>

        <el-form-item label="新密码">
          <el-input
            v-model="profileForm.newPassword"
            type="password"
            show-password
            placeholder="留空则不修改"
          />
        </el-form-item>
        <el-form-item label="确认密码">
          <el-input
            v-model="profileForm.confirmPassword"
            type="password"
            show-password
            placeholder="再次输入新密码"
          />
        </el-form-item>

        <el-form-item>
          <el-button type="primary" :loading="savingProfile" @click="handleSaveProfile">
            保存
          </el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- API Key 管理 -->
    <el-card class="profile-card">
      <template #header>
        <div class="card-header-row">
          <span>API Keys</span>
          <el-button type="primary" size="small" @click="showCreateKeyForm = true">
            + 创建 API Key
          </el-button>
        </div>
      </template>

      <!-- 创建 Key 表单 -->
      <div v-if="showCreateKeyForm" class="create-key-form">
        <el-form :model="apiKeyForm" inline>
          <el-form-item label="名称" required>
            <el-input v-model="apiKeyForm.name" placeholder="Key 名称" style="width: 180px" />
          </el-form-item>
          <el-form-item label="过期时间">
            <el-date-picker
              v-model="apiKeyForm.expiresAt"
              type="datetime"
              placeholder="可选，留空不过期"
              style="width: 200px"
            />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" size="small" :loading="creatingKey" @click="handleCreateKey">
              创建
            </el-button>
            <el-button size="small" @click="showCreateKeyForm = false">取消</el-button>
          </el-form-item>
        </el-form>
      </div>

      <!-- 新创建的 Key 明文展示 -->
      <el-alert
        v-if="newlyCreatedKey"
        title="API Key 已创建，请立即复制保存（关闭后无法再次查看）"
        type="success"
        :closable="true"
        show-icon
        class="new-key-alert"
        @close="newlyCreatedKey = null"
      >
        <div class="new-key-display">
          <code>{{ newlyCreatedKey }}</code>
          <el-button size="small" text @click="copyKey(newlyCreatedKey!)">复制</el-button>
        </div>
      </el-alert>

      <el-table :data="apiKeys" v-loading="loadingKeys" stripe size="small">
        <el-table-column prop="name" label="名称" width="150" />
        <el-table-column prop="maskedApiKey" label="Key" min-width="200">
          <template #default="{ row }">
            <code>{{ row.maskedApiKey || '***' }}</code>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'danger'" size="small">
              {{ row.enabled ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="过期时间" width="160">
          <template #default="{ row }">
            {{ row.expiresAt ? formatDate(row.expiresAt) : '永不过期' }}
          </template>
        </el-table-column>
        <el-table-column label="最后使用" width="160">
          <template #default="{ row }">
            {{ row.lastUsedAt ? formatDate(row.lastUsedAt) : '从未使用' }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="80">
          <template #default="{ row }">
            <el-button text type="danger" size="small" @click="handleRevokeKey(row.id)">
              撤销
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
import { getCurrentUser, updateUser, listApiKeys, createApiKey, revokeApiKey } from '@/api/users'
import type { UserDTO, UserUpdateForm, ApiKeyDTO } from '@/types'
import { copyToClipboard } from '@/utils/clipboard'
import { parseRoles, roleLabel, roleTagType, formatDate } from '@/utils/userFormatters'

// ===== 个人信息 =====

const profile = ref<UserDTO | null>(null)
const loadingProfile = ref(false)
const savingProfile = ref(false)

const profileForm = ref({
  displayName: '',
  email: '',
  newPassword: '',
  confirmPassword: '',
})

async function loadProfile() {
  loadingProfile.value = true
  try {
    profile.value = await getCurrentUser()
    profileForm.value.displayName = profile.value.displayName ?? ''
    profileForm.value.email = profile.value.email ?? ''
  } catch {
    /* interceptor handles error */
  } finally {
    loadingProfile.value = false
  }
}

async function handleSaveProfile() {
  const f = profileForm.value
  if (f.newPassword && f.newPassword !== f.confirmPassword) {
    ElMessage.warning('两次输入的密码不一致')
    return
  }

  if (!profile.value) return

  savingProfile.value = true
  try {
    const updateForm: UserUpdateForm = {
      displayName: f.displayName,
      email: f.email,
      ...(f.newPassword ? { newPassword: f.newPassword } : {}),
    }
    await updateUser(profile.value.id, updateForm)
    ElMessage.success('个人信息已更新')
    profileForm.value.newPassword = ''
    profileForm.value.confirmPassword = ''
    await loadProfile()
  } catch {
    /* interceptor handles error */
  } finally {
    savingProfile.value = false
  }
}

// ===== API Key 管理 =====

const apiKeys = ref<ApiKeyDTO[]>([])
const loadingKeys = ref(false)
const showCreateKeyForm = ref(false)
const creatingKey = ref(false)
const newlyCreatedKey = ref<string | null>(null)

const apiKeyForm = ref({
  name: '',
  expiresAt: null as Date | null,
})

async function loadApiKeys() {
  if (!profile.value) return
  loadingKeys.value = true
  try {
    apiKeys.value = await listApiKeys(profile.value.id)
  } catch {
    /* interceptor handles error */
  } finally {
    loadingKeys.value = false
  }
}

async function handleCreateKey() {
  if (!apiKeyForm.value.name.trim()) {
    ElMessage.warning('请输入 Key 名称')
    return
  }
  if (!profile.value) return

  creatingKey.value = true
  try {
    const result = await createApiKey(profile.value.id, {
      name: apiKeyForm.value.name.trim(),
      expiresAt: apiKeyForm.value.expiresAt ? apiKeyForm.value.expiresAt.toISOString() : undefined,
    })
    newlyCreatedKey.value = result.apiKey ?? null
    showCreateKeyForm.value = false
    apiKeyForm.value = { name: '', expiresAt: null }
    ElMessage.success('API Key 已创建')
    await loadApiKeys()
  } catch {
    /* interceptor handles error */
  } finally {
    creatingKey.value = false
  }
}

async function handleRevokeKey(apiKeyId: string) {
  try {
    await ElMessageBox.confirm('确定要撤销此 API Key 吗？撤销后将无法恢复。', '确认撤销', {
      type: 'warning',
    })
    await revokeApiKey(apiKeyId)
    ElMessage.success('API Key 已撤销')
    await loadApiKeys()
  } catch {
    /* cancelled or error */
  }
}

async function copyKey(key: string) {
  const ok = await copyToClipboard(key)
  if (ok) {
    ElMessage.success('已复制到剪贴板')
  } else {
    ElMessage.error('复制失败')
  }
}

// ===== 初始化 =====

onMounted(async () => {
  await loadProfile()
  await loadApiKeys()
})
</script>

<style scoped>
.profile-view {
  padding: 20px;
  height: 100%;
  overflow: auto;
}

.profile-view h2 {
  margin: 0 0 16px 0;
  font-size: 20px;
  color: #303133;
}

.profile-card {
  margin-bottom: 20px;
}

.card-header-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.create-key-form {
  background: #f5f7fa;
  padding: 12px 16px;
  border-radius: 4px;
  margin-bottom: 12px;
}

.new-key-alert {
  margin-bottom: 12px;
}

.new-key-display {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
}

.new-key-display code {
  background: #f5f7fa;
  padding: 4px 8px;
  border-radius: 4px;
  font-size: 13px;
  word-break: break-all;
  flex: 1;
}
</style>
