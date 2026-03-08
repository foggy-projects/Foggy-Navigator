<template>
  <div class="users-view">
    <div class="users-header">
      <h2>用户管理</h2>
      <el-button type="primary" @click="openCreateDialog">+ 添加用户</el-button>
    </div>

    <el-table :data="users" v-loading="loading" stripe style="width: 100%">
      <el-table-column prop="username" label="用户名" width="130" />
      <el-table-column prop="displayName" label="显示名称" width="140">
        <template #default="{ row }">
          {{ row.displayName || '-' }}
        </template>
      </el-table-column>
      <el-table-column prop="email" label="邮箱" min-width="180">
        <template #default="{ row }">
          {{ row.email || '-' }}
        </template>
      </el-table-column>
      <el-table-column label="角色" width="200">
        <template #default="{ row }">
          <el-tag
            v-for="role in parseRoles(row.roles)"
            :key="role"
            :type="roleTagType(role)"
            size="small"
            style="margin-right: 4px"
          >
            {{ roleLabel(role) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="statusTagType(row.status)" size="small">
            {{ statusLabel(row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="最后登录" width="170">
        <template #default="{ row }">
          {{ row.lastLoginAt ? formatDate(row.lastLoginAt) : '从未登录' }}
        </template>
      </el-table-column>
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <el-button text size="small" @click="openEditDialog(row)">编辑</el-button>
          <el-button text size="small" @click="openApiKeysDialog(row)">API Keys</el-button>
          <el-button
            text
            type="danger"
            size="small"
            :disabled="row.id === currentUserId"
            @click="handleDeleteUser(row)"
          >
            删除
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 创建/编辑用户对话框 -->
    <el-dialog
      v-model="showUserDialog"
      :title="dialogMode === 'add' ? '添加用户' : '编辑用户'"
      width="500px"
      @close="resetUserForm"
    >
      <el-form :model="userForm" label-width="90px">
        <el-form-item label="用户名" required>
          <el-input
            v-model="userForm.username"
            :disabled="dialogMode === 'edit'"
            placeholder="请输入用户名"
          />
        </el-form-item>
        <el-form-item v-if="dialogMode === 'add'" label="密码" required>
          <el-input
            v-model="userForm.password"
            type="password"
            show-password
            placeholder="请输入密码"
          />
        </el-form-item>
        <el-form-item v-if="dialogMode === 'edit'" label="新密码">
          <el-input
            v-model="userForm.newPassword"
            type="password"
            show-password
            placeholder="留空则不修改密码"
          />
        </el-form-item>
        <el-form-item label="显示名称">
          <el-input v-model="userForm.displayName" placeholder="请输入显示名称" />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="userForm.email" placeholder="请输入邮箱" />
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="userForm.selectedRoles" multiple placeholder="请选择角色" style="width: 100%">
            <el-option
              v-for="r in allRoles"
              :key="r.value"
              :label="r.label"
              :value="r.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item v-if="dialogMode === 'edit'" label="状态">
          <el-select v-model="userForm.status" style="width: 100%">
            <el-option label="启用" value="ACTIVE" />
            <el-option label="禁用" value="DISABLED" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showUserDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSaveUser">保存</el-button>
      </template>
    </el-dialog>

    <!-- API Key 管理对话框 -->
    <el-dialog
      v-model="showApiKeysDialog"
      :title="`API Keys - ${apiKeysUser?.username ?? ''}`"
      width="700px"
    >
      <div class="apikeys-toolbar">
        <el-button type="primary" size="small" @click="showCreateKeyForm = true">
          + 创建 API Key
        </el-button>
      </div>

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
            <el-button type="primary" size="small" :loading="creatingKey" @click="handleCreateApiKey">
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
        :closable="false"
        show-icon
        class="new-key-alert"
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
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listUsersByTenant,
  createUser,
  updateUser,
  deleteUser,
  listApiKeys,
  createApiKey,
  revokeApiKey,
} from '@/api/users'
import type { UserDTO, UserStatus, UserUpdateForm, ApiKeyDTO } from '@/types'
import { getUserInfo } from '@/utils/auth'
import { copyToClipboard } from '@/utils/clipboard'
import { parseRoles, roleLabel, roleTagType, statusLabel, statusTagType, formatDate } from '@/utils/userFormatters'

// ===== 常量 =====

const allRoles = [
  { value: 'SUPER_ADMIN', label: '超级管理员' },
  { value: 'TENANT_ADMIN', label: '租户管理员' },
  { value: 'DEVELOPER', label: '开发者' },
  { value: 'VIEWER', label: '观察者' },
]

// ===== 用户列表 =====

const users = ref<UserDTO[]>([])
const loading = ref(false)
const cachedUserInfo = getUserInfo()
const currentUserId = cachedUserInfo?.userId ?? ''
const currentTenantId = cachedUserInfo?.tenantId ?? ''

async function loadUsers() {
  if (!currentTenantId) {
    ElMessage.error('无法获取租户信息，请重新登录')
    return
  }
  loading.value = true
  try {
    users.value = await listUsersByTenant(currentTenantId)
  } catch {
    /* interceptor handles error */
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadUsers()
})

// ===== 创建/编辑用户 =====

const showUserDialog = ref(false)
const dialogMode = ref<'add' | 'edit'>('add')
const editingUserId = ref('')
const saving = ref(false)

const userForm = ref({
  username: '',
  password: '',
  newPassword: '',
  displayName: '',
  email: '',
  selectedRoles: [] as string[],
  status: 'ACTIVE' as UserStatus,
})

function resetUserForm() {
  userForm.value = {
    username: '',
    password: '',
    newPassword: '',
    displayName: '',
    email: '',
    selectedRoles: [],
    status: 'ACTIVE',
  }
}

function openCreateDialog() {
  dialogMode.value = 'add'
  editingUserId.value = ''
  resetUserForm()
  showUserDialog.value = true
}

function openEditDialog(row: UserDTO) {
  dialogMode.value = 'edit'
  editingUserId.value = row.id
  userForm.value = {
    username: row.username,
    password: '',
    newPassword: '',
    displayName: row.displayName ?? '',
    email: row.email ?? '',
    selectedRoles: parseRoles(row.roles),
    status: row.status,
  }
  showUserDialog.value = true
}

async function handleSaveUser() {
  const f = userForm.value
  if (!f.username.trim()) {
    ElMessage.warning('请输入用户名')
    return
  }

  saving.value = true
  try {
    if (dialogMode.value === 'add') {
      if (!f.password) {
        ElMessage.warning('请输入密码')
        saving.value = false
        return
      }
      await createUser({
        tenantId: currentTenantId,
        username: f.username.trim(),
        password: f.password,
        email: f.email || undefined,
        displayName: f.displayName || undefined,
        roles: f.selectedRoles.length > 0 ? f.selectedRoles.join(',') : undefined,
      })
    } else {
      const updateForm: UserUpdateForm = {
        email: f.email,
        displayName: f.displayName,
        roles: f.selectedRoles.length > 0 ? f.selectedRoles.join(',') : undefined,
        status: f.status,
        ...(f.newPassword ? { newPassword: f.newPassword } : {}),
      }
      await updateUser(editingUserId.value, updateForm)
    }
    showUserDialog.value = false
    ElMessage.success(dialogMode.value === 'add' ? '用户已创建' : '用户已更新')
    await loadUsers()
  } catch {
    /* interceptor handles error */
  } finally {
    saving.value = false
  }
}

async function handleDeleteUser(row: UserDTO) {
  try {
    await ElMessageBox.confirm(
      `确定要删除用户 "${row.username}" 吗？此操作不可恢复。`,
      '确认删除',
      { type: 'warning' },
    )
    await deleteUser(row.id)
    ElMessage.success('用户已删除')
    await loadUsers()
  } catch {
    /* cancelled or error */
  }
}

// ===== API Key 管理 =====

const showApiKeysDialog = ref(false)
const apiKeysUser = ref<UserDTO | null>(null)
const apiKeys = ref<ApiKeyDTO[]>([])
const loadingKeys = ref(false)
const showCreateKeyForm = ref(false)
const creatingKey = ref(false)
const newlyCreatedKey = ref<string | null>(null)

const apiKeyForm = ref({
  name: '',
  expiresAt: null as Date | null,
})

async function openApiKeysDialog(row: UserDTO) {
  apiKeysUser.value = row
  showApiKeysDialog.value = true
  showCreateKeyForm.value = false
  newlyCreatedKey.value = null
  apiKeyForm.value = { name: '', expiresAt: null }
  await loadApiKeys(row.id)
}

async function loadApiKeys(userId: string) {
  loadingKeys.value = true
  try {
    apiKeys.value = await listApiKeys(userId)
  } catch {
    /* interceptor handles error */
  } finally {
    loadingKeys.value = false
  }
}

async function handleCreateApiKey() {
  if (!apiKeyForm.value.name.trim()) {
    ElMessage.warning('请输入 Key 名称')
    return
  }
  if (!apiKeysUser.value) return

  creatingKey.value = true
  try {
    const result = await createApiKey(apiKeysUser.value.id, {
      name: apiKeyForm.value.name.trim(),
      expiresAt: apiKeyForm.value.expiresAt ? apiKeyForm.value.expiresAt.toISOString() : undefined,
    })
    newlyCreatedKey.value = result.apiKey ?? null
    showCreateKeyForm.value = false
    apiKeyForm.value = { name: '', expiresAt: null }
    ElMessage.success('API Key 已创建')
    await loadApiKeys(apiKeysUser.value.id)
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
    if (apiKeysUser.value) {
      await loadApiKeys(apiKeysUser.value.id)
    }
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
</script>

<style scoped>
.users-view {
  padding: 20px;
  height: 100%;
  overflow: auto;
}

.users-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.users-header h2 {
  margin: 0;
  font-size: 20px;
color: #303133;
}

.apikeys-toolbar {
  margin-bottom: 12px;
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
