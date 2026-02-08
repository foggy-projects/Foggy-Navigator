<template>
  <div class="settings-layout">
    <!-- Header -->
    <header class="settings-header">
      <el-button text @click="router.push('/')">
        <el-icon><ArrowLeft /></el-icon>
        返回
      </el-button>
      <span class="header-title">系统配置</span>
    </header>

    <div class="settings-body">
      <!-- 左侧导航 -->
      <nav class="settings-nav">
        <div
          :class="['nav-item', { active: activeTab === 'datasource' }]"
          @click="activeTab = 'datasource'"
        >
          数据源
        </div>
        <div
          :class="['nav-item', { active: activeTab === 'semantic' }]"
          @click="activeTab = 'semantic'"
        >
          语义层
        </div>
      </nav>

      <!-- 右侧内容 -->
      <section class="settings-content">
        <!-- 数据源 Tab -->
        <div v-if="activeTab === 'datasource'">
          <div class="content-toolbar">
            <h3>数据源列表</h3>
            <el-button type="primary" size="small" @click="openDsDialog()">+ 新增</el-button>
          </div>
          <el-table :data="datasources" stripe style="width: 100%" v-loading="dsLoading">
            <el-table-column prop="name" label="名称" min-width="120" />
            <el-table-column prop="type" label="类型" width="80" />
            <el-table-column prop="dbType" label="数据库" width="100" />
            <el-table-column label="主机/端口" min-width="160">
              <template #default="{ row }">
                {{ row.host }}:{{ row.port }}
              </template>
            </el-table-column>
            <el-table-column prop="databaseName" label="数据库名" min-width="120" />
            <el-table-column label="连接状态" width="90" align="center">
              <template #default="{ row }">
                <el-tag :type="row.connectionValid ? 'success' : 'info'" size="small">
                  {{ row.connectionValid ? '正常' : '未验证' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="200" align="center">
              <template #default="{ row }">
                <el-button text size="small" @click="openDsDialog(row)">编辑</el-button>
                <el-button
                  text
                  size="small"
                  type="success"
                  :loading="testingId === row.id"
                  @click="handleTestConnection(row.id)"
                >
                  测试连接
                </el-button>
                <el-button text size="small" type="danger" @click="handleDeleteDs(row.id)">
                  删除
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>

        <!-- 语义层 Tab -->
        <div v-if="activeTab === 'semantic'">
          <div class="content-toolbar">
            <h3>语义层列表</h3>
            <el-button type="primary" size="small" @click="openSlDialog()">+ 新增</el-button>
          </div>
          <el-table :data="semanticLayers" stripe style="width: 100%" v-loading="slLoading">
            <el-table-column label="关联数据源" min-width="120">
              <template #default="{ row }">
                {{ getDatasourceName(row.datasourceId) }}
              </template>
            </el-table-column>
            <el-table-column prop="gitRepoUrl" label="Git 仓库" min-width="200" show-overflow-tooltip />
            <el-table-column prop="gitBranch" label="分支" width="100" />
            <el-table-column prop="gitAuthType" label="认证方式" width="90" />
            <el-table-column prop="modelCount" label="模型数" width="80" align="center" />
            <el-table-column prop="status" label="状态" width="90" align="center">
              <template #default="{ row }">
                <el-tag size="small">{{ row.status || '-' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="140" align="center">
              <template #default="{ row }">
                <el-button text size="small" @click="openSlDialog(row)">编辑</el-button>
                <el-button text size="small" type="danger" @click="handleDeleteSl(row.id)">
                  删除
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </section>
    </div>

    <!-- 数据源编辑弹窗 -->
    <el-dialog
      v-model="dsDialogVisible"
      :title="dsForm.id ? '编辑数据源' : '新增数据源'"
      width="560px"
      @close="resetDsForm"
    >
      <el-form :model="dsForm" label-width="100px" :rules="dsRules" ref="dsFormRef">
        <el-form-item label="名称" prop="name">
          <el-input v-model="dsForm.name" placeholder="如：生产MySQL" />
        </el-form-item>
        <el-form-item label="类型" prop="type">
          <el-select v-model="dsForm.type" placeholder="选择类型" style="width: 100%">
            <el-option label="JDBC" value="JDBC" />
            <el-option label="MONGO" value="MONGO" />
          </el-select>
        </el-form-item>
        <template v-if="dsForm.type === 'JDBC'">
          <el-form-item label="数据库类型" prop="dbType">
            <el-select v-model="dsForm.dbType" placeholder="选择数据库类型" style="width: 100%">
              <el-option label="MySQL" value="mysql" />
              <el-option label="PostgreSQL" value="postgresql" />
              <el-option label="Oracle" value="oracle" />
              <el-option label="SQL Server" value="sqlserver" />
            </el-select>
          </el-form-item>
        </template>
        <el-form-item label="主机" prop="host">
          <el-input v-model="dsForm.host" placeholder="如：localhost" />
        </el-form-item>
        <el-form-item label="端口" prop="port">
          <el-input-number v-model="dsForm.port" :min="1" :max="65535" style="width: 100%" />
        </el-form-item>
        <el-form-item label="数据库名" prop="databaseName">
          <el-input v-model="dsForm.databaseName" placeholder="数据库名称" />
        </el-form-item>
        <el-form-item label="用户名" prop="username">
          <el-input v-model="dsForm.username" placeholder="用户名" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input
            v-model="dsForm.password"
            type="password"
            show-password
            :placeholder="dsForm.id ? '留空则不修改' : '密码'"
          />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="dsForm.description" type="textarea" :rows="2" placeholder="可选描述" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dsDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="dsSaving" @click="handleSaveDs">保存</el-button>
      </template>
    </el-dialog>

    <!-- 语义层编辑弹窗 -->
    <el-dialog
      v-model="slDialogVisible"
      :title="slForm.id ? '编辑语义层' : '新增语义层'"
      width="560px"
      @close="resetSlForm"
    >
      <el-form :model="slForm" label-width="100px" :rules="slRules" ref="slFormRef">
        <el-form-item label="关联数据源" prop="datasourceId">
          <el-select v-model="slForm.datasourceId" placeholder="选择数据源" style="width: 100%">
            <el-option
              v-for="ds in datasources"
              :key="ds.id"
              :label="ds.name"
              :value="ds.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="Git 仓库 URL" prop="gitRepoUrl">
          <el-input v-model="slForm.gitRepoUrl" placeholder="https://github.com/org/repo.git" />
        </el-form-item>
        <el-form-item label="分支" prop="gitBranch">
          <el-input v-model="slForm.gitBranch" placeholder="main" />
        </el-form-item>
        <el-form-item label="认证方式" prop="gitAuthType">
          <el-select v-model="slForm.gitAuthType" placeholder="选择认证方式" style="width: 100%">
            <el-option label="无需认证" value="NONE" />
            <el-option label="Token" value="TOKEN" />
            <el-option label="用户名/密码" value="BASIC" />
          </el-select>
        </el-form-item>
        <template v-if="slForm.gitAuthType === 'TOKEN'">
          <el-form-item label="Access Token">
            <el-input
              v-model="slForm.accessToken"
              type="password"
              show-password
              :placeholder="slForm.id ? '留空则不修改' : 'Token'"
            />
          </el-form-item>
        </template>
        <template v-if="slForm.gitAuthType === 'BASIC'">
          <el-form-item label="用户名">
            <el-input v-model="slForm.gitUsername" placeholder="Git 用户名" />
          </el-form-item>
          <el-form-item label="密码">
            <el-input
              v-model="slForm.gitPassword"
              type="password"
              show-password
              :placeholder="slForm.id ? '留空则不修改' : 'Git 密码'"
            />
          </el-form-item>
        </template>
        <el-form-item label="描述">
          <el-input v-model="slForm.description" type="textarea" :rows="2" placeholder="可选描述" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="slDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="slSaving" @click="handleSaveSl">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { ArrowLeft } from '@element-plus/icons-vue'
import * as configApi from '@/api/config'
import type { DatasourceConfig, SemanticLayerConfig } from '@/types'

const router = useRouter()

// ===== 共享状态 =====
const activeTab = ref<'datasource' | 'semantic'>('datasource')

// ===== 数据源 =====
const datasources = ref<DatasourceConfig[]>([])
const dsLoading = ref(false)
const dsSaving = ref(false)
const testingId = ref<string | null>(null)
const dsDialogVisible = ref(false)
const dsFormRef = ref<FormInstance>()

interface DsFormState {
  id: string
  name: string
  type: 'JDBC' | 'MONGO'
  dbType: string
  host: string
  port: number
  databaseName: string
  username: string
  password: string
  description: string
}

const dsFormDefaults: DsFormState = {
  id: '',
  name: '',
  type: 'JDBC',
  dbType: 'mysql',
  host: '',
  port: 3306,
  databaseName: '',
  username: '',
  password: '',
  description: '',
}

const dsForm = ref<DsFormState>({ ...dsFormDefaults })

const dsRules: FormRules = {
  name: [{ required: true, message: '请输入名称', trigger: 'blur' }],
  type: [{ required: true, message: '请选择类型', trigger: 'change' }],
  host: [{ required: true, message: '请输入主机地址', trigger: 'blur' }],
  port: [{ required: true, message: '请输入端口', trigger: 'blur' }],
  databaseName: [{ required: true, message: '请输入数据库名', trigger: 'blur' }],
}

async function loadDatasources() {
  dsLoading.value = true
  try {
    datasources.value = await configApi.listDatasources()
  } catch {
    ElMessage.error('加载数据源列表失败')
  } finally {
    dsLoading.value = false
  }
}

function openDsDialog(row?: DatasourceConfig) {
  if (row) {
    dsForm.value = {
      id: row.id,
      name: row.name,
      type: row.type,
      dbType: row.dbType || 'mysql',
      host: row.host,
      port: row.port,
      databaseName: row.databaseName,
      username: row.username,
      password: '',
      description: row.description || '',
    }
  } else {
    dsForm.value = { ...dsFormDefaults }
  }
  dsDialogVisible.value = true
}

function resetDsForm() {
  dsFormRef.value?.resetFields()
}

async function handleSaveDs() {
  const valid = await dsFormRef.value?.validate().catch(() => false)
  if (!valid) return

  const f = dsForm.value
  const form = {
    basicInfo: {
      name: f.name,
      type: f.type as 'JDBC' | 'MONGO',
      description: f.description || undefined,
    },
    jdbcInfo:
      f.type === 'JDBC'
        ? {
            dbType: f.dbType,
            host: f.host,
            port: f.port,
            databaseName: f.databaseName,
            username: f.username,
            password: f.password || undefined,
          }
        : undefined,
    mongoInfo:
      f.type === 'MONGO'
        ? {
            hosts: f.host,
            port: f.port,
            database: f.databaseName,
            username: f.username,
            password: f.password || undefined,
          }
        : undefined,
  }

  dsSaving.value = true
  try {
    if (f.id) {
      await configApi.updateDatasource(f.id, form)
      ElMessage.success('更新成功')
    } else {
      await configApi.saveDatasource(form)
      ElMessage.success('创建成功')
    }
    dsDialogVisible.value = false
    await loadDatasources()
  } catch {
    // error handled by client interceptor
  } finally {
    dsSaving.value = false
  }
}

async function handleTestConnection(id: string) {
  testingId.value = id
  try {
    const result = await configApi.testDatasourceConnection(id)
    if (result.success) {
      ElMessage.success(result.message)
    } else {
      ElMessage.warning(result.message)
    }
    await loadDatasources()
  } catch {
    // error handled by client interceptor
  } finally {
    testingId.value = null
  }
}

async function handleDeleteDs(id: string) {
  try {
    await ElMessageBox.confirm('确认删除该数据源？删除后不可恢复。', '提示', {
      type: 'warning',
      confirmButtonText: '确认删除',
      cancelButtonText: '取消',
    })
    await configApi.deleteDatasource(id)
    ElMessage.success('删除成功')
    await loadDatasources()
  } catch {
    // user cancelled or error handled by interceptor
  }
}

// ===== 语义层 =====
const semanticLayers = ref<SemanticLayerConfig[]>([])
const slLoading = ref(false)
const slSaving = ref(false)
const slDialogVisible = ref(false)
const slFormRef = ref<FormInstance>()

interface SlFormState {
  id: string
  datasourceId: string
  gitRepoUrl: string
  gitBranch: string
  gitAuthType: string
  accessToken: string
  gitUsername: string
  gitPassword: string
  description: string
}

const slFormDefaults: SlFormState = {
  id: '',
  datasourceId: '',
  gitRepoUrl: '',
  gitBranch: 'main',
  gitAuthType: 'NONE',
  accessToken: '',
  gitUsername: '',
  gitPassword: '',
  description: '',
}

const slForm = ref<SlFormState>({ ...slFormDefaults })

const slRules: FormRules = {
  datasourceId: [{ required: true, message: '请选择关联数据源', trigger: 'change' }],
  gitRepoUrl: [{ required: true, message: '请输入 Git 仓库 URL', trigger: 'blur' }],
  gitBranch: [{ required: true, message: '请输入分支', trigger: 'blur' }],
  gitAuthType: [{ required: true, message: '请选择认证方式', trigger: 'change' }],
}

async function loadSemanticLayers() {
  slLoading.value = true
  try {
    semanticLayers.value = await configApi.listSemanticLayers()
  } catch {
    ElMessage.error('加载语义层列表失败')
  } finally {
    slLoading.value = false
  }
}

function getDatasourceName(dsId: string): string {
  const ds = datasources.value.find((d) => d.id === dsId)
  return ds ? ds.name : dsId
}

function openSlDialog(row?: SemanticLayerConfig) {
  if (row) {
    slForm.value = {
      id: row.id,
      datasourceId: row.datasourceId,
      gitRepoUrl: row.gitRepoUrl || '',
      gitBranch: row.gitBranch || 'main',
      gitAuthType: row.gitAuthType || 'NONE',
      accessToken: '',
      gitUsername: '',
      gitPassword: '',
      description: row.description || '',
    }
  } else {
    slForm.value = { ...slFormDefaults }
  }
  slDialogVisible.value = true
}

function resetSlForm() {
  slFormRef.value?.resetFields()
}

async function handleSaveSl() {
  const valid = await slFormRef.value?.validate().catch(() => false)
  if (!valid) return

  const f = slForm.value
  const form = {
    datasourceId: f.datasourceId,
    description: f.description || undefined,
    gitConfig: {
      repoUrl: f.gitRepoUrl,
      branch: f.gitBranch,
      authType: f.gitAuthType as 'NONE' | 'TOKEN' | 'BASIC' | 'SSH',
      accessToken: f.gitAuthType === 'TOKEN' && f.accessToken ? f.accessToken : undefined,
      username: f.gitAuthType === 'BASIC' && f.gitUsername ? f.gitUsername : undefined,
      password: f.gitAuthType === 'BASIC' && f.gitPassword ? f.gitPassword : undefined,
    },
  }

  slSaving.value = true
  try {
    if (f.id) {
      await configApi.updateSemanticLayer(f.id, form)
      ElMessage.success('更新成功')
    } else {
      await configApi.saveSemanticLayer(form)
      ElMessage.success('创建成功')
    }
    slDialogVisible.value = false
    await loadSemanticLayers()
  } catch {
    // error handled by client interceptor
  } finally {
    slSaving.value = false
  }
}

async function handleDeleteSl(id: string) {
  try {
    await ElMessageBox.confirm('确认删除该语义层配置？删除后不可恢复。', '提示', {
      type: 'warning',
      confirmButtonText: '确认删除',
      cancelButtonText: '取消',
    })
    await configApi.deleteSemanticLayer(id)
    ElMessage.success('删除成功')
    await loadSemanticLayers()
  } catch {
    // user cancelled or error handled by interceptor
  }
}

// ===== 初始化 =====
onMounted(async () => {
  await loadDatasources()
  await loadSemanticLayers()
})
</script>

<style scoped>
.settings-layout {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background: #f5f7fa;
}

.settings-header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 20px;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
  flex-shrink: 0;
}

.header-title {
  font-size: 16px;
  font-weight: 600;
  color: #303133;
}

.settings-body {
  display: flex;
  flex: 1;
  min-height: 0;
}

.settings-nav {
  width: 140px;
  flex-shrink: 0;
  background: #fff;
  border-right: 1px solid #e4e7ed;
  padding: 12px 0;
}

.nav-item {
  padding: 10px 20px;
  font-size: 14px;
  color: #606266;
  cursor: pointer;
  transition: all 0.2s;
}

.nav-item:hover {
  color: #409eff;
  background: #f0f7ff;
}

.nav-item.active {
  color: #409eff;
  background: #ecf5ff;
  font-weight: 500;
  border-right: 2px solid #409eff;
}

.settings-content {
  flex: 1;
  padding: 20px 24px;
  overflow-y: auto;
}

.content-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.content-toolbar h3 {
  margin: 0;
  font-size: 15px;
  color: #303133;
}
</style>
