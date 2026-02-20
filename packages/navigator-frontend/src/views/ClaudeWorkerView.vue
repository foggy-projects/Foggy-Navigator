<template>
  <div class="worker-layout">
    <!-- Left Panel: Two-level Tree Sidebar -->
    <aside class="worker-sidebar">
      <div class="sidebar-header">
        <h3>Workers</h3>
        <el-dropdown trigger="click" @command="handleAddCommand">
          <el-button type="primary" size="small">+ 添加</el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="worker">添加 Worker</el-dropdown-item>
              <el-dropdown-item command="directory" :disabled="!selectedWorkerId">
                添加工作目录
              </el-dropdown-item>
              <el-dropdown-item command="project" :disabled="!selectedWorkerId">
                添加项目目录
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
      <div class="worker-list">
        <template v-for="worker in workerState.workers.value" :key="worker.workerId">
          <!-- Worker node (Level 1) -->
          <div
            :class="[
              'worker-item',
              { active: selectedWorkerId === worker.workerId && !selectedDirectoryId },
            ]"
            @click="selectWorker(worker.workerId)"
          >
            <div class="worker-info">
              <span
                class="expand-icon"
                @click.stop="toggleExpand(worker.workerId)"
              >
                {{ expandedWorkerIds.has(worker.workerId) ? '▼' : '▶' }}
              </span>
              <span :class="['status-dot', worker.status?.toLowerCase()]" />
              <span class="worker-name">{{ worker.name }}</span>
            </div>
            <div class="worker-meta">{{ worker.hostname || worker.baseUrl }}</div>
          </div>
          <!-- Directory nodes (Level 2): PROJECT dirs first, then orphan STANDARD dirs -->
          <template v-if="expandedWorkerIds.has(worker.workerId)">
            <!-- PROJECT directories -->
            <template v-for="dir in projectDirectoriesForWorker(worker.workerId)" :key="dir.directoryId">
              <div
                :class="[
                  'directory-item project-dir',
                  { active: selectedDirectoryId === dir.directoryId },
                ]"
                @click="selectDirectory(worker.workerId, dir.directoryId)"
              >
                <div class="dir-info">
                  <span
                    class="expand-icon"
                    @click.stop="toggleProjectExpand(dir.directoryId)"
                  >
                    {{ expandedProjectIds.has(dir.directoryId) ? '▼' : '▶' }}
                  </span>
                  <span class="project-icon">&#128230;</span>
                  <span class="dir-name">{{ dir.projectName }}</span>
                  <span class="child-count" :title="childrenForProject(dir.directoryId).length + ' 子目录'">
                    {{ childrenForProject(dir.directoryId).length }}
                  </span>
                </div>
              </div>
              <!-- Level 3: child directories under PROJECT -->
              <template v-if="expandedProjectIds.has(dir.directoryId)">
                <div
                  v-for="child in childrenForProject(dir.directoryId)"
                  :key="child.directoryId"
                  :class="[
                    'directory-item child-dir',
                    { active: selectedDirectoryId === child.directoryId },
                  ]"
                  @click="selectDirectory(worker.workerId, child.directoryId)"
                >
                  <div class="dir-info">
                    <span class="dir-name">{{ child.projectName }}</span>
                    <el-tag v-if="child.worktree" size="small" type="info" effect="plain" class="worktree-tag">worktree</el-tag>
                    <span v-if="child.gitStatus === 'dirty'" class="dirty-dot" title="Uncommitted changes" />
                  </div>
                  <div v-if="child.gitBranch" class="dir-branch">
                    <span class="branch-icon">⎇</span> {{ child.gitBranch }}
                  </div>
                </div>
              </template>
            </template>
            <!-- Orphan STANDARD directories (no parentProjectId) -->
            <div
              v-for="dir in orphanDirectoriesForWorker(worker.workerId)"
              :key="dir.directoryId"
              :class="[
                'directory-item',
                { active: selectedDirectoryId === dir.directoryId },
              ]"
              @click="selectDirectory(worker.workerId, dir.directoryId)"
            >
              <div class="dir-info">
                <span class="dir-name">{{ dir.projectName }}</span>
                <el-tag v-if="dir.worktree" size="small" type="info" effect="plain" class="worktree-tag">worktree</el-tag>
                <span v-if="dir.gitStatus === 'dirty'" class="dirty-dot" title="Uncommitted changes" />
              </div>
              <div v-if="dir.gitBranch" class="dir-branch">
                <span class="branch-icon">⎇</span> {{ dir.gitBranch }}
              </div>
            </div>
            <div
              v-if="directoriesForWorker(worker.workerId).length === 0"
              class="empty-dir-hint"
            >
              暂无工作目录
            </div>
          </template>
        </template>
        <div v-if="workerState.workers.value.length === 0" class="empty-hint">
          暂无 Worker，点击上方添加
        </div>
      </div>
      <div class="sidebar-footer">
        <el-button text size="small" @click="router.push('/')">
          <el-icon><Back /></el-icon> 返回会话
        </el-button>
      </div>
    </aside>

    <!-- Middle Panel: Main Content Area -->
    <main :class="['worker-main', { 'has-panes': panes.length > 0 }]" @paste="handlePaste" @drop="handleDrop" @dragover="handleDragOver">
      <!-- Hidden file input for image picker -->
      <input ref="fileInputRef" type="file" multiple accept="image/*" style="display: none" @change="handleFileSelect">
      <!-- Directory selected: show git info + task form scoped to directory -->
      <template v-if="selectedDirectory">
        <!-- Compact directory header -->
        <div class="dir-compact-header">
          <div class="dir-compact-info">
            <h2>{{ selectedDirectory.projectName }}</h2>
            <el-tag v-if="selectedDirectory.gitBranch" size="small">
              ⎇ {{ selectedDirectory.gitBranch }}
            </el-tag>
            <el-tag
              v-if="selectedDirectory.gitStatus"
              :type="selectedDirectory.gitStatus === 'clean' ? 'success' : selectedDirectory.gitStatus === 'dirty' ? 'warning' : 'info'"
              size="small"
            >
              {{ selectedDirectory.gitStatus }}
            </el-tag>
            <code class="dir-path-inline">{{ selectedDirectory.path }}</code>
            <el-tag
              v-if="selectedDirectory.defaultAuthMode"
              :type="selectedDirectory.defaultAuthConfigured ? 'success' : 'info'"
              size="small"
            >
              {{ authModeLabel(selectedDirectory.defaultAuthMode) }}
            </el-tag>
            <el-tag v-else size="small" type="warning">Auth 未配置</el-tag>
          </div>
          <div class="header-actions">
            <el-button size="small" :loading="syncing" @click="handleSyncGitInfo">
              同步 Git
            </el-button>
            <el-button size="small" :loading="syncingSessions" @click="handleSyncSessions">
              同步会话
            </el-button>
            <el-button
              v-if="!selectedDirectory.worktree && selectedDirectory.directoryType !== 'PROJECT'"
              size="small"
              @click="showWorktreeDialog = true"
            >
              创建 Worktree
            </el-button>
            <el-button size="small" @click="openFileBrowser">浏览文件</el-button>
            <el-button size="small" @click="showEditDirectoryDialog = true">编辑</el-button>
            <el-button
              v-if="selectedDirectory.worktree"
              size="small"
              type="warning"
              text
              @click="handleRemoveWorktree"
            >
              清理 Worktree
            </el-button>
            <el-button v-else size="small" type="danger" text @click="handleDeleteDirectory">删除</el-button>
          </div>
        </div>
        <div v-if="parsedAgentTeams.length" class="agent-teams-bar">
          <el-switch
            v-model="taskForm.useTeams"
            size="small"
            inline-prompt
            active-text="ON"
            inactive-text="OFF"
            style="margin-right: 6px"
          />
          <span class="agent-teams-label" :style="{ opacity: taskForm.useTeams ? 1 : 0.4 }">Agent Teams:</span>
          <el-tag
            v-for="agent in parsedAgentTeams"
            :key="agent"
            size="small"
            :type="taskForm.useTeams ? 'info' : 'info'"
            :effect="taskForm.useTeams ? 'light' : 'plain'"
            :style="{ opacity: taskForm.useTeams ? 1 : 0.4 }"
          >
            {{ agent }}
          </el-tag>
        </div>

        <!-- Task Form: collapse when panes are open -->
        <div v-if="panes.length === 0" class="task-form">
          <el-form :model="taskForm" label-position="top" size="default">
            <el-form-item label="Prompt">
              <SlashCommandInput
                v-model="taskForm.prompt"
                :rows="3"
                placeholder="输入任务描述... (可粘贴截图或拖拽图片)"
                :skills="directorySkills"
                @submit="handleCreateTask"
                @command="handleSlashCommand"
              />
            </el-form-item>
            <!-- Image preview strip -->
            <div v-if="attachedImages.length > 0" class="image-preview-strip">
              <div v-for="(img, idx) in attachedImages" :key="idx" class="image-preview-item">
                <img :src="img.previewUrl" :alt="img.name" :title="img.name" />
                <span class="image-remove" @click="removeImage(idx)">&times;</span>
              </div>
            </div>
            <div v-if="taskForm.model || taskForm.maxTurns" class="active-overrides">
              <el-tag v-if="taskForm.model" size="small" closable @close="taskForm.model = ''">
                模型: {{ shortModel(taskForm.model) }}
              </el-tag>
              <el-tag v-if="taskForm.maxTurns" size="small" closable @close="taskForm.maxTurns = null">
                轮次: {{ taskForm.maxTurns }}
              </el-tag>
            </div>
            <el-form-item>
              <el-button
                type="primary"
                :disabled="!taskForm.prompt || selectedWorkerEntity?.status !== 'ONLINE'"
                @click="handleCreateTask"
              >
                运行任务
              </el-button>
              <el-button text @click="fileInputRef?.click()" title="附加图片">&#128206;</el-button>
              <el-select v-model="taskForm.permissionMode" size="small" style="width: 150px; margin-left: 8px">
                <el-option value="bypassPermissions" label="跳过权限" />
                <el-option value="acceptEdits" label="自动接受编辑" />
                <el-option value="plan" label="只读(Plan)" />
                <el-option value="default" label="交互式审批" />
              </el-select>
            </el-form-item>
          </el-form>
        </div>
        <div v-else class="new-task-mini">
          <SlashCommandInput
            v-model="taskForm.prompt"
            :rows="1"
            size="small"
            placeholder="新建任务... (可粘贴截图)"
            :skills="directorySkills"
            @submit="handleCreateTask"
            @command="handleSlashCommand"
          >
            <template #append>
              <el-button text size="small" @click="fileInputRef?.click()" title="附加图片">&#128206;</el-button>
              <el-button
                :disabled="!taskForm.prompt || selectedWorkerEntity?.status !== 'ONLINE' || panes.length >= MAX_PANES"
                @click="handleCreateTask"
              >
                运行
              </el-button>
            </template>
          </SlashCommandInput>
          <div v-if="attachedImages.length > 0" class="image-preview-strip compact">
            <div v-for="(img, idx) in attachedImages" :key="idx" class="image-preview-item small">
              <img :src="img.previewUrl" :alt="img.name" :title="img.name" />
              <span class="image-remove" @click="removeImage(idx)">&times;</span>
            </div>
          </div>
          <div v-if="taskForm.model || taskForm.maxTurns" class="active-overrides">
            <el-tag v-if="taskForm.model" size="small" closable @close="taskForm.model = ''">
              模型: {{ shortModel(taskForm.model) }}
            </el-tag>
            <el-tag v-if="taskForm.maxTurns" size="small" closable @close="taskForm.maxTurns = null">
              轮次: {{ taskForm.maxTurns }}
            </el-tag>
          </div>
        </div>

        <!-- Empty state when no panes -->
        <div v-if="panes.length === 0" class="pane-empty-hint">
          从右侧历史选择会话查看，或新建任务开始
        </div>

        <!-- Multi-Pane Grid -->
        <TaskPaneGrid
          v-if="panes.length > 0"
          :panes="panes"
          :skills="directorySkills"
          @close="closePane"
          @abort="abortPane"
          @send="handlePaneSend"
          @command="handleSlashCommand"
          @permission-respond="handlePermissionRespond"
          @question-respond="handleQuestionRespond"
          @plan-respond="handlePlanRespond"
        />
      </template>

      <!-- Worker selected (no directory): show worker details + all tasks -->
      <template v-else-if="selectedWorkerEntity">
        <div class="worker-header">
          <div class="header-info">
            <h2>{{ selectedWorkerEntity.name }}</h2>
            <el-tag :type="statusTagType(selectedWorkerEntity.status)" size="small">
              {{ selectedWorkerEntity.status }}
            </el-tag>
            <span v-if="selectedWorkerEntity.workerVersion" class="version-tag">
              v{{ selectedWorkerEntity.workerVersion }}
            </span>
          </div>
          <div class="header-actions">
            <el-button size="small" @click="handleRefreshStatus">刷新状态</el-button>
            <el-button size="small" @click="showEditDialog = true">编辑</el-button>
            <el-button size="small" type="danger" text @click="handleDelete">删除</el-button>
          </div>
        </div>

        <!-- Task Form: collapse when panes are open -->
        <div v-if="panes.length === 0" class="task-form">
          <el-form :model="taskForm" label-position="top" size="default">
            <el-form-item label="Prompt">
              <SlashCommandInput
                v-model="taskForm.prompt"
                :rows="3"
                placeholder="输入任务描述... (可粘贴截图或拖拽图片)"
                :skills="directorySkills"
                @submit="handleCreateTask"
                @command="handleSlashCommand"
              />
            </el-form-item>
            <!-- Image preview strip -->
            <div v-if="attachedImages.length > 0" class="image-preview-strip">
              <div v-for="(img, idx) in attachedImages" :key="idx" class="image-preview-item">
                <img :src="img.previewUrl" :alt="img.name" :title="img.name" />
                <span class="image-remove" @click="removeImage(idx)">&times;</span>
              </div>
            </div>
            <el-form-item label="工作目录 (cwd)">
              <el-input v-model="taskForm.cwd" placeholder="可选，如 /home/user/project" />
            </el-form-item>
            <div v-if="taskForm.model || taskForm.maxTurns" class="active-overrides">
              <el-tag v-if="taskForm.model" size="small" closable @close="taskForm.model = ''">
                模型: {{ shortModel(taskForm.model) }}
              </el-tag>
              <el-tag v-if="taskForm.maxTurns" size="small" closable @close="taskForm.maxTurns = null">
                轮次: {{ taskForm.maxTurns }}
              </el-tag>
            </div>
            <el-form-item>
              <el-button
                type="primary"
                :disabled="!taskForm.prompt || selectedWorkerEntity.status !== 'ONLINE'"
                @click="handleCreateTask"
              >
                运行任务
              </el-button>
              <el-button text @click="fileInputRef?.click()" title="附加图片">&#128206;</el-button>
              <el-select v-model="taskForm.permissionMode" size="small" style="width: 150px; margin-left: 8px">
                <el-option value="bypassPermissions" label="跳过权限" />
                <el-option value="acceptEdits" label="自动接受编辑" />
                <el-option value="plan" label="只读(Plan)" />
                <el-option value="default" label="交互式审批" />
              </el-select>
            </el-form-item>
          </el-form>
        </div>
        <div v-else class="new-task-mini">
          <SlashCommandInput
            v-model="taskForm.prompt"
            :rows="1"
            size="small"
            placeholder="新建任务... (可粘贴截图)"
            :skills="directorySkills"
            @submit="handleCreateTask"
            @command="handleSlashCommand"
          >
            <template #append>
              <el-button text size="small" @click="fileInputRef?.click()" title="附加图片">&#128206;</el-button>
              <el-button
                :disabled="!taskForm.prompt || selectedWorkerEntity.status !== 'ONLINE' || panes.length >= MAX_PANES"
                @click="handleCreateTask"
              >
                运行
              </el-button>
            </template>
          </SlashCommandInput>
          <div v-if="attachedImages.length > 0" class="image-preview-strip compact">
            <div v-for="(img, idx) in attachedImages" :key="idx" class="image-preview-item small">
              <img :src="img.previewUrl" :alt="img.name" :title="img.name" />
              <span class="image-remove" @click="removeImage(idx)">&times;</span>
            </div>
          </div>
          <div v-if="taskForm.model || taskForm.maxTurns" class="active-overrides">
            <el-tag v-if="taskForm.model" size="small" closable @close="taskForm.model = ''">
              模型: {{ shortModel(taskForm.model) }}
            </el-tag>
            <el-tag v-if="taskForm.maxTurns" size="small" closable @close="taskForm.maxTurns = null">
              轮次: {{ taskForm.maxTurns }}
            </el-tag>
          </div>
        </div>

        <!-- Empty state when no panes -->
        <div v-if="panes.length === 0" class="pane-empty-hint">
          从右侧历史选择会话查看，或新建任务开始
        </div>

        <!-- Multi-Pane Grid -->
        <TaskPaneGrid
          v-if="panes.length > 0"
          :panes="panes"
          :skills="directorySkills"
          @close="closePane"
          @abort="abortPane"
          @send="handlePaneSend"
          @command="handleSlashCommand"
          @permission-respond="handlePermissionRespond"
          @question-respond="handleQuestionRespond"
          @plan-respond="handlePlanRespond"
        />
      </template>

      <template v-else>
        <div class="empty-state">
          <h2>Claude Workers</h2>
          <p>选择一个 Worker 或添加新的 Worker 开始使用</p>
        </div>
      </template>
    </main>

    <!-- Right Panel: Task History -->
    <aside v-if="selectedWorkerId" class="worker-history">
      <div class="history-header">
        <h3>历史会话</h3>
        <div class="history-header-actions">
          <el-button
            size="small"
            text
            title="刷新会话列表"
            @click="handleRefreshConversations"
          >
            &#8635;
          </el-button>
          <el-button
            v-if="activeConversations.length > 0"
            size="small"
            text
            title="批量配置 Auth"
            @click="handleBatchAuthConfig"
          >
            &#128273; 批量
          </el-button>
        </div>
      </div>
      <div class="history-content">
        <!-- Conversation list (shared template for directory / worker-level) -->
        <div
          v-if="activeConversations.length > 0"
          class="conv-list"
        >
          <div
            v-for="conv in activeConversations"
            :key="conv.sessionId"
            :class="['conv-item', { 'conv-pinned': conv.config?.pinned }]"
            @click="viewTask(conv.latestTask)"
          >
            <div class="conv-row-1">
              <span
                v-if="conv.config?.pinned"
                class="conv-pin-icon active"
                title="已置顶"
                @click.stop="handleTogglePin(conv)"
              >&#128204;</span>
              <span class="conv-prompt" :title="conv.config?.customTitle || conv.firstPrompt">{{
                truncate(conv.config?.customTitle || conv.firstPrompt, 36)
              }}</span>
              <span :class="['conv-status-dot', conv.latestTask.status.toLowerCase()]" :title="conv.latestTask.status" />
            </div>
            <div class="conv-row-2">
              <span v-if="conv.tasks.length > 1" class="conv-rounds">{{ conv.tasks.length }}轮</span>
              <span v-if="conv.latestTask.model" class="conv-model">{{ shortModel(conv.latestTask.model) }}</span>
              <span v-if="conv.totalCost > 0" class="conv-cost">${{ conv.totalCost.toFixed(2) }}</span>
              <span v-if="conv.config?.authBound" class="conv-auth-badge" :title="'Auth: ' + (conv.config.authMode || 'bound')">&#128273;</span>
              <span class="conv-time">{{ formatTime(conv.latestTask.createdAt) }}</span>
              <span class="conv-hover-actions" @click.stop>
                <el-button
                  size="small"
                  text
                  :title="conv.config?.pinned ? '取消置顶' : '置顶'"
                  @click="handleTogglePin(conv)"
                >
                  {{ conv.config?.pinned ? '&#128204;' : '&#128392;' }}
                </el-button>
                <el-button
                  size="small"
                  text
                  title="编辑标题"
                  @click="handleEditTitle(conv)"
                >
                  &#9998;
                </el-button>
                <el-button
                  size="small"
                  text
                  title="Auth 配置"
                  @click="handleAuthConfig(conv)"
                >
                  &#128273;
                </el-button>
                <el-button
                  size="small"
                  text
                  title="详情"
                  @click="handleShowDetail(conv)"
                >
                  &#8505;
                </el-button>
                <el-button
                  v-if="conv.latestTask.status === 'RUNNING'"
                  type="warning"
                  size="small"
                  text
                  @click="handleAbortTask(conv.latestTask.taskId)"
                >
                  中止
                </el-button>
                <el-button
                  v-if="conv.latestTask.status !== 'RUNNING' && conv.claudeSessionId"
                  type="primary"
                  size="small"
                  text
                  @click="handleResumeFromHistory(conv.latestTask)"
                >
                  继续
                </el-button>
                <el-button
                  v-if="conv.latestTask.status !== 'RUNNING' && hasCheckpoints(conv.latestTask)"
                  type="info"
                  size="small"
                  text
                  @click.stop="showRewindDialog(conv.latestTask)"
                >
                  回退
                </el-button>
                <el-button
                  v-if="conv.latestTask.status !== 'RUNNING'"
                  size="small"
                  text
                  class="delete-btn"
                  @click="handleDeleteConversation(conv)"
                >
                  删除
                </el-button>
              </span>
            </div>
          </div>
        </div>
        <div v-if="activeConversations.length === 0" class="empty-hint">暂无历史会话</div>
        <!-- Pagination -->
        <el-pagination
          v-if="selectedDirectoryId ? dirTaskTotal > dirTaskSize : workerState.taskTotal.value > workerState.taskSize.value"
          class="task-pagination"
          small
          layout="prev, pager, next"
          :total="selectedDirectoryId ? dirTaskTotal : workerState.taskTotal.value"
          :page-size="selectedDirectoryId ? dirTaskSize : workerState.taskSize.value"
          :current-page="(selectedDirectoryId ? dirTaskPage : workerState.taskPage.value) + 1"
          @current-change="selectedDirectoryId ? handleDirPageChange($event) : handlePageChange($event)"
        />
      </div>
    </aside>

    <!-- Add Worker Dialog -->
    <el-dialog v-model="showAddDialog" title="添加 Worker" width="480px">
      <el-form :model="addForm" label-position="top">
        <el-form-item label="名称" required>
          <el-input v-model="addForm.name" placeholder="如：我的家庭机" />
        </el-form-item>
        <el-form-item label="地址" required>
          <el-input v-model="addForm.baseUrl" placeholder="如：http://192.168.1.100:3001" />
        </el-form-item>
        <el-form-item label="认证令牌" required>
          <el-input
            v-model="addForm.authToken"
            type="password"
            show-password
            placeholder="Worker 预共享令牌"
          />
        </el-form-item>
        <el-form-item label="认证模式">
          <el-select v-model="addForm.authMode" style="width: 100%">
            <el-option label="订阅模式 (Claude Max)" value="SUBSCRIPTION" />
            <el-option label="API Key 模式" value="API_KEY" />
            <el-option label="自定义端点" value="CUSTOM_ENDPOINT" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showAddDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleAdd">添加</el-button>
      </template>
    </el-dialog>

    <!-- Edit Worker Dialog -->
    <el-dialog v-model="showEditDialog" title="编辑 Worker" width="480px">
      <el-form :model="editForm" label-position="top">
        <el-form-item label="名称">
          <el-input v-model="editForm.name" />
        </el-form-item>
        <el-form-item label="地址">
          <el-input v-model="editForm.baseUrl" />
        </el-form-item>
        <el-form-item label="认证令牌">
          <el-input
            v-model="editForm.authToken"
            type="password"
            show-password
            placeholder="留空保持不变"
          />
        </el-form-item>
        <el-form-item label="认证模式">
          <el-select v-model="editForm.authMode" style="width: 100%">
            <el-option label="订阅模式 (Claude Max)" value="SUBSCRIPTION" />
            <el-option label="API Key 模式" value="API_KEY" />
            <el-option label="自定义端点" value="CUSTOM_ENDPOINT" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showEditDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleEdit">保存</el-button>
      </template>
    </el-dialog>

    <!-- Add Directory Dialog -->
    <el-dialog v-model="showAddDirectoryDialog" :title="addDirForm.directoryType === 'PROJECT' ? '添加项目目录' : '添加工作目录'" width="480px">
      <el-form :model="addDirForm" label-position="top">
        <el-form-item label="目录类型">
          <el-radio-group v-model="addDirForm.directoryType">
            <el-radio value="STANDARD">普通目录</el-radio>
            <el-radio value="PROJECT">项目目录</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="项目名称" required>
          <el-input v-model="addDirForm.projectName" placeholder="如：foggy-navigator / feature-branch" />
        </el-form-item>
        <el-form-item label="路径" required>
          <el-input v-model="addDirForm.path" placeholder="如：/home/user/projects/foggy-navigator" />
        </el-form-item>
        <el-form-item v-if="addDirForm.directoryType === 'STANDARD' && projectDirectoriesForCurrentWorker.length > 0" label="所属项目">
          <el-select v-model="addDirForm.parentProjectId" clearable placeholder="(独立目录)" style="width: 100%">
            <el-option
              v-for="proj in projectDirectoriesForCurrentWorker"
              :key="proj.directoryId"
              :label="proj.projectName"
              :value="proj.directoryId"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showAddDirectoryDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleAddDirectory">添加</el-button>
      </template>
    </el-dialog>

    <!-- Edit Directory Dialog -->
    <el-dialog v-model="showEditDirectoryDialog" title="编辑工作目录" width="480px">
      <el-form :model="editDirForm" label-position="top">
        <el-form-item label="项目名称">
          <el-input v-model="editDirForm.projectName" />
        </el-form-item>
        <el-form-item label="路径">
          <el-input v-model="editDirForm.path" />
        </el-form-item>
        <el-form-item label="Agent Teams">
          <el-input
            v-model="editDirForm.agentTeamsConfig"
            type="textarea"
            :rows="6"
            placeholder='{"reviewer": {"description": "...", "prompt": "..."}}'
          />
          <div class="form-tip">
            JSON 格式定义子 Agent 团队。Claude 会自动分派子任务给团队成员。
          </div>
        </el-form-item>
        <el-form-item v-if="selectedDirectory?.directoryType === 'PROJECT'" label="项目任务 Prompt">
          <el-input
            v-model="editDirForm.projectTaskPrompt"
            type="textarea"
            :rows="4"
            placeholder="定义项目级任务分配的 system prompt..."
          />
          <div class="form-tip">
            项目编排器使用此 prompt 将任务分解并分派给子目录。
          </div>
        </el-form-item>
        <el-form-item v-if="selectedDirectory?.directoryType === 'STANDARD' && projectDirectoriesForCurrentWorker.length > 0" label="所属项目">
          <el-select v-model="editDirForm.parentProjectId" clearable placeholder="(独立目录)" style="width: 100%">
            <el-option
              v-for="proj in projectDirectoriesForCurrentWorker"
              :key="proj.directoryId"
              :label="proj.projectName"
              :value="proj.directoryId"
            />
          </el-select>
        </el-form-item>
        <el-divider content-position="left">Auth 默认配置</el-divider>
        <el-form-item label="认证模式">
          <el-radio-group v-model="editDirForm.defaultAuthMode">
            <el-radio value="">未配置</el-radio>
            <el-radio value="SUBSCRIPTION">Subscription</el-radio>
            <el-radio value="API_KEY">API Key</el-radio>
            <el-radio value="CUSTOM_ENDPOINT">自定义端点</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="editDirForm.defaultAuthMode === 'API_KEY'" label="API Key">
          <el-input
            v-model="editDirForm.defaultAuthToken"
            type="password"
            show-password
            :placeholder="selectedDirectory?.maskedDefaultAuthToken || '留空保持不变'"
          />
        </el-form-item>
        <el-form-item v-if="editDirForm.defaultAuthMode === 'CUSTOM_ENDPOINT'" label="Auth Token">
          <el-input
            v-model="editDirForm.defaultAuthToken"
            type="password"
            show-password
            :placeholder="selectedDirectory?.maskedDefaultAuthToken || '自定义端点的认证 token'"
          />
        </el-form-item>
        <el-form-item v-if="editDirForm.defaultAuthMode === 'CUSTOM_ENDPOINT'" label="Base URL">
          <el-input v-model="editDirForm.defaultBaseUrl" placeholder="https://aiproxy.example.com/api/v1/anthropic" />
        </el-form-item>
        <div v-if="editDirForm.defaultAuthMode === 'SUBSCRIPTION'" class="form-tip">
          使用 Worker 端 claude login 凭据，无需配置 Token
        </div>
      </el-form>
      <template #footer>
        <el-button @click="showEditDirectoryDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleEditDirectory">保存</el-button>
      </template>
    </el-dialog>

    <!-- Auth Bind Dialog -->
    <el-dialog v-model="showAuthDialog" title="Auth 配置" width="500px">
      <template v-if="authDialogReadonly">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="认证模式">{{ authForm.authMode || '-' }}</el-descriptions-item>
          <el-descriptions-item label="Base URL">{{ authForm.baseUrl || '(默认)' }}</el-descriptions-item>
          <el-descriptions-item label="状态">已锁定，不可修改</el-descriptions-item>
        </el-descriptions>
      </template>
      <template v-else>
        <el-form :model="authForm" label-position="top">
          <el-form-item label="认证模式">
            <el-radio-group v-model="authForm.authMode">
              <el-radio value="SUBSCRIPTION">订阅模式</el-radio>
              <el-radio value="API_KEY">API Key</el-radio>
              <el-radio value="CUSTOM_ENDPOINT">自定义端点</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item v-if="authForm.authMode === 'SUBSCRIPTION'" label="Auth Token">
            <el-input v-model="authForm.authToken" type="password" show-password placeholder="留空则使用 claude login 的订阅" />
            <div class="form-tip">订阅模式可不填 token，使用 Worker 端的 claude login 凭据</div>
          </el-form-item>
          <el-form-item v-if="authForm.authMode === 'API_KEY'" label="API Key" required>
            <el-input v-model="authForm.authToken" type="password" show-password placeholder="sk-ant-xxx" />
          </el-form-item>
          <el-form-item v-if="authForm.authMode === 'CUSTOM_ENDPOINT'" label="Auth Token" required>
            <el-input v-model="authForm.authToken" type="password" show-password placeholder="自定义端点的认证 token" />
          </el-form-item>
          <el-form-item v-if="authForm.authMode === 'CUSTOM_ENDPOINT'" label="Base URL" required>
            <el-input v-model="authForm.baseUrl" placeholder="https://aiproxy.example.com/api/v1/anthropic" />
          </el-form-item>
        </el-form>
      </template>
      <template #footer>
        <el-button @click="showAuthDialog = false">{{ authDialogReadonly ? '关闭' : '取消' }}</el-button>
        <el-button v-if="!authDialogReadonly" type="primary" :loading="saving" @click="handleBindAuth">绑定</el-button>
      </template>
    </el-dialog>

    <!-- Batch Auth Bind Dialog -->
    <el-dialog v-model="showBatchAuthDialog" title="批量 Auth 配置" width="520px">
      <el-form :model="batchAuthForm" label-position="top">
        <el-form-item label="认证模式">
          <el-radio-group v-model="batchAuthForm.authMode">
            <el-radio value="SUBSCRIPTION">订阅模式</el-radio>
            <el-radio value="API_KEY">API Key</el-radio>
            <el-radio value="CUSTOM_ENDPOINT">自定义端点</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="batchAuthForm.authMode === 'SUBSCRIPTION'" label="Auth Token">
          <el-input v-model="batchAuthForm.authToken" type="password" show-password placeholder="留空则使用订阅凭据" />
        </el-form-item>
        <el-form-item v-if="batchAuthForm.authMode === 'API_KEY'" label="API Key" required>
          <el-input v-model="batchAuthForm.authToken" type="password" show-password placeholder="sk-ant-xxx" />
        </el-form-item>
        <el-form-item v-if="batchAuthForm.authMode === 'CUSTOM_ENDPOINT'" label="Auth Token" required>
          <el-input v-model="batchAuthForm.authToken" type="password" show-password placeholder="自定义端点的认证 token" />
        </el-form-item>
        <el-form-item v-if="batchAuthForm.authMode === 'CUSTOM_ENDPOINT'" label="Base URL" required>
          <el-input v-model="batchAuthForm.baseUrl" placeholder="https://aiproxy.example.com/api/v1/anthropic" />
        </el-form-item>
        <el-form-item>
          <el-checkbox v-model="batchAuthForm.skipExisting">跳过已有 Auth 配置的会话</el-checkbox>
        </el-form-item>
        <div class="form-tip">
          将为当前 {{ selectedDirectoryId ? '目录' : 'Worker' }} 下的 {{ batchAuthSessionIds.length }} 个会话批量配置 Auth
        </div>
      </el-form>
      <template #footer>
        <el-button @click="showBatchAuthDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleBatchBindAuth">批量绑定</el-button>
      </template>
    </el-dialog>

    <!-- Create Worktree Dialog -->
    <el-dialog v-model="showWorktreeDialog" title="创建 Git Worktree" width="440px">
      <el-form :model="worktreeForm" label-position="top">
        <el-form-item label="分支名" required>
          <el-input v-model="worktreeForm.branch" placeholder="如：hotfix/prod-issue-123" />
        </el-form-item>
      </el-form>
      <div class="form-tip">
        将基于当前目录创建 git worktree，在新分支上并发工作。
      </div>
      <template #footer>
        <el-button @click="showWorktreeDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleCreateWorktree">创建</el-button>
      </template>
    </el-dialog>

    <!-- Conversation Detail Dialog -->
    <el-dialog v-model="showDetailDialog" title="会话详情" width="560px">
      <el-descriptions v-if="detailConv" :column="2" border>
        <el-descriptions-item label="Session ID" :span="2">
          <code>{{ detailConv.sessionId }}</code>
        </el-descriptions-item>
        <el-descriptions-item label="Claude Session ID" :span="2">
          <code>{{ detailConv.claudeSessionId || '-' }}</code>
        </el-descriptions-item>
        <el-descriptions-item label="自定义标题" :span="2">
          {{ detailConv.config?.customTitle || '(未设置)' }}
        </el-descriptions-item>
        <el-descriptions-item label="原始 Prompt" :span="2">
          {{ detailConv.firstPrompt }}
        </el-descriptions-item>
        <el-descriptions-item label="任务轮次">{{ detailConv.tasks.length }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ detailConv.latestTask.status }}</el-descriptions-item>
        <el-descriptions-item label="模型">{{ detailConv.latestTask.model || '-' }}</el-descriptions-item>
        <el-descriptions-item label="工作目录">{{ detailConv.latestTask.cwd || '-' }}</el-descriptions-item>
        <el-descriptions-item label="总费用">${{ detailConv.totalCost.toFixed(4) }}</el-descriptions-item>
        <el-descriptions-item label="总 Tokens">
          {{ detailTotalTokens.input }} in / {{ detailTotalTokens.output }} out
        </el-descriptions-item>
        <el-descriptions-item label="置顶">{{ detailConv.config?.pinned ? '是' : '否' }}</el-descriptions-item>
        <el-descriptions-item label="Auth 模式">
          {{ detailConv.config?.authBound ? detailAuthModeLabel : '未绑定' }}
        </el-descriptions-item>
        <el-descriptions-item v-if="detailConv.config?.authBound" label="Auth Token" :span="2">
          <code>{{ detailConv.config.maskedAuthToken || '(无)' }}</code>
        </el-descriptions-item>
        <el-descriptions-item v-if="detailConv.config?.baseUrl" label="Base URL" :span="2">
          {{ detailConv.config.baseUrl }}
        </el-descriptions-item>
        <el-descriptions-item label="创建时间" :span="2">
          {{ new Date(detailConv.latestTask.createdAt).toLocaleString('zh-CN') }}
        </el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <el-button @click="showDetailDialog = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- Rewind Dialog -->
    <el-dialog v-model="showRewind" title="回退文件" width="480px">
      <p style="margin-bottom: 12px">选择回退到的 Checkpoint（文件将还原到该时间点）：</p>
      <el-radio-group v-model="rewindSelectedId" style="display: flex; flex-direction: column; gap: 8px">
        <el-radio
          v-for="cp in rewindCheckpoints"
          :key="cp.id"
          :value="cp.id"
          style="height: auto; padding: 6px 0"
        >
          <span>Turn {{ cp.turnIndex }}</span>
          <span style="margin-left: 12px; color: #909399; font-size: 12px">{{ cp.timestamp }}</span>
        </el-radio>
      </el-radio-group>
      <template #footer>
        <el-button @click="showRewind = false">取消</el-button>
        <el-button type="warning" :disabled="!rewindSelectedId" :loading="rewindLoading" @click="handleRewind">
          确认回退
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, shallowRef, triggerRef, computed, reactive, onMounted, onUnmounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Back } from '@element-plus/icons-vue'
import { useClaudeWorker } from '@/composables/useClaudeWorker'
import { useTaskPane } from '@/composables/useTaskPane'
import type { TaskPaneState } from '@/composables/useTaskPane'
import TaskPaneGrid from '@/components/worker/TaskPaneGrid.vue'
import SlashCommandInput from '@/components/worker/SlashCommandInput.vue'
import * as dirApi from '@/api/claudeWorker'
import type { ClaudeTask, WorkingDirectory, SkillInfo, ConversationConfig } from '@/types'

