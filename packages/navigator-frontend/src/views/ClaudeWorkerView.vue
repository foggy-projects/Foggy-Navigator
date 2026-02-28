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
                <template v-for="child in childrenForProject(dir.directoryId)" :key="child.directoryId">
                  <div
                    :class="[
                      'directory-item child-dir',
                      { active: selectedDirectoryId === child.directoryId },
                    ]"
                    @click="selectDirectory(worker.workerId, child.directoryId)"
                  >
                    <div class="dir-info">
                      <span class="dir-name">{{ child.projectName }}</span>
                      <span v-if="child.gitStatus === 'dirty'" class="dirty-dot" title="Uncommitted changes" />
                    </div>
                    <div v-if="child.gitBranch" class="dir-branch">
                      <span class="branch-icon">⎇</span> {{ child.gitBranch }}
                    </div>
                  </div>
                  <!-- Level 4: worktrees nested under source directory -->
                  <div
                    v-for="wt in worktreesForDirectory(child.directoryId)"
                    :key="wt.directoryId"
                    :class="[
                      'directory-item worktree-dir',
                      { active: selectedDirectoryId === wt.directoryId },
                    ]"
                    @click="selectDirectory(worker.workerId, wt.directoryId)"
                  >
                    <div class="dir-info">
                      <span class="worktree-indent">└─</span>
                      <span class="dir-name">{{ wt.projectName }}</span>
                      <el-tag size="small" type="info" effect="plain" class="worktree-tag">worktree</el-tag>
                      <span v-if="wt.gitStatus === 'dirty'" class="dirty-dot" title="Uncommitted changes" />
                    </div>
                    <div v-if="wt.gitBranch" class="dir-branch worktree-branch">
                      <span class="branch-icon">⎇</span> {{ wt.gitBranch }}
                    </div>
                  </div>
                </template>
              </template>
            </template>
            <!-- Orphan STANDARD directories (no parentProjectId, no sourceDirectoryId) -->
            <template v-for="dir in orphanDirectoriesForWorker(worker.workerId)" :key="dir.directoryId">
              <div
                :class="[
                  'directory-item',
                  { active: selectedDirectoryId === dir.directoryId },
                ]"
                @click="selectDirectory(worker.workerId, dir.directoryId)"
              >
                <div class="dir-info">
                  <span class="dir-name">{{ dir.projectName }}</span>
                  <span v-if="dir.gitStatus === 'dirty'" class="dirty-dot" title="Uncommitted changes" />
                </div>
                <div v-if="dir.gitBranch" class="dir-branch">
                  <span class="branch-icon">⎇</span> {{ dir.gitBranch }}
                </div>
              </div>
              <!-- Worktrees nested under orphan directory -->
              <div
                v-for="wt in worktreesForDirectory(dir.directoryId)"
                :key="wt.directoryId"
                :class="[
                  'directory-item worktree-dir orphan-worktree',
                  { active: selectedDirectoryId === wt.directoryId },
                ]"
                @click="selectDirectory(worker.workerId, wt.directoryId)"
              >
                <div class="dir-info">
                  <span class="worktree-indent">└─</span>
                  <span class="dir-name">{{ wt.projectName }}</span>
                  <el-tag size="small" type="info" effect="plain" class="worktree-tag">worktree</el-tag>
                  <span v-if="wt.gitStatus === 'dirty'" class="dirty-dot" title="Uncommitted changes" />
                </div>
                <div v-if="wt.gitBranch" class="dir-branch worktree-branch">
                  <span class="branch-icon">⎇</span> {{ wt.gitBranch }}
                </div>
              </div>
            </template>
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
      <input ref="fileInputRef" type="file" multiple accept="image/*" class="sr-only" @change="handleFileSelect">
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
            <el-button size="small" @click="handleToggleTerminal">终端</el-button>
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
        <div v-if="favoriteScripts.length > 0" class="fav-scripts-bar">
          <span class="fav-scripts-label">常用脚本</span>
          <el-tag
            v-for="s in favoriteScripts"
            :key="s.path"
            size="small"
            :type="s.pinned ? '' : 'info'"
            class="fav-script-tag"
            @click="handleRunFavScript(s)"
            @contextmenu.prevent="handleFavScriptCtx($event, s)"
          >
            <span v-if="s.pinned" class="pin-icon">&#128204;</span>{{ s.name }}
          </el-tag>
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
                auto-grow
                placeholder="输入任务描述... (可粘贴截图或拖拽图片)"
                :skills="directorySkills"
                @submit="handleCreateTask"
                @command="handleSlashCommand"
                @history-prev="handleTaskHistoryPrev"
                @history-next="handleTaskHistoryNext"
              />
            </el-form-item>
            <!-- Image preview strip -->
            <div v-if="attachedImages.length > 0" class="image-preview-strip">
              <div v-for="(img, idx) in attachedImages" :key="idx" class="image-preview-item">
                <img :src="img.previewUrl" :alt="img.name" :title="img.name" />
                <span class="image-remove" @click="removeImage(idx)">&times;</span>
              </div>
            </div>
            <div v-if="taskForm.model || taskForm.maxTurns || platformModelConfig" class="active-overrides">
              <el-tag v-if="platformModelConfig" size="small" type="success">
                API: {{ platformModelConfig.name }}
              </el-tag>
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
              <el-select
                v-if="platformModels.length > 0"
                v-model="platformModelConfigId"
                size="small"
                style="width: 180px; margin-left: 8px"
                placeholder="API 凭证"
                clearable
              >
                <el-option
                  v-for="m in platformModels"
                  :key="m.id"
                  :value="m.id"
                  :label="m.name"
                />
              </el-select>
            </el-form-item>
          </el-form>
        </div>
        <div v-else class="new-task-mini">
          <div class="mini-input-row">
            <SlashCommandInput
              v-model="taskForm.prompt"
              :rows="1"
              auto-grow
              :max-rows="4"
              size="small"
              placeholder="新建任务... (Shift+Enter 换行，可粘贴截图)"
              :skills="directorySkills"
              @submit="handleCreateTask"
              @command="handleSlashCommand"
              @history-prev="handleTaskHistoryPrev"
              @history-next="handleTaskHistoryNext"
            />
            <el-button text size="small" @click="fileInputRef?.click()" title="附加图片">&#128206;</el-button>
            <el-button
              size="small"
              :disabled="!taskForm.prompt || selectedWorkerEntity?.status !== 'ONLINE' || panes.length >= MAX_PANES"
              @click="handleCreateTask"
            >
              运行
            </el-button>
          </div>
          <div v-if="attachedImages.length > 0" class="image-preview-strip compact">
            <div v-for="(img, idx) in attachedImages" :key="idx" class="image-preview-item small">
              <img :src="img.previewUrl" :alt="img.name" :title="img.name" />
              <span class="image-remove" @click="removeImage(idx)">&times;</span>
            </div>
          </div>
          <div v-if="taskForm.model || taskForm.maxTurns || platformModelConfig" class="active-overrides">
            <el-tag v-if="platformModelConfig" size="small" type="success">
              API: {{ platformModelConfig.name }}
            </el-tag>
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
          @rewind="handlePaneRewind"
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

        <!-- Worker Tabs: Agents & CLI Processes -->
        <el-tabs v-model="workerActiveTab" class="worker-tabs" @tab-change="handleWorkerTabChange">
          <el-tab-pane name="agents">
            <template #label>
              <span>Coding Agents</span>
              <el-tag v-if="agentsForWorker.length > 0" size="small" type="info" style="margin-left: 6px;">{{ agentsForWorker.length }}</el-tag>
            </template>
            <div class="tab-pane-toolbar">
              <el-button size="small" @click="openAgentRegisterDialog">+ 注册 Agent</el-button>
            </div>
            <div v-if="agentsForWorker.length === 0" class="agent-empty">
              暂无 Agent，点击上方注册
            </div>
            <div v-for="agent in agentsForWorker" :key="agent.agentId" class="agent-card">
              <div class="agent-card-info">
                <div class="agent-card-name">{{ agent.name }}</div>
                <div v-if="agent.description" class="agent-card-desc">{{ agent.description }}</div>
                <div v-if="agent.projectSummary" class="agent-card-summary">{{ agent.projectSummary }}</div>
                <div class="agent-card-meta">
                  <span v-if="agent.defaultDirectoryId" class="agent-dir-tag">
                    &#128193; {{ dirNameById(agent.defaultDirectoryId) }}
                    <span v-if="dirBranchById(agent.defaultDirectoryId)" class="agent-branch">({{ dirBranchById(agent.defaultDirectoryId) }})</span>
                  </span>
                  <span v-if="agent.authorizedDirectories" class="agent-dir-count">
                    {{ agent.authorizedDirectories.length }} 个授权目录
                  </span>
                </div>
              </div>
              <div class="agent-card-actions">
                <el-button size="small" text @click="openAgentEditDialog(agent)">编辑</el-button>
                <el-button size="small" text @click="openAgentBindingDialog(agent)">目录</el-button>
                <el-button size="small" text type="danger" @click="handleDeleteAgent(agent)">删除</el-button>
              </div>
            </div>
          </el-tab-pane>

          <el-tab-pane name="processes">
            <template #label>
              <span>CLI 进程</span>
              <el-tag v-if="cliProcesses.length > 0" size="small" :type="cliProcesses.some(p => p.isOrphan) ? 'danger' : 'info'" style="margin-left: 6px;">{{ cliProcesses.length }}</el-tag>
            </template>
            <div class="tab-pane-toolbar">
              <el-button size="small" :loading="loadingProcesses" @click="loadCliProcesses">刷新</el-button>
            </div>
            <el-table
              v-loading="loadingProcesses"
              :data="cliProcesses"
              size="small"
              stripe
              empty-text="未检测到 Claude CLI 进程"
              style="width: 100%"
            >
              <el-table-column prop="pid" label="PID" width="80" />
              <el-table-column prop="command" label="命令" min-width="200" show-overflow-tooltip />
              <el-table-column label="内存" width="80">
                <template #default="{ row }">{{ row.memoryMb ? row.memoryMb.toFixed(1) + ' MB' : '-' }}</template>
              </el-table-column>
              <el-table-column label="启动时间" width="150">
                <template #default="{ row }">{{ row.startedAt || '-' }}</template>
              </el-table-column>
              <el-table-column label="状态" width="80" align="center">
                <template #default="{ row }">
                  <el-tag :type="row.isOrphan ? 'danger' : 'success'" size="small">{{ row.isOrphan ? '孤儿' : '活跃' }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="140" align="center">
                <template #default="{ row }">
                  <el-button text size="small" @click="handleKillProcess(row.pid, false)">终止</el-button>
                  <el-button text size="small" type="danger" @click="handleKillProcess(row.pid, true)">强制</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>
        </el-tabs>

        <!-- Task Form: collapse when panes are open -->
        <div v-if="panes.length === 0" class="task-form">
          <el-form :model="taskForm" label-position="top" size="default">
            <el-form-item label="Prompt">
              <SlashCommandInput
                v-model="taskForm.prompt"
                :rows="3"
                auto-grow
                placeholder="输入任务描述... (可粘贴截图或拖拽图片)"
                :skills="directorySkills"
                @submit="handleCreateTask"
                @command="handleSlashCommand"
                @history-prev="handleTaskHistoryPrev"
                @history-next="handleTaskHistoryNext"
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
            <div v-if="taskForm.model || taskForm.maxTurns || platformModelConfig" class="active-overrides">
              <el-tag v-if="platformModelConfig" size="small" type="success">
                API: {{ platformModelConfig.name }}
              </el-tag>
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
              <el-select
                v-if="platformModels.length > 0"
                v-model="platformModelConfigId"
                size="small"
                style="width: 180px; margin-left: 8px"
                placeholder="API 凭证"
                clearable
              >
                <el-option
                  v-for="m in platformModels"
                  :key="m.id"
                  :value="m.id"
                  :label="m.name"
                />
              </el-select>
            </el-form-item>
          </el-form>
        </div>
        <div v-else class="new-task-mini">
          <div class="mini-input-row">
            <SlashCommandInput
              v-model="taskForm.prompt"
              :rows="1"
              auto-grow
              :max-rows="4"
              size="small"
              placeholder="新建任务... (Shift+Enter 换行，可粘贴截图)"
              :skills="directorySkills"
              @submit="handleCreateTask"
              @command="handleSlashCommand"
              @history-prev="handleTaskHistoryPrev"
              @history-next="handleTaskHistoryNext"
            />
            <el-button text size="small" @click="fileInputRef?.click()" title="附加图片">&#128206;</el-button>
            <el-button
              size="small"
              :disabled="!taskForm.prompt || selectedWorkerEntity.status !== 'ONLINE' || panes.length >= MAX_PANES"
              @click="handleCreateTask"
            >
              运行
            </el-button>
          </div>
          <div v-if="attachedImages.length > 0" class="image-preview-strip compact">
            <div v-for="(img, idx) in attachedImages" :key="idx" class="image-preview-item small">
              <img :src="img.previewUrl" :alt="img.name" :title="img.name" />
              <span class="image-remove" @click="removeImage(idx)">&times;</span>
            </div>
          </div>
          <div v-if="taskForm.model || taskForm.maxTurns || platformModelConfig" class="active-overrides">
            <el-tag v-if="platformModelConfig" size="small" type="success">
              API: {{ platformModelConfig.name }}
            </el-tag>
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
          @rewind="handlePaneRewind"
        />
      </template>

      <template v-else>
        <div class="empty-state">
          <h2>Claude Workers</h2>
          <p>选择一个 Worker 或添加新的 Worker 开始使用</p>
        </div>
      </template>

      <!-- SSH Terminal Panel -->
      <SshTerminalPanel
        v-if="activeWorkspace"
        :visible="activeWorkspace.terminalVisible.value"
        :maximized="activeWorkspace.terminalMaximized.value"
        :minimized="activeWorkspace.terminalMinimized.value"
        :height="activeWorkspace.terminalHeight.value"
        :tabs="activeWorkspace.terminalTabs.value"
        :active-tab-id="activeWorkspace.activeTermTabId.value"
        @add-tab="handleAddTerminalTab"
        @close-tab="handleCloseTerminalTab"
        @activate-tab="(id) => activeWorkspace!.activeTermTabId.value = id"
        @toggle-maximize="activeWorkspace.terminalMaximized.value = !activeWorkspace.terminalMaximized.value"
        @toggle-minimize="handleToggleMinimize"
        @close-panel="activeWorkspace.terminalVisible.value = false"
        @resize="(h) => activeWorkspace!.terminalHeight.value = h"
        @pop-out="handlePopOutTerminal"
        @sync="syncSshSessions(true)"
      >
        <SshTerminal
          v-for="tab in activeWorkspace.terminalTabs.value"
          :key="tab.tabId"
          :tab="tab"
          :active="tab.tabId === activeWorkspace.activeTermTabId.value"
          :worker-id="selectedWorkerId!"
        />
      </SshTerminalPanel>
    </main>

    <!-- Right Panel: Task History -->
    <aside v-if="selectedWorkerId || workerState.activeTasks.value.length > 0" class="worker-history">
      <div class="history-header">
        <h3>历史会话</h3>
        <div class="history-header-actions">
          <template v-if="batchSelectMode">
            <el-button size="small" text @click="handleBatchSelectAll">
              {{ selectedConvIds.size === activeConversations.length ? '取消全选' : '全选' }}
            </el-button>
            <el-button
              size="small"
              type="danger"
              text
              :disabled="selectedConvIds.size === 0"
              @click="handleBatchDelete"
            >
              删除({{ selectedConvIds.size }})
            </el-button>
            <el-button
              size="small"
              text
              :disabled="selectedConvIds.size === 0"
              @click="handleBatchAuthConfig"
            >
              &#128273; Auth
            </el-button>
            <el-button size="small" text @click="exitBatchSelectMode">取消</el-button>
          </template>
          <template v-else>
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
              title="批量选择"
              @click="batchSelectMode = true"
            >
              选择
            </el-button>
          </template>
        </div>
      </div>
      <div class="history-content">
        <!-- Active sessions section (global: RUNNING + AWAITING_PERMISSION) -->
        <div v-if="activeSessionConvs.length > 0" class="active-sessions-section">
          <div class="active-header">
            <span class="active-title">&#9679; 进行中 ({{ activeSessionConvs.length }})</span>
            <el-button size="small" text @click="workerState.loadActiveTasks()">&#8635;</el-button>
          </div>
          <div
            v-for="ac in activeSessionConvs"
            :key="ac.sessionId"
            class="active-conv-item"
            @click="navigateToActiveSession(ac)"
          >
            <div class="conv-row-1">
              <el-tag v-if="ac.latestTask.directoryName" size="small" type="info" effect="plain" class="active-dir-tag">{{ ac.latestTask.directoryName }}</el-tag>
              <span :class="['conv-status-dot', ac.latestTask.status.toLowerCase()]" :title="ac.latestTask.status" />
              <span class="active-status-label">{{ ac.latestTask.status === 'RUNNING' ? '运行中' : '等待授权' }}</span>
            </div>
            <div class="conv-row-1" style="margin-top: 2px;">
              <span class="conv-prompt" :title="ac.config?.customTitle || ac.firstPrompt">{{ truncate(ac.config?.customTitle || ac.firstPrompt, 30) }}</span>
            </div>
            <div class="conv-row-2">
              <el-tag v-for="tag in (ac.config?.tags || [])" :key="tag" :type="tagColor(tag)" size="small" class="conv-tag">{{ tag }}</el-tag>
              <span v-if="ac.totalCost > 0" class="conv-cost">${{ ac.totalCost.toFixed(2) }}</span>
              <span v-if="ac.latestTask.durationMs" class="conv-time">{{ Math.round((ac.latestTask.durationMs || 0) / 60000) }}min</span>
              <span class="conv-time">{{ formatTime(ac.latestTask.createdAt) }}</span>
            </div>
          </div>
          <div class="active-divider">
            <span class="active-divider-line" />
            <span class="active-divider-text">历史会话</span>
            <span class="active-divider-line" />
          </div>
        </div>
        <!-- Conversation list (shared template for directory / worker-level) -->
        <div
          v-if="activeConversations.length > 0"
          class="conv-list"
        >
          <div
            v-for="conv in activeConversations"
            :key="conv.sessionId"
            :class="['conv-item', { 'conv-pinned': conv.config?.pinned, 'conv-selected': batchSelectMode && selectedConvIds.has(conv.sessionId) }]"
            @click="batchSelectMode ? toggleConvSelection(conv.sessionId) : viewTask(conv.latestTask)"
          >
            <div class="conv-row-1">
              <el-checkbox
                v-if="batchSelectMode"
                :model-value="selectedConvIds.has(conv.sessionId)"
                @click.stop
                @change="toggleConvSelection(conv.sessionId)"
                class="conv-checkbox"
              />
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
              <el-tag v-for="tag in (conv.config?.tags || [])" :key="tag" :type="tagColor(tag)" size="small" class="conv-tag">{{ tag }}</el-tag>
              <span v-if="conv.tasks.length > 1" class="conv-rounds">{{ conv.tasks.length }}轮</span>
              <span v-if="conv.latestTask.model" class="conv-model">{{ shortModel(conv.latestTask.model) }}</span>
              <span v-if="conv.totalCost > 0" class="conv-cost">${{ conv.totalCost.toFixed(2) }}</span>
              <span v-if="conv.config?.authBound" class="conv-auth-badge" :title="'Auth: ' + (conv.config.authMode || 'bound')">&#128273;</span>
              <span class="conv-time">{{ formatTime(conv.latestTask.createdAt) }}</span>
              <!-- Visible action buttons -->
              <span class="conv-actions" @click.stop>
                <el-button
                  v-if="conv.latestTask.status !== 'RUNNING' && conv.claudeSessionId"
                  type="primary"
                  size="small"
                  text
                  title="继续对话"
                  @click="handleResumeFromHistory(conv.latestTask)"
                >
                  继续
                </el-button>
                <el-button
                  v-if="conv.latestTask.status === 'RUNNING'"
                  type="warning"
                  size="small"
                  text
                  title="中止任务"
                  @click="handleAbortTask(conv.latestTask.taskId)"
                >
                  中止
                </el-button>
                <el-dropdown trigger="click" @click.stop>
                  <span class="conv-more-trigger" @click.stop>&#8943;</span>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item @click="handleTogglePin(conv)">
                        {{ conv.config?.pinned ? '取消置顶' : '置顶' }}
                      </el-dropdown-item>
                      <el-dropdown-item @click="handleEditTitle(conv)">
                        编辑标题
                      </el-dropdown-item>
                      <el-dropdown-item @click="handleAuthConfig(conv)">
                        Auth 配置
                      </el-dropdown-item>
                      <el-dropdown-item @click="handleEditTags(conv)">
                        标签
                      </el-dropdown-item>
                      <el-dropdown-item @click="handleShowDetail(conv)">
                        详情
                      </el-dropdown-item>
                      <el-dropdown-item
                        v-if="conv.latestTask.status !== 'RUNNING' && hasCheckpoints(conv.latestTask)"
                        @click="showRewindDialog(conv.latestTask)"
                      >
                        回退
                      </el-dropdown-item>
                      <el-dropdown-item
                        v-if="canScanCheckpoints(conv.latestTask)"
                        :disabled="scanningTaskId === conv.latestTask.taskId"
                        @click="handleScanCheckpoints(conv.latestTask)"
                      >
                        {{ scanningTaskId === conv.latestTask.taskId ? '扫描中...' : '扫描' }}
                      </el-dropdown-item>
                      <el-dropdown-item
                        v-if="conv.latestTask.status !== 'RUNNING'"
                        divided
                        class="delete-dropdown-item"
                        @click="handleDeleteConversation(conv)"
                      >
                        删除
                      </el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
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
        <el-divider content-position="left">SSH 终端（可选）</el-divider>
        <el-form-item label="SSH 用户名">
          <el-input v-model="addForm.sshUsername" placeholder="如 root" />
        </el-form-item>
        <el-form-item label="SSH 端口">
          <el-input-number v-model="addForm.sshPort" :min="1" :max="65535" style="width: 100%" />
        </el-form-item>
        <el-form-item label="SSH 密码">
          <el-input v-model="addForm.sshPassword" type="password" show-password placeholder="SSH 登录密码" />
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
        <el-divider content-position="left">SSH 终端（可选）</el-divider>
        <el-form-item label="SSH 用户名">
          <el-input v-model="editForm.sshUsername" placeholder="如 root" />
        </el-form-item>
        <el-form-item label="SSH 端口">
          <el-input-number v-model="editForm.sshPort" :min="1" :max="65535" style="width: 100%" />
        </el-form-item>
        <el-form-item label="SSH 密码">
          <el-input
            v-model="editForm.sshPassword"
            type="password"
            show-password
            :placeholder="selectedWorkerEntity?.sshPasswordConfigured ? '已保存，留空不改' : 'SSH 登录密码'"
          />
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
        <el-form-item v-if="batchAuthForm.authMode === 'API_KEY'" label="API Key">
          <el-input v-model="batchAuthForm.authToken" type="password" show-password placeholder="sk-ant-xxx" />
          <div v-if="batchAuthForm.modelConfigId" class="form-tip">留空将使用所选平台模型配置</div>
        </el-form-item>
        <el-form-item v-if="batchAuthForm.authMode === 'CUSTOM_ENDPOINT'" label="Auth Token">
          <el-input v-model="batchAuthForm.authToken" type="password" show-password placeholder="自定义端点的认证 token" />
          <div v-if="batchAuthForm.modelConfigId" class="form-tip">留空将使用所选平台模型配置</div>
        </el-form-item>
        <el-form-item v-if="batchAuthForm.authMode === 'CUSTOM_ENDPOINT'" label="Base URL">
          <el-input v-model="batchAuthForm.baseUrl" placeholder="https://aiproxy.example.com/api/v1/anthropic" />
          <div v-if="batchAuthForm.modelConfigId" class="form-tip">留空将使用所选平台模型配置</div>
        </el-form-item>
        <el-form-item v-if="platformModels.length > 0" label="平台模型配置">
          <el-select v-model="batchAuthForm.modelConfigId" clearable placeholder="不覆盖模型配置" style="width: 100%">
            <el-option
              v-for="m in platformModels"
              :key="m.id"
              :label="m.name"
              :value="m.id"
            />
          </el-select>
          <div class="form-tip" style="margin-top: 4px">选择后将作为后续任务的默认 API 配置</div>
        </el-form-item>
        <el-form-item>
          <el-checkbox v-model="batchAuthForm.skipExisting">跳过已有 Auth 配置的会话</el-checkbox>
        </el-form-item>
        <div class="form-tip">
          将为{{ selectedConvIds.size > 0 ? '选中的' : '当前 ' + (selectedDirectoryId ? '目录' : 'Worker') + ' 下的' }} {{ batchAuthSessionIds.length }} 个会话批量配置 Auth
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

    <!-- Rewind Dialog (dual-mode) -->
    <el-dialog v-model="showRewind" title="回退" width="520px">
      <p style="margin-bottom: 12px">选择回退到的 Checkpoint：</p>
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
      <div style="margin-top: 16px">
        <p style="margin-bottom: 8px; font-weight: 500">回退模式：</p>
        <el-radio-group v-model="rewindMode">
          <el-radio value="conversation_fork">
            仅回退会话
            <span style="color: #909399; font-size: 12px; margin-left: 4px">(从该 turn 继续新对话)</span>
          </el-radio>
          <el-radio value="file_rewind" :disabled="!rewindFileEnabled">
            回退会话 + 文件
            <span v-if="!rewindFileEnabled" style="color: #F56C6C; font-size: 12px; margin-left: 4px">
              (该会话未启用文件快照)
            </span>
            <span v-else style="color: #909399; font-size: 12px; margin-left: 4px">(文件还原到该时间点)</span>
          </el-radio>
        </el-radio-group>
      </div>
      <template #footer>
        <el-button @click="showRewind = false">取消</el-button>
        <el-button type="warning" :disabled="!rewindSelectedId" :loading="rewindLoading" @click="handleRewind">
          确认回退
        </el-button>
      </template>
    </el-dialog>

    <!-- In-pane Rewind Dialog (from message-level "回退到此") -->
    <el-dialog v-model="paneRewindVisible" title="回退到此" width="440px">
      <p style="margin-bottom: 8px">
        回退到第 <strong>{{ paneRewindTurnIndex }}</strong> 轮用户消息
        <span v-if="paneRewindCheckpoint" style="color: #909399; font-size: 12px; margin-left: 8px">
          ({{ paneRewindCheckpoint.timestamp }})
        </span>
      </p>
      <div style="margin-top: 12px">
        <p style="margin-bottom: 8px; font-weight: 500">回退模式：</p>
        <el-radio-group v-model="paneRewindMode">
          <el-radio value="conversation_fork">
            仅回退会话
            <span style="color: #909399; font-size: 12px; margin-left: 4px">(从该轮继续新对话)</span>
          </el-radio>
          <el-radio value="file_rewind" :disabled="!paneRewindCheckpoint || !paneRewindFileEnabled">
            回退会话 + 文件
            <template v-if="!paneRewindCheckpoint">
              <span style="color: #F56C6C; font-size: 12px; margin-left: 4px">(该轮次无 Checkpoint)</span>
            </template>
            <template v-else-if="!paneRewindFileEnabled">
              <span style="color: #F56C6C; font-size: 12px; margin-left: 4px">(未启用文件快照)</span>
            </template>
            <template v-else>
              <span style="color: #909399; font-size: 12px; margin-left: 4px">(文件还原到该时间点)</span>
            </template>
          </el-radio>
        </el-radio-group>
      </div>
      <template #footer>
        <el-button @click="paneRewindVisible = false">取消</el-button>
        <el-button type="warning" :loading="paneRewindLoading" @click="executePaneRewind">
          确认回退
        </el-button>
      </template>
    </el-dialog>

    <!-- SSH Connect Dialog -->
    <el-dialog v-model="showSshDialog" title="SSH 连接" width="420px">
      <el-form :model="sshForm" label-position="top">
        <el-form-item label="主机" required>
          <el-input v-model="sshForm.host" placeholder="192.168.1.100" />
        </el-form-item>
        <el-form-item label="端口">
          <el-input-number v-model="sshForm.port" :min="1" :max="65535" style="width: 100%" />
        </el-form-item>
        <el-form-item label="用户名" required>
          <el-input v-model="sshForm.username" placeholder="root" />
        </el-form-item>
        <el-form-item label="密码" required>
          <el-input v-model="sshForm.password" type="password" show-password placeholder="SSH 密码" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showSshDialog = false">取消</el-button>
        <el-button type="primary" :loading="sshConnecting" @click="handleSshConnect">连接</el-button>
      </template>
    </el-dialog>

    <!-- Register Agent Dialog -->
    <el-dialog v-model="showAgentRegisterDialog" title="注册 Agent" width="480px">
      <el-form :model="agentForm" label-position="top">
        <el-form-item label="名称" required>
          <el-input v-model="agentForm.name" placeholder="如：payment-agent" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="agentForm.description" placeholder="可选描述" />
        </el-form-item>
        <el-form-item label="默认工作目录" required>
          <el-select v-model="agentForm.defaultDirectoryId" style="width: 100%" placeholder="选择目录">
            <el-option
              v-for="dir in directoriesForWorker(selectedWorkerId!)"
              :key="dir.directoryId"
              :label="dir.projectName + (dir.gitBranch ? ' (' + dir.gitBranch + ')' : '')"
              :value="dir.directoryId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="默认分支">
          <el-input v-model="agentForm.defaultBranch" placeholder="留空则由任务决定" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showAgentRegisterDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleRegisterAgent">注册</el-button>
      </template>
    </el-dialog>

    <!-- Edit Agent Dialog -->
    <el-dialog v-model="showAgentEditDialog" title="编辑 Agent" width="520px">
      <el-form :model="agentForm" label-position="top">
        <el-form-item label="名称" required>
          <el-input v-model="agentForm.name" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="agentForm.description" />
        </el-form-item>
        <el-form-item label="默认工作目录">
          <el-select v-model="agentForm.defaultDirectoryId" style="width: 100%" placeholder="选择目录">
            <el-option
              v-for="dir in directoriesForWorker(selectedWorkerId!)"
              :key="dir.directoryId"
              :label="dir.projectName + (dir.gitBranch ? ' (' + dir.gitBranch + ')' : '')"
              :value="dir.directoryId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="默认分支">
          <el-input v-model="agentForm.defaultBranch" placeholder="留空则由任务决定" />
        </el-form-item>
        <el-form-item>
          <template #label>
            <div style="display: flex; align-items: center; justify-content: space-between; width: 100%;">
              <span>项目概述</span>
              <el-button size="small" text type="primary" :loading="generatingSummary" @click="handleGenerateSummary">
                AI 自动生成
              </el-button>
            </div>
          </template>
          <el-input
            v-model="agentForm.projectSummary"
            type="textarea"
            :rows="5"
            placeholder="技术栈、主要模块、对外API端点等（可手动填写或点击自动生成）"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showAgentEditDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleUpdateAgent">保存</el-button>
      </template>
    </el-dialog>

    <!-- Agent Directory Binding Dialog -->
    <el-dialog v-model="showAgentBindingDialog" :title="'目录绑定 - ' + (bindingAgent?.name || '')" width="520px">
      <div v-if="bindingDirectories.length === 0" class="agent-empty" style="margin-bottom: 12px">
        暂无已绑定目录
      </div>
      <div v-else class="agent-binding-list">
        <div v-for="dir in bindingDirectories" :key="dir.directoryId" class="agent-binding-item">
          <span>{{ dir.projectName }}{{ dir.gitBranch ? ' (' + dir.gitBranch + ')' : '' }}</span>
          <el-button size="small" text type="danger" @click="handleUnbindDirectory(bindingAgent!.agentId, dir.directoryId)">
            解绑
          </el-button>
        </div>
      </div>
      <el-divider />
      <div class="agent-binding-add">
        <el-select v-model="bindDirectoryId" style="flex: 1" placeholder="选择要绑定的目录">
          <el-option
            v-for="dir in availableBindDirectories"
            :key="dir.directoryId"
            :label="dir.projectName + (dir.gitBranch ? ' (' + dir.gitBranch + ')' : '')"
            :value="dir.directoryId"
          />
        </el-select>
        <el-button type="primary" size="small" :disabled="!bindDirectoryId" :loading="saving" @click="handleBindDirectory">
          绑定
        </el-button>
      </div>
      <template #footer>
        <el-button @click="showAgentBindingDialog = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- Tag editing dialog -->
    <el-dialog v-model="showTagDialog" title="编辑标签" width="440px">
      <el-select
        v-model="tagForm.tags"
        multiple
        filterable
        allow-create
        default-first-option
        placeholder="选择或输入标签"
        style="width: 100%"
      >
        <el-option label="主任务" value="主任务" />
        <el-option label="临时" value="临时" />
        <el-option label="bugfix" value="bugfix" />
        <el-option label="feature" value="feature" />
      </el-select>
      <template #footer>
        <el-button @click="showTagDialog = false">取消</el-button>
        <el-button type="primary" :loading="savingTags" @click="handleSaveTags">保存</el-button>
      </template>
    </el-dialog>

    <!-- Favorite script context menu -->
    <Teleport to="body">
      <div
        v-if="favScriptCtx.visible"
        class="fav-script-ctx"
        :style="{ left: favScriptCtx.x + 'px', top: favScriptCtx.y + 'px' }"
      >
        <div class="fav-ctx-item" @click="handleTogglePinFavScript">
          {{ favScriptCtx.script?.pinned ? '取消固定' : '固定脚本' }}
        </div>
        <div class="fav-ctx-item fav-ctx-danger" @click="handleRemoveFavScript">移除脚本</div>
      </div>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
import { ref, triggerRef, computed, reactive, onMounted, onUnmounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Back } from '@element-plus/icons-vue'
import { useClaudeWorker } from '@/composables/useClaudeWorker'
import { useCodingAgent } from '@/composables/useCodingAgent'
import { useInputMemory } from '@/composables/useInputMemory'
import { useFavoriteScripts, type FavoriteScript } from '@/composables/useFavoriteScripts'
import { useTaskPane } from '@/composables/useTaskPane'
import type { TaskPaneState } from '@/composables/useTaskPane'
import {
  getOrCreateWorkspace,
  disposeWorkspace,
  disposeAllWorkspaces,
  isDirectorySynced,
  markDirectorySynced,
  restoreTerminalTabs,
} from '@/composables/useWorkspaceContext'
import type { WorkspaceContext, SshTerminalTab } from '@/composables/useWorkspaceContext'
import TaskPaneGrid from '@/components/worker/TaskPaneGrid.vue'
import SshTerminalPanel from '@/components/worker/SshTerminalPanel.vue'
import SshTerminal from '@/components/worker/SshTerminal.vue'
import SlashCommandInput from '@/components/worker/SlashCommandInput.vue'
import * as dirApi from '@/api/claudeWorker'
import * as sshApi from '@/api/ssh'
import { listAgentModelOverrides, listModelConfigs } from '@/api/platform'
import * as agentApi from '@/api/codingAgent'
import type { ClaudeTask, WorkingDirectory, SkillInfo, ConversationConfig, LlmModelConfig, CodingAgent, DirectorySummary } from '@/types'

const MAX_PANES = 4

const router = useRouter()
const workerState = useClaudeWorker()
const agentState = useCodingAgent()

// --- Agent management state ---
const showAgentRegisterDialog = ref(false)
const showAgentEditDialog = ref(false)
const showAgentBindingDialog = ref(false)
const editingAgent = ref<CodingAgent | null>(null)
const bindingAgent = ref<CodingAgent | null>(null)
const bindingDirectories = ref<DirectorySummary[]>([])
const agentForm = ref({ name: '', description: '', defaultDirectoryId: '', defaultBranch: '', projectSummary: '' })
const generatingSummary = ref(false)
const bindDirectoryId = ref('')

const agentsForWorker = computed(() =>
  agentState.agents.value.filter(a => a.workerId === selectedWorkerId.value),
)

// --- Worker tabs state (Agents / CLI Processes) ---
const workerActiveTab = ref('agents')
function handleWorkerTabChange(tab: string) {
  if (tab === 'processes' && cliProcesses.value.length === 0) {
    loadCliProcesses()
  }
}

// --- CLI process management state ---
import type { CliProcessInfo } from '@/types'
const cliProcesses = ref<CliProcessInfo[]>([])
const cliProcessActiveTaskCount = ref(0)
const loadingProcesses = ref(false)

async function loadCliProcesses() {
  if (!selectedWorkerId.value) return
  loadingProcesses.value = true
  try {
    const data = await dirApi.listCliProcesses(selectedWorkerId.value)
    cliProcesses.value = data.processes
    cliProcessActiveTaskCount.value = data.activeTaskCount
  } catch {
    cliProcesses.value = []
    cliProcessActiveTaskCount.value = 0
  } finally {
    loadingProcesses.value = false
  }
}

async function handleKillProcess(pid: number, force = false) {
  if (!selectedWorkerId.value) return
  const action = force ? '强制终止' : '终止'
  try {
    await ElMessageBox.confirm(
      `确定要${action}进程 PID ${pid} 吗？`,
      `${action}进程`,
      { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return // cancelled
  }
  try {
    const result = await dirApi.killCliProcess(selectedWorkerId.value, pid, force)
    if (result.status === 'killed') {
      ElMessage.success(`进程 ${pid} 已${action}`)
    } else {
      ElMessage.warning(result.message)
    }
    await loadCliProcesses()
  } catch (e: unknown) {
    ElMessage.error(`${action}失败: ${e instanceof Error ? e.message : e}`)
  }
}

const selectedWorkerId = ref<string | null>(null)
const selectedDirectoryId = ref<string | null>(null)
const expandedWorkerIds = reactive(new Set<string>())
const expandedProjectIds = reactive(new Set<string>())
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
const batchAuthForm = ref({ authMode: 'SUBSCRIPTION', authToken: '', baseUrl: '', skipExisting: true, modelConfigId: '' })
const batchAuthSessionIds = ref<string[]>([])

// Batch selection state
const batchSelectMode = ref(false)
const selectedConvIds = ref<Set<string>>(new Set())

// Tag dialog state
const showTagDialog = ref(false)
const savingTags = ref(false)
const tagDialogSessionId = ref('')
const tagForm = ref<{ tags: string[] }>({ tags: [] })

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
const rewindMode = ref<'file_rewind' | 'conversation_fork'>('conversation_fork')
const rewindFileEnabled = ref(false)
const rewindLoading = ref(false)
const rewindCheckpoints = ref<{ id: string; turnIndex: number; timestamp: string }[]>([])

// Scan checkpoints state
const scanningTaskId = ref('')

// Directory task pagination (separate from global task pagination)
const directoryTasks = ref<ClaudeTask[]>([])
const dirTaskPage = ref(0)
const dirTaskSize = ref(20)
const dirTaskTotal = ref(0)

// --- Workspace context: panes + terminal per directory/worker ---
const activeWorkspaceKey = computed<string | null>(() =>
  selectedDirectoryId.value ?? (selectedWorkerId.value ? `worker:${selectedWorkerId.value}` : null),
)
const activeWorkspace = computed<WorkspaceContext | null>(() => {
  const key = activeWorkspaceKey.value
  return key ? getOrCreateWorkspace(key) : null
})
const panes = computed<TaskPaneState[]>(() => activeWorkspace.value?.panes.value ?? [])

// SSH connect dialog state
const showSshDialog = ref(false)
const sshForm = ref({ host: '', port: 22, username: '', password: '' })
const sshConnecting = ref(false)

const addForm = ref({
  name: '',
  baseUrl: '',
  authToken: '',
  authMode: 'SUBSCRIPTION',
  sshUsername: '',
  sshPort: 22 as number,
  sshPassword: '',
})

const editForm = ref({
  name: '',
  baseUrl: '',
  authToken: '',
  authMode: 'SUBSCRIPTION',
  sshUsername: '',
  sshPort: 22 as number,
  sshPassword: '',
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

// --- 平台模型配置（供任务创建时选择 API 凭证） ---
const platformModels = ref<LlmModelConfig[]>([])
const platformModelConfigId = ref('')
const platformModelConfig = computed(() =>
  platformModels.value.find((m) => m.id === platformModelConfigId.value) || null,
)

async function loadPlatformModelConfig() {
  try {
    const [overrides, models] = await Promise.all([
      listAgentModelOverrides(),
      listModelConfigs(selectedWorkerId.value || undefined),
    ])
    const previousId = platformModelConfigId.value
    platformModels.value = models.filter((m) => m.hasApiKey)
    // 保持之前选中的模型（如果仍在列表中）
    if (previousId && platformModels.value.some((m) => m.id === previousId)) {
      platformModelConfigId.value = previousId
    } else {
      // 自动选择 claude-worker 的 agent-model override（如有）
      const override = overrides.find((o) => o.agentId === 'claude-worker')
      if (override && platformModels.value.some((m) => m.id === override.modelConfigId)) {
        platformModelConfigId.value = override.modelConfigId
      } else if (platformModels.value.length > 0) {
        platformModelConfigId.value = platformModels.value[0]!.id
      } else {
        platformModelConfigId.value = ''
      }
    }
  } catch {
    // best-effort
  }
}

// --- Agent management functions ---
function dirNameById(dirId?: string): string {
  if (!dirId) return '-'
  const dir = workerState.directories.value.find(d => d.directoryId === dirId)
  return dir?.projectName || dirId.substring(0, 8)
}

function dirBranchById(dirId?: string): string | undefined {
  if (!dirId) return undefined
  return workerState.directories.value.find(d => d.directoryId === dirId)?.gitBranch || undefined
}

function openAgentRegisterDialog() {
  agentForm.value = { name: '', description: '', defaultDirectoryId: '', defaultBranch: '', projectSummary: '' }
  showAgentRegisterDialog.value = true
}

function openAgentEditDialog(agent: CodingAgent) {
  editingAgent.value = agent
  agentForm.value = {
    name: agent.name,
    description: agent.description || '',
    defaultDirectoryId: agent.defaultDirectoryId || '',
    defaultBranch: agent.defaultBranch || '',
    projectSummary: agent.projectSummary || '',
  }
  showAgentEditDialog.value = true
}

async function handleRegisterAgent() {
  if (!agentForm.value.name || !agentForm.value.defaultDirectoryId || !selectedWorkerId.value) return
  saving.value = true
  try {
    await agentState.registerAgent({
      name: agentForm.value.name,
      description: agentForm.value.description || undefined,
      workerId: selectedWorkerId.value,
      defaultDirectoryId: agentForm.value.defaultDirectoryId,
      defaultBranch: agentForm.value.defaultBranch || undefined,
    })
    showAgentRegisterDialog.value = false
    ElMessage.success('Agent 注册成功')
  } catch (e: any) {
    ElMessage.error(e.message || '注册失败')
  } finally {
    saving.value = false
  }
}

async function handleUpdateAgent() {
  if (!editingAgent.value) return
  saving.value = true
  try {
    await agentState.updateAgent(editingAgent.value.agentId, {
      name: agentForm.value.name,
      description: agentForm.value.description || undefined,
      defaultBranch: agentForm.value.defaultBranch || undefined,
      defaultDirectoryId: agentForm.value.defaultDirectoryId || undefined,
      projectSummary: agentForm.value.projectSummary || undefined,
    })
    showAgentEditDialog.value = false
    ElMessage.success('Agent 更新成功')
  } catch (e: any) {
    ElMessage.error(e.message || '更新失败')
  } finally {
    saving.value = false
  }
}

async function handleGenerateSummary() {
  if (!editingAgent.value) return
  generatingSummary.value = true
  try {
    const updated = await agentState.generateSummary(editingAgent.value.agentId)
    if (updated.projectSummary) {
      agentForm.value.projectSummary = updated.projectSummary
      ElMessage.success('项目概述已生成')
    } else {
      ElMessage.warning('未能生成项目概述')
    }
  } catch (e: any) {
    ElMessage.error(e.message || '生成失败')
  } finally {
    generatingSummary.value = false
  }
}

async function handleDeleteAgent(agent: CodingAgent) {
  try {
    await ElMessageBox.confirm(`确认删除 Agent「${agent.name}」？`, '删除 Agent', { type: 'warning' })
  } catch { return }
  try {
    await agentState.deleteAgent(agent.agentId)
    ElMessage.success('已删除')
  } catch (e: any) {
    ElMessage.error(e.message || '删除失败')
  }
}

async function openAgentBindingDialog(agent: CodingAgent) {
  bindingAgent.value = agent
  bindDirectoryId.value = ''
  try {
    bindingDirectories.value = await agentApi.getAgentDirectories(agent.agentId)
  } catch {
    bindingDirectories.value = []
  }
  showAgentBindingDialog.value = true
}

const availableBindDirectories = computed(() => {
  if (!selectedWorkerId.value || !bindingAgent.value) return []
  const boundIds = new Set(bindingDirectories.value.map(d => d.directoryId))
  return workerState.directories.value
    .filter(d => d.workerId === selectedWorkerId.value && !boundIds.has(d.directoryId))
})

async function handleBindDirectory() {
  if (!bindingAgent.value || !bindDirectoryId.value) return
  saving.value = true
  try {
    await agentState.bindDirectory(bindingAgent.value.agentId, bindDirectoryId.value)
    bindingDirectories.value = await agentApi.getAgentDirectories(bindingAgent.value.agentId)
    await agentState.loadAgents()
    bindDirectoryId.value = ''
    ElMessage.success('已绑定')
  } catch (e: any) {
    ElMessage.error(e.message || '绑定失败')
  } finally {
    saving.value = false
  }
}

async function handleUnbindDirectory(agentId: string, dirId: string) {
  saving.value = true
  try {
    await agentState.unbindDirectory(agentId, dirId)
    bindingDirectories.value = bindingDirectories.value.filter(d => d.directoryId !== dirId)
    await agentState.loadAgents()
    ElMessage.success('已解绑')
  } catch (e: any) {
    ElMessage.error(e.message || '解绑失败')
  } finally {
    saving.value = false
  }
}

// --- Favorite scripts: per-directory ---
const favScriptsScope = computed(() => selectedDirectoryId.value || '')
const { scripts: favoriteScripts, recordRun: recordFavScript, pinScript, removeScript: removeFavScript } = useFavoriteScripts(favScriptsScope)

// --- Input memory: draft persistence + history for top task input ---
const taskInputScope = computed(() => {
  if (selectedDirectoryId.value) return 'task-' + selectedDirectoryId.value
  if (selectedWorkerId.value) return 'task-worker-' + selectedWorkerId.value
  return ''
})
const taskMemory = useInputMemory(taskInputScope)

// Save draft on every keystroke
watch(() => taskForm.value.prompt, (val) => taskMemory.saveDraft(val))

// Restore draft when scope changes (directory/worker switch)
watch(taskInputScope, () => {
  taskForm.value.prompt = taskMemory.loadDraft()
})

function handleTaskHistoryPrev() {
  const text = taskMemory.historyPrev(taskForm.value.prompt)
  if (text != null) taskForm.value.prompt = text
}
function handleTaskHistoryNext() {
  const text = taskMemory.historyNext()
  if (text != null) taskForm.value.prompt = text
}

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

// Active sessions: group activeTasks into ConversationGroups
const activeSessionConvs = computed(() => groupTasksToConversations(workerState.activeTasks.value))

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
    (d) => d.workerId === workerId && d.directoryType !== 'PROJECT' && !d.parentProjectId && !d.sourceDirectoryId,
  )
}

function childrenForProject(projectDirectoryId: string): WorkingDirectory[] {
  return workerState.directories.value.filter(
    (d) => d.parentProjectId === projectDirectoryId && !d.sourceDirectoryId,
  )
}

function worktreesForDirectory(directoryId: string): WorkingDirectory[] {
  return workerState.directories.value.filter(
    (d) => d.sourceDirectoryId === directoryId,
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

// Active tasks polling (fallback) + SSE-driven refresh
let activeTasksInterval: ReturnType<typeof setInterval> | null = null

function handleTaskUpdateEvent(event: Event) {
  const detail = (event as CustomEvent).detail
  if (detail?.type === 'task_status_change' && detail?.taskId) {
    const newStatus = detail.status as string
    if (['RUNNING', 'AWAITING_PERMISSION'].includes(newStatus)) {
      // Active state — update in-place or refresh list
      const existing = workerState.activeTasks.value.find(t => t.taskId === detail.taskId)
      if (existing) {
        existing.status = newStatus as any
      } else {
        workerState.loadActiveTasks()
      }
    } else {
      // Terminal state — remove from active list + update task list
      workerState.activeTasks.value = workerState.activeTasks.value.filter(
        t => t.taskId !== detail.taskId
      )
      const inTasks = workerState.tasks.value.find(t => t.taskId === detail.taskId)
      if (inTasks) {
        inTasks.status = newStatus as any
        if (detail.errorMessage) inTasks.errorMessage = detail.errorMessage
      }
    }
  } else {
    workerState.loadActiveTasks() // Fallback for legacy format
  }
}

function handleNotificationReconnected() {
  workerState.loadActiveTasks()
}

onMounted(async () => {
  window.addEventListener('message', handleFileBrowserMessage)
  document.addEventListener('click', closeFavScriptCtx)
  // Listen for SSE-driven task updates (from useNotifications)
  window.addEventListener('task-update', handleTaskUpdateEvent)
  window.addEventListener('notification-reconnected', handleNotificationReconnected)
  await Promise.all([workerState.loadWorkers(), workerState.loadTasks(), workerState.loadActiveTasks(), loadPlatformModelConfig(), agentState.loadAgents()])
  // Load conversation configs for all loaded tasks (including active)
  const allSessionIds = [
    ...workerState.tasks.value.map((t) => t.sessionId),
    ...workerState.activeTasks.value.map((t) => t.sessionId),
  ]
  const sessionIds = [...new Set(allSessionIds)]
  if (sessionIds.length > 0) {
    await workerState.loadConversationConfigs(sessionIds)
  }
  // Start polling active tasks every 15s (fallback for SSE disconnects)
  activeTasksInterval = setInterval(() => workerState.loadActiveTasks(), 15000)
})

onUnmounted(() => {
  if (activeTasksInterval) {
    clearInterval(activeTasksInterval)
    activeTasksInterval = null
  }
  window.removeEventListener('task-update', handleTaskUpdateEvent)
  window.removeEventListener('notification-reconnected', handleNotificationReconnected)
  window.removeEventListener('message', handleFileBrowserMessage)
  document.removeEventListener('click', closeFavScriptCtx)
  disposeAllWorkspaces()
})

// ---- File browser cross-window messaging ----------------------------------

function generateRunCommand(filePath: string): string {
  const ext = filePath.substring(filePath.lastIndexOf('.')).toLowerCase()
  switch (ext) {
    case '.sh': return `bash ${filePath}`
    case '.ps1': return `powershell -File ${filePath}`
    case '.py': return `python ${filePath}`
    case '.js': return `node ${filePath}`
    case '.ts': return `npx ts-node ${filePath}`
    default: return `./${filePath}`
  }
}

function handleFileBrowserMessage(event: MessageEvent) {
  if (event.origin !== window.location.origin) return
  if (!event.data) return

  const { type, directoryId, workerId, filePath } = event.data
  if (!directoryId || !workerId || !filePath) return

  if (type === 'run-in-terminal') {
    // 确保对应 worker/directory 处于选中态
    selectedWorkerId.value = workerId
    selectedDirectoryId.value = directoryId

    const command = generateRunCommand(filePath)
    doSshConnect(undefined, command)
    recordFavScript(filePath)
  } else if (type === 'pin-to-scripts') {
    selectedWorkerId.value = workerId
    selectedDirectoryId.value = directoryId
    pinScript(filePath)
    ElMessage.success(`已固定: ${filePath}`)
  }
}

// ---- Favorite scripts: tag bar interactions --------------------------------

const favScriptCtx = ref<{ visible: boolean; x: number; y: number; script: FavoriteScript | null }>({
  visible: false, x: 0, y: 0, script: null,
})

function handleFavScriptCtx(e: MouseEvent, s: FavoriteScript) {
  favScriptCtx.value = { visible: true, x: e.clientX, y: e.clientY, script: s }
}

function closeFavScriptCtx() {
  favScriptCtx.value.visible = false
}

function handleRemoveFavScript() {
  if (favScriptCtx.value.script) {
    removeFavScript(favScriptCtx.value.script.path)
  }
  closeFavScriptCtx()
}

function handleTogglePinFavScript() {
  const s = favScriptCtx.value.script
  if (!s) return
  if (s.pinned) {
    // Unpin: remove and re-add as auto
    removeFavScript(s.path)
    recordFavScript(s.path)
  } else {
    pinScript(s.path)
  }
  closeFavScriptCtx()
}

function handleRunFavScript(s: FavoriteScript) {
  const command = generateRunCommand(s.path)
  doSshConnect(undefined, command)
  recordFavScript(s.path)
}

/** Called when any pane's task reaches a terminal state */
function handleTaskFinished(_paneId: string) {
  workerState.loadTasks()
  if (selectedDirectoryId.value) {
    loadDirectoryTasks()
  }
}

function toggleExpand(workerId: string) {
  if (expandedWorkerIds.has(workerId)) {
    expandedWorkerIds.delete(workerId)
  } else {
    expandedWorkerIds.add(workerId)
    workerState.loadDirectories(workerId)
  }
}

function navigateToActiveSession(conv: ConversationGroup) {
  const task = conv.latestTask
  // Auto-select the corresponding worker & directory
  if (task.workerId && task.workerId !== selectedWorkerId.value) {
    selectWorker(task.workerId)
  }
  if (task.directoryId && task.directoryId !== selectedDirectoryId.value) {
    selectDirectory(task.workerId, task.directoryId)
  }
  // Open the task in a pane
  viewTask(task)
}

function selectWorker(workerId: string) {
  selectedWorkerId.value = workerId
  selectedDirectoryId.value = null
  directorySkills.value = []
  cliProcesses.value = []
  workerActiveTab.value = 'agents'
  exitBatchSelectMode()
  // No disposeAllPanes — workspace context preserves panes per directory
  // Auto-expand
  if (!expandedWorkerIds.has(workerId)) {
    expandedWorkerIds.add(workerId)
    workerState.loadDirectories(workerId)
  }
  // 切换 Worker 时刷新可用模型列表
  loadPlatformModelConfig()
}

function selectDirectory(workerId: string, directoryId: string) {
  selectedWorkerId.value = workerId
  selectedDirectoryId.value = directoryId
  exitBatchSelectMode()
  // No disposeAllPanes — workspace context preserves panes per directory
  taskForm.value.prompt = ''
  taskForm.value.cwd = ''
  dirTaskPage.value = 0
  loadDirectoryTasks()
  loadDirectorySkills()
  // Auto-sync SSH sessions from backend (once per directory per page load)
  syncSshSessions()
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
    dirTaskTotal.value = result.totalSessions
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
    addForm.value = { name: '', baseUrl: '', authToken: '', authMode: 'SUBSCRIPTION', sshUsername: '', sshPort: 22, sshPassword: '' }
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
    const form: Record<string, unknown> = {
      name: editForm.value.name,
      baseUrl: editForm.value.baseUrl,
      authMode: editForm.value.authMode,
      sshUsername: editForm.value.sshUsername,
      sshPort: editForm.value.sshPort,
    }
    // 密码类字段：有值才发送，空串不发（避免清空已保存的密码）
    if (editForm.value.authToken) {
      form.authToken = editForm.value.authToken
    }
    if (editForm.value.sshPassword) {
      form.sshPassword = editForm.value.sshPassword
    }
    await workerState.updateWorker(selectedWorkerId.value, form)
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
    // Dispose workspace for this worker
    disposeWorkspace(`worker:${selectedWorkerId.value}`)
    // Also dispose all directory workspaces under this worker
    const dirs = workerState.directories.value.filter(d => d.workerId === selectedWorkerId.value)
    for (const dir of dirs) {
      disposeWorkspace(dir.directoryId)
      // Clean up drafts for directories under this worker
      taskMemory.deleteDraft('task-' + dir.directoryId)
    }
    // Clean up draft for this worker
    taskMemory.deleteDraft('task-worker-' + selectedWorkerId.value)
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
    disposeWorkspace(selectedDirectoryId.value)
    // Clean up draft for this directory
    taskMemory.deleteDraft('task-' + selectedDirectoryId.value)
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
    disposeWorkspace(selectedDirectoryId.value!)
    selectedDirectoryId.value = null
    ElMessage.success('Worktree 已清理')
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error('清理失败')
    }
  }
}

function openFileBrowser() {
  if (!selectedDirectoryId.value) return
  const url = `${window.location.origin}/#/files?directoryId=${selectedDirectoryId.value}&workerId=${selectedWorkerId.value}`
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
    const { value: title } = (await ElMessageBox.prompt('输入自定义标题', '编辑标题', {
      confirmButtonText: '保存',
      cancelButtonText: '取消',
      inputValue: conv.config?.customTitle || '',
      inputPlaceholder: '留空则显示原始 prompt',
    })) as { value: string }
    await workerState.setTitle(conv.sessionId, title || '')
    ElMessage.success('标题已更新')
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error('更新失败')
    }
  }
}

function handleEditTags(conv: ConversationGroup) {
  tagDialogSessionId.value = conv.sessionId
  tagForm.value.tags = [...(conv.config?.tags || [])]
  showTagDialog.value = true
}

async function handleSaveTags() {
  savingTags.value = true
  try {
    await workerState.updateTags(tagDialogSessionId.value, tagForm.value.tags)
    showTagDialog.value = false
    ElMessage.success('标签已更新')
  } catch {
    ElMessage.error('更新标签失败')
  } finally {
    savingTags.value = false
  }
}

function tagColor(tag: string): '' | 'success' | 'warning' | 'danger' | 'info' {
  switch (tag) {
    case '主任务': return ''
    case '临时': return 'info'
    case 'bugfix': return 'danger'
    case 'feature': return 'success'
    default: return 'info'
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
  // If in batch select mode, use selected conversations; otherwise all
  if (batchSelectMode.value && selectedConvIds.value.size > 0) {
    batchAuthSessionIds.value = [...selectedConvIds.value]
  } else {
    batchAuthSessionIds.value = activeConversations.value.map((c) => c.sessionId)
  }
  batchAuthForm.value = { authMode: 'SUBSCRIPTION', authToken: '', baseUrl: '', skipExisting: true, modelConfigId: platformModelConfigId.value }
  showBatchAuthDialog.value = true
}

function toggleConvSelection(sessionId: string) {
  const next = new Set(selectedConvIds.value)
  if (next.has(sessionId)) {
    next.delete(sessionId)
  } else {
    next.add(sessionId)
  }
  selectedConvIds.value = next
}

function handleBatchSelectAll() {
  if (selectedConvIds.value.size === activeConversations.value.length) {
    selectedConvIds.value = new Set()
  } else {
    selectedConvIds.value = new Set(activeConversations.value.map((c) => c.sessionId))
  }
}

function exitBatchSelectMode() {
  batchSelectMode.value = false
  selectedConvIds.value = new Set()
}

async function handleBatchDelete() {
  const count = selectedConvIds.value.size
  if (count === 0) return
  try {
    await ElMessageBox.confirm(
      `确认删除选中的 ${count} 个会话？此操作不可恢复。`,
      '提示',
      { type: 'warning', confirmButtonText: '确认', cancelButtonText: '取消' },
    )
    const convs = activeConversations.value.filter((c) => selectedConvIds.value.has(c.sessionId))
    let deleted = 0
    for (const conv of convs) {
      for (const task of conv.tasks) {
        if (task.status !== 'RUNNING') {
          await workerState.deleteTask(task.taskId)
          deleted++
        }
      }
      // Clean up draft for this conversation
      taskMemory.deleteDraft('pane-' + conv.sessionId)
    }
    ElMessage.success(`已删除 ${deleted} 个任务`)
    exitBatchSelectMode()
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

async function handleBatchBindAuth() {
  const mode = batchAuthForm.value.authMode
  // 如果选择了平台模型配置，token 和 baseUrl 可以不填
  const hasModelConfig = !!batchAuthForm.value.modelConfigId
  if (mode === 'API_KEY' && !batchAuthForm.value.authToken && !hasModelConfig) {
    ElMessage.warning('请填写 API Key')
    return
  }
  if (mode === 'CUSTOM_ENDPOINT' && !batchAuthForm.value.baseUrl && !hasModelConfig) {
    ElMessage.warning('请填写 Base URL 或选择平台模型配置')
    return
  }
  if (mode === 'CUSTOM_ENDPOINT' && !batchAuthForm.value.authToken && !hasModelConfig) {
    ElMessage.warning('请填写 Token 或选择平台模型配置')
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
      modelConfigId: batchAuthForm.value.modelConfigId,
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
    if (activeWorkspace.value) triggerRef(activeWorkspace.value.panes)
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
    if (activeWorkspace.value) triggerRef(activeWorkspace.value.panes)
  } catch {
    ElMessage.error('Question response failed')
  }
}

async function handlePlanRespond(paneId: string, permissionId: string, decision: string, denyMessage?: string, planAction?: string) {
  const pane = panes.value.find((p) => p.paneId === paneId)
  if (!pane?.task.value) return

  try {
    await workerState.respondToPermission(pane.task.value.taskId, {
      permissionId,
      decision,
      denyMessage: denyMessage || (decision === 'deny' ? 'Plan rejected by user' : undefined),
      planAction,
    })
    pane.chatState.resolvePermission(permissionId, decision === 'allow' ? 'approved' : 'denied')
    if (decision === 'allow') {
      pane.task.value.status = 'RUNNING'
    }
    if (activeWorkspace.value) triggerRef(activeWorkspace.value.panes)
  } catch {
    ElMessage.error('Plan response failed')
  }
}

// In-pane rewind state
const paneRewindVisible = ref(false)
const paneRewindPaneId = ref('')
const paneRewindTurnIndex = ref(0)
const paneRewindTask = ref<ClaudeTask | null>(null)
const paneRewindMode = ref<'conversation_fork' | 'file_rewind'>('conversation_fork')
const paneRewindCheckpoint = ref<{ id: string; turnIndex: number; timestamp: string } | null>(null)
const paneRewindFileEnabled = ref(false)
const paneRewindLoading = ref(false)

function handlePaneRewind(paneId: string, turnIndex: number) {
  const pane = panes.value.find((p) => p.paneId === paneId)
  if (!pane?.task.value) return

  const task = pane.task.value
  paneRewindPaneId.value = paneId
  paneRewindTurnIndex.value = turnIndex
  paneRewindTask.value = task
  paneRewindMode.value = 'conversation_fork'
  paneRewindFileEnabled.value = task.fileCheckpointingEnabled === true
  paneRewindLoading.value = false

  // Try to find a matching checkpoint for this turn
  let cp: { id: string; turnIndex: number; timestamp: string } | null = null
  if (task.checkpoints) {
    try {
      const cps = JSON.parse(task.checkpoints) as { id: string; turnIndex: number; timestamp: string }[]
      cp = cps.find((c) => c.turnIndex === turnIndex) || null
    } catch { /* ignore */ }
  }
  paneRewindCheckpoint.value = cp

  paneRewindVisible.value = true
}

async function executePaneRewind() {
  if (!paneRewindTask.value) return
  paneRewindLoading.value = true

  const task = paneRewindTask.value
  const mode = paneRewindMode.value
  const cpId = paneRewindCheckpoint.value?.id || null

  // file_rewind requires a checkpoint
  if (mode === 'file_rewind' && !cpId) {
    ElMessage.error('该轮次无 Checkpoint，无法回退文件')
    paneRewindLoading.value = false
    return
  }

  try {
    const result = await dirApi.rewindTask(task.taskId, cpId, mode, paneRewindTurnIndex.value)
    if (mode === 'conversation_fork') {
      // Conversation rewind: no new task created.
      // 1. Remove messages from turn X onwards in the chat UI
      // 2. Fill the pane input with the original prompt
      const userPrompt = result.userPrompt || ''
      const pane = panes.value.find((p) => p.paneId === paneRewindPaneId.value)
      if (pane) {
        // Remove messages: find the Nth user message and truncate from there
        const msgs = pane.chatState.messages.value
        let userCount = 0
        let cutIndex = -1
        for (let i = 0; i < msgs.length; i++) {
          if (msgs[i]!.sender === 'user') {
            userCount++
            if (userCount === paneRewindTurnIndex.value) {
              cutIndex = i
              break
            }
          }
        }
        if (cutIndex >= 0) {
          pane.chatState.messages.value = msgs.slice(0, cutIndex)
        }
        if (userPrompt) {
          pane.pendingInput.value = userPrompt
        }
      }
      ElMessage.success('会话已回退，请编辑提示词后发送')
    } else {
      ElMessage.success('文件已回退')
      workerState.loadTasks()
      if (selectedDirectoryId.value) loadDirectoryTasks()
    }
    paneRewindVisible.value = false
  } catch (e) {
    const msg = e instanceof Error ? e.message : '回退失败'
    ElMessage.error(msg)
  } finally {
    paneRewindLoading.value = false
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
      permissionMode?: string; modelConfigId?: string
    } = {
      workerId: selectedWorkerId.value,
      prompt,
    }

    if (selectedDirectoryId.value) {
      form.directoryId = selectedDirectoryId.value
    } else if (taskForm.value.cwd) {
      // Check if any existing directory matches this cwd path
      const cwdInput = taskForm.value.cwd.replace(/[\\/]+$/, '') // normalize trailing slashes
      const existingDir = workerState.directories.value.find(
        d => d.workerId === selectedWorkerId.value && d.path.replace(/[\\/]+$/, '') === cwdInput,
      )
      if (existingDir) {
        form.directoryId = existingDir.directoryId
      } else {
        // Prompt user to auto-create a WorkingDirectory
        try {
          await ElMessageBox.confirm(
            `路径 "${cwdInput}" 尚未注册为工作目录。是否自动创建？\n创建后可在侧边栏管理该目录和关联任务。`,
            '创建工作目录',
            { confirmButtonText: '创建并运行', cancelButtonText: '仅本次使用', type: 'info', distinguishCancelAndClose: true },
          )
          // User confirmed — create directory first
          const pathSegments = cwdInput.replace(/\\/g, '/').split('/')
          const projectName = pathSegments[pathSegments.length - 1] || cwdInput
          const newDir = await dirApi.createDirectory({
            workerId: selectedWorkerId.value!,
            projectName,
            path: cwdInput,
          })
          await workerState.loadDirectories(selectedWorkerId.value!)
          form.directoryId = newDir.directoryId
          ElMessage.success(`工作目录 "${projectName}" 已创建`)
        } catch (action) {
          if (action === 'cancel') {
            // User chose "仅本次使用" — pass raw cwd
            form.cwd = cwdInput
          } else {
            // User closed the dialog — abort task creation
            return
          }
        }
      }
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
    if (platformModelConfigId.value) {
      form.modelConfigId = platformModelConfigId.value
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
    taskMemory.addToHistory(prompt)
    taskMemory.clearDraft()
    taskForm.value.prompt = ''
    clearAttachedImages()

    const pane = createPane(task)
    await pane.connect(task.sessionId)
  } catch (e: unknown) {
    ElMessage.error('创建任务失败: ' + ((e as Error).message || '未知错误'))
  }
}

function createPane(task: ClaudeTask): TaskPaneState {
  const ws = activeWorkspace.value
  if (!ws) throw new Error('No active workspace')
  const paneId = `pane-${++ws.paneCounter}`
  const pane = useTaskPane(paneId, { onTaskFinished: handleTaskFinished })
  pane.task.value = task
  ws.panes.value = [...ws.panes.value, pane]
  return pane
}

function closePane(paneId: string) {
  const ws = activeWorkspace.value
  if (!ws) return
  const idx = ws.panes.value.findIndex((p) => p.paneId === paneId)
  if (idx >= 0) {
    ws.panes.value[idx]!.dispose()
    ws.panes.value = ws.panes.value.filter((_, i) => i !== idx)
  }
}

async function abortPane(paneId: string) {
  const pane = panes.value.find((p) => p.paneId === paneId)
  if (!pane?.task.value) return
  try {
    await workerState.abortTask(pane.task.value.taskId)
    pane.task.value.status = 'ABORTED'
    if (activeWorkspace.value) triggerRef(activeWorkspace.value.panes)
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
    if (platformModelConfigId.value) {
      resumeForm.modelConfigId = platformModelConfigId.value
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
  rewindFileEnabled.value = task.fileCheckpointingEnabled === true
  rewindMode.value = 'conversation_fork'
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
    const selectedCp = rewindCheckpoints.value.find(cp => cp.id === rewindSelectedId.value)
    await dirApi.rewindTask(
      rewindTaskId.value,
      rewindSelectedId.value,
      rewindMode.value,
      selectedCp?.turnIndex,
    )
    if (rewindMode.value === 'conversation_fork') {
      ElMessage.success('会话已 fork，新任务已创建')
      // Refresh task list to show new task
      workerState.loadTasks()
      if (selectedDirectoryId.value) loadDirectoryTasks()
    } else {
      ElMessage.success('文件已回退')
    }
    showRewind.value = false
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : '回退失败'
    ElMessage.error(msg)
  } finally {
    rewindLoading.value = false
  }
}

function canScanCheckpoints(task: ClaudeTask): boolean {
  return !!task.claudeSessionId && !hasCheckpoints(task) && task.status !== 'RUNNING'
}

async function handleScanCheckpoints(task: ClaudeTask) {
  scanningTaskId.value = task.taskId
  try {
    const result = await dirApi.scanCheckpoints(task.taskId)
    if (result.count > 0) {
      task.checkpoints = result.checkpoints
      ElMessage.success(`扫描到 ${result.count} 个 Checkpoint`)
    } else {
      ElMessage.info('未发现 Checkpoint')
    }
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : '扫描失败'
    ElMessage.error(msg)
  } finally {
    scanningTaskId.value = ''
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
    // Clean up draft for this conversation (session)
    taskMemory.deleteDraft('pane-' + conv.sessionId)
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
    const { value: prompt } = (await ElMessageBox.prompt('输入后续指令', '继续对话', {
      confirmButtonText: '发送',
      cancelButtonText: '取消',
      inputType: 'textarea',
      inputPlaceholder: '请输入后续任务描述...',
    })) as { value: string }
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
      sshUsername: selectedWorkerEntity.value.sshUsername || '',
      sshPort: selectedWorkerEntity.value.sshPort || 22,
      sshPassword: '',
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

// ===== SSH Terminal Handlers =====

function prefillSshForm() {
  const worker = selectedWorkerEntity.value
  sshForm.value = {
    host: worker?.hostname || '',
    port: worker?.sshPort || 22,
    username: worker?.sshUsername || '',
    password: '',
  }
}

/** Worker 已配置 SSH 凭证时直连（密码由后端自动填充） */
function workerHasSshCredentials(): boolean {
  const worker = selectedWorkerEntity.value
  return !!(worker?.sshUsername && worker?.sshPasswordConfigured)
}

/**
 * 从后端同步 SSH 会话列表，恢复当前目录的终端 tab。
 */
async function syncSshSessions(force = false) {
  const wId = selectedWorkerId.value
  const dId = selectedDirectoryId.value
  if (!wId || !dId) return
  if (!force && isDirectorySynced(dId)) return

  try {
    const sessions = await sshApi.listSshSessions(wId)
    // Filter sessions belonging to the current directory
    const dirSessions = sessions.filter((s) => s.directoryId === dId)
    if (dirSessions.length > 0) {
      const ws = activeWorkspace.value
      if (ws) {
        restoreTerminalTabs(ws, dirSessions)
      }
    }
  } catch (e) {
    console.warn('SSH session sync failed:', e)
  } finally {
    markDirectorySynced(dId)
  }
}

function handleToggleTerminal() {
  // Blur button so pressing Enter won't toggle the terminal again
  ;(document.activeElement as HTMLElement)?.blur()
  const ws = activeWorkspace.value
  if (!ws) return
  if (ws.terminalVisible.value) {
    // If minimized, restore instead of closing
    if (ws.terminalMinimized.value) {
      ws.terminalMinimized.value = false
      return
    }
    ws.terminalVisible.value = false
  } else {
    ws.terminalVisible.value = true
    ws.terminalMinimized.value = false
    // If no tabs yet, open new terminal
    if (ws.terminalTabs.value.length === 0) {
      if (workerHasSshCredentials()) {
        // 目录已保存凭证，直接连接
        doSshConnect()
      } else {
        prefillSshForm()
        showSshDialog.value = true
      }
    }
  }
}

function handleToggleMinimize() {
  const ws = activeWorkspace.value
  if (!ws) return
  ws.terminalMinimized.value = !ws.terminalMinimized.value
  // Exit maximized when minimizing
  if (ws.terminalMinimized.value && ws.terminalMaximized.value) {
    ws.terminalMaximized.value = false
  }
}

function handleAddTerminalTab() {
  if (workerHasSshCredentials()) {
    doSshConnect()
  } else {
    prefillSshForm()
    showSshDialog.value = true
  }
}

async function handleSshConnect() {
  // 从对话框提交
  if (!sshForm.value.host || !sshForm.value.username || !sshForm.value.password) {
    ElMessage.warning('请填写完整的 SSH 连接信息')
    return
  }
  await doSshConnect({
    host: sshForm.value.host,
    port: sshForm.value.port,
    username: sshForm.value.username,
    password: sshForm.value.password,
  })
}

/**
 * 执行 SSH 连接。
 * overrides 为空时由后端从 directoryId 自动读取保存的凭证。
 */
async function doSshConnect(
  overrides?: { host: string; port: number; username: string; password: string },
  initialCommand?: string,
) {
  const ws = activeWorkspace.value
  if (!ws || !selectedWorkerId.value) return
  sshConnecting.value = true
  try {
    const connectForm: Parameters<typeof sshApi.sshConnect>[0] = {
      workerId: selectedWorkerId.value,
      host: overrides?.host || '',
      port: overrides?.port || 22,
      username: overrides?.username || '',
      password: overrides?.password || '',
    }
    // 传 directoryId 让后端自动填充已保存的凭证
    if (selectedDirectoryId.value) {
      (connectForm as Record<string, unknown>).directoryId = selectedDirectoryId.value
    }
    const result = await sshApi.sshConnect(connectForm)

    // Create WebSocket connection via Java backend proxy
    const wsConn = new WebSocket(sshApi.buildSshWsUrl(result.wsUrl))
    wsConn.binaryType = 'arraybuffer'

    const worker = selectedWorkerEntity.value
    const label = overrides?.username
      ? `${overrides.username}@${overrides.host}`
      : `${worker?.sshUsername || ''}@${worker?.hostname || ''}`

    const tabId = `term-${Date.now()}`
    const tab: SshTerminalTab = {
      tabId,
      label,
      sshSessionId: result.sessionId,
      wsUrl: result.wsUrl,
      ws: wsConn,
      buffer: [],
      initialCommand,
    }

    ws.terminalTabs.value = [...ws.terminalTabs.value, tab]
    ws.activeTermTabId.value = tabId
    ws.terminalVisible.value = true
    showSshDialog.value = false

    // Handle WS close: update tab state
    wsConn.onclose = () => {
      tab.ws = null
    }
    wsConn.onerror = () => {
      ElMessage.error('终端 WebSocket 连接错误')
    }
  } catch (e: unknown) {
    ElMessage.error('SSH 连接失败: ' + ((e as Error).message || '未知错误'))
  } finally {
    sshConnecting.value = false
  }
}

function handleCloseTerminalTab(tabId: string) {
  const ws = activeWorkspace.value
  if (!ws || !selectedWorkerId.value) return
  const tab = ws.terminalTabs.value.find((t) => t.tabId === tabId)
  if (!tab) return

  // Close WS
  if (tab.ws) {
    tab.ws.close()
    tab.ws = null
  }

  // Close SSH session on worker (best-effort)
  sshApi.sshClose(tab.sshSessionId, selectedWorkerId.value).catch(() => {})

  ws.terminalTabs.value = ws.terminalTabs.value.filter((t) => t.tabId !== tabId)

  // Switch active tab
  if (ws.activeTermTabId.value === tabId) {
    ws.activeTermTabId.value = ws.terminalTabs.value[0]?.tabId ?? null
  }

  // Hide panel if no tabs left
  if (ws.terminalTabs.value.length === 0) {
    ws.terminalVisible.value = false
  }
}

function handlePopOutTerminal() {
  const ws = activeWorkspace.value
  if (!ws) return
  const tab = ws.terminalTabs.value.find((t) => t.tabId === ws.activeTermTabId.value)
  if (!tab) return

  const popup = window.open('', '_blank', 'width=900,height=600')
  if (!popup) {
    ElMessage.warning('弹出窗口被浏览器拦截')
    return
  }

  popup.document.write(`<!DOCTYPE html>
<html>
<head>
  <title>${tab.label} - SSH Terminal</title>
  <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/@xterm/xterm@6.0.0/css/xterm.min.css">
  <style>body { margin: 0; background: #1e1e1e; } #terminal { width: 100vw; height: 100vh; }</style>
</head>
<body>
  <div id="terminal"></div>
  <script src="https://cdn.jsdelivr.net/npm/@xterm/xterm@6.0.0/lib/xterm.min.js"><\/script>
  <script src="https://cdn.jsdelivr.net/npm/@xterm/addon-fit@0.11.0/lib/addon-fit.min.js"><\/script>
  <script>
    const term = new window.Terminal({ cursorBlink: true, fontSize: 13, fontFamily: 'Consolas, monospace' });
    const fitAddon = new window.FitAddon.FitAddon();
    term.loadAddon(fitAddon);
    term.open(document.getElementById('terminal'));
    fitAddon.fit();
    const ws = new WebSocket(${JSON.stringify(tab.wsUrl)});
    ws.binaryType = 'arraybuffer';
    ws.onmessage = (e) => {
      if (typeof e.data === 'string') term.write(e.data);
      else term.write(new Uint8Array(e.data));
    };
    term.onData((data) => { if (ws.readyState === 1) ws.send(data); });
    window.addEventListener('resize', () => {
      fitAddon.fit();
      const dims = fitAddon.proposeDimensions();
      if (dims && ws.readyState === 1) ws.send(JSON.stringify({ type: 'resize', cols: dims.cols, rows: dims.rows }));
    });
    ws.onclose = () => term.write('\\r\\n[连接已关闭]');
  <\/script>
</body>
</html>`)
  popup.document.close()
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

.worktree-dir {
  padding-left: 72px !important;
}

.orphan-worktree {
  padding-left: 40px !important;
}

.worktree-indent {
  color: #c0c4cc;
  font-size: 12px;
  margin-right: 4px;
  user-select: none;
}

.worktree-branch {
  padding-left: 20px;
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

.mini-input-row {
  display: flex;
  align-items: flex-end;
  gap: 4px;
}

.mini-input-row .slash-input-wrap {
  flex: 1;
  min-width: 0;
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

.conv-item:hover .conv-actions {
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

.conv-actions {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 2px;
  flex-shrink: 0;
}

.conv-more-trigger {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border-radius: 4px;
  cursor: pointer;
  font-size: 14px;
  color: #909399;
  transition: all 0.15s;
  user-select: none;
}

.conv-more-trigger:hover {
  background: #dcdfe6;
  color: #303133;
}

:deep(.delete-dropdown-item) {
  color: #f56c6c !important;
}

.conv-pinned {
  background: #fdf6ec;
  border-left: 2px solid #e6a23c;
}

.conv-selected {
  background: #ecf5ff;
}

.conv-checkbox {
  flex-shrink: 0;
  margin-right: 4px;
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

/* Kept for backward compat — now primarily using dropdown */

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

/* Visually hidden but accessible (for file input) */
.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  border: 0;
}

/* ---- Favorite scripts bar ---- */
.fav-scripts-bar {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 8px;
  flex-shrink: 0;
  flex-wrap: wrap;
}

.fav-scripts-label {
  font-size: 12px;
  color: #909399;
  flex-shrink: 0;
}

.fav-script-tag {
  cursor: pointer;
}

.pin-icon {
  margin-right: 2px;
}

/* ---- Agent management section ---- */
.agent-empty {
  padding: 16px;
  text-align: center;
  color: #909399;
  font-size: 13px;
}

.agent-card {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 12px;
  border-bottom: 1px solid #f0f0f0;
}

.agent-card:last-child {
  border-bottom: none;
}

.agent-card-name {
  font-size: 14px;
  font-weight: 500;
  color: #303133;
}

.agent-card-desc {
  font-size: 12px;
  color: #909399;
  margin-top: 2px;
}
.agent-card-summary {
  font-size: 12px;
  color: #606266;
  margin-top: 4px;
  padding: 4px 8px;
  background: #f5f7fa;
  border-radius: 4px;
  white-space: pre-wrap;
  max-height: 80px;
  overflow-y: auto;
  line-height: 1.4;
}

.agent-card-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 4px;
  font-size: 12px;
  color: #606266;
}

.agent-dir-tag {
  display: inline-flex;
  align-items: center;
  gap: 2px;
}

.agent-branch {
  color: #909399;
}

.agent-dir-count {
  color: #909399;
}

.agent-card-actions {
  display: flex;
  gap: 2px;
  flex-shrink: 0;
}

.agent-binding-list {
  margin-bottom: 8px;
}

.agent-binding-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 6px 8px;
  border-bottom: 1px solid #f0f0f0;
  font-size: 13px;
}

.agent-binding-item:last-child {
  border-bottom: none;
}

.agent-binding-add {
  display: flex;
  gap: 8px;
  align-items: center;
}

/* --- Worker Tabs (Agents / CLI Processes) --- */

.worker-tabs {
  margin-bottom: 12px;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  overflow: hidden;
  flex-shrink: 0;
}

.worker-tabs :deep(.el-tabs__header) {
  margin: 0;
  padding: 0 12px;
  background: #f5f7fa;
}

.worker-tabs :deep(.el-tabs__nav-wrap::after) {
  height: 1px;
  background: #e4e7ed;
}

.worker-tabs :deep(.el-tabs__content) {
  padding: 8px 12px;
}

.tab-pane-toolbar {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 8px;
}

/* Active sessions section */
.active-sessions-section {
  margin-bottom: 4px;
}

.active-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 10px;
}

.active-title {
  font-size: 13px;
  font-weight: 600;
  color: #409eff;
}

.active-conv-item {
  padding: 8px 10px;
  border-radius: 6px;
  cursor: pointer;
  transition: background-color 0.15s;
  border-left: 3px solid #409eff;
  margin: 2px 0;
  background: #f0f7ff;
}

.active-conv-item:hover {
  background: #e1eeff;
}

.active-dir-tag {
  font-size: 11px;
  padding: 0 4px;
  height: 18px;
  line-height: 18px;
  margin-right: 4px;
}

.active-status-label {
  font-size: 12px;
  color: #409eff;
  font-weight: 500;
}

.active-divider {
  display: flex;
  align-items: center;
  margin: 8px 0 4px;
  gap: 8px;
}

.active-divider-line {
  flex: 1;
  height: 1px;
  background: #e4e7ed;
}

.active-divider-text {
  font-size: 11px;
  color: #909399;
  white-space: nowrap;
}

/* Conversation tags */
.conv-tag {
  font-size: 10px;
  height: 18px;
  line-height: 18px;
  padding: 0 4px;
  flex-shrink: 0;
}
</style>

<style>
/* Fav-script context menu — unscoped because it's teleported to body */
.fav-script-ctx {
  position: fixed;
  z-index: 9999;
  background: #2d2d2d;
  border: 1px solid #454545;
  border-radius: 4px;
  padding: 4px 0;
  min-width: 120px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.4);
  font-size: 13px;
  color: #cccccc;
}

.fav-ctx-item {
  padding: 6px 16px;
  cursor: pointer;
  white-space: nowrap;
}

.fav-ctx-item:hover {
  background: #094771;
  color: #fff;
}

.fav-ctx-danger:hover {
  background: #c74e39;
}
</style>
