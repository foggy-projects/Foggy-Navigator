<template>
  <div class="settings-layout">
    <div class="settings-header">
      <h2>系统设置</h2>
      <el-button class="settings-help-button" plain @click="showHelpDrawer = true">
        <el-icon><QuestionFilled /></el-icon>
        Worker 安装帮助
      </el-button>
    </div>

    <el-tabs v-model="activeTab" class="settings-tabs">
      <!-- Tab 1: Git Providers -->
      <el-tab-pane label="Git 提供者" name="git">
        <div class="tab-toolbar">
          <el-dropdown @command="handleAddGit">
            <el-button type="primary" size="small">+ 添加</el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="GITHUB">GitHub</el-dropdown-item>
                <el-dropdown-item command="GITLAB">GitLab</el-dropdown-item>
                <el-dropdown-item command="GITEE">Gitee</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>

        <el-table :data="gitProviders" v-loading="loadingGit" stripe>
          <el-table-column prop="providerType" label="类型" width="120" />
          <el-table-column prop="baseUrl" label="服务地址" min-width="200">
            <template #default="{ row }">{{ row.baseUrl || '默认' }}</template>
          </el-table-column>
          <el-table-column prop="username" label="用户名" width="150">
            <template #default="{ row }">{{ row.username || '-' }}</template>
          </el-table-column>
          <el-table-column label="Token" width="80" align="center">
            <template #default="{ row }">
              <el-tag :type="row.hasToken ? 'success' : 'danger'" size="small">
                {{ row.hasToken ? '已配置' : '未配置' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="80" align="center">
            <template #default="{ row }">
              <el-tag :type="row.isActive ? 'success' : 'info'" size="small">
                {{ row.isActive ? '启用' : '停用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="140" align="center">
            <template #default="{ row }">
              <el-button text size="small" @click="editGitProvider(row)">编辑</el-button>
              <el-button text type="danger" size="small" @click="deleteGit(row.id)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- Tab 2: AI Models -->
      <el-tab-pane label="LLM 配置" name="llm">
        <div class="tab-toolbar">
          <div class="toolbar-left">
            <el-button type="primary" size="small" @click="showLlmDialog('add')">+ 添加</el-button>
            <span class="preset-label">快速填充：</span>
            <el-button v-for="preset in llmPresets" :key="preset.name" size="small" @click="applyPreset(preset)">
              {{ preset.name }}
            </el-button>
          </div>
        </div>

        <el-table :data="llmModels" v-loading="loadingLlm" stripe>
          <el-table-column prop="name" label="名称" min-width="140" sortable />
          <el-table-column prop="category" label="类别" width="100" sortable>
            <template #default="{ row }">
              <el-tag size="small">{{ categoryLabel(row.category) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="modelName" label="模型" width="160" sortable />
          <el-table-column prop="baseUrl" label="API 地址" min-width="200" show-overflow-tooltip sortable />
          <el-table-column label="默认" width="80" align="center" sortable :sort-method="(a: any, b: any) => (a.isDefault ? 1 : 0) - (b.isDefault ? 1 : 0)">
            <template #default="{ row }">
              <span v-if="row.isDefault" style="color: #67c23a">&#10003;</span>
            </template>
          </el-table-column>
          <el-table-column label="范围" width="110" align="center" sortable :sort-method="(a: any, b: any) => (a.scope || '').localeCompare(b.scope || '')">
            <template #default="{ row }">
              <el-tag v-if="row.scope === 'RESTRICTED'" type="warning" size="small">
                限定 ({{ row.allowedWorkerIds?.length || 0 }})
              </el-tag>
              <el-tag v-else size="small">全局</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="后端" width="100" align="center">
            <template #default="{ row }">
              <el-tag v-if="row.workerBackend === 'OPENAI_CODEX'" type="success" size="small">Codex</el-tag>
              <el-tag v-else-if="row.workerBackend === 'GEMINI_CLI'" type="warning" size="small">Gemini</el-tag>
              <el-tag v-else-if="row.workerBackend === 'CLAUDE_CODE'" size="small">Claude</el-tag>
              <span v-else style="color: #c0c4cc">-</span>
            </template>
          </el-table-column>
          <el-table-column label="API Key" width="80" align="center">
            <template #default="{ row }">
              <el-tag :type="row.hasApiKey ? 'success' : 'danger'" size="small">
                {{ row.hasApiKey ? '已配置' : '未配置' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="260" align="center">
            <template #default="{ row, $index }">
              <el-button text size="small" :disabled="$index === 0" @click="moveLlm($index, -1)">&#9650;</el-button>
              <el-button text size="small" :disabled="$index === llmModels.length - 1" @click="moveLlm($index, 1)">&#9660;</el-button>
              <el-button text size="small" :loading="row._testing" @click="handleTestSaved(row)">测试</el-button>
              <el-button text size="small" @click="editLlmModel(row)">编辑</el-button>
              <el-button text type="danger" size="small" @click="deleteLlm(row.id)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- Tab 3: Agent Model Overrides -->
      <el-tab-pane label="Agent 模型" name="agent-model">
        <div class="tab-toolbar">
          <el-button type="primary" size="small" @click="showOverrideDialog = true">+ 添加覆盖</el-button>
        </div>

        <el-table :data="agentOverrides" v-loading="loadingOverrides" stripe>
          <el-table-column prop="agentId" label="Agent ID" min-width="200" />
          <el-table-column label="使用模型" min-width="200">
            <template #default="{ row }">
              {{ resolveModelName(row.modelConfigId) }}
            </template>
          </el-table-column>
          <el-table-column label="操作" width="100" align="center">
            <template #default="{ row }">
              <el-button text type="danger" size="small" @click="deleteOverride(row.agentId)">移除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- Tab 5: User Memories -->
      <el-tab-pane label="记忆管理" name="memories">
        <div class="tab-toolbar">
          <el-button type="primary" size="small" @click="showMemoryDialog('add')">+ 添加记忆</el-button>
        </div>

        <el-table :data="memories" v-loading="loadingMemories" stripe>
          <el-table-column prop="category" label="类别" width="100">
            <template #default="{ row }">
              <el-tag :type="memoryCategoryTag(row.category)" size="small">
                {{ memoryCategoryLabel(row.category) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="content" label="内容" min-width="300" show-overflow-tooltip />
          <el-table-column prop="source" label="来源" width="80" align="center">
            <template #default="{ row }">
              <el-tag :type="row.source === 'AUTO' ? 'warning' : ''" size="small">
                {{ row.source === 'AUTO' ? '自动' : '手动' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="更新时间" width="170">
            <template #default="{ row }">{{ formatTime(row.updatedAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="140" align="center">
            <template #default="{ row }">
              <el-button text size="small" @click="editMemory(row)">编辑</el-button>
              <el-button text type="danger" size="small" @click="deleteMemoryById(row.id)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- Tab 6: API Credentials -->
      <el-tab-pane label="API 凭证" name="credentials">
        <div class="tab-toolbar">
          <el-button type="primary" size="small" @click="showCredentialDialog('add')">+ 添加凭证</el-button>
        </div>

        <el-table :data="credentials" v-loading="loadingCredentials" stripe>
          <el-table-column prop="name" label="名称" min-width="140" />
          <el-table-column prop="category" label="分类" width="120">
            <template #default="{ row }">
              <el-tag size="small">{{ row.category || 'default' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="baseUrl" label="API 地址" min-width="200" show-overflow-tooltip />
          <el-table-column prop="authType" label="认证类型" width="120">
            <template #default="{ row }">
              <el-tag size="small">{{ authTypeLabel(row.authType) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="description" label="描述" min-width="150" show-overflow-tooltip />
          <el-table-column label="状态" width="80" align="center">
            <template #default="{ row }">
              <el-tag :type="row.isActive ? 'success' : 'info'" size="small">
                {{ row.isActive ? '启用' : '停用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="API Key" width="80" align="center">
            <template #default="{ row }">
              <el-tag :type="row.hasApiKey ? 'success' : 'danger'" size="small">
                {{ row.hasApiKey ? '已配置' : '未配置' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="200" align="center">
            <template #default="{ row }">
              <el-button text size="small" :loading="row._testing" @click="handleTestCredential(row)">测试</el-button>
              <el-button text size="small" @click="editCredential(row)">编辑</el-button>
              <el-button text type="danger" size="small" @click="deleteCredentialById(row.id)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- Tab 4: Claude Workers -->
      <el-tab-pane label="Claude Workers" name="workers">
        <div class="tab-toolbar">
          <el-button type="primary" size="small" @click="showWorkerAddDialog = true">+ 添加 Worker</el-button>
        </div>

        <el-table :data="workers" v-loading="loadingWorkers" stripe>
          <el-table-column label="状态" width="60" align="center">
            <template #default="{ row }">
              <span :class="['status-dot', row.status?.toLowerCase()]" />
            </template>
          </el-table-column>
          <el-table-column prop="name" label="名称" min-width="140" />
          <el-table-column prop="baseUrl" label="地址" min-width="220" show-overflow-tooltip />
          <el-table-column prop="authMode" label="模式" width="140" />
          <el-table-column label="操作" width="200" align="center">
            <template #default="{ row }">
              <el-button text size="small" @click="handleWorkerHealthCheck(row.workerId)">检测</el-button>
              <el-button text size="small" @click="editWorker(row)">编辑</el-button>
              <el-button text type="danger" size="small" @click="deleteWorkerById(row.workerId)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- Tab: Task Assistant -->
      <el-tab-pane label="任务助手" name="assistant">
        <div v-loading="assistantLoading" class="assistant-config">
          <!-- 未创建状态 -->
          <template v-if="!assistantConfig || !assistantConfig.workerId">
            <p class="tab-description">
              AI 编程会话管理助手会在任务开始/完成/失败时自动生成智能通知和行动建议。
              选择一个 Worker 来创建助手。
            </p>

            <el-form label-position="top" style="max-width: 480px">
              <el-form-item label="Worker" required>
                <el-select v-model="assistantCreateForm.workerId" placeholder="选择 Worker" style="width: 100%">
                  <el-option
                    v-for="w in workers"
                    :key="w.workerId"
                    :label="w.name || w.workerId"
                    :value="w.workerId"
                  />
                </el-select>
              </el-form-item>

              <el-form-item label="工作目录路径">
                <el-input v-model="assistantCreateForm.directoryPath" placeholder="~/foggy-assistant" />
                <div class="el-form-item__description" style="color: var(--el-text-color-secondary); font-size: 12px; margin-top: 4px">
                  留空则默认使用 ~/foggy-assistant
                </div>
              </el-form-item>

              <el-form-item label="LLM 配置">
                <el-select v-model="assistantCreateForm.modelConfigId" placeholder="使用 Worker 默认模型" clearable style="width: 100%">
                  <el-option
                    v-for="m in llmModels"
                    :key="m.id"
                    :label="`${m.name} (${m.modelName})`"
                    :value="m.id"
                  />
                </el-select>
                <div class="el-form-item__description" style="color: var(--el-text-color-secondary); font-size: 12px; margin-top: 4px">
                  留空则使用 Worker 默认模型
                </div>
              </el-form-item>

              <el-form-item>
                <el-button
                  type="primary"
                  :loading="assistantSaving"
                  :disabled="!assistantCreateForm.workerId"
                  @click="handleCreateAssistant"
                >
                  创建助手
                </el-button>
              </el-form-item>
            </el-form>
          </template>

          <!-- 已创建状态 -->
          <template v-else>
            <p class="tab-description">
              AI 编程会话管理助手已配置，会在任务开始/完成/失败时自动生成智能通知和行动建议。
            </p>

            <el-descriptions :column="1" border style="max-width: 480px; margin-bottom: 16px">
              <el-descriptions-item label="Worker">
                {{ getWorkerName(assistantConfig.workerId) }}
              </el-descriptions-item>
              <el-descriptions-item label="工作目录">
                {{ assistantConfig.cwd || '~/foggy-assistant' }}
              </el-descriptions-item>
              <el-descriptions-item label="LLM 配置">
                {{ assistantConfig.model || '（Worker 默认）' }}
              </el-descriptions-item>
              <el-descriptions-item label="会话 ID">
                {{ assistantConfig.claudeSessionId || '（新会话）' }}
              </el-descriptions-item>
            </el-descriptions>

            <el-form label-position="top" style="max-width: 480px">
              <el-form-item label="启用">
                <el-switch v-model="assistantEnabledToggle" @change="handleToggleAssistant" />
              </el-form-item>

              <el-form-item>
                <div style="display: flex; gap: 8px">
                  <el-button
                    :loading="assistantTesting"
                    :disabled="!assistantConfig?.enabled"
                    @click="handleTestNotification"
                  >
                    测试通知
                  </el-button>
                  <el-button
                    type="danger"
                    plain
                    @click="handleDeleteAssistant"
                  >
                    删除助手
                  </el-button>
                </div>
              </el-form-item>
            </el-form>
          </template>
        </div>
      </el-tab-pane>
    </el-tabs>

    <el-drawer v-model="showHelpDrawer" size="760px" destroy-on-close>
      <template #header>
        <div class="help-drawer-header">
          <div>
            <div class="help-drawer-eyebrow">Foggy Navigator Help</div>
            <div class="help-drawer-title">Claude / Codex Worker 安装与配置</div>
          </div>
        </div>
      </template>

      <div class="help-drawer-body">
        <el-alert
          type="info"
          :closable="false"
          show-icon
          title="这里说明的是远端 Worker 的安装、升级和平台接入方式。平台侧的 addons/claude-worker-agent 与 addons/codex-worker-agent 随 Navigator 服务部署后即可生效。"
        />

        <el-tabs v-model="helpTab" class="help-tabs">
          <el-tab-pane label="Claude Worker" name="claude">
            <section class="help-section">
              <div class="help-section-title">对应模块</div>
              <div class="help-section-text">
                平台侧 addon：<code>addons/claude-worker-agent</code><br>
                远端执行器：<code>tools/claude-agent-worker</code>
              </div>
            </section>

            <section class="help-section">
              <div class="help-section-title">一键安装</div>
              <div class="help-section-text">适用于远端开发机、个人电脑或家里主机。</div>
              <pre class="help-code"># Linux / macOS
curl -sSL https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/claude-worker/install.sh | bash

# Windows PowerShell
irm https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/claude-worker/install.ps1 | iex</pre>
            </section>

            <section class="help-section">
              <div class="help-section-title">升级</div>
              <pre class="help-code">claude-worker upgrade

# 也可以指定本地安装包
claude-worker upgrade /path/to/claude-worker-0.2.0-linux.tar.gz</pre>
            </section>

            <section class="help-section">
              <div class="help-section-title">配置文件</div>
              <div class="help-section-text">
                安装目录默认是 <code>~/.claude-worker/</code>，核心配置写在 <code>~/.claude-worker/.env</code>。
              </div>
              <pre class="help-code">AGENT_WORKER_PORT=3031
AGENT_WORKER_WORKER_NAME=我的-Claude-Worker
AGENT_WORKER_WORKER_TOKEN=your-token

# Windows: AGENT_WORKER_ALLOWED_CWDS=["D:\\projects"]
# Linux:   AGENT_WORKER_ALLOWED_CWDS=["/home/user/projects"]
AGENT_WORKER_ALLOWED_CWDS=[]

# 认证方式三选一
# 1. 订阅模式（推荐）：先执行 claude login
# 2. Proxy 模式：
# AGENT_WORKER_ANTHROPIC_AUTH_TOKEN=any-token
# AGENT_WORKER_ANTHROPIC_BASE_URL=http://localhost:8082
# 3. API Key 模式：
# AGENT_WORKER_ANTHROPIC_API_KEY=sk-ant-api03-xxx</pre>
            </section>

            <section class="help-section">
              <div class="help-section-title">启动与平台接入</div>
              <pre class="help-code">claude-worker start
claude-worker status</pre>
              <ul class="help-list">
                <li>在当前页面的「Claude Workers」里点击「+ 添加 Worker」。</li>
                <li>地址填写远端机器地址，例如 <code>http://192.168.1.100:3031</code>。</li>
                <li>认证令牌填写 <code>AGENT_WORKER_WORKER_TOKEN</code>。</li>
                <li>认证模式按远端机器实际方式选择：订阅模式、API Key 模式或自定义端点。</li>
                <li>如果希望平台统一下发模型配置，在「LLM 配置」里创建后端为 Claude 的模型项即可。</li>
              </ul>
            </section>
          </el-tab-pane>

          <el-tab-pane label="Codex Worker" name="codex">
            <section class="help-section">
              <div class="help-section-title">对应模块</div>
              <div class="help-section-text">
                平台侧 addon：<code>addons/codex-worker-agent</code><br>
                远端执行器：<code>tools/codex-agent-worker</code>
              </div>
            </section>

            <section class="help-section">
              <div class="help-section-title">一键安装</div>
              <pre class="help-code"># Linux / macOS
curl -sSL https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/codex-worker/install.sh | bash

# Windows PowerShell
irm https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/codex-worker/install.ps1 | iex</pre>
            </section>

            <section class="help-section">
              <div class="help-section-title">升级</div>
              <pre class="help-code">codex-worker upgrade</pre>
              <div class="help-section-text">
                升级时会自动保留已有 <code>.env</code>，并在安装目录重新部署运行文件。
              </div>
            </section>

            <section class="help-section">
              <div class="help-section-title">配置文件</div>
              <div class="help-section-text">
                安装目录默认是 <code>~/.codex-worker/</code>，核心配置写在 <code>~/.codex-worker/.env</code>。
              </div>
              <pre class="help-code">CODEX_WORKER_PORT=3051
CODEX_WORKER_HOST=0.0.0.0
CODEX_WORKER_NAME=我的-Codex-Worker
CODEX_WORKER_TOKEN=your-token
CODEX_ALLOWED_CWDS=

# 订阅模式：先执行 codex login，保持 OPENAI_API_KEY 为空
# API Key 模式：直接填写
OPENAI_API_KEY=sk-xxx
# OPENAI_BASE_URL=https://api.openai.com/v1

CODEX_MAX_CONCURRENT_TASKS=6
CODEX_LOG_LEVEL=info</pre>
              <ul class="help-list">
                <li>订阅模式依赖本机 <code>~/.codex/auth.json</code>，先执行 <code>codex login</code>。</li>
                <li>API Key 模式可由 Worker 本地 <code>OPENAI_API_KEY</code> 提供，也可由平台请求时覆盖下发。</li>
                <li><code>CODEX_ALLOWED_CWDS</code> 留空表示不限制；填写时必须是绝对路径。</li>
              </ul>
            </section>

            <section class="help-section">
              <div class="help-section-title">启动与平台接入</div>
              <pre class="help-code">codex-worker start
codex-worker status</pre>
              <ul class="help-list">
                <li>先在「LLM 配置」里新增一个后端为 Codex 的模型配置。</li>
                <li>订阅模式下 API Key 可以留空；API Key 模式则填写平台侧 Key。</li>
                <li>在 Workers 页面维护机器时，补齐 Codex Base URL、Codex Token、Codex 默认模型。</li>
                <li>常见地址是 <code>http://&lt;host&gt;:3051</code>，令牌对应 <code>CODEX_WORKER_TOKEN</code>。</li>
              </ul>
            </section>
          </el-tab-pane>

          <el-tab-pane label="推荐流程" name="workflow">
            <section class="help-section">
              <div class="help-section-title">推荐接入顺序</div>
              <ul class="help-list">
                <li>先在远端机器安装并启动 Worker，确认 <code>/health</code> 可访问。</li>
                <li>再回到平台录入 Worker 地址和令牌，执行一次「检测」。</li>
                <li>最后在「LLM 配置」里补齐模型、后端和 API Key/订阅模式策略。</li>
                <li>如果平台要统一管控鉴权，优先把 Key 配在「LLM 配置」里，而不是散落在每台机器。</li>
              </ul>
            </section>

            <section class="help-section">
              <div class="help-section-title">排查提示</div>
              <ul class="help-list">
                <li>Worker 检测失败，优先检查端口、防火墙、Base URL 是否可从 Navigator 所在机器访问。</li>
                <li>Claude 无法执行时，检查远端是否已经 <code>claude login</code> 或是否配置了 Proxy / API Key。</li>
                <li>Codex 无法执行时，检查远端是否已经 <code>codex login</code>，或平台侧/本地侧是否提供了有效的 OpenAI Key。</li>
                <li>目录相关报错通常是白名单问题：Claude 看 <code>AGENT_WORKER_ALLOWED_CWDS</code>，Codex 看 <code>CODEX_ALLOWED_CWDS</code>。</li>
              </ul>
            </section>
          </el-tab-pane>
        </el-tabs>
      </div>
    </el-drawer>

    <!-- Git Provider Dialog -->
    <el-dialog v-model="showGitDialog" :title="gitDialogMode === 'add' ? '添加 Git 提供者' : '编辑 Git 提供者'" width="480px">
      <el-form :model="gitForm" label-position="top">
        <el-form-item label="类型">
          <el-select v-model="gitForm.providerType" :disabled="gitDialogMode === 'edit'" style="width: 100%">
            <el-option label="GitHub" value="GITHUB" />
            <el-option label="GitLab" value="GITLAB" />
            <el-option label="Gitee" value="GITEE" />
          </el-select>
        </el-form-item>
        <el-form-item label="访问令牌" required>
          <el-input v-model="gitForm.accessToken" type="password" show-password :placeholder="gitDialogMode === 'edit' ? '留空保持不变' : 'Personal Access Token'" />
        </el-form-item>
        <el-form-item v-if="gitForm.providerType === 'GITLAB'" label="服务地址">
          <el-input v-model="gitForm.baseUrl" placeholder="如 https://gitlab.example.com" />
        </el-form-item>
        <el-form-item label="用户名（可选）">
          <el-input v-model="gitForm.username" placeholder="Git 用户名" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showGitDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveGit">保存</el-button>
      </template>
    </el-dialog>

    <!-- LLM Model Dialog -->
    <el-dialog v-model="showLlmDialog_" :title="llmDialogMode === 'add' ? '添加 AI 模型' : '编辑 AI 模型'" width="560px">
      <el-form :model="llmForm" label-position="top">
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="显示名称" required>
              <el-input v-model="llmForm.name" placeholder="如：通义千问-Max" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="模型类别" required>
              <el-select v-model="llmForm.category" style="width: 100%">
                <el-option label="通用" value="GENERAL" />
                <el-option label="编程" value="CODING" />
                <el-option label="推理" value="REASONING" />
                <el-option label="视觉" value="VISION" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="API Base URL" :required="!supportsSubscriptionBackend(llmForm.workerBackend)">
          <el-input v-model="llmForm.baseUrl" :placeholder="baseUrlPlaceholder(llmForm.workerBackend)" />
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="模型名称" required>
              <el-select
                v-if="llmForm.workerBackend === 'CLAUDE_CODE'"
                v-model="llmForm.modelName"
                filterable
                allow-create
                reserve-keyword
                default-first-option
                placeholder="选择或输入模型名称"
              >
                <el-option
                  v-for="opt in claudeBackendOptions"
                  :key="opt.value"
                  :value="opt.value"
                  :label="opt.description || opt.label"
                />
              </el-select>
              <el-select
                v-else-if="llmForm.workerBackend === 'OPENAI_CODEX'"
                v-model="llmForm.modelName"
                filterable
                allow-create
                reserve-keyword
                default-first-option
                placeholder="选择或输入 Codex 模型别名"
              >
                <el-option
                  v-for="opt in codexBackendOptions"
                  :key="opt.value"
                  :value="opt.value"
                  :label="opt.description || opt.label"
                />
              </el-select>
              <el-select
                v-else-if="llmForm.workerBackend === 'GEMINI_CLI'"
                v-model="llmForm.modelName"
                filterable
                allow-create
                reserve-keyword
                default-first-option
                placeholder="选择或输入 Gemini 模型别名"
              >
                <el-option
                  v-for="opt in geminiBackendOptions"
                  :key="opt.value"
                  :value="opt.value"
                  :label="opt.description || opt.label"
                />
              </el-select>
              <el-input v-else v-model="llmForm.modelName" placeholder="如：qwen-max" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="API Key" :required="!supportsSubscriptionBackend(llmForm.workerBackend) && llmDialogMode === 'add'">
              <el-input v-model="llmForm.apiKey" type="password" show-password :placeholder="apiKeyPlaceholder(llmForm.workerBackend, llmDialogMode)" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item v-if="llmForm.workerBackend === 'CLAUDE_CODE'" label="">
          <div class="form-hint" style="color: #909399; font-size: 12px">
            Claude Code 支持两种认证模式：填写 API Key 使用 API 模式，留空 URL 和 API Key 则使用订阅模式（依赖 Worker 本机 `claude login`）
          </div>
        </el-form-item>
        <el-form-item v-if="llmForm.workerBackend === 'OPENAI_CODEX'" label="">
          <div class="form-hint" style="color: #909399; font-size: 12px">
            Codex 支持两种认证模式：填写 API Key 使用 API 模式，留空则使用订阅模式（~/.codex/auth.json）
          </div>
        </el-form-item>
        <el-form-item v-if="llmForm.workerBackend === 'GEMINI_CLI'" label="">
          <div class="form-hint" style="color: #909399; font-size: 12px">
            Gemini CLI 支持两种认证模式：填写 API Key 使用 Gemini API，留空则使用订阅或本机登录模式（依赖 Worker 本机 gemini login）
          </div>
        </el-form-item>
        <el-form-item v-if="llmForm.workerBackend === 'CLAUDE_CODE'" label="可用模型">
          <el-checkbox-group v-model="llmForm.availableModels">
            <el-checkbox
              v-for="opt in claudeBackendOptions"
              :key="opt.value"
              :value="opt.value"
              :label="opt.label"
            />
          </el-checkbox-group>
          <div class="form-hint" style="color: #909399; font-size: 12px; margin-top: 4px">
            勾选此 API Key 支持的模型，Workers 页面仅显示已勾选项；不勾选则不限制
          </div>
        </el-form-item>
        <el-form-item v-if="llmForm.workerBackend === 'OPENAI_CODEX'" label="可用模型">
          <el-checkbox-group v-model="llmForm.availableModels">
            <el-checkbox
              v-for="opt in codexBackendOptions"
              :key="opt.value"
              :value="opt.value"
              :label="opt.label"
            />
          </el-checkbox-group>
          <div class="form-hint" style="color: #909399; font-size: 12px; margin-top: 4px">
            勾选可用的 Codex 模型，Workers 页面仅显示已勾选项；不勾选则不限制
          </div>
        </el-form-item>
        <el-form-item v-if="llmForm.workerBackend === 'GEMINI_CLI'" label="可用模型">
          <el-checkbox-group v-model="llmForm.availableModels">
            <el-checkbox
              v-for="opt in geminiBackendOptions"
              :key="opt.value"
              :value="opt.value"
              :label="opt.label"
            />
          </el-checkbox-group>
          <div class="form-hint" style="color: #909399; font-size: 12px; margin-top: 4px">
            勾选可用的 Gemini 模型别名，Workers 页面仅显示已勾选项；不勾选则不限制
          </div>
        </el-form-item>
        <el-form-item>
          <el-checkbox v-model="llmForm.isDefault">设为该类别的默认模型</el-checkbox>
        </el-form-item>
        <el-form-item v-if="llmForm.category === 'CODING'" label="Worker 后端">
          <el-radio-group v-model="llmForm.workerBackend">
            <el-radio-button value="CLAUDE_CODE">Claude Code</el-radio-button>
            <el-radio-button value="OPENAI_CODEX">OpenAI Codex</el-radio-button>
            <el-radio-button value="GEMINI_CLI">Gemini CLI</el-radio-button>
          </el-radio-group>
          <div class="form-hint" style="color: #909399; font-size: 12px; margin-top: 4px">
            指定此编程模型由哪个 Worker 后端执行
          </div>
        </el-form-item>
        <el-form-item>
          <template #label>
            环境变量
            <el-popover placement="right" :width="460" trigger="click">
              <template #reference>
                <el-icon style="cursor: pointer; margin-left: 4px; vertical-align: middle; color: #409eff"><QuestionFilled /></el-icon>
              </template>
              <div style="font-size: 13px; line-height: 1.8">
                <p style="margin: 0 0 8px; font-weight: 600">常用环境变量参考</p>
                <template v-if="llmForm.workerBackend === 'CLAUDE_CODE'">
                  <el-descriptions :column="1" size="small" border>
                    <el-descriptions-item label="CLAUDE_AUTOCOMPACT_PCT_OVERRIDE">
                      自动压缩触发比例（0-100）。如 80 表示上下文占 80% 时触发压缩。<br/>
                      <el-tag size="small" type="info">建议值：80</el-tag>
                    </el-descriptions-item>
                  </el-descriptions>
                </template>
                <template v-else-if="llmForm.workerBackend === 'OPENAI_CODEX'">
                  <el-descriptions :column="1" size="small" border>
                    <el-descriptions-item label="model_context_window">
                      模型可用的上下文窗口大小（token 数）。代理非原生模型时需按实际能力配置。<br/>
                      <el-tag size="small" type="info">GPT-5.4: 1000000</el-tag>
                      <el-tag size="small" type="info">128K 模型: 128000</el-tag>
                    </el-descriptions-item>
                    <el-descriptions-item label="model_auto_compact_token_limit">
                      触发自动历史压缩的 token 阈值。达到此值时 Codex 自动压缩对话历史。<br/>
                      <el-tag size="small" type="info">建议值：上下文窗口的 80%</el-tag>
                    </el-descriptions-item>
                    <el-descriptions-item label="tool_output_token_limit">
                      单个工具输出的 token 上限。超长输出会被截断。<br/>
                      <el-tag size="small" type="info">建议值：50000</el-tag>
                    </el-descriptions-item>
                  </el-descriptions>
                </template>
                <template v-else-if="llmForm.workerBackend === 'GEMINI_CLI'">
                  <el-descriptions :column="1" size="small" border>
                    <el-descriptions-item label="GEMINI_MODEL_ALIASES">
                      自定义模型别名映射，格式如 <code>gemini-pro=auto-gemini-3,flash=gemini-3-flash-preview</code>。<br/>
                      <el-tag size="small" type="info">用于把稳定别名映射到真实版本</el-tag>
                    </el-descriptions-item>
                    <el-descriptions-item label="GEMINI_DEFAULT_MODEL">
                      Worker 默认模型别名或真实模型名。平台未显式下发时会使用它。<br/>
                      <el-tag size="small" type="info">建议值：gemini-flash</el-tag>
                    </el-descriptions-item>
                  </el-descriptions>
                </template>
                <template v-else>
                  <p style="color: #909399">选择 Worker 后端后显示对应的环境变量说明</p>
                </template>
                <p style="margin: 8px 0 0; color: #909399; font-size: 12px">
                  点击变量名可快速添加到列表
                </p>
              </div>
            </el-popover>
          </template>
          <div style="width: 100%">
            <div v-for="(item, idx) in llmForm.envVars" :key="idx" style="display: flex; gap: 8px; margin-bottom: 8px; width: 100%">
              <el-input v-model="item.key" placeholder="变量名" style="flex: 1" />
              <el-input v-model="item.value" placeholder="值" style="width: 120px" />
              <el-button size="small" type="danger" text @click="llmForm.envVars.splice(idx, 1)">删除</el-button>
            </div>
            <el-button size="small" @click="llmForm.envVars.push({ key: '', value: '' })">+ 添加变量</el-button>
          </div>
        </el-form-item>
        <el-form-item label="访问范围">
          <el-radio-group v-model="llmForm.scope">
            <el-radio value="GLOBAL">全局（所有 Worker 可用）</el-radio>
            <el-radio value="RESTRICTED">限定 Worker</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="llmForm.scope === 'RESTRICTED'" label="允许的 Worker" required>
          <el-select v-model="llmForm.allowedWorkerIds" multiple placeholder="选择 Worker" style="width: 100%">
            <el-option
              v-for="w in workers"
              :key="w.workerId"
              :label="w.name"
              :value="w.workerId"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showLlmDialog_ = false">取消</el-button>
        <el-button :loading="testingLlm" @click="handleTestLlm">测试连接</el-button>
        <el-button type="primary" :loading="saving" @click="saveLlm">保存</el-button>
      </template>
    </el-dialog>

    <!-- Agent Override Dialog -->
    <el-dialog v-model="showOverrideDialog" title="添加 Agent 模型覆盖" width="460px">
      <el-form :model="overrideForm" label-position="top">
        <el-form-item label="Agent ID" required>
          <el-input v-model="overrideForm.agentId" placeholder="如：coding-agent" />
        </el-form-item>
        <el-form-item label="使用模型" required>
          <el-select v-model="overrideForm.modelConfigId" style="width: 100%">
            <el-option
              v-for="m in llmModels"
              :key="m.id"
              :label="`${m.name} (${categoryLabel(m.category)})`"
              :value="m.id"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showOverrideDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveOverride">保存</el-button>
      </template>
    </el-dialog>

    <!-- Worker Add/Edit Dialog -->
    <el-dialog v-model="showWorkerAddDialog" :title="workerDialogMode === 'add' ? '添加 Worker' : '编辑 Worker'" width="480px">
      <el-form :model="workerForm" label-position="top">
        <el-form-item label="名称" required>
          <el-input v-model="workerForm.name" placeholder="如：我的家庭机" />
        </el-form-item>
        <el-form-item label="地址" required>
          <el-input v-model="workerForm.baseUrl" placeholder="如：http://192.168.1.100:3031" />
        </el-form-item>
        <el-form-item label="认证令牌" :required="workerDialogMode === 'add'">
          <el-input v-model="workerForm.authToken" type="password" show-password :placeholder="workerDialogMode === 'edit' ? '留空保持不变' : 'Worker 预共享令牌'" />
        </el-form-item>
        <el-form-item label="认证模式">
          <el-select v-model="workerForm.authMode" style="width: 100%">
            <el-option label="订阅模式 (Claude Max)" value="SUBSCRIPTION" />
            <el-option label="API Key 模式" value="API_KEY" />
            <el-option label="自定义端点" value="CUSTOM_ENDPOINT" />
          </el-select>
        </el-form-item>
        <el-divider content-position="left">Codex Worker（可选）</el-divider>
        <el-form-item label="Codex Base URL">
          <el-input v-model="workerForm.codexBaseUrl" placeholder="如：http://192.168.1.100:3051" />
        </el-form-item>
        <el-form-item label="Codex Token">
          <el-input v-model="workerForm.codexAuthToken" type="password" show-password :placeholder="workerDialogMode === 'edit' ? '留空保持不变' : 'Codex Worker 令牌'" />
        </el-form-item>
        <el-form-item label="Codex 默认模型">
          <el-input v-model="workerForm.codexModel" placeholder="如：codex-latest（alias）或 gpt-5.5（真实模型）" />
        </el-form-item>
        <el-divider content-position="left">Gemini Worker（可选）</el-divider>
        <el-form-item label="Gemini Base URL">
          <el-input v-model="workerForm.geminiBaseUrl" placeholder="如：http://192.168.1.100:3071" />
        </el-form-item>
        <el-form-item label="Gemini Token">
          <el-input v-model="workerForm.geminiAuthToken" type="password" show-password :placeholder="workerDialogMode === 'edit' ? '留空保持不变' : 'Gemini Worker 令牌'" />
        </el-form-item>
        <el-form-item label="Gemini 默认模型">
          <el-input v-model="workerForm.geminiModel" placeholder="如：gemini-flash" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showWorkerAddDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveWorkerForm">保存</el-button>
      </template>
    </el-dialog>
    <!-- Memory Add/Edit Dialog -->
    <el-dialog v-model="showMemoryDialog_" :title="memoryDialogMode === 'add' ? '添加记忆' : '编辑记忆'" width="480px">
      <el-form :model="memoryForm" label-position="top">
        <el-form-item label="类别">
          <el-select v-model="memoryForm.category" style="width: 100%">
            <el-option label="偏好" value="PREFERENCE" />
            <el-option label="事实" value="FACT" />
            <el-option label="备注" value="NOTE" />
          </el-select>
        </el-form-item>
        <el-form-item label="内容" required>
          <el-input v-model="memoryForm.content" type="textarea" :rows="3" placeholder="如：我偏好使用 Python 编程" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showMemoryDialog_ = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveMemoryForm">保存</el-button>
      </template>
    </el-dialog>

    <!-- API Credential Add/Edit Dialog -->
    <el-dialog v-model="showCredentialDialog_" :title="credentialDialogMode === 'add' ? '添加 API 凭证' : '编辑 API 凭证'" width="560px">
      <el-form :model="credentialForm" label-position="top">
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="名称" required>
              <el-input v-model="credentialForm.name" placeholder="如：阿里云天气API" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="分类">
              <el-input v-model="credentialForm.category" placeholder="如：weather, payment" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="API Base URL">
          <el-input v-model="credentialForm.baseUrl" placeholder="如：https://api.example.com" />
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="认证类型" required>
              <el-select v-model="credentialForm.authType" style="width: 100%">
                <el-option label="API Key" value="API_KEY" />
                <el-option label="Bearer Token" value="BEARER_TOKEN" />
                <el-option label="Basic Auth" value="BASIC_AUTH" />
                <el-option label="自定义 Header" value="CUSTOM_HEADER" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12" v-if="credentialForm.authType === 'CUSTOM_HEADER'">
            <el-form-item label="Header 名称">
              <el-input v-model="credentialForm.authHeaderName" placeholder="如：X-API-Key" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="API Key / Token" :required="credentialDialogMode === 'add'">
          <el-input v-model="credentialForm.apiKey" type="password" show-password :placeholder="credentialDialogMode === 'edit' ? '留空保持不变' : 'API Key 或 Token'" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="credentialForm.description" type="textarea" :rows="2" placeholder="凭证用途说明" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showCredentialDialog_ = false">取消</el-button>
        <el-button :loading="testingCredential" @click="handleTestCredentialForm">测试连接</el-button>
        <el-button type="primary" :loading="saving" @click="saveCredentialForm">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { resetSetupStatus } from '@/router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { QuestionFilled } from '@element-plus/icons-vue'
import {
  listGitProviders as apiListGit,
  saveGitProvider as apiSaveGit,
  updateGitProvider as apiUpdateGit,
  deleteGitProvider as apiDeleteGit,
  listModelConfigs as apiListLlm,
  saveModelConfig as apiSaveLlm,
  updateModelConfig as apiUpdateLlm,
  deleteModelConfig as apiDeleteLlm,
  reorderModelConfigs as apiReorderLlm,
  listAgentModelOverrides as apiListOverrides,
  setAgentModelOverride as apiSetOverride,
  removeAgentModelOverride as apiRemoveOverride,
  testLlmConnection as apiTestLlm,
  testSavedLlmConnection as apiTestSavedLlm,
  listMemories as apiListMemories,
  saveMemory as apiSaveMemory,
  updateMemory as apiUpdateMemory,
  deleteMemory as apiDeleteMemory,
  listCredentials as apiListCredentials,
  saveCredential as apiSaveCredential,
  updateCredential as apiUpdateCredential,
  deleteCredential as apiDeleteCredential,
  testCredentialConnection as apiTestCredential,
  testSavedCredentialConnection as apiTestSavedCredential,
} from '@/api/platform'
import {
  listWorkers as apiListWorkers,
  registerWorker as apiRegisterWorker,
  updateWorker as apiUpdateWorker,
  deleteWorker as apiDeleteWorker,
  triggerHealthCheck as apiHealthCheck,
} from '@/api/claudeWorker'
import {
  getAssistantConfig as apiGetAssistantConfig,
  createAssistant as apiCreateAssistant,
  updateAssistantConfig as apiUpdateAssistantConfig,
  deleteAssistant as apiDeleteAssistant,
  testNotification as apiTestNotification,
} from '@/api/notification'
import type { TaskAssistantConfig } from '@/api/notification'
import type { GitProviderConfig, LlmModelConfig, AgentModelOverride, ClaudeWorker, GitProviderType, LlmModelCategory, ModelAccessScope, UserMemory, UserMemoryCategory, ApiCredential, AuthType, WorkerBackend } from '@/types'
import { getModelOptionsByBackend } from '@/utils/llmModelOptions'

// 统一 Claude / Codex / Gemini 模型候选（见 utils/llmModelOptions.ts）。
// 1.0.4 起：Codex 切到 alias-only 模式，三个 backend 都使用扁平 alias 列表，
// 与 Claude / Gemini 命名风格保持一致；模型版本升级时仅改 Worker 配置，前端零变动。
const claudeBackendOptions = getModelOptionsByBackend('CLAUDE_CODE' as WorkerBackend)
const codexBackendOptions = getModelOptionsByBackend('OPENAI_CODEX' as WorkerBackend)
const geminiBackendOptions = getModelOptionsByBackend('GEMINI_CLI' as WorkerBackend)

const activeTab = ref('git')
const showHelpDrawer = ref(false)
const helpTab = ref('claude')
const saving = ref(false)

// ===== Loading states =====
const loadingGit = ref(false)
const loadingLlm = ref(false)
const loadingOverrides = ref(false)
const loadingWorkers = ref(false)
const loadingMemories = ref(false)
const loadingCredentials = ref(false)

// ===== Data =====
const gitProviders = ref<GitProviderConfig[]>([])
const llmModels = ref<LlmModelConfig[]>([])
const agentOverrides = ref<AgentModelOverride[]>([])
const workers = ref<ClaudeWorker[]>([])
const memories = ref<UserMemory[]>([])
const credentials = ref<ApiCredential[]>([])

// ===== Git Dialog =====
const showGitDialog = ref(false)
const gitDialogMode = ref<'add' | 'edit'>('add')
const editingGitId = ref('')
const gitForm = ref({
  providerType: 'GITHUB' as GitProviderType,
  baseUrl: '',
  accessToken: '',
  username: '',
})

function handleAddGit(type: GitProviderType) {
  gitDialogMode.value = 'add'
  editingGitId.value = ''
  gitForm.value = { providerType: type, baseUrl: '', accessToken: '', username: '' }
  showGitDialog.value = true
}

function editGitProvider(row: GitProviderConfig) {
  gitDialogMode.value = 'edit'
  editingGitId.value = row.id
  gitForm.value = {
    providerType: row.providerType,
    baseUrl: row.baseUrl || '',
    accessToken: '',
    username: row.username || '',
  }
  showGitDialog.value = true
}

async function saveGit() {
  if (!gitForm.value.accessToken && gitDialogMode.value === 'add') {
    ElMessage.warning('请填写访问令牌')
    return
  }
  saving.value = true
  try {
    if (gitDialogMode.value === 'add') {
      await apiSaveGit({
        providerType: gitForm.value.providerType,
        baseUrl: gitForm.value.baseUrl || undefined,
        accessToken: gitForm.value.accessToken,
        username: gitForm.value.username || undefined,
      })
    } else {
      const form: Record<string, unknown> = {}
      if (gitForm.value.accessToken) form.accessToken = gitForm.value.accessToken
      if (gitForm.value.baseUrl) form.baseUrl = gitForm.value.baseUrl
      if (gitForm.value.username) form.username = gitForm.value.username
      await apiUpdateGit(editingGitId.value, form)
    }
    showGitDialog.value = false
    ElMessage.success('保存成功')
    await loadGitProviders()
  } catch {
    ElMessage.error('保存失败')
  } finally {
    saving.value = false
  }
}

async function deleteGit(id: string) {
  try {
    await ElMessageBox.confirm('确认删除该 Git 提供者？', '提示', { type: 'warning' })
    await apiDeleteGit(id)
    resetSetupStatus()
    ElMessage.success('已删除')
    await loadGitProviders()
  } catch { /* cancelled */ }
}

// ===== LLM Dialog =====
const showLlmDialog_ = ref(false)
const llmDialogMode = ref<'add' | 'edit'>('add')
const editingLlmId = ref('')
const llmForm = ref({
  name: '',
  category: 'GENERAL' as LlmModelCategory,
  baseUrl: '',
  modelName: '',
  apiKey: '',
  isDefault: false,
  scope: 'GLOBAL' as ModelAccessScope,
  allowedWorkerIds: [] as string[],
  envVars: [] as Array<{ key: string; value: string }>,
  workerBackend: undefined as import('@/types').WorkerBackend | undefined,
  availableModels: [] as string[],
})

const llmPresets = [
  { name: '阿里通义', displayName: '通义千问-Max', category: 'GENERAL' as LlmModelCategory, baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', modelName: 'qwen-max' },
  { name: 'DeepSeek', displayName: 'DeepSeek-R1', category: 'REASONING' as LlmModelCategory, baseUrl: 'https://api.deepseek.com/v1', modelName: 'deepseek-reasoner' },
  { name: 'OpenAI', displayName: 'GPT-4o', category: 'GENERAL' as LlmModelCategory, baseUrl: 'https://api.openai.com/v1', modelName: 'gpt-4o' },
]

function showLlmDialog(mode: 'add' | 'edit') {
  llmDialogMode.value = mode
  if (mode === 'add') {
    editingLlmId.value = ''
    llmForm.value = { name: '', category: 'GENERAL', baseUrl: '', modelName: '', apiKey: '', isDefault: false, scope: 'GLOBAL', allowedWorkerIds: [], envVars: [], workerBackend: undefined, availableModels: [] }
  }
  showLlmDialog_.value = true
}

function applyPreset(preset: (typeof llmPresets)[number]) {
  llmDialogMode.value = 'add'
  editingLlmId.value = ''
  llmForm.value = {
    name: preset.displayName,
    category: preset.category,
    baseUrl: preset.baseUrl,
    modelName: preset.modelName,
    apiKey: '',
    isDefault: false,
    scope: 'GLOBAL',
    allowedWorkerIds: [],
    envVars: [],
    workerBackend: undefined,
    availableModels: [],
  }
  showLlmDialog_.value = true
}

function editLlmModel(row: LlmModelConfig) {
  llmDialogMode.value = 'edit'
  editingLlmId.value = row.id
  llmForm.value = {
    name: row.name,
    category: row.category,
    baseUrl: row.baseUrl,
    modelName: row.modelName,
    apiKey: '',
    isDefault: row.isDefault,
    scope: row.scope || 'GLOBAL',
    allowedWorkerIds: row.allowedWorkerIds ? [...row.allowedWorkerIds] : [],
    envVars: row.envVars ? Object.entries(row.envVars).map(([key, value]) => ({ key, value })) : [],
    workerBackend: row.workerBackend,
    availableModels: row.availableModels ? [...row.availableModels] : [],
  }
  showLlmDialog_.value = true
}

async function saveLlm() {
  const supportsSubscription = supportsSubscriptionBackend(llmForm.value.workerBackend)
  if (!llmForm.value.name || !llmForm.value.modelName) {
    ElMessage.warning('请填写必填项')
    return
  }
  if (!supportsSubscription && !llmForm.value.baseUrl) {
    ElMessage.warning('请填写 API Base URL')
    return
  }
  if (!supportsSubscription && !llmForm.value.apiKey && llmDialogMode.value === 'add') {
    ElMessage.warning('请填写 API Key')
    return
  }
  // Claude Code / Codex: apiKey 和 baseUrl 可选（留空 = 订阅模式）
  if (llmForm.value.scope === 'RESTRICTED' && (!llmForm.value.allowedWorkerIds || llmForm.value.allowedWorkerIds.length === 0)) {
    ElMessage.warning('限定模式下请至少选择一个 Worker')
    return
  }
  saving.value = true
  try {
    if (llmDialogMode.value === 'add') {
      const envVarsMap = Object.fromEntries(
        llmForm.value.envVars.filter(e => e.key.trim()).map(e => [e.key.trim(), e.value])
      )
      await apiSaveLlm({
        name: llmForm.value.name,
        category: llmForm.value.category,
        baseUrl: llmForm.value.baseUrl || '',
        modelName: llmForm.value.modelName,
        apiKey: llmForm.value.apiKey || '',
        isDefault: llmForm.value.isDefault,
        scope: llmForm.value.scope,
        allowedWorkerIds: llmForm.value.scope === 'RESTRICTED' ? llmForm.value.allowedWorkerIds : undefined,
        envVars: Object.keys(envVarsMap).length > 0 ? envVarsMap : undefined,
        workerBackend: llmForm.value.category === 'CODING' ? llmForm.value.workerBackend : undefined,
        availableModels: (llmForm.value.workerBackend === 'CLAUDE_CODE' || llmForm.value.workerBackend === 'OPENAI_CODEX' || llmForm.value.workerBackend === 'GEMINI_CLI')
          && llmForm.value.availableModels.length > 0
          ? llmForm.value.availableModels
          : undefined,
      })
    } else {
      const envVarsMap = Object.fromEntries(
        llmForm.value.envVars.filter(e => e.key.trim()).map(e => [e.key.trim(), e.value])
      )
      const form: Record<string, unknown> = {
        name: llmForm.value.name,
        category: llmForm.value.category,
        baseUrl: llmForm.value.baseUrl || '',
        modelName: llmForm.value.modelName,
        isDefault: llmForm.value.isDefault,
        scope: llmForm.value.scope,
        allowedWorkerIds: llmForm.value.scope === 'RESTRICTED' ? llmForm.value.allowedWorkerIds : [],
        envVars: envVarsMap,
        workerBackend: llmForm.value.category === 'CODING' ? (llmForm.value.workerBackend || null) : null,
        availableModels: (llmForm.value.workerBackend === 'CLAUDE_CODE' || llmForm.value.workerBackend === 'OPENAI_CODEX' || llmForm.value.workerBackend === 'GEMINI_CLI')
          && llmForm.value.availableModels.length > 0
          ? llmForm.value.availableModels
          : null,
      }
      if (llmForm.value.apiKey) form.apiKey = llmForm.value.apiKey
      await apiUpdateLlm(editingLlmId.value, form)
    }
    showLlmDialog_.value = false
    ElMessage.success('保存成功')
    await loadLlmModels()
  } catch {
    ElMessage.error('保存失败')
  } finally {
    saving.value = false
  }
}

const testingLlm = ref(false)

async function handleTestLlm() {
  if (!llmForm.value.baseUrl || !llmForm.value.modelName || !llmForm.value.apiKey) {
    ElMessage.warning('请先填写 Base URL、模型名称和 API Key')
    return
  }
  testingLlm.value = true
  try {
    const reply = await apiTestLlm({
      baseUrl: llmForm.value.baseUrl,
      apiKey: llmForm.value.apiKey,
      modelName: llmForm.value.modelName,
    })
    ElMessage.success('连接成功: ' + (reply || 'OK'))
  } catch (e: any) {
    ElMessage.error('连接失败: ' + (e?.response?.data?.msg || e?.message || '未知错误'))
  } finally {
    testingLlm.value = false
  }
}

function supportsSubscriptionBackend(workerBackend?: import('@/types').WorkerBackend) {
  return workerBackend === 'OPENAI_CODEX' || workerBackend === 'CLAUDE_CODE' || workerBackend === 'GEMINI_CLI'
}

function baseUrlPlaceholder(workerBackend?: import('@/types').WorkerBackend) {
  if (workerBackend === 'OPENAI_CODEX') {
    return '留空使用默认 OpenAI 端点（订阅模式无需填写）'
  }
  if (workerBackend === 'GEMINI_CLI') {
    return '留空表示使用 Gemini CLI 登录态或订阅模式（依赖 Worker 本机 gemini login）'
  }
  if (workerBackend === 'CLAUDE_CODE') {
    return '留空表示使用 Claude Code 订阅模式（依赖 Worker 本机 claude login）'
  }
  return '如：https://dashscope.aliyuncs.com/compatible-mode/v1'
}

function apiKeyPlaceholder(workerBackend?: import('@/types').WorkerBackend, mode: 'add' | 'edit' = 'add') {
  if (mode === 'edit') {
    return '留空保持不变'
  }
  if (supportsSubscriptionBackend(workerBackend)) {
    return '留空使用订阅模式'
  }
  return 'API Key'
}

async function handleTestSaved(row: LlmModelConfig & { _testing?: boolean }) {
  row._testing = true
  try {
    const reply = await apiTestSavedLlm(row.id)
    ElMessage.success('连接成功: ' + (reply || 'OK'))
  } catch (e: any) {
    ElMessage.error('连接失败: ' + (e?.response?.data?.msg || e?.message || '未知错误'))
  } finally {
    row._testing = false
  }
}

async function deleteLlm(id: string) {
  try {
    await ElMessageBox.confirm('确认删除该模型配置？', '提示', { type: 'warning' })
    await apiDeleteLlm(id)
    resetSetupStatus()
    ElMessage.success('已删除')
    await loadLlmModels()
  } catch { /* cancelled */ }
}

async function moveLlm(index: number, direction: -1 | 1) {
  const target = index + direction
  if (target < 0 || target >= llmModels.value.length) return
  const arr = [...llmModels.value]
  const temp = arr[index]!
  arr[index] = arr[target]!
  arr[target] = temp
  llmModels.value = arr
  try {
    await apiReorderLlm(arr.map(m => m.id))
  } catch {
    await loadLlmModels()
  }
}

function categoryLabel(c: string): string {
  const map: Record<string, string> = { GENERAL: '通用', CODING: '编程', REASONING: '推理', VISION: '视觉' }
  return map[c] || c
}

// ===== Agent Override =====
const showOverrideDialog = ref(false)
const overrideForm = ref({ agentId: '', modelConfigId: '' })

async function saveOverride() {
  if (!overrideForm.value.agentId || !overrideForm.value.modelConfigId) {
    ElMessage.warning('请填写完整')
    return
  }
  saving.value = true
  try {
    await apiSetOverride(overrideForm.value)
    showOverrideDialog.value = false
    overrideForm.value = { agentId: '', modelConfigId: '' }
    ElMessage.success('保存成功')
    await loadOverrides()
  } catch {
    ElMessage.error('保存失败')
  } finally {
    saving.value = false
  }
}

async function deleteOverride(agentId: string) {
  try {
    await ElMessageBox.confirm('确认移除该 Agent 的模型覆盖？', '提示', { type: 'warning' })
    await apiRemoveOverride(agentId)
    ElMessage.success('已移除')
    await loadOverrides()
  } catch { /* cancelled */ }
}

function resolveModelName(modelConfigId: string): string {
  const m = llmModels.value.find((m) => m.id === modelConfigId)
  return m ? `${m.name} (${m.modelName})` : modelConfigId
}

// ===== Workers =====
const showWorkerAddDialog = ref(false)
const workerDialogMode = ref<'add' | 'edit'>('add')
const editingWorkerId = ref('')
const workerForm = ref({
  name: '',
  baseUrl: '',
  authToken: '',
  authMode: 'SUBSCRIPTION',
  codexBaseUrl: '',
  codexAuthToken: '',
  codexModel: '',
  geminiBaseUrl: '',
  geminiAuthToken: '',
  geminiModel: '',
})

function editWorker(row: ClaudeWorker) {
  workerDialogMode.value = 'edit'
  editingWorkerId.value = row.workerId
  workerForm.value = {
    name: row.name,
    baseUrl: row.baseUrl,
    authToken: '',
    authMode: row.authMode || 'SUBSCRIPTION',
    codexBaseUrl: row.codexBaseUrl || '',
    codexAuthToken: '',
    codexModel: row.codexModel || '',
    geminiBaseUrl: row.geminiBaseUrl || '',
    geminiAuthToken: '',
    geminiModel: row.geminiModel || '',
  }
  showWorkerAddDialog.value = true
}

function buildWorkerConfig(baseUrl: string, authToken: string, model: string) {
  const normalizedBaseUrl = baseUrl.trim()
  if (!normalizedBaseUrl) {
    return undefined
  }
  return {
    baseUrl: normalizedBaseUrl,
    authToken: authToken.trim() || undefined,
    model: model.trim() || undefined,
  }
}

function buildWorkerPayload() {
  return {
    name: workerForm.value.name,
    baseUrl: workerForm.value.baseUrl,
    authToken: workerForm.value.authToken,
    authMode: workerForm.value.authMode,
    codexConfig: buildWorkerConfig(
      workerForm.value.codexBaseUrl,
      workerForm.value.codexAuthToken,
      workerForm.value.codexModel,
    ),
    geminiConfig: buildWorkerConfig(
      workerForm.value.geminiBaseUrl,
      workerForm.value.geminiAuthToken,
      workerForm.value.geminiModel,
    ),
  }
}

async function saveWorkerForm() {
  if (!workerForm.value.name || !workerForm.value.baseUrl) {
    ElMessage.warning('请填写名称和地址')
    return
  }
  saving.value = true
  try {
    if (workerDialogMode.value === 'add') {
      if (!workerForm.value.authToken) {
        ElMessage.warning('请填写认证令牌')
        saving.value = false
        return
      }
      await apiRegisterWorker(buildWorkerPayload())
    } else {
      const form = buildWorkerPayload()
      if (workerForm.value.authToken) form.authToken = workerForm.value.authToken
      await apiUpdateWorker(editingWorkerId.value, form)
    }
    showWorkerAddDialog.value = false
    workerForm.value = {
      name: '',
      baseUrl: '',
      authToken: '',
      authMode: 'SUBSCRIPTION',
      codexBaseUrl: '',
      codexAuthToken: '',
      codexModel: '',
      geminiBaseUrl: '',
      geminiAuthToken: '',
      geminiModel: '',
    }
    ElMessage.success('保存成功')
    await loadWorkers()
  } catch (e: any) {
    const errorMsg = e?.response?.data?.msg || e?.response?.data?.message || e?.message || '保存失败'
    ElMessage.error(errorMsg)
  } finally {
    saving.value = false
  }
}

async function deleteWorkerById(workerId: string) {
  try {
    await ElMessageBox.confirm('确认删除该 Worker？', '提示', { type: 'warning' })
    await apiDeleteWorker(workerId)
    ElMessage.success('已删除')
    await loadWorkers()
  } catch { /* cancelled */ }
}

async function handleWorkerHealthCheck(workerId: string) {
  try {
    await apiHealthCheck(workerId)
    ElMessage.success('状态已刷新')
    await loadWorkers()
  } catch {
    ElMessage.error('健康检查失败')
  }
}

// ===== Memories =====
const showMemoryDialog_ = ref(false)
const memoryDialogMode = ref<'add' | 'edit'>('add')
const editingMemoryId = ref('')
const memoryForm = ref({ category: 'FACT' as UserMemoryCategory, content: '' })

function showMemoryDialog(mode: 'add' | 'edit') {
  memoryDialogMode.value = mode
  if (mode === 'add') {
    editingMemoryId.value = ''
    memoryForm.value = { category: 'FACT', content: '' }
  }
  showMemoryDialog_.value = true
}

function editMemory(row: UserMemory) {
  memoryDialogMode.value = 'edit'
  editingMemoryId.value = row.id
  memoryForm.value = { category: row.category, content: row.content }
  showMemoryDialog_.value = true
}

async function saveMemoryForm() {
  if (!memoryForm.value.content) {
    ElMessage.warning('请填写记忆内容')
    return
  }
  saving.value = true
  try {
    if (memoryDialogMode.value === 'add') {
      await apiSaveMemory(memoryForm.value)
    } else {
      await apiUpdateMemory(editingMemoryId.value, memoryForm.value)
    }
    showMemoryDialog_.value = false
    ElMessage.success('保存成功')
    await loadMemories()
  } catch {
    ElMessage.error('保存失败')
  } finally {
    saving.value = false
  }
}

async function deleteMemoryById(id: string) {
  try {
    await ElMessageBox.confirm('确认删除该记忆？', '提示', { type: 'warning' })
    await apiDeleteMemory(id)
    ElMessage.success('已删除')
    await loadMemories()
  } catch { /* cancelled */ }
}

function memoryCategoryLabel(c: string): string {
  const map: Record<string, string> = { PREFERENCE: '偏好', FACT: '事实', NOTE: '备注' }
  return map[c] || c
}

function memoryCategoryTag(c: string): '' | 'success' | 'warning' {
  const map: Record<string, '' | 'success' | 'warning'> = { PREFERENCE: 'success', FACT: '', NOTE: 'warning' }
  return map[c] || ''
}

function formatTime(t: string): string {
  if (!t) return '-'
  return t.replace('T', ' ').substring(0, 19)
}

// ===== Credentials =====
const showCredentialDialog_ = ref(false)
const credentialDialogMode = ref<'add' | 'edit'>('add')
const editingCredentialId = ref('')
const credentialForm = ref({
  name: '',
  category: '',
  baseUrl: '',
  apiKey: '',
  authType: 'API_KEY' as AuthType,
  authHeaderName: '',
  description: '',
})
const testingCredential = ref(false)

function showCredentialDialog(mode: 'add' | 'edit') {
  credentialDialogMode.value = mode
  if (mode === 'add') {
    editingCredentialId.value = ''
    credentialForm.value = { name: '', category: '', baseUrl: '', apiKey: '', authType: 'API_KEY', authHeaderName: '', description: '' }
  }
  showCredentialDialog_.value = true
}

function editCredential(row: ApiCredential) {
  credentialDialogMode.value = 'edit'
  editingCredentialId.value = row.id
  credentialForm.value = {
    name: row.name,
    category: row.category,
    baseUrl: row.baseUrl || '',
    apiKey: '',
    authType: row.authType,
    authHeaderName: row.authHeaderName || '',
    description: row.description || '',
  }
  showCredentialDialog_.value = true
}

async function saveCredentialForm() {
  if (!credentialForm.value.name) {
    ElMessage.warning('请填写名称')
    return
  }
  if (!credentialForm.value.apiKey && credentialDialogMode.value === 'add') {
    ElMessage.warning('请填写 API Key')
    return
  }
  saving.value = true
  try {
    if (credentialDialogMode.value === 'add') {
      await apiSaveCredential(credentialForm.value)
    } else {
      const form: Record<string, unknown> = {
        name: credentialForm.value.name,
        category: credentialForm.value.category,
        baseUrl: credentialForm.value.baseUrl,
        authType: credentialForm.value.authType,
        authHeaderName: credentialForm.value.authHeaderName,
        description: credentialForm.value.description,
      }
      if (credentialForm.value.apiKey) form.apiKey = credentialForm.value.apiKey
      await apiUpdateCredential(editingCredentialId.value, form)
    }
    showCredentialDialog_.value = false
    ElMessage.success('保存成功')
    await loadCredentials()
  } catch {
    ElMessage.error('保存失败')
  } finally {
    saving.value = false
  }
}

async function handleTestCredentialForm() {
  if (!credentialForm.value.baseUrl || !credentialForm.value.apiKey) {
    ElMessage.warning('请先填写 Base URL 和 API Key')
    return
  }
  testingCredential.value = true
  try {
    const reply = await apiTestCredential({
      baseUrl: credentialForm.value.baseUrl,
      apiKey: credentialForm.value.apiKey,
      authType: credentialForm.value.authType,
      authHeaderName: credentialForm.value.authHeaderName,
    })
    ElMessage.success('连接成功: ' + (reply || 'OK'))
  } catch (e: any) {
    ElMessage.error('连接失败: ' + (e?.response?.data?.msg || e?.message || '未知错误'))
  } finally {
    testingCredential.value = false
  }
}

async function handleTestCredential(row: ApiCredential & { _testing?: boolean }) {
  row._testing = true
  try {
    const reply = await apiTestSavedCredential(row.id)
    ElMessage.success('连接成功: ' + (reply || 'OK'))
  } catch (e: any) {
    ElMessage.error('连接失败: ' + (e?.response?.data?.msg || e?.message || '未知错误'))
  } finally {
    row._testing = false
  }
}

async function deleteCredentialById(id: string) {
  try {
    await ElMessageBox.confirm('确认删除该凭证？', '提示', { type: 'warning' })
    await apiDeleteCredential(id)
    resetSetupStatus()
    ElMessage.success('已删除')
    await loadCredentials()
  } catch { /* cancelled */ }
}

function authTypeLabel(t: string): string {
  const map: Record<string, string> = { API_KEY: 'API Key', BEARER_TOKEN: 'Bearer Token', BASIC_AUTH: 'Basic Auth', CUSTOM_HEADER: '自定义 Header' }
  return map[t] || t
}

// ===== Data Loading =====
async function loadGitProviders() {
  loadingGit.value = true
  try { gitProviders.value = await apiListGit() } catch { /* handled by interceptor */ }
  finally { loadingGit.value = false }
}

async function loadLlmModels() {
  loadingLlm.value = true
  try { llmModels.value = await apiListLlm() } catch { /* handled by interceptor */ }
  finally { loadingLlm.value = false }
}

async function loadOverrides() {
  loadingOverrides.value = true
  try { agentOverrides.value = await apiListOverrides() } catch { /* handled by interceptor */ }
  finally { loadingOverrides.value = false }
}

async function loadWorkers() {
  loadingWorkers.value = true
  try { workers.value = await apiListWorkers() } catch { /* handled by interceptor */ }
  finally { loadingWorkers.value = false }
}

async function loadMemories() {
  loadingMemories.value = true
  try { memories.value = await apiListMemories() } catch { /* handled by interceptor */ }
  finally { loadingMemories.value = false }
}

async function loadCredentials() {
  loadingCredentials.value = true
  try { credentials.value = await apiListCredentials() } catch { /* handled by interceptor */ }
  finally { loadingCredentials.value = false }
}

// ===== Task Assistant =====
const assistantConfig = ref<TaskAssistantConfig | null>(null)
const assistantLoading = ref(false)
const assistantSaving = ref(false)
const assistantTesting = ref(false)
const assistantEnabledToggle = ref(false)
const assistantCreateForm = ref({ workerId: '', directoryPath: '', modelConfigId: '' })

async function loadAssistantConfig() {
  assistantLoading.value = true
  try {
    assistantConfig.value = await apiGetAssistantConfig()
    if (assistantConfig.value) {
      assistantEnabledToggle.value = assistantConfig.value.enabled || false
    }
  } catch { /* handled by interceptor */ }
  finally { assistantLoading.value = false }
}

async function handleCreateAssistant() {
  assistantSaving.value = true
  try {
    const selectedModel = llmModels.value.find(m => m.id === assistantCreateForm.value.modelConfigId)
    assistantConfig.value = await apiCreateAssistant({
      workerId: assistantCreateForm.value.workerId,
      directoryPath: assistantCreateForm.value.directoryPath || undefined,
      modelConfigId: assistantCreateForm.value.modelConfigId || undefined,
      model: selectedModel?.modelName || undefined,
    })
    assistantEnabledToggle.value = assistantConfig.value?.enabled || false
    ElMessage.success('助手创建成功')
  } catch (e: any) {
    ElMessage.error('创建失败: ' + (e?.response?.data?.msg || e?.message || '未知错误'))
  } finally {
    assistantSaving.value = false
  }
}

async function handleToggleAssistant(val: boolean) {
  try {
    assistantConfig.value = await apiUpdateAssistantConfig({ enabled: val })
    ElMessage.success(val ? '助手已启用' : '助手已禁用')
  } catch {
    assistantEnabledToggle.value = !val
    ElMessage.error('操作失败')
  }
}

async function handleDeleteAssistant() {
  try {
    await ElMessageBox.confirm('确认删除助手？Worker 上的工作目录将保留。', '提示', { type: 'warning' })
    await apiDeleteAssistant()
    assistantConfig.value = null
    assistantCreateForm.value = { workerId: '', directoryPath: '', modelConfigId: '' }
    ElMessage.success('助手已删除')
  } catch { /* cancelled */ }
}

async function handleTestNotification() {
  assistantTesting.value = true
  try {
    await apiTestNotification()
    ElMessage.success('测试通知已发送')
  } catch {
    ElMessage.error('测试失败，请检查配置')
  } finally {
    assistantTesting.value = false
  }
}

function getWorkerName(workerId: string | null): string {
  if (!workerId) return '（未选择）'
  const w = workers.value.find(w => w.workerId === workerId)
  return w ? (w.name || w.workerId) : workerId
}

onMounted(() => {
  loadGitProviders()
  loadLlmModels()
  loadOverrides()
  loadWorkers()
  loadMemories()
  loadCredentials()
  loadAssistantConfig()
})
</script>

<style scoped>
.settings-layout {
  max-width: 960px;
  margin: 0 auto;
  padding: 24px;
}

.settings-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.settings-header h2 {
  margin: 0;
  font-size: 22px;
  color: #303133;
}

.settings-help-button {
  gap: 6px;
}

.settings-tabs {
  background: #fff;
  border-radius: 8px;
  padding: 16px 20px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
}

.tab-toolbar {
  display: flex;
  align-items: center;
  margin-bottom: 16px;
}

.toolbar-left {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.preset-label {
  font-size: 13px;
  color: #909399;
  margin-left: 12px;
}

.status-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.status-dot.online { background: #67c23a; }
.status-dot.offline { background: #c0c4cc; }
.status-dot.unknown { background: #e6a23c; }

.tab-description {
  color: #909399;
  font-size: 13px;
  margin-bottom: 20px;
  line-height: 1.6;
}

.assistant-config {
  padding: 4px 0;
}

.help-drawer-header {
  display: flex;
  align-items: center;
}

.help-drawer-eyebrow {
  font-size: 12px;
  color: #909399;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  margin-bottom: 4px;
}

.help-drawer-title {
  font-size: 20px;
  font-weight: 600;
  color: #303133;
}

.help-drawer-body {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.help-tabs :deep(.el-tabs__content) {
  padding-top: 8px;
}

.help-section {
  border: 1px solid #ebeef5;
  border-radius: 10px;
  padding: 16px;
  margin-bottom: 16px;
  background: #fff;
}

.help-section-title {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 8px;
}

.help-section-text {
  color: #606266;
  line-height: 1.7;
  font-size: 13px;
}

.help-code {
  margin: 12px 0 0;
  padding: 12px 14px;
  background: #0f172a;
  color: #e2e8f0;
  border-radius: 8px;
  overflow-x: auto;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

.help-list {
  margin: 12px 0 0;
  padding-left: 18px;
  color: #606266;
  font-size: 13px;
  line-height: 1.7;
}

.help-list li + li {
  margin-top: 6px;
}
</style>