const MAX_PANES = 4

const router = useRouter()
const workerState = useClaudeWorker()

const selectedWorkerId = ref<string | null>(null)
const selectedDirectoryId = ref<string | null>(null)
const expandedWorkerIds = reactive(new Set<string>())
const expandedProjectIds = reactive(new Set<string>())
const panes = shallowRef<TaskPaneState[]>([])
const showAddDialog = ref(false)
const showEditDialog = ref(false)
const showAddDirectoryDialog = ref(false)
const showEditDirectoryDialog = ref(false)
const saving = ref(false)
const syncing = ref(false)
const syncingSessions = ref(false)
const directorySkills = ref<SkillInfo[]>([])

// Worktree dialog state
const showWorktreeDialog = ref(false)
const worktreeForm = ref({ branch: '' })

// Auth dialog state
const showAuthDialog = ref(false)
const authDialogReadonly = ref(false)
const authDialogSessionId = ref('')
const authForm = ref({ authMode: 'SUBSCRIPTION', authToken: '', baseUrl: '' })

// Batch auth dialog state
const showBatchAuthDialog = ref(false)
const batchAuthForm = ref({ authMode: 'SUBSCRIPTION', authToken: '', baseUrl: '', skipExisting: true })
const batchAuthSessionIds = ref<string[]>([])

// Detail dialog state
const showDetailDialog = ref(false)
const detailConv = ref<ConversationGroup | null>(null)
const detailTotalTokens = computed(() => {
  if (!detailConv.value) return { input: 0, output: 0 }
  const tasks = detailConv.value.tasks
  return {
    input: tasks.reduce((s, t) => s + (t.inputTokens || 0), 0),
    output: tasks.reduce((s, t) => s + (t.outputTokens || 0), 0),
  }
})
const detailAuthModeLabel = computed(() => {
  const mode = detailConv.value?.config?.authMode
  if (mode === 'SUBSCRIPTION') return '订阅模式'
  if (mode === 'API_KEY') return 'API Key'
  if (mode === 'CUSTOM_ENDPOINT') return '自定义端点'
  return mode || '-'
})

// Rewind dialog state
const showRewind = ref(false)
const rewindTaskId = ref('')
const rewindSelectedId = ref('')
const rewindLoading = ref(false)
const rewindCheckpoints = ref<{ id: string; turnIndex: number; timestamp: string }[]>([])

// Directory task pagination (separate from global task pagination)
const directoryTasks = ref<ClaudeTask[]>([])
const dirTaskPage = ref(0)
const dirTaskSize = ref(20)
const dirTaskTotal = ref(0)

let paneCounter = 0

const addForm = ref({
  name: '',
  baseUrl: '',
  authToken: '',
  authMode: 'SUBSCRIPTION',
})

const editForm = ref({
  name: '',
  baseUrl: '',
  authToken: '',
  authMode: 'SUBSCRIPTION',
})

const addDirForm = ref({
  projectName: '',
  path: '',
  directoryType: 'STANDARD' as string,
  parentProjectId: '' as string,
})

const editDirForm = ref({
  projectName: '',
  path: '',
  agentTeamsConfig: '',
  projectTaskPrompt: '',
  parentProjectId: '' as string,
  defaultAuthMode: '' as string,
  defaultAuthToken: '' as string,
  defaultBaseUrl: '' as string,
})

const taskForm = ref({
  prompt: '',
  cwd: '',
  model: '' as string,
  maxTurns: null as number | null,
  useTeams: true,
  permissionMode: 'bypassPermissions' as string,
})

// --- Image attachment state ---
interface ImageAttachment {
  name: string
  base64: string
  mimeType: string
  previewUrl: string
  size: number  // original file size in bytes
}
const attachedImages = ref<ImageAttachment[]>([])
const fileInputRef = ref<HTMLInputElement | null>(null)
const MAX_IMAGES = 10
const MAX_SINGLE_SIZE = 10 * 1024 * 1024 // 10MB per image before compression

async function compressImage(file: File): Promise<ImageAttachment> {
  return new Promise((resolve, reject) => {
    const img = new Image()
    const objectUrl = URL.createObjectURL(file)
    img.onload = () => {
      const MAX_DIM = 1920
      let { width, height } = img
      if (width > MAX_DIM || height > MAX_DIM) {
        const ratio = Math.min(MAX_DIM / width, MAX_DIM / height)
        width = Math.round(width * ratio)
        height = Math.round(height * ratio)
      }
      const canvas = document.createElement('canvas')
      canvas.width = width
      canvas.height = height
      const ctx = canvas.getContext('2d')!
      ctx.drawImage(img, 0, 0, width, height)
      canvas.toBlob(
        (blob) => {
          URL.revokeObjectURL(objectUrl)
          if (!blob) { reject(new Error('Compression failed')); return }
          const reader = new FileReader()
          reader.onload = () => {
            const base64 = (reader.result as string).split(',')[1]!
            resolve({
              name: file.name.replace(/\.\w+$/, '.webp'),
              base64,
              mimeType: 'image/webp',
              previewUrl: URL.createObjectURL(blob),
              size: file.size,
            })
          }
          reader.onerror = reject
          reader.readAsDataURL(blob)
        },
        'image/webp',
        0.85,
      )
    }
    img.onerror = () => { URL.revokeObjectURL(objectUrl); reject(new Error('Image load failed')) }
    img.src = objectUrl
  })
}

async function addImageFiles(files: FileList | File[]) {
  for (const file of Array.from(files)) {
    if (!file.type.startsWith('image/')) continue
    if (attachedImages.value.length >= MAX_IMAGES) {
      ElMessage.warning(`最多附加 ${MAX_IMAGES} 张图片`)
      break
    }
    if (file.size > MAX_SINGLE_SIZE) {
      ElMessage.warning(`图片 "${file.name}" 超过 10MB，已跳过`)
      continue
    }
    try {
      const attachment = await compressImage(file)
      attachedImages.value.push(attachment)
    } catch {
      ElMessage.warning(`图片 "${file.name}" 处理失败`)
    }
  }
}

function removeImage(index: number) {
  const removed = attachedImages.value.splice(index, 1)
  removed.forEach((img) => URL.revokeObjectURL(img.previewUrl))
}

function handlePaste(e: ClipboardEvent) {
  const items = e.clipboardData?.items
  if (!items) return
  const imageFiles: File[] = []
  for (const item of Array.from(items)) {
    if (item.type.startsWith('image/')) {
      const file = item.getAsFile()
      if (file) {
        // Generate a meaningful name for clipboard images
        const ext = file.type.split('/')[1] || 'png'
        const named = new File([file], `screenshot-${Date.now()}.${ext}`, { type: file.type })
        imageFiles.push(named)
      }
    }
  }
  if (imageFiles.length > 0) {
    e.preventDefault()
    addImageFiles(imageFiles)
  }
}

function handleDrop(e: DragEvent) {
  e.preventDefault()
  const files = e.dataTransfer?.files
  if (files && files.length > 0) {
    addImageFiles(files)
  }
}

function handleDragOver(e: DragEvent) {
  e.preventDefault()
}

function handleFileSelect(e: Event) {
  const input = e.target as HTMLInputElement
  if (input.files && input.files.length > 0) {
    addImageFiles(input.files)
    input.value = '' // reset so same file can be selected again
  }
}

function clearAttachedImages() {
  attachedImages.value.forEach((img) => URL.revokeObjectURL(img.previewUrl))
  attachedImages.value = []
}

const selectedWorkerEntity = computed(() =>
  workerState.workers.value.find((w) => w.workerId === selectedWorkerId.value),
)

const selectedDirectory = computed(() =>
  workerState.directories.value.find((d) => d.directoryId === selectedDirectoryId.value),
)

const parsedAgentTeams = computed(() => {
  const dir = selectedDirectory.value
  if (!dir?.agentTeamsConfig) return []
  try { return Object.keys(JSON.parse(dir.agentTeamsConfig)) }
  catch { return [] }
})

const workerTasks = computed(() =>
  workerState.tasks.value.filter((t) => t.workerId === selectedWorkerId.value),
)

// Per-conversation grouping for history panel
interface ConversationGroup {
  sessionId: string
  claudeSessionId: string
  latestTask: ClaudeTask
  tasks: ClaudeTask[]
  totalCost: number
  firstPrompt: string
  config?: ConversationConfig
}

function groupTasksToConversations(taskList: ClaudeTask[]): ConversationGroup[] {
  const groups = new Map<string, ClaudeTask[]>()
  for (const task of taskList) {
    const key = task.sessionId
    const existing = groups.get(key)
    if (existing) {
      existing.push(task)
    } else {
      groups.set(key, [task])
    }
  }
  return Array.from(groups.values())
    .map((tasks) => ({
      sessionId: tasks[0]!.sessionId,
      claudeSessionId: tasks.find((t) => t.claudeSessionId)?.claudeSessionId || '',
      latestTask: tasks[0]!,
      tasks,
      totalCost: tasks.reduce((s, t) => s + (t.costUsd || 0), 0),
      firstPrompt: tasks[tasks.length - 1]!.prompt,
      config: workerState.conversationConfigs.value.get(tasks[0]!.sessionId),
    }))
    .sort((a, b) => {
      // Pinned conversations first (by pinnedAt desc)
      const aPinned = a.config?.pinned ?? false
      const bPinned = b.config?.pinned ?? false
      if (aPinned && !bPinned) return -1
      if (!aPinned && bPinned) return 1
      if (aPinned && bPinned) {
        const aTime = a.config?.pinnedAt ? new Date(a.config.pinnedAt).getTime() : 0
        const bTime = b.config?.pinnedAt ? new Date(b.config.pinnedAt).getTime() : 0
        return bTime - aTime
      }
      // Then by creation time desc
      return new Date(b.latestTask.createdAt).getTime() - new Date(a.latestTask.createdAt).getTime()
    })
}

const workerConversations = computed(() => groupTasksToConversations(workerTasks.value))
const directoryConversations = computed(() => groupTasksToConversations(directoryTasks.value))
const activeConversations = computed(() =>
  selectedDirectoryId.value ? directoryConversations.value : workerConversations.value,
)

function directoriesForWorker(workerId: string): WorkingDirectory[] {
  return workerState.directories.value.filter((d) => d.workerId === workerId)
}

function projectDirectoriesForWorker(workerId: string): WorkingDirectory[] {
  return workerState.directories.value.filter(
    (d) => d.workerId === workerId && d.directoryType === 'PROJECT',
  )
}

function orphanDirectoriesForWorker(workerId: string): WorkingDirectory[] {
  return workerState.directories.value.filter(
    (d) => d.workerId === workerId && d.directoryType !== 'PROJECT' && !d.parentProjectId,
  )
}

function childrenForProject(projectDirectoryId: string): WorkingDirectory[] {
  return workerState.directories.value.filter(
    (d) => d.parentProjectId === projectDirectoryId,
  )
}

const projectDirectoriesForCurrentWorker = computed(() => {
  if (!selectedWorkerId.value) return []
  return projectDirectoriesForWorker(selectedWorkerId.value)
})

function toggleProjectExpand(projectId: string) {
  if (expandedProjectIds.has(projectId)) {
    expandedProjectIds.delete(projectId)
  } else {
    expandedProjectIds.add(projectId)
  }
}

onMounted(async () => {
  await Promise.all([workerState.loadWorkers(), workerState.loadTasks()])
  // Load conversation configs for all loaded tasks
  const sessionIds = [...new Set(workerState.tasks.value.map((t) => t.sessionId))]
  if (sessionIds.length > 0) {
    await workerState.loadConversationConfigs(sessionIds)
  }
})

onUnmounted(() => {
  disposeAllPanes()
})

/** Called when any pane's task reaches a terminal state */
function handleTaskFinished(_paneId: string) {
  workerState.loadTasks()
  if (selectedDirectoryId.value) {
    loadDirectoryTasks()
  }
}

function disposeAllPanes() {
  for (const pane of panes.value) {
    pane.dispose()
  }
  panes.value = []
}

function toggleExpand(workerId: string) {
  if (expandedWorkerIds.has(workerId)) {
    expandedWorkerIds.delete(workerId)
  } else {
    expandedWorkerIds.add(workerId)
    workerState.loadDirectories(workerId)
  }
}

function selectWorker(workerId: string) {
  selectedWorkerId.value = workerId
  selectedDirectoryId.value = null
  directorySkills.value = []
  disposeAllPanes()
  // Auto-expand
  if (!expandedWorkerIds.has(workerId)) {
    expandedWorkerIds.add(workerId)
    workerState.loadDirectories(workerId)
  }
}

function selectDirectory(workerId: string, directoryId: string) {
  selectedWorkerId.value = workerId
  selectedDirectoryId.value = directoryId
  disposeAllPanes()
  taskForm.value.prompt = ''
  taskForm.value.cwd = ''
  dirTaskPage.value = 0
  loadDirectoryTasks()
  loadDirectorySkills()
}

async function loadDirectoryTasks() {
  if (!selectedDirectoryId.value) return
  try {
    const result = await dirApi.listTasksByDirectoryPaged(
      selectedDirectoryId.value,
      dirTaskPage.value,
      dirTaskSize.value,
    )
    directoryTasks.value = result.content
    dirTaskTotal.value = result.totalElements
  } catch {
    try {
      directoryTasks.value = await dirApi.listTasksByDirectory(selectedDirectoryId.value)
      dirTaskTotal.value = directoryTasks.value.length
    } catch {
      directoryTasks.value = []
      dirTaskTotal.value = 0
    }
  }
  // Load configs for directory tasks
  const sessionIds = [...new Set(directoryTasks.value.map((t) => t.sessionId))]
  if (sessionIds.length > 0) {
    workerState.loadConversationConfigs(sessionIds)
  }
}

function handleAddCommand(command: string) {
  if (command === 'worker') {
    showAddDialog.value = true
  } else if (command === 'directory') {
    if (!selectedWorkerId.value) {
      ElMessage.warning('请先选择一个 Worker')
      return
    }
    const parentId = selectedDirectory.value?.directoryType === 'PROJECT' ? selectedDirectory.value.directoryId : ''
    addDirForm.value = { projectName: '', path: '', directoryType: 'STANDARD', parentProjectId: parentId }
    showAddDirectoryDialog.value = true
  } else if (command === 'project') {
    if (!selectedWorkerId.value) {
      ElMessage.warning('请先选择一个 Worker')
      return
    }
    addDirForm.value = { projectName: '', path: '', directoryType: 'PROJECT', parentProjectId: '' }
    showAddDirectoryDialog.value = true
  }
}

async function handleAdd() {
  if (!addForm.value.name || !addForm.value.baseUrl || !addForm.value.authToken) {
    ElMessage.warning('请填写完整信息')
    return
  }
  saving.value = true
  try {
    await workerState.registerWorker(addForm.value)
    showAddDialog.value = false
    addForm.value = { name: '', baseUrl: '', authToken: '', authMode: 'SUBSCRIPTION' }
    ElMessage.success('Worker 添加成功')
  } catch {
    ElMessage.error('添加失败')
  } finally {
    saving.value = false
  }
}

async function handleEdit() {
  if (!selectedWorkerId.value) return
  saving.value = true
  try {
    await workerState.updateWorker(selectedWorkerId.value, editForm.value)
    showEditDialog.value = false
    ElMessage.success('更新成功')
  } catch {
    ElMessage.error('更新失败')
  } finally {
    saving.value = false
  }
}

async function handleDelete() {
  if (!selectedWorkerId.value) return
  try {
    await ElMessageBox.confirm('确认删除该 Worker？', '提示', {
      type: 'warning',
      confirmButtonText: '确认',
      cancelButtonText: '取消',
    })
    disposeAllPanes()
    await workerState.deleteWorker(selectedWorkerId.value)
    selectedWorkerId.value = null
    selectedDirectoryId.value = null
    ElMessage.success('已删除')
  } catch {
    // cancelled
  }
}

async function handleRefreshStatus() {
  if (!selectedWorkerId.value) return
  try {
    await workerState.refreshWorkerStatus(selectedWorkerId.value)
    ElMessage.success('状态已刷新')
  } catch {
    ElMessage.error('刷新失败')
  }
}

async function handleAddDirectory() {
  if (!selectedWorkerId.value || !addDirForm.value.projectName || !addDirForm.value.path) {
    ElMessage.warning('请填写完整信息')
    return
  }
  saving.value = true
  try {
    const form: Parameters<typeof workerState.createDirectory>[0] = {
      workerId: selectedWorkerId.value,
      projectName: addDirForm.value.projectName,
      path: addDirForm.value.path,
    }
    if (addDirForm.value.directoryType && addDirForm.value.directoryType !== 'STANDARD') {
      (form as Record<string, unknown>).directoryType = addDirForm.value.directoryType
    }
    if (addDirForm.value.parentProjectId) {
      (form as Record<string, unknown>).parentProjectId = addDirForm.value.parentProjectId
    }
    await workerState.createDirectory(form)
    showAddDirectoryDialog.value = false
    ElMessage.success(addDirForm.value.directoryType === 'PROJECT' ? '项目目录添加成功' : '工作目录添加成功')
  } catch (e: unknown) {
    ElMessage.error('添加失败: ' + ((e as Error).message || '未知错误'))
  } finally {
    saving.value = false
  }
}

async function handleEditDirectory() {
  if (!selectedDirectoryId.value) return
  saving.value = true
  try {
    const form: Record<string, string | undefined> = {
      projectName: editDirForm.value.projectName,
      path: editDirForm.value.path,
      agentTeamsConfig: editDirForm.value.agentTeamsConfig,
    }
    if (selectedDirectory.value?.directoryType === 'PROJECT') {
      form.projectTaskPrompt = editDirForm.value.projectTaskPrompt
    }
    if (selectedDirectory.value?.directoryType === 'STANDARD') {
      form.parentProjectId = editDirForm.value.parentProjectId || ''
    }
    // Auth config
    form.defaultAuthMode = editDirForm.value.defaultAuthMode
    if (editDirForm.value.defaultAuthToken) {
      form.defaultAuthToken = editDirForm.value.defaultAuthToken
    }
    if (editDirForm.value.defaultAuthMode === 'CUSTOM_ENDPOINT') {
      form.defaultBaseUrl = editDirForm.value.defaultBaseUrl
    }
    const updated = await dirApi.updateDirectory(selectedDirectoryId.value, form)
    const idx = workerState.directories.value.findIndex(
      (d) => d.directoryId === selectedDirectoryId.value,
    )
    if (idx >= 0) workerState.directories.value[idx] = updated
    showEditDirectoryDialog.value = false
    ElMessage.success('更新成功')
  } catch {
    ElMessage.error('更新失败')
  } finally {
    saving.value = false
  }
}

async function handleDeleteDirectory() {
  if (!selectedDirectoryId.value) return
  try {
    await ElMessageBox.confirm('确认删除该工作目录？', '提示', {
      type: 'warning',
      confirmButtonText: '确认',
      cancelButtonText: '取消',
    })
    disposeAllPanes()
    await workerState.deleteDirectory(selectedDirectoryId.value)
    selectedDirectoryId.value = null
    ElMessage.success('已删除')
  } catch {
    // cancelled
  }
}

async function handleCreateWorktree() {
  if (!selectedDirectoryId.value || !worktreeForm.value.branch) {
    ElMessage.warning('请填写分支名')
    return
  }
  saving.value = true
  try {
    const newDir = await dirApi.createWorktree(selectedDirectoryId.value, worktreeForm.value.branch)
    workerState.directories.value.push(newDir)
    showWorktreeDialog.value = false
    worktreeForm.value = { branch: '' }
    ElMessage.success(`Worktree 已创建: ${newDir.projectName}`)
  } catch (e: unknown) {
    ElMessage.error('创建 Worktree 失败: ' + ((e as Error).message || '未知错误'))
  } finally {
    saving.value = false
  }
}

async function handleRemoveWorktree() {
  if (!selectedDirectoryId.value) return
  try {
    await ElMessageBox.confirm('确认清理该 Worktree？会删除磁盘上的 worktree 目录。', '提示', {
      type: 'warning',
      confirmButtonText: '确认',
      cancelButtonText: '取消',
    })
    await dirApi.removeWorktree(selectedDirectoryId.value)
    workerState.directories.value = workerState.directories.value.filter(
      (d) => d.directoryId !== selectedDirectoryId.value,
    )
    selectedDirectoryId.value = null
    disposeAllPanes()
    ElMessage.success('Worktree 已清理')
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error('清理失败')
    }
  }
}

function openFileBrowser() {
  if (!selectedDirectoryId.value) return
  const url = `${window.location.origin}/#/files?directoryId=${selectedDirectoryId.value}`
  window.open(url, '_blank', 'width=1400,height=900')
}

async function handleSyncGitInfo() {
  if (!selectedDirectoryId.value) return
  syncing.value = true
  try {
    await workerState.syncGitInfo(selectedDirectoryId.value)
    ElMessage.success('Git 信息已同步')
  } catch {
    ElMessage.error('同步失败')
  } finally {
    syncing.value = false
  }
}

async function handleSyncSessions() {
  if (!selectedWorkerId.value) return
  syncingSessions.value = true
  try {
    const result = await workerState.syncSessions(selectedWorkerId.value)
    ElMessage.success(`已同步 ${result.synced} 个新会话，共 ${result.total} 个`)
    // Refresh task lists to show newly synced sessions
    await workerState.loadTasks()
    if (selectedDirectoryId.value) {
      await loadDirectoryTasks()
    }
    // Reload configs for all tasks
    const sessionIds = [...new Set(workerState.tasks.value.map((t) => t.sessionId))]
    if (sessionIds.length > 0) {
      workerState.loadConversationConfigs(sessionIds)
    }
  } catch {
    ElMessage.error('会话同步失败')
  } finally {
    syncingSessions.value = false
  }
}

async function handleTogglePin(conv: ConversationGroup) {
  try {
    const newPinned = !(conv.config?.pinned ?? false)
    await workerState.togglePin(conv.sessionId, newPinned)
    ElMessage.success(newPinned ? '已置顶' : '已取消置顶')
  } catch {
    ElMessage.error('操作失败')
  }
}

async function handleEditTitle(conv: ConversationGroup) {
  try {
    const { value: title } = await ElMessageBox.prompt('输入自定义标题', '编辑标题', {
      confirmButtonText: '保存',
      cancelButtonText: '取消',
      inputValue: conv.config?.customTitle || '',
      inputPlaceholder: '留空则显示原始 prompt',
    })
    await workerState.setTitle(conv.sessionId, title || '')
    ElMessage.success('标题已更新')
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error('更新失败')
    }
  }
}

function handleAuthConfig(conv: ConversationGroup) {
  authDialogSessionId.value = conv.sessionId
  if (conv.config?.authBound) {
    authDialogReadonly.value = true
    authForm.value = {
      authMode: conv.config.authMode || '',
      authToken: '',
      baseUrl: conv.config.baseUrl || '',
    }
  } else {
    authDialogReadonly.value = false
    authForm.value = { authMode: 'SUBSCRIPTION', authToken: '', baseUrl: '' }
  }
  showAuthDialog.value = true
}

async function handleBindAuth() {
  const mode = authForm.value.authMode
  if (mode === 'API_KEY' && !authForm.value.authToken) {
    ElMessage.warning('请填写 API Key')
    return
  }
  if (mode === 'CUSTOM_ENDPOINT' && (!authForm.value.authToken || !authForm.value.baseUrl)) {
    ElMessage.warning('请填写 Token 和 Base URL')
    return
  }
  saving.value = true
  try {
    await workerState.bindAuth(authDialogSessionId.value, {
      authMode: mode,
      authToken: authForm.value.authToken,
      baseUrl: mode === 'CUSTOM_ENDPOINT' ? authForm.value.baseUrl : undefined,
    })
    showAuthDialog.value = false
    ElMessage.success('Auth 已绑定')
  } catch {
    ElMessage.error('绑定失败')
  } finally {
    saving.value = false
  }
}

async function handleRefreshConversations() {
  if (selectedDirectoryId.value) {
    await loadDirectoryTasks()
  } else {
    await workerState.loadTasks()
  }
}

function handleBatchAuthConfig() {
  const convs = activeConversations.value
  batchAuthSessionIds.value = convs.map((c) => c.sessionId)
  batchAuthForm.value = { authMode: 'SUBSCRIPTION', authToken: '', baseUrl: '', skipExisting: true }
  showBatchAuthDialog.value = true
}

async function handleBatchBindAuth() {
  const mode = batchAuthForm.value.authMode
  if (mode === 'API_KEY' && !batchAuthForm.value.authToken) {
    ElMessage.warning('请填写 API Key')
    return
  }
  if (mode === 'CUSTOM_ENDPOINT' && (!batchAuthForm.value.authToken || !batchAuthForm.value.baseUrl)) {
    ElMessage.warning('请填写 Token 和 Base URL')
    return
  }
  saving.value = true
  try {
    const result = await workerState.batchBindAuth({
      sessionIds: batchAuthSessionIds.value,
      authMode: mode,
      authToken: batchAuthForm.value.authToken,
      baseUrl: mode === 'CUSTOM_ENDPOINT' ? batchAuthForm.value.baseUrl : undefined,
      skipExisting: batchAuthForm.value.skipExisting,
    })
    showBatchAuthDialog.value = false
    ElMessage.success(`已绑定 ${result.bound} / ${result.total} 个会话`)
  } catch {
    ElMessage.error('批量绑定失败')
  } finally {
    saving.value = false
  }
}

function handleShowDetail(conv: ConversationGroup) {
  detailConv.value = conv
  showDetailDialog.value = true
}

async function handlePermissionRespond(paneId: string, permissionId: string, decision: string, scope: string) {
  const pane = panes.value.find((p) => p.paneId === paneId)
  if (!pane?.task.value) return

  try {
    await workerState.respondToPermission(pane.task.value.taskId, {
      permissionId,
      decision,
      denyMessage: decision === 'deny' ? 'Denied by user' : undefined,
      scope,
    })
    pane.chatState.resolvePermission(permissionId, decision === 'allow' ? 'approved' : 'denied')
    if (decision === 'allow') {
      pane.task.value.status = 'RUNNING'
    }
    triggerRef(panes)
  } catch {
    ElMessage.error('Permission response failed')
  }
}

async function handleQuestionRespond(paneId: string, permissionId: string, answers: Record<string, string>) {
  const pane = panes.value.find((p) => p.paneId === paneId)
  if (!pane?.task.value) return

  try {
    await workerState.respondToPermission(pane.task.value.taskId, {
      permissionId,
      decision: 'allow',
      answers,
    })
    pane.chatState.resolvePermission(permissionId, 'approved')
    pane.task.value.status = 'RUNNING'
    triggerRef(panes)
  } catch {
    ElMessage.error('Question response failed')
  }
}

async function handlePlanRespond(paneId: string, permissionId: string, decision: string, denyMessage?: string) {
  const pane = panes.value.find((p) => p.paneId === paneId)
  if (!pane?.task.value) return

  try {
    await workerState.respondToPermission(pane.task.value.taskId, {
      permissionId,
      decision,
      denyMessage: denyMessage || (decision === 'deny' ? 'Plan rejected by user' : undefined),
    })
    pane.chatState.resolvePermission(permissionId, decision === 'allow' ? 'approved' : 'denied')
    if (decision === 'allow') {
      pane.task.value.status = 'RUNNING'
    }
    triggerRef(panes)
  } catch {
    ElMessage.error('Plan response failed')
  }
}

function handleSlashCommand(payload: { command: string; value: string | number }) {
  if (payload.command === 'model') {
    taskForm.value.model = payload.value as string
    ElMessage.success(payload.value ? `模型已设为 ${shortModel(payload.value as string)}` : '已恢复默认模型')
  } else if (payload.command === 'turns') {
    taskForm.value.maxTurns = payload.value as number
    ElMessage.success(`最大轮次已设为 ${payload.value}`)
  }
}

async function loadDirectorySkills() {
  if (!selectedDirectoryId.value) {
    directorySkills.value = []
    return
  }
  try {
    directorySkills.value = await dirApi.listSkills(selectedDirectoryId.value)
  } catch {
    directorySkills.value = []
  }
}

async function handleCreateTask() {
  if (!selectedWorkerId.value || !taskForm.value.prompt) return
  // Strip leading "/" to prevent Claude Code CLI from interpreting it as a slash command
  let prompt = taskForm.value.prompt
  if (prompt.startsWith('/')) {
    prompt = prompt.slice(1)
  }
  if (!prompt.trim()) return
  if (panes.value.length >= MAX_PANES) {
    ElMessage.warning(`最多同时打开 ${MAX_PANES} 个面板，请先关闭一个`)
    return
  }
  try {
    const form: {
      workerId: string; prompt: string; cwd?: string; directoryId?: string
      model?: string; maxTurns?: number; agentTeamsJson?: string; images?: string
      permissionMode?: string
    } = {
      workerId: selectedWorkerId.value,
      prompt,
    }

    if (selectedDirectoryId.value) {
      form.directoryId = selectedDirectoryId.value
    } else if (taskForm.value.cwd) {
      form.cwd = taskForm.value.cwd
    }
    if (taskForm.value.model) {
      form.model = taskForm.value.model
    }
    if (taskForm.value.maxTurns != null) {
      form.maxTurns = taskForm.value.maxTurns
    }
    if (taskForm.value.useTeams && selectedDirectory.value?.agentTeamsConfig) {
      form.agentTeamsJson = selectedDirectory.value.agentTeamsConfig
    }
    if (taskForm.value.permissionMode) {
      form.permissionMode = taskForm.value.permissionMode
    }
    // Attach images as JSON string
    if (attachedImages.value.length > 0) {
      form.images = JSON.stringify(
        attachedImages.value.map((img) => ({
          name: img.name,
          data: img.base64,
          mime_type: img.mimeType,
        })),
      )
    }

    const task = await workerState.createTask(form)
    taskForm.value.prompt = ''
    clearAttachedImages()

    const pane = createPane(task)
    await pane.connect(task.sessionId)
  } catch (e: unknown) {
    ElMessage.error('创建任务失败: ' + ((e as Error).message || '未知错误'))
  }
}

function createPane(task: ClaudeTask): TaskPaneState {
  const paneId = `pane-${++paneCounter}`
  const pane = useTaskPane(paneId, { onTaskFinished: handleTaskFinished })
  pane.task.value = task
  panes.value = [...panes.value, pane]
  return pane
}

function closePane(paneId: string) {
  const idx = panes.value.findIndex((p) => p.paneId === paneId)
  if (idx >= 0) {
    panes.value[idx]!.dispose()
    panes.value = panes.value.filter((_, i) => i !== idx)
  }
}

async function abortPane(paneId: string) {
  const pane = panes.value.find((p) => p.paneId === paneId)
  if (!pane?.task.value) return
  try {
    await workerState.abortTask(pane.task.value.taskId)
    pane.task.value.status = 'ABORTED'
    triggerRef(panes)
    ElMessage.info('任务已中止')
  } catch {
    ElMessage.error('中止失败')
  }
}

/** Handle inline send from pane input (replaces dialog-based resume) */
async function handlePaneSend(paneId: string, content: string) {
  const pane = panes.value.find((p) => p.paneId === paneId)
  const oldTask = pane?.task.value
  if (!pane || !oldTask?.claudeSessionId || !selectedWorkerId.value) return

  try {
    const resumeForm: Parameters<typeof workerState.resumeTask>[0] = {
      workerId: selectedWorkerId.value,
      claudeSessionId: oldTask.claudeSessionId,
      prompt: content,
      cwd: oldTask.cwd,
      directoryId: oldTask.directoryId,
    }
    resumeForm.sessionId = oldTask.sessionId
    if (taskForm.value.model) {
      resumeForm.model = taskForm.value.model
    }
    if (taskForm.value.maxTurns != null) {
      resumeForm.maxTurns = taskForm.value.maxTurns
    }
    if (taskForm.value.permissionMode) {
      (resumeForm as Record<string, unknown>).permissionMode = taskForm.value.permissionMode
    }
    const newTask = await workerState.resumeTask(resumeForm)

    pane.resumeInPlace(newTask)

    workerState.loadTasks()
    if (selectedDirectoryId.value) {
      loadDirectoryTasks()
    }
  } catch {
    ElMessage.error('发送失败')
  }
}

function handlePageChange(page: number) {
  workerState.loadTasksPage(page - 1)
}

function handleDirPageChange(page: number) {
  dirTaskPage.value = page - 1
  loadDirectoryTasks()
}

async function viewTask(task: ClaudeTask) {
  // Per-conversation: match by sessionId (same conversation = same pane)
  const existing = panes.value.find((p) => p.task.value?.sessionId === task.sessionId)
  if (existing) return

  if (panes.value.length >= MAX_PANES) {
    ElMessage.warning(`最多同时打开 ${MAX_PANES} 个面板，请先关闭一个`)
    return
  }

  const pane = createPane(task)
  await pane.connect(task.sessionId)

  // For synced tasks: if Navigator session was empty, load JSONL history from Worker
  if (pane.chatState.messages.value.length === 0 && task.claudeSessionId && task.workerId) {
    try {
      const history = await dirApi.getWorkerSessionMessages(task.workerId, task.claudeSessionId)
      if (history.length > 0) {
        const { AipMessageType } = await import('@foggy/chat')
        const historyMsgs = history.map((m, i) => ({
          id: `synced-${task.claudeSessionId}-${i}`,
          type: AipMessageType.TEXT_COMPLETE,
          sender: (m.role === 'user' ? 'user' : 'assistant') as 'user' | 'assistant',
          content: m.content,
          timestamp: m.timestamp ? new Date(m.timestamp).getTime() : 0,
        }))
        pane.chatState.messages.value.push(...historyMsgs)
      }
    } catch {
      // JSONL history loading is best-effort
    }
  }
}

async function handleAbortTask(taskId: string) {
  try {
    await ElMessageBox.confirm('确认中止该任务？', '提示', {
      type: 'warning',
      confirmButtonText: '确认',
      cancelButtonText: '取消',
    })
    await workerState.abortTask(taskId)
    ElMessage.success('任务已中止')
    // Refresh task lists
    workerState.loadTasks()
    if (selectedDirectoryId.value) {
      loadDirectoryTasks()
    }
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error('中止失败')
    }
  }
}

function hasCheckpoints(task: ClaudeTask): boolean {
  if (!task.checkpoints) return false
  try {
    const arr = JSON.parse(task.checkpoints)
    return Array.isArray(arr) && arr.length > 0
  } catch {
    return false
  }
}

function showRewindDialog(task: ClaudeTask) {
  rewindTaskId.value = task.taskId
  rewindSelectedId.value = ''
  try {
    rewindCheckpoints.value = task.checkpoints ? JSON.parse(task.checkpoints) : []
  } catch {
    rewindCheckpoints.value = []
  }
  showRewind.value = true
}

async function handleRewind() {
  if (!rewindSelectedId.value || !rewindTaskId.value) return
  rewindLoading.value = true
  try {
    await dirApi.rewindTask(rewindTaskId.value, rewindSelectedId.value)
    ElMessage.success('文件已回退')
    showRewind.value = false
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : '回退失败'
    ElMessage.error(msg)
  } finally {
    rewindLoading.value = false
  }
}

async function handleDeleteConversation(conv: ConversationGroup) {
  try {
    await ElMessageBox.confirm(
      `确认删除该会话？包含 ${conv.tasks.length} 个任务，此操作不可恢复。`,
      '提示',
      { type: 'warning', confirmButtonText: '确认', cancelButtonText: '取消' },
    )
    // Delete all non-running tasks in the conversation
    for (const task of conv.tasks) {
      if (task.status !== 'RUNNING') {
        await workerState.deleteTask(task.taskId)
      }
    }
    ElMessage.success('会话已删除')
    workerState.loadTasks()
    if (selectedDirectoryId.value) {
      loadDirectoryTasks()
    }
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error('删除失败')
    }
  }
}

async function handleResumeFromHistory(task: ClaudeTask) {
  if (!task.claudeSessionId || !selectedWorkerId.value) return

  try {
    const { value: prompt } = await ElMessageBox.prompt('输入后续指令', '继续对话', {
      confirmButtonText: '发送',
      cancelButtonText: '取消',
      inputType: 'textarea',
      inputPlaceholder: '请输入后续任务描述...',
    })
    if (!prompt) return

    const newTask = await workerState.resumeTask({
      workerId: selectedWorkerId.value,
      claudeSessionId: task.claudeSessionId,
      prompt,
      cwd: task.cwd,
      directoryId: task.directoryId,
      sessionId: task.sessionId,  // per-conversation: reuse session
    })

    // Per-conversation: find existing pane by sessionId
    const existingPane = panes.value.find((p) => p.task.value?.sessionId === task.sessionId)
    if (existingPane) {
      existingPane.resumeInPlace(newTask)
    } else {
      if (panes.value.length >= MAX_PANES) {
        ElMessage.warning(`最多同时打开 ${MAX_PANES} 个面板，请先关闭一个`)
        return
      }
      const newPane = createPane(newTask)
      await newPane.connect(newTask.sessionId)  // load full history
    }

    workerState.loadTasks()
    if (selectedDirectoryId.value) {
      loadDirectoryTasks()
    }
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error('创建任务失败')
    }
  }
}

watch(showEditDialog, (val) => {
  if (val && selectedWorkerEntity.value) {
    editForm.value = {
      name: selectedWorkerEntity.value.name,
      baseUrl: selectedWorkerEntity.value.baseUrl,
      authToken: '',
      authMode: selectedWorkerEntity.value.authMode || 'SUBSCRIPTION',
    }
  }
})

watch(showEditDirectoryDialog, (val) => {
  if (val && selectedDirectory.value) {
    editDirForm.value = {
      projectName: selectedDirectory.value.projectName,
      path: selectedDirectory.value.path,
      agentTeamsConfig: selectedDirectory.value.agentTeamsConfig || '',
      projectTaskPrompt: selectedDirectory.value.projectTaskPrompt || '',
      parentProjectId: selectedDirectory.value.parentProjectId || '',
      defaultAuthMode: selectedDirectory.value.defaultAuthMode || '',
      defaultAuthToken: '',
      defaultBaseUrl: selectedDirectory.value.defaultBaseUrl || '',
    }
  }
})

function statusTagType(status: string) {
  if (status === 'ONLINE') return 'success'
  if (status === 'OFFLINE') return 'danger'
  return 'info'
}

function truncate(text: string, maxLen: number) {
  return text.length > maxLen ? text.substring(0, maxLen) + '...' : text
}

function authModeLabel(mode: string): string {
  if (mode === 'API_KEY') return 'API Key'
  if (mode === 'CUSTOM_ENDPOINT') return 'Custom'
  if (mode === 'SUBSCRIPTION') return 'Subscription'
  return mode
}

function shortModel(model: string): string {
  const match = model.match(/(sonnet|opus|haiku)[\w-]*/i)
  return match ? match[0] : model.split('-').slice(1, 3).join('-')
}

function formatTime(dateStr: string): string {
  const d = new Date(dateStr)
  const now = new Date()
  if (d.toDateString() === now.toDateString()) {
    return d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  }
  return d.toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' })
}
</script>

<style scoped>
.worker-layout {
  display: flex;
  height: 100vh;
}

.worker-sidebar {
  width: 260px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  background: #f7f8fa;
  border-right: 1px solid #e4e7ed;
}

.sidebar-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  border-bottom: 1px solid #e4e7ed;
}

.sidebar-header h3 {
  margin: 0;
  font-size: 16px;
  color: #303133;
}

.worker-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}

.worker-item {
  padding: 10px 12px;
  margin-bottom: 2px;
  border-radius: 6px;
  cursor: pointer;
  transition: background-color 0.2s;
}

.worker-item:hover {
  background: #ebeef5;
}

.worker-item.active {
  background: #e1eaff;
}

.worker-info {
  display: flex;
  align-items: center;
  gap: 6px;
}

.expand-icon {
  font-size: 10px;
  width: 14px;
  color: #909399;
  cursor: pointer;
  user-select: none;
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}

.status-dot.online {
  background: #67c23a;
}

.status-dot.offline {
  background: #c0c4cc;
}

.status-dot.unknown {
  background: #e6a23c;
}

.worker-name {
  font-size: 14px;
  color: #303133;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.worker-meta {
  font-size: 12px;
  color: #909399;
  margin-top: 2px;
  margin-left: 28px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* Directory items (Level 2) */
.directory-item {
  padding: 8px 12px 8px 40px;
  margin-bottom: 2px;
  border-radius: 6px;
  cursor: pointer;
  transition: background-color 0.2s;
}

.directory-item:hover {
  background: #ebeef5;
}

.directory-item.active {
  background: #d4e5ff;
}

.dir-info {
  display: flex;
  align-items: center;
  gap: 6px;
}

.dir-name {
  font-size: 13px;
  color: #303133;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.dirty-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #e6a23c;
  flex-shrink: 0;
}

.dir-branch {
  font-size: 11px;
  color: #909399;
  margin-top: 2px;
  margin-left: 0;
}

.branch-icon {
  font-size: 12px;
}

.project-dir .dir-info {
  gap: 4px;
}

.project-icon {
  font-size: 14px;
  flex-shrink: 0;
}

.child-count {
  font-size: 11px;
  color: #909399;
  background: #f0f2f5;
  padding: 0 5px;
  border-radius: 8px;
  flex-shrink: 0;
}

.child-dir {
  padding-left: 56px !important;
}

.worktree-tag {
  margin-left: 4px;
  transform: scale(0.85);
}

.empty-dir-hint {
  padding: 8px 12px 8px 40px;
  font-size: 12px;
  color: #c0c4cc;
}

.sidebar-footer {
  padding: 10px 12px;
  border-top: 1px solid #e4e7ed;
}

/* Main content area (middle column) */
.worker-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  overflow-y: auto;
  padding: 20px 24px;
}

/* When panes are open: no scroll, pane grid fills remaining space */
.worker-main.has-panes {
  overflow: hidden;
}

/* History panel (right column) */
.worker-history {
  width: 320px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  background: #fafafa;
  border-left: 1px solid #e4e7ed;
}

.history-header {
  padding: 12px 16px;
  border-bottom: 1px solid #e4e7ed;
  background: #fff;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.history-header h3 {
  margin: 0;
  font-size: 16px;
  color: #303133;
}

.history-header-actions {
  display: flex;
  align-items: center;
  gap: 2px;
}

.history-content {
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;
  padding: 12px;
}

.worker-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
  flex-shrink: 0;
}

.header-info {
  display: flex;
  align-items: center;
  gap: 10px;
}

.header-info h2 {
  margin: 0;
  font-size: 20px;
}

.version-tag {
  font-size: 12px;
  color: #909399;
}

.remote-url {
  font-size: 12px;
  color: #909399;
  max-width: 300px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.header-actions {
  display: flex;
  gap: 8px;
}

.task-form {
  background: #fafafa;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 16px;
  margin-bottom: 12px;
  flex-shrink: 0;
}

.active-overrides {
  display: flex;
  gap: 6px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}


/* Compact directory header */
.dir-compact-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
  flex-shrink: 0;
}

.dir-compact-info {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.dir-compact-info h2 {
  margin: 0;
  font-size: 18px;
  white-space: nowrap;
}

.dir-path-inline {
  font-size: 12px;
  color: #909399;
  font-family: 'Cascadia Code', 'Fira Code', monospace;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 260px;
}

.agent-teams-bar {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 8px;
  flex-shrink: 0;
}

.agent-teams-label {
  font-size: 12px;
  color: #909399;
}

.form-tip {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
  line-height: 1.4;
}

/* Mini task form (when panes are open) */
.new-task-mini {
  flex-shrink: 0;
  margin-bottom: 8px;
}

/* Empty pane hint */
.pane-empty-hint {
  flex: 1;
  display: flex;
  justify-content: center;
  align-items: center;
  color: #c0c4cc;
  font-size: 14px;
}

/* Conversation list (compact history panel) */
.conv-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.conv-item {
  padding: 8px 10px;
  border-radius: 6px;
  cursor: pointer;
  transition: background-color 0.15s;
  overflow: hidden;
}

.conv-item:hover {
  background: #ebeef5;
}

.conv-item:hover .conv-hover-actions {
  opacity: 1;
}

.conv-row-1 {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.conv-prompt {
  font-size: 13px;
  color: #303133;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  flex: 1;
  min-width: 0;
}

.conv-status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}

.conv-status-dot.completed { background: #67c23a; }
.conv-status-dot.running { background: #409eff; animation: convPulse 1.5s infinite; }
.conv-status-dot.failed { background: #f56c6c; }
.conv-status-dot.aborted { background: #e6a23c; }

@keyframes convPulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.3; }
}

.conv-row-2 {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 2px;
  font-size: 12px;
  color: #909399;
  overflow: hidden;
  min-width: 0;
}

.conv-rounds {
  color: #409eff;
  font-weight: 500;
  white-space: nowrap;
  flex-shrink: 0;
}

.conv-model {
  font-size: 11px;
  color: #909399;
  background: #f0f2f5;
  padding: 0 4px;
  border-radius: 3px;
  font-family: 'Cascadia Code', 'Fira Code', monospace;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.conv-cost {
  color: #67c23a;
  white-space: nowrap;
  flex-shrink: 0;
}

.conv-time {
  flex-shrink: 0;
}

.conv-hover-actions {
  margin-left: auto;
  opacity: 0;
  transition: opacity 0.15s;
  display: flex;
  gap: 0;
  flex-shrink: 0;
}

.conv-pinned {
  background: #fdf6ec;
  border-left: 2px solid #e6a23c;
}

.conv-pin-icon {
  cursor: pointer;
  font-size: 12px;
  flex-shrink: 0;
  opacity: 0.5;
}

.conv-pin-icon.active {
  opacity: 1;
}

.conv-auth-badge {
  font-size: 11px;
  cursor: default;
  flex-shrink: 0;
}

.delete-btn {
  color: #c0c4cc !important;
}

.delete-btn:hover {
  color: #f56c6c !important;
}

.task-pagination {
  margin-top: 12px;
  justify-content: center;
}

.empty-hint {
  text-align: center;
  color: #909399;
  font-size: 13px;
  padding: 24px 0;
}

.empty-state {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  color: #909399;
}

.empty-state h2 {
  font-size: 24px;
  color: #606266;
  margin-bottom: 8px;
}

/* Image preview strip */
.image-preview-strip {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
  padding: 8px;
  background: #f5f7fa;
  border-radius: 6px;
  border: 1px dashed #dcdfe6;
}

.image-preview-strip.compact {
  margin-bottom: 6px;
  padding: 4px 6px;
  gap: 4px;
}

.image-preview-item {
  position: relative;
  width: 80px;
  height: 80px;
  border-radius: 6px;
  overflow: hidden;
  border: 1px solid #e4e7ed;
  flex-shrink: 0;
}

.image-preview-item.small {
  width: 48px;
  height: 48px;
  border-radius: 4px;
}

.image-preview-item img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.image-remove {
  position: absolute;
  top: 2px;
  right: 2px;
  width: 18px;
  height: 18px;
  background: rgba(0, 0, 0, 0.5);
  color: #fff;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  cursor: pointer;
  opacity: 0;
  transition: opacity 0.15s;
}

.image-preview-item:hover .image-remove {
  opacity: 1;
}
</style>
