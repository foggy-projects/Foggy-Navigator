<template>
  <div :class="['worker-layout', { 'fullscreen-active': isSessionFullscreen }]">
    <!-- Left Panel: Two-level Tree Sidebar -->
    <aside :class="['worker-sidebar', { collapsed: prefs.leftPanelCollapsed }]">
      <div class="sidebar-header">
        <h3>Workers</h3>
        <div class="sidebar-header-actions">
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
          <span class="panel-collapse-btn" title="收起侧栏" @click="prefs.leftPanelCollapsed = true">&laquo;</span>
        </div>
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
        <el-button text size="small" @click="router.push('/chat')">
          <el-icon><Back /></el-icon> 返回会话
        </el-button>
      </div>
    </aside>

    <!-- Middle Panel: Main Content Area -->
    <main :class="['worker-main', { 'has-panes': panes.length > 0 }]" @paste="handlePaste" @drop="handleDrop" @dragover="handleDragOver">
      <!-- Expand buttons for collapsed panels -->
      <span v-if="prefs.leftPanelCollapsed" class="panel-expand-btn left-expand" title="展开侧栏" @click="prefs.leftPanelCollapsed = false">&raquo;</span>
      <span v-if="prefs.rightPanelCollapsed" class="panel-expand-btn right-expand" title="展开历史" @click="prefs.rightPanelCollapsed = false">&laquo;</span>
      <!-- Hidden file input for attachment picker (images + files) -->
      <input ref="fileInputRef" type="file" multiple class="sr-only" @change="handleFileSelect">
      <!-- Fullscreen exit bar (only visible in fullscreen mode) -->
      <div v-if="isSessionFullscreen" class="fullscreen-exit-bar">
        <el-button text @click="exitFullscreen">← 退出全屏</el-button>
        <div class="fullscreen-exit-bar-right">
          <el-button text @click="showPencilCanvas = true" title="打开画板">🎨 画板</el-button>
        </div>
      </div>
      <!-- PencilCanvas overlay -->
      <PencilCanvas
        :visible="showPencilCanvas"
        @save="handleCanvasSave"
        @close="showPencilCanvas = false"
      />
      <!-- ScreenshotAnnotator overlay -->
      <ScreenshotAnnotator
        :visible="showAnnotator"
        :image-url="annotatorImageUrl"
        :image-index="annotatorImageIndex"
        @save="handleAnnotatorSave"
        @close="showAnnotator = false"
      />
      <!-- Attachment image lightbox (click-to-enlarge) -->
      <Teleport to="body">
        <div v-if="previewAttachmentIdx >= 0 && attachments[previewAttachmentIdx]?.isImage" class="att-lightbox" @click="previewAttachmentIdx = -1">
          <img :src="attachments[previewAttachmentIdx]!.previewUrl" :alt="attachments[previewAttachmentIdx]!.name" class="att-lightbox-img" @click.stop />
          <span class="att-lightbox-close" @click="previewAttachmentIdx = -1">&times;</span>
        </div>
      </Teleport>
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
            <el-dropdown size="small" trigger="click" @command="openCodeServer">
              <el-button size="small">
                VS Code<el-icon class="el-icon--right"><ArrowDown /></el-icon>
              </el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="internal">内网访问</el-dropdown-item>
                  <el-dropdown-item command="public">公网访问</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
            <el-button size="small" @click="handleToggleTerminal">终端</el-button>
            <el-button size="small" @click="toggleFullscreen" title="全屏会话">⤢</el-button>
            <el-button size="small" @click="openMilestoneManager">里程碑</el-button>
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
        <!-- Task Form: collapse when panes are open -->
        <div v-if="panes.length === 0" class="task-form">
          <label class="form-label">Prompt</label>
          <SlashCommandInput
            ref="dirFullInputRef"
            v-model="taskForm.prompt"
            :rows="3"
            auto-grow
            placeholder="输入任务描述... (可粘贴截图或拖拽文件, ./ 搜索文件)"
            :skills="directorySkills"
            :agents="allAgentItems"
            :directory-id="selectedDirectoryId ?? undefined"
            @submit="handleCreateTask"
            @command="handleSlashCommand"
            @history-prev="handleTaskHistoryPrev"
            @history-next="handleTaskHistoryNext"
          />
          <!-- Attachment preview strip -->
          <div v-if="attachments.length > 0" class="image-preview-strip" style="margin-top: 8px">
            <div v-for="(att, idx) in attachments" :key="idx" class="image-preview-item" :class="{ 'file-item': !att.isImage }">
              <template v-if="att.isImage">
                <img :src="att.previewUrl" :alt="att.name" :title="att.name" @click="previewAttachmentIdx = idx" />
                <span class="image-annotate" @click="openAnnotator(idx)" title="标注">🖊️</span>
              </template>
              <div v-else class="file-preview" :title="att.name">
                <span class="file-icon">{{ fileIcon(att.mimeType) }}</span>
                <span class="file-name">{{ att.name }}</span>
              </div>
              <span class="image-remove" @click="removeAttachment(idx)">&times;</span>
            </div>
          </div>
          <div class="form-action-bar">
            <el-button
              type="primary"
              :disabled="!taskForm.prompt || selectedWorkerEntity?.status !== 'ONLINE'"
              :loading="creatingTask"
              @click="handleCreateTask"
            >
              运行任务
            </el-button>
            <button class="mini-tool-btn" @click="fileInputRef?.click()" title="附加文件">
              <span class="btn-icon">&#128206;</span><span class="btn-label">附件</span>
            </button>
            <button class="mini-tool-btn" @click="showPencilCanvas = true" title="打开画板">
              <span class="btn-icon">🎨</span><span class="btn-label">画板</span>
            </button>
            <button class="mini-tool-btn" @click="insertDotSlash(dirFullInputRef)" title="搜索文件路径 (./)">
              <span class="btn-icon">&#128193;</span><span class="btn-label">搜索</span>
            </button>
            <el-select v-model="taskForm.permissionMode" size="small" class="toolbar-select" style="width: 120px">
              <el-option value="bypassPermissions" label="跳过权限" />
              <el-option value="acceptEdits" label="自动接受编辑" />
              <el-option value="plan" label="只读(Plan)" />
              <el-option value="default" label="交互式审批" />
            </el-select>
            <el-select v-model="taskForm.model" size="small" class="toolbar-select" style="width: 120px">
              <el-option v-for="opt in claudeModelOptions" :key="opt.value" :value="opt.value" :label="opt.label" />
            </el-select>
            <el-select
              v-if="platformModels.length > 0"
              v-model="platformModelConfigId"
              size="small"
              class="toolbar-select"
              style="width: 140px"
              placeholder="LLM 配置"
              clearable
            >
              <el-option v-for="m in platformModels" :key="m.id" :value="m.id" :label="m.name" />
            </el-select>
            <el-select
              v-if="agentTeamsConfigs.length > 0"
              v-model="selectedAgentTeamsConfigId"
              placeholder="Teams"
              clearable
              size="small"
              class="toolbar-select"
              style="width: 140px"
            >
              <el-option v-for="cfg in agentTeamsConfigs" :key="cfg.configId" :label="cfg.name + (cfg.isDefault ? ' ★' : '')" :value="cfg.configId" />
            </el-select>
            <el-tag v-for="agent in parsedAgentTeams" :key="agent" size="small" type="info" effect="light">{{ agent }}</el-tag>
            <el-tag v-if="platformModelConfig" size="small" type="success">API: {{ platformModelConfig.name }}</el-tag>
            <el-tag v-if="taskForm.maxTurns" size="small" closable @close="taskForm.maxTurns = null">轮次: {{ taskForm.maxTurns }}</el-tag>
          </div>
        </div>
        <div v-else class="new-task-mini">
          <div class="mini-input-row">
            <SlashCommandInput
              ref="dirMiniInputRef"
              v-model="taskForm.prompt"
              :rows="1"
              auto-grow
              :max-rows="4"
              size="small"
              placeholder="新建任务... (Ctrl+Enter 发送, ./ 搜索文件)"
              :skills="directorySkills"
              :agents="allAgentItems"
              :directory-id="selectedDirectoryId ?? undefined"
              @submit="handleCreateTask"
              @command="handleSlashCommand"
              @history-prev="handleTaskHistoryPrev"
              @history-next="handleTaskHistoryNext"
            />
            <el-button
              class="mini-run-btn"
              size="small"
              :disabled="!taskForm.prompt || selectedWorkerEntity?.status !== 'ONLINE'"
              :loading="creatingTask"
              @click="handleCreateTask"
            >
              运行
            </el-button>
          </div>
          <div class="mini-toolbar">
            <button class="mini-tool-btn" @click="fileInputRef?.click()" title="附加文件">
              <span class="btn-icon">&#128206;</span><span class="btn-label">附件</span>
            </button>
            <button class="mini-tool-btn" @click="showPencilCanvas = true" title="打开画板">
              <span class="btn-icon">🎨</span><span class="btn-label">画板</span>
            </button>
            <button class="mini-tool-btn" @click="insertDotSlash(dirMiniInputRef)" title="搜索文件路径 (./)">
              <span class="btn-icon">&#128193;</span><span class="btn-label">搜索</span>
            </button>
            <el-select
              v-if="agentTeamsConfigs.length > 0"
              v-model="selectedAgentTeamsConfigId"
              placeholder="Teams"
              clearable
              size="small"
              class="toolbar-select"
              style="width: 130px"
            >
              <el-option v-for="cfg in agentTeamsConfigs" :key="cfg.configId" :label="cfg.name + (cfg.isDefault ? ' ★' : '')" :value="cfg.configId" />
            </el-select>
            <el-tag v-for="agent in parsedAgentTeams" :key="agent" size="small" type="info" effect="light">{{ agent }}</el-tag>
            <el-select
              v-if="platformModels.length > 0"
              v-model="platformModelConfigId"
              size="small"
              class="toolbar-select"
              style="width: 140px"
              placeholder="LLM 配置"
              clearable
            >
              <el-option v-for="m in platformModels" :key="m.id" :value="m.id" :label="m.name" />
            </el-select>
            <el-select v-model="taskForm.model" size="small" class="toolbar-select" style="width: 120px">
              <el-option v-for="opt in claudeModelOptions" :key="opt.value" :value="opt.value" :label="opt.label" />
            </el-select>
            <el-tag v-if="taskForm.maxTurns" size="small" closable @close="taskForm.maxTurns = null">轮次: {{ taskForm.maxTurns }}</el-tag>
          </div>
          <div v-if="attachments.length > 0" class="image-preview-strip compact">
            <div v-for="(att, idx) in attachments" :key="idx" class="image-preview-item small" :class="{ 'file-item': !att.isImage }">
              <template v-if="att.isImage">
                <img :src="att.previewUrl" :alt="att.name" :title="att.name" @click="previewAttachmentIdx = idx" />
                <span class="image-annotate" @click="openAnnotator(idx)" title="标注">🖊️</span>
              </template>
              <div v-else class="file-preview" :title="att.name">
                <span class="file-icon">{{ fileIcon(att.mimeType) }}</span>
                <span class="file-name">{{ att.name }}</span>
              </div>
              <span class="image-remove" @click="removeAttachment(idx)">&times;</span>
            </div>
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
          :agents="allAgentItems"
          :focused-pane-id="focusedPaneId"
          :pane-labels="PANE_LABELS"
          @close="closePane"
          @abort="abortPane"
          @send="handlePaneSend"
          @command="handleSlashCommand"
          @permission-respond="handlePermissionRespond"
          @question-respond="handleQuestionRespond"
          @plan-respond="handlePlanRespond"
          @rewind="handlePaneRewind"
          @reconnect="handlePaneReconnect"
          @forward="handlePaneForward"
          @link-click="handleLinkClick"
          @focus="focusedPaneId = $event"
        >
          <template #header-extra="{ paneState }">
            <span
              v-if="paneInteractionState(paneState)"
              :class="['pane-interaction-tag', paneInteractionState(paneState)!.toLowerCase()]"
            >{{ interactionStateLabel(paneInteractionState(paneState)!) }}</span>
            <el-button
              v-if="paneInteractionState(paneState) === 'AWAITING_REPLY'"
              size="small"
              text
              title="搁置会话"
              @click="handlePaneHold(paneState.task.value?.sessionId)"
            >搁置</el-button>
            <el-button
              v-if="paneInteractionState(paneState) === 'AWAITING_REPLY'"
              size="small"
              text
              title="归档会话"
              @click="handlePaneArchive(paneState.task.value?.sessionId)"
            >归档</el-button>
            <el-button
              v-if="paneInteractionState(paneState) === 'ARCHIVED'"
              size="small"
              text
              title="取消归档会话"
              @click="handlePaneUnarchive(paneState.task.value?.sessionId)"
            >取消归档</el-button>
            <el-button
              v-if="paneInteractionState(paneState) === 'AWAITING_REPLY'"
              size="small"
              text
              type="danger"
              title="删除会话"
              @click="handlePaneDelete(paneState.task.value?.sessionId)"
            >删除</el-button>
            <el-button
              v-if="paneState.task.value?.status === 'FAILED'"
              size="small"
              text
              title="重新同步失败的任务"
              :loading="resyncingTaskId === paneState.task.value?.taskId"
              @click="handlePaneResync(paneState)"
            >重新同步</el-button>
          </template>
        </TaskPaneGrid>
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
            <el-button size="small" :loading="syncingSkills" @click="handleSyncSkills">同步 Skills</el-button>
            <el-button size="small" @click="showEditDialog = true">编辑</el-button>
            <el-button size="small" type="danger" text @click="handleDelete">删除</el-button>
          </div>
        </div>

        <!-- Worker Tabs: CLI Processes & Agents -->
        <el-tabs v-model="workerActiveTab" class="worker-tabs" @tab-change="handleWorkerTabChange">
          <el-tab-pane name="processes">
            <template #label>
              <span>CLI 进程</span>
              <el-tag v-if="cliProcesses.length > 0" size="small" :type="cliProcesses.some(p => p.is_orphan) ? 'danger' : 'info'" style="margin-left: 6px;">{{ cliProcesses.length }}</el-tag>
            </template>
            <div class="tab-pane-toolbar">
              <el-button size="small" :loading="loadingProcesses" @click="loadCliProcesses">刷新</el-button>
            </div>
            <el-table
              v-loading="loadingProcesses"
              :data="cliProcesses"
              size="small"
              stripe
              empty-text="未检测到 CLI 进程"
              style="width: 100%"
            >
              <el-table-column label="类型" width="90">
                <template #default="{ row }">
                  <el-tag size="small" :type="row.process_type === 'codex' ? 'success' : 'info'">
                    {{ row.process_type === 'codex' ? 'Codex' : 'Claude' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="pid" label="PID" width="80" />
              <el-table-column prop="command" label="命令" min-width="200" show-overflow-tooltip />
              <el-table-column label="内存" width="80">
                <template #default="{ row }">{{ row.memory_mb ? row.memory_mb.toFixed(1) + ' MB' : '-' }}</template>
              </el-table-column>
              <el-table-column label="启动时间" width="150">
                <template #default="{ row }">{{ row.started_at || '-' }}</template>
              </el-table-column>
              <el-table-column label="会话" width="120">
                <template #default="{ row }">
                  <el-tooltip
                    v-if="row.foggy_session_id && row.claude_session_id"
                    :content="row.claude_session_id"
                    placement="top"
                  >
                    <el-link
                      type="primary"
                      :underline="false"
                      @click="navigateToProcessSession(row)"
                    >
                      {{ row.claude_session_id.slice(0, 8) }}
                    </el-link>
                  </el-tooltip>
                  <el-tooltip
                    v-else-if="row.codex_thread_id"
                    :content="row.codex_thread_id"
                    placement="top"
                  >
                    <el-tag size="small" type="success">{{ row.codex_thread_id.slice(0, 8) }}</el-tag>
                  </el-tooltip>
                  <el-tag v-else-if="!row.is_orphan" size="small" type="info">新会话</el-tag>
                  <span v-else>-</span>
                </template>
              </el-table-column>
              <el-table-column label="模型" width="100">
                <template #default="{ row }">
                  <el-tooltip v-if="row.model" :content="row.model" placement="top">
                    <el-tag size="small" type="" :disable-transitions="true">{{ formatModelShort(row.model) }}</el-tag>
                  </el-tooltip>
                  <span v-else>-</span>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="160" align="center">
                <template #default="{ row }">
                  <template v-if="row.orphan_first_seen_at">
                    <el-tooltip :content="orphanTooltip(row)" placement="top">
                      <el-tag type="danger" size="small" style="cursor:help">
                        孤儿 · {{ orphanCountdown(row) }}
                      </el-tag>
                    </el-tooltip>
                  </template>
                  <el-tag v-else-if="row.is_orphan" type="warning" size="small">孤儿</el-tag>
                  <el-tag v-else type="success" size="small">活跃</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="200" align="center">
                <template #default="{ row }">
                  <template v-if="row.process_type !== 'codex' && row.foggy_task_id">
                    <el-button text size="small" @click="handleResyncTask(row.foggy_task_id)">重新同步</el-button>
                    <el-divider direction="vertical" style="margin: 0 4px;" />
                  </template>
                  <el-button text size="small" @click="handleKillProcess(row, false)">终止</el-button>
                  <el-button text size="small" type="danger" @click="handleKillProcess(row, true)">强制</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>

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
                  <span v-if="agent.defaultModelConfigName" class="agent-model-tag">
                    {{ agent.defaultModelConfigName }}
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
        </el-tabs>

        <!-- Task Form: collapse when panes are open -->
        <div v-if="panes.length === 0" class="task-form">
          <label class="form-label">Prompt</label>
          <SlashCommandInput
            v-model="taskForm.prompt"
            :rows="3"
            auto-grow
            placeholder="输入任务描述... (可粘贴截图或拖拽文件)"
            :skills="directorySkills"
            :agents="allAgentItems"
            @submit="handleCreateTask"
            @command="handleSlashCommand"
            @history-prev="handleTaskHistoryPrev"
            @history-next="handleTaskHistoryNext"
          />
          <!-- Attachment preview strip -->
          <div v-if="attachments.length > 0" class="image-preview-strip" style="margin-top: 8px">
            <div v-for="(att, idx) in attachments" :key="idx" class="image-preview-item" :class="{ 'file-item': !att.isImage }">
              <template v-if="att.isImage">
                <img :src="att.previewUrl" :alt="att.name" :title="att.name" @click="previewAttachmentIdx = idx" />
                <span class="image-annotate" @click="openAnnotator(idx)" title="标注">🖊️</span>
              </template>
              <div v-else class="file-preview" :title="att.name">
                <span class="file-icon">{{ fileIcon(att.mimeType) }}</span>
                <span class="file-name">{{ att.name }}</span>
              </div>
              <span class="image-remove" @click="removeAttachment(idx)">&times;</span>
            </div>
          </div>
          <div class="form-action-bar">
            <el-button
              type="primary"
              :disabled="!taskForm.prompt || selectedWorkerEntity.status !== 'ONLINE'"
              :loading="creatingTask"
              @click="handleCreateTask"
            >
              运行任务
            </el-button>
            <button class="mini-tool-btn" @click="fileInputRef?.click()" title="附加文件">
              <span class="btn-icon">&#128206;</span><span class="btn-label">附件</span>
            </button>
            <button class="mini-tool-btn" @click="showPencilCanvas = true" title="打开画板">
              <span class="btn-icon">🎨</span><span class="btn-label">画板</span>
            </button>
            <el-select v-model="taskForm.permissionMode" size="small" class="toolbar-select" style="width: 120px">
              <el-option value="bypassPermissions" label="跳过权限" />
              <el-option value="acceptEdits" label="自动接受编辑" />
              <el-option value="plan" label="只读(Plan)" />
              <el-option value="default" label="交互式审批" />
            </el-select>
            <el-select v-model="taskForm.model" size="small" class="toolbar-select" style="width: 120px">
              <el-option v-for="opt in claudeModelOptions" :key="opt.value" :value="opt.value" :label="opt.label" />
            </el-select>
            <el-select
              v-if="platformModels.length > 0"
              v-model="platformModelConfigId"
              size="small"
              class="toolbar-select"
              style="width: 140px"
              placeholder="LLM 配置"
              clearable
            >
              <el-option v-for="m in platformModels" :key="m.id" :value="m.id" :label="m.name" />
            </el-select>
            <el-tag v-if="platformModelConfig" size="small" type="success">LLM: {{ platformModelConfig.name }}</el-tag>
            <el-tag v-if="taskForm.maxTurns" size="small" closable @close="taskForm.maxTurns = null">轮次: {{ taskForm.maxTurns }}</el-tag>
          </div>
          <div style="margin-top: 8px">
            <label class="form-label">工作目录 (cwd)</label>
            <el-input v-model="taskForm.cwd" size="small" placeholder="可选，如 /home/user/project" />
          </div>
        </div>
        <div v-else class="new-task-mini">
          <div class="mini-input-row">
            <SlashCommandInput
              v-model="taskForm.prompt"
              :rows="1"
              auto-grow
              :max-rows="4"
              size="small"
              placeholder="新建任务... (Ctrl+Enter 发送，可拖拽文件)"
              :skills="directorySkills"
              :agents="allAgentItems"
              @submit="handleCreateTask"
              @command="handleSlashCommand"
              @history-prev="handleTaskHistoryPrev"
              @history-next="handleTaskHistoryNext"
            />
            <el-button
              class="mini-run-btn"
              size="small"
              :disabled="!taskForm.prompt || selectedWorkerEntity.status !== 'ONLINE'"
              :loading="creatingTask"
              @click="handleCreateTask"
            >
              运行
            </el-button>
          </div>
          <div class="mini-toolbar">
            <button class="mini-tool-btn" @click="fileInputRef?.click()" title="附加文件">
              <span class="btn-icon">&#128206;</span><span class="btn-label">附件</span>
            </button>
            <button class="mini-tool-btn" @click="showPencilCanvas = true" title="打开画板">
              <span class="btn-icon">🎨</span><span class="btn-label">画板</span>
            </button>
            <el-select
              v-if="platformModels.length > 0"
              v-model="platformModelConfigId"
              size="small"
              class="toolbar-select"
              style="width: 140px"
              placeholder="LLM 配置"
              clearable
            >
              <el-option v-for="m in platformModels" :key="m.id" :value="m.id" :label="m.name" />
            </el-select>
            <el-select v-model="taskForm.model" size="small" class="toolbar-select" style="width: 120px">
              <el-option v-for="opt in claudeModelOptions" :key="opt.value" :value="opt.value" :label="opt.label" />
            </el-select>
            <el-tag v-if="taskForm.maxTurns" size="small" closable @close="taskForm.maxTurns = null">轮次: {{ taskForm.maxTurns }}</el-tag>
          </div>
          <div v-if="attachments.length > 0" class="image-preview-strip compact">
            <div v-for="(att, idx) in attachments" :key="idx" class="image-preview-item small" :class="{ 'file-item': !att.isImage }">
              <template v-if="att.isImage">
                <img :src="att.previewUrl" :alt="att.name" :title="att.name" @click="previewAttachmentIdx = idx" />
                <span class="image-annotate" @click="openAnnotator(idx)" title="标注">🖊️</span>
              </template>
              <div v-else class="file-preview" :title="att.name">
                <span class="file-icon">{{ fileIcon(att.mimeType) }}</span>
                <span class="file-name">{{ att.name }}</span>
              </div>
              <span class="image-remove" @click="removeAttachment(idx)">&times;</span>
            </div>
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
          :agents="allAgentItems"
          :focused-pane-id="focusedPaneId"
          :pane-labels="PANE_LABELS"
          @close="closePane"
          @abort="abortPane"
          @send="handlePaneSend"
          @command="handleSlashCommand"
          @permission-respond="handlePermissionRespond"
          @question-respond="handleQuestionRespond"
          @plan-respond="handlePlanRespond"
          @rewind="handlePaneRewind"
          @reconnect="handlePaneReconnect"
          @forward="handlePaneForward"
          @link-click="handleLinkClick"
          @focus="focusedPaneId = $event"
        >
          <template #header-extra="{ paneState }">
            <span
              v-if="paneInteractionState(paneState)"
              :class="['pane-interaction-tag', paneInteractionState(paneState)!.toLowerCase()]"
            >{{ interactionStateLabel(paneInteractionState(paneState)!) }}</span>
            <el-button
              v-if="paneInteractionState(paneState) === 'AWAITING_REPLY'"
              size="small"
              text
              title="搁置会话"
              @click="handlePaneHold(paneState.task.value?.sessionId)"
            >搁置</el-button>
            <el-button
              v-if="paneInteractionState(paneState) === 'AWAITING_REPLY'"
              size="small"
              text
              title="归档会话"
              @click="handlePaneArchive(paneState.task.value?.sessionId)"
            >归档</el-button>
            <el-button
              v-if="paneInteractionState(paneState) === 'ARCHIVED'"
              size="small"
              text
              title="取消归档会话"
              @click="handlePaneUnarchive(paneState.task.value?.sessionId)"
            >取消归档</el-button>
            <el-button
              v-if="paneInteractionState(paneState) === 'AWAITING_REPLY'"
              size="small"
              text
              type="danger"
              title="删除会话"
              @click="handlePaneDelete(paneState.task.value?.sessionId)"
            >删除</el-button>
            <el-button
              v-if="paneState.task.value?.status === 'FAILED'"
              size="small"
              text
              title="重新同步失败的任务"
              :loading="resyncingTaskId === paneState.task.value?.taskId"
              @click="handlePaneResync(paneState)"
            >重新同步</el-button>
          </template>
        </TaskPaneGrid>
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
    <aside v-if="selectedWorkerId || workerState.activeTasks.value.length > 0" :class="['worker-history', { collapsed: prefs.rightPanelCollapsed }]">
      <div class="history-header">
        <span class="panel-collapse-btn" title="收起历史" @click="prefs.rightPanelCollapsed = true">&raquo;</span>
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
              title="搜索会话"
              @click="showSessionSearch = true"
            >
              &#128269;
            </el-button>
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
      <div class="history-filter-bar">
        <div class="filter-group">
          <span
            :class="['filter-tag', { active: ALL_STATES.every((s) => interactionStateFilters.has(s)) }]"
            @click="toggleAllStates()"
          >全部</span>
          <span
            :class="['filter-tag awaiting', { active: interactionStateFilters.has('AWAITING_REPLY') }]"
            @click="toggleStateFilter('AWAITING_REPLY')"
          >待回复</span>
          <span
            :class="['filter-tag', { active: interactionStateFilters.has('PROCESSING') }]"
            @click="toggleStateFilter('PROCESSING')"
          >处理中</span>
          <span
            :class="['filter-tag', { active: interactionStateFilters.has('ON_HOLD') }]"
            @click="toggleStateFilter('ON_HOLD')"
          >已搁置</span>
          <span
            :class="['filter-tag', { active: interactionStateFilters.has('ARCHIVED') }]"
            @click="toggleStateFilter('ARCHIVED')"
          >已归档</span>
        </div>
        <div class="filter-group">
          <span
            :class="['filter-tag', { active: sessionSourceFilters.has('PLATFORM') }]"
            @click="toggleSourceFilter('PLATFORM')"
          >平台</span>
          <span
            :class="['filter-tag', { active: sessionSourceFilters.has('SYNCED') }]"
            @click="toggleSourceFilter('SYNCED')"
          >同步</span>
          <span
            :class="['filter-tag', { active: ALL_SOURCES.every((s) => sessionSourceFilters.has(s)) }]"
            @click="toggleAllSources()"
          >全部来源</span>
        </div>
      </div>
      <div class="history-content">
        <!-- Global attention section: active tasks + awaiting reply -->
        <div v-if="globalAttentionConvs.length > 0" class="active-sessions-section">
          <div class="active-header" @click="toggleAttentionCollapsed">
            <span class="active-title">
              <span class="expand-icon">{{ prefs.attentionCollapsed ? '▶' : '▼' }}</span>
              &#9679; 需要关注 ({{ globalAttentionConvs.length }})
            </span>
            <el-button size="small" text @click.stop="() => { workerState.loadActiveTasks(); workerState.loadAwaitingReplyTasks() }">&#8635;</el-button>
          </div>
          <template v-if="!prefs.attentionCollapsed">
          <template v-for="item in attentionDisplayList" :key="item.type === 'conv' ? item.conv.sessionId : `wh-${item.workerId}`">
            <!-- Worker group header -->
            <div v-if="item.type === 'worker-header'" class="attention-worker-header">
              <span class="attention-worker-name">{{ item.workerName }}</span>
              <span class="attention-worker-count">({{ item.count }})</span>
            </div>
            <!-- Conversation item -->
            <div
              v-else
              :class="['active-conv-item', { 'conv-pane-focused': item.conv.sessionId === focusedSessionId, 'active-conv-pinned': item.conv.config?.pinned }]"
              @click="navigateToActiveSession(item.conv)"
            >
              <div class="conv-row-1">
                <span
                  v-if="item.conv.config?.pinned"
                  class="conv-pin-icon active"
                  title="已置顶"
                  @click.stop="handleTogglePin(item.conv)"
                >&#128204;</span>
                <span
                  v-if="paneSessionMap.has(item.conv.sessionId)"
                  :class="['sidebar-pane-letter', `pane-letter-${paneSessionMap.get(item.conv.sessionId)!.label.toLowerCase()}`]"
                >{{ paneSessionMap.get(item.conv.sessionId)!.label }}</span>
                <el-tag v-if="item.conv.latestTask.directoryName" size="small" type="info" effect="plain" class="active-dir-tag">{{ item.conv.latestTask.directoryName }}</el-tag>
                <span :class="['conv-interaction-badge', (item.conv.config?.interactionState || 'processing').toLowerCase()]" />
                <span class="active-status-label">{{ attentionStatusLabel(item.conv) }}</span>
              </div>
              <div class="conv-row-1" style="margin-top: 2px;">
                <span class="conv-prompt" :title="item.conv.config?.customTitle || item.conv.firstPrompt">{{ truncate(item.conv.config?.customTitle || item.conv.firstPrompt, 30) }}</span>
              </div>
              <div v-if="item.conv.config?.customTitle" class="conv-row-subtitle">
                <span class="conv-latest-prompt" :title="item.conv.latestTask.prompt">{{ truncate(item.conv.latestTask.prompt, 30) }}</span>
              </div>
              <div class="conv-row-2">
                <el-tag
                  v-if="resolveConversationMilestone(item.conv)"
                  :type="milestoneTagType(resolveConversationMilestone(item.conv)?.status)"
                  size="small"
                  class="conv-tag milestone-tag"
                  :title="resolveConversationMilestone(item.conv)?.docPath || ''"
                >
                  {{ resolveConversationMilestone(item.conv)?.name }}
                </el-tag>
                <el-tag v-for="tag in (item.conv.config?.tags || [])" :key="tag" :type="tagColor(tag)" size="small" class="conv-tag">{{ tag }}</el-tag>
                <span v-if="item.conv.totalCost > 0" class="conv-cost">${{ item.conv.totalCost.toFixed(2) }}</span>
                <span v-if="item.conv.latestTask.durationMs" class="conv-time">{{ Math.round((item.conv.latestTask.durationMs || 0) / 60000) }}min</span>
                <span class="conv-time">{{ formatTime(item.conv.latestTask.createdAt) }}</span>
                <!-- Action buttons -->
                <span class="conv-actions" @click.stop>
                  <el-button
                    v-if="canResumeConversation(item.conv)"
                    type="primary"
                    size="small"
                    text
                    :disabled="isSessionBusy(item.conv)"
                    title="继续对话"
                    @click="handleResumeFromHistory(item.conv.latestTask)"
                  >
                    继续
                  </el-button>
                  <el-button
                    v-if="['RUNNING', 'AWAITING_PERMISSION'].includes(item.conv.latestTask.status)"
                    type="warning"
                    size="small"
                    text
                    title="中止任务"
                    @click="handleAbortTask(item.conv.latestTask.taskId)"
                  >
                    中止
                  </el-button>
                  <el-dropdown trigger="click" @click.stop>
                    <span class="conv-more-trigger" @click.stop>&#8943;</span>
                    <template #dropdown>
                      <el-dropdown-menu>
                        <el-dropdown-item @click="handleTogglePin(item.conv)">
                          {{ item.conv.config?.pinned ? '取消置顶' : '置顶' }}
                        </el-dropdown-item>
                        <el-dropdown-item @click="handleEditTitle(item.conv)">
                          编辑标题
                        </el-dropdown-item>
                        <el-dropdown-item @click="handleEditTags(item.conv)">
                          标签
                        </el-dropdown-item>
                        <el-dropdown-item @click="handleShowDetail(item.conv)">
                          详情
                        </el-dropdown-item>
                        <el-dropdown-item
                          v-if="canRepairContext(item.conv)"
                          @click="handleRepairContext(item.conv)"
                        >
                          修复上下文
                        </el-dropdown-item>
                        <el-dropdown-item
                          v-if="item.conv.config?.interactionState !== 'ARCHIVED' && item.conv.config?.interactionState !== 'ON_HOLD'"
                          @click="handleHoldConversation(item.conv)"
                        >
                          搁置
                        </el-dropdown-item>
                        <el-dropdown-item
                          v-if="item.conv.config?.interactionState === 'ON_HOLD'"
                          @click="handleUnholdConversation(item.conv)"
                        >
                          取消搁置
                        </el-dropdown-item>
                        <el-dropdown-item
                          v-if="item.conv.config?.interactionState !== 'ARCHIVED'"
                          @click="handleArchiveConversation(item.conv)"
                        >
                          归档
                        </el-dropdown-item>
                        <el-dropdown-item
                          v-if="item.conv.config?.interactionState === 'ARCHIVED'"
                          @click="handleUnarchiveConversation(item.conv)"
                        >
                          取消归档
                        </el-dropdown-item>
                        <el-dropdown-item
                          v-if="item.conv.latestTask.status !== 'RUNNING'"
                          divided
                          class="delete-dropdown-item"
                          @click="handleDeleteConversation(item.conv)"
                        >
                          删除
                        </el-dropdown-item>
                      </el-dropdown-menu>
                    </template>
                  </el-dropdown>
                </span>
              </div>
            </div>
          </template>
          </template>
          <div class="active-divider">
            <span class="active-divider-line" />
            <span class="active-divider-text">历史会话</span>
            <span class="active-divider-line" />
          </div>
        </div>
        <!-- Conversation list (shared template for directory / worker-level) -->
        <div v-if="selectedDirectoryId && groupedActiveConversations.length > 0" class="conv-list grouped-conv-list">
          <div v-for="group in groupedActiveConversations" :key="group.key" class="milestone-group">
            <div class="milestone-group-header">
              <div class="milestone-group-main">
                <span class="milestone-group-title">{{ group.label }}</span>
                <el-tag
                  v-if="group.milestone"
                  size="small"
                  :type="milestoneTagType(group.milestone.status)"
                  effect="plain"
                >
                  {{ milestoneStatusLabel(group.milestone.status) }}
                </el-tag>
                <span class="milestone-group-count">{{ group.conversations.length }} 个会话</span>
              </div>
              <code v-if="group.milestone?.docPath" class="milestone-group-doc">{{ group.milestone.docPath }}</code>
            </div>
            <div
              v-for="conv in group.conversations"
              :key="conv.sessionId"
              :class="['conv-item', { 'conv-pinned': conv.config?.pinned, 'conv-selected': batchSelectMode && selectedConvIds.has(conv.sessionId), 'conv-pane-focused': conv.sessionId === focusedSessionId }]"
              @click="batchSelectMode ? toggleConvSelection(conv.sessionId) : viewTask(conv.latestTask)"
            >
              <div class="conv-row-1">
                <span
                  v-if="paneSessionMap.has(conv.sessionId)"
                  :class="['sidebar-pane-letter', `pane-letter-${paneSessionMap.get(conv.sessionId)!.label.toLowerCase()}`]"
                >{{ paneSessionMap.get(conv.sessionId)!.label }}</span>
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
                <span
                  v-if="conv.config?.interactionState"
                  :class="['conv-interaction-badge', conv.config.interactionState.toLowerCase()]"
                  :title="interactionStateLabel(conv.config.interactionState)"
                />
                <span class="conv-prompt" :title="conv.config?.customTitle || conv.firstPrompt">{{
                  truncate(conv.config?.customTitle || conv.firstPrompt, 36)
                }}</span>
                <el-tag
                  v-if="parentConversation(conv)"
                  size="small"
                  type="info"
                  effect="plain"
                  class="conv-rel-tag"
                  @click.stop="viewTask(parentConversation(conv)!.latestTask)"
                >
                  上游
                </el-tag>
                <el-popover
                  v-if="childConversations(conv).length > 0"
                  placement="bottom"
                  trigger="click"
                  width="320"
                >
                  <template #reference>
                    <el-tag size="small" type="success" effect="plain" class="conv-rel-tag" @click.stop>
                      子会话 {{ childConversations(conv).length }}
                    </el-tag>
                  </template>
                  <div class="child-session-popover">
                    <div class="child-session-title">转发出的子会话</div>
                    <div
                      v-for="child in childConversations(conv)"
                      :key="child.sessionId"
                      class="child-session-item"
                      @click="viewTask(child.latestTask)"
                    >
                      <span class="child-session-name" :title="child.config?.customTitle || child.firstPrompt">
                        {{ truncate(child.config?.customTitle || child.firstPrompt, 28) }}
                      </span>
                      <span class="child-session-time">{{ formatTime(child.latestTask.createdAt) }}</span>
                    </div>
                  </div>
                </el-popover>
                <span :class="['conv-status-dot', conv.latestTask.status.toLowerCase()]" :title="conv.latestTask.status" />
              </div>
              <div v-if="conv.config?.customTitle" class="conv-row-subtitle">
                <span class="conv-latest-prompt" :title="conv.latestTask.prompt">{{ truncate(conv.latestTask.prompt, 36) }}</span>
              </div>
              <div class="conv-row-2">
                <el-tag
                  v-if="resolveConversationMilestone(conv)"
                  :type="milestoneTagType(resolveConversationMilestone(conv)?.status)"
                  size="small"
                  class="conv-tag milestone-tag"
                  :title="resolveConversationMilestone(conv)?.docPath || ''"
                >
                  {{ resolveConversationMilestone(conv)?.name }}
                </el-tag>
                <el-tag v-for="tag in (conv.config?.tags || [])" :key="tag" :type="tagColor(tag)" size="small" class="conv-tag">{{ tag }}</el-tag>
                <span v-if="conv.tasks.length > 1" class="conv-rounds">{{ conv.tasks.length }}轮</span>
                <span v-if="conv.latestTask.model" class="conv-model">{{ shortModel(conv.latestTask.model) }}</span>
                <span v-if="conv.totalCost > 0" class="conv-cost">${{ conv.totalCost.toFixed(2) }}</span>
                <span v-if="conv.config?.authBound" class="conv-auth-badge" :title="'Auth: ' + (conv.config.authMode || 'bound')">&#128273;</span>
                <span class="conv-time">{{ formatTime(conv.latestTask.createdAt) }}</span>
                <span class="conv-actions" @click.stop>
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
                        <el-dropdown-item @click="handleEditMilestone(conv)">
                          设置里程碑
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
                          v-if="conv.config?.interactionState !== 'ARCHIVED' && conv.config?.interactionState !== 'ON_HOLD'"
                          @click="handleHoldConversation(conv)"
                        >
                          搁置
                        </el-dropdown-item>
                        <el-dropdown-item
                          v-if="conv.config?.interactionState === 'ON_HOLD'"
                          @click="handleUnholdConversation(conv)"
                        >
                          取消搁置
                        </el-dropdown-item>
                        <el-dropdown-item
                          v-if="conv.config?.interactionState !== 'ARCHIVED'"
                          @click="handleArchiveConversation(conv)"
                        >
                          归档
                        </el-dropdown-item>
                        <el-dropdown-item
                          v-if="conv.config?.interactionState === 'ARCHIVED'"
                          @click="handleUnarchiveConversation(conv)"
                        >
                          取消归档
                        </el-dropdown-item>
                        <el-dropdown-item
                          v-if="canRepairContext(conv)"
                          @click="handleRepairContext(conv)"
                        >
                          修复上下文
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
                          v-if="conv.latestTask.status === 'FAILED'"
                          :disabled="resyncingTaskId === conv.latestTask.taskId"
                          @click="handleResyncFromList(conv)"
                        >
                          {{ resyncingTaskId === conv.latestTask.taskId ? '同步中...' : '重新同步' }}
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
        </div>
        <div
          v-else-if="activeConversations.length > 0"
          class="conv-list"
        >
          <div
            v-for="conv in activeConversations"
            :key="conv.sessionId"
            :class="['conv-item', { 'conv-pinned': conv.config?.pinned, 'conv-selected': batchSelectMode && selectedConvIds.has(conv.sessionId), 'conv-pane-focused': conv.sessionId === focusedSessionId }]"
            @click="batchSelectMode ? toggleConvSelection(conv.sessionId) : viewTask(conv.latestTask)"
          >
            <div class="conv-row-1">
              <span
                v-if="paneSessionMap.has(conv.sessionId)"
                :class="['sidebar-pane-letter', `pane-letter-${paneSessionMap.get(conv.sessionId)!.label.toLowerCase()}`]"
              >{{ paneSessionMap.get(conv.sessionId)!.label }}</span>
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
              <span
                v-if="conv.config?.interactionState"
                :class="['conv-interaction-badge', conv.config.interactionState.toLowerCase()]"
                :title="interactionStateLabel(conv.config.interactionState)"
              />
              <span class="conv-prompt" :title="conv.config?.customTitle || conv.firstPrompt">{{
                truncate(conv.config?.customTitle || conv.firstPrompt, 36)
              }}</span>
              <el-tag
                v-if="parentConversation(conv)"
                size="small"
                type="info"
                effect="plain"
                class="conv-rel-tag"
                @click.stop="viewTask(parentConversation(conv)!.latestTask)"
              >
                上游
              </el-tag>
              <el-popover
                v-if="childConversations(conv).length > 0"
                placement="bottom"
                trigger="click"
                width="320"
              >
                <template #reference>
                  <el-tag size="small" type="success" effect="plain" class="conv-rel-tag" @click.stop>
                    子会话 {{ childConversations(conv).length }}
                  </el-tag>
                </template>
                <div class="child-session-popover">
                  <div class="child-session-title">转发出的子会话</div>
                  <div
                    v-for="child in childConversations(conv)"
                    :key="child.sessionId"
                    class="child-session-item"
                    @click="viewTask(child.latestTask)"
                  >
                    <span class="child-session-name" :title="child.config?.customTitle || child.firstPrompt">
                      {{ truncate(child.config?.customTitle || child.firstPrompt, 28) }}
                    </span>
                    <span class="child-session-time">{{ formatTime(child.latestTask.createdAt) }}</span>
                  </div>
                </div>
              </el-popover>
              <span :class="['conv-status-dot', conv.latestTask.status.toLowerCase()]" :title="conv.latestTask.status" />
            </div>
            <div v-if="conv.config?.customTitle" class="conv-row-subtitle">
              <span class="conv-latest-prompt" :title="conv.latestTask.prompt">{{ truncate(conv.latestTask.prompt, 36) }}</span>
            </div>
            <div class="conv-row-2">
              <el-tag
                v-if="resolveConversationMilestone(conv)"
                :type="milestoneTagType(resolveConversationMilestone(conv)?.status)"
                size="small"
                class="conv-tag milestone-tag"
                :title="resolveConversationMilestone(conv)?.docPath || ''"
              >
                {{ resolveConversationMilestone(conv)?.name }}
              </el-tag>
              <el-tag v-for="tag in (conv.config?.tags || [])" :key="tag" :type="tagColor(tag)" size="small" class="conv-tag">{{ tag }}</el-tag>
              <span v-if="conv.tasks.length > 1" class="conv-rounds">{{ conv.tasks.length }}轮</span>
              <span v-if="conv.latestTask.model" class="conv-model">{{ shortModel(conv.latestTask.model) }}</span>
              <span v-if="conv.totalCost > 0" class="conv-cost">${{ conv.totalCost.toFixed(2) }}</span>
              <span v-if="conv.config?.authBound" class="conv-auth-badge" :title="'Auth: ' + (conv.config.authMode || 'bound')">&#128273;</span>
              <span class="conv-time">{{ formatTime(conv.latestTask.createdAt) }}</span>
              <!-- Visible action buttons -->
              <span class="conv-actions" @click.stop>
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
                      <el-dropdown-item @click="handleEditMilestone(conv)">
                        设置里程碑
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
                        v-if="conv.config?.interactionState !== 'ARCHIVED' && conv.config?.interactionState !== 'ON_HOLD'"
                        @click="handleHoldConversation(conv)"
                      >
                        搁置
                      </el-dropdown-item>
                      <el-dropdown-item
                        v-if="conv.config?.interactionState === 'ON_HOLD'"
                        @click="handleUnholdConversation(conv)"
                      >
                        取消搁置
                      </el-dropdown-item>
                      <el-dropdown-item
                        v-if="conv.config?.interactionState !== 'ARCHIVED'"
                        @click="handleArchiveConversation(conv)"
                      >
                        归档
                      </el-dropdown-item>
                      <el-dropdown-item
                        v-if="conv.config?.interactionState === 'ARCHIVED'"
                        @click="handleUnarchiveConversation(conv)"
                      >
                        取消归档
                      </el-dropdown-item>
                      <el-dropdown-item
                        v-if="canRepairContext(conv)"
                        @click="handleRepairContext(conv)"
                      >
                        修复上下文
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
                        v-if="conv.latestTask.status === 'FAILED'"
                        :disabled="resyncingTaskId === conv.latestTask.taskId"
                        @click="handleResyncFromList(conv)"
                      >
                        {{ resyncingTaskId === conv.latestTask.taskId ? '同步中...' : '重新同步' }}
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
          <el-input v-model="addForm.baseUrl" placeholder="如：http://192.168.1.100:3031" />
        </el-form-item>
        <el-form-item label="认证令牌" required>
          <el-input
            v-model="addForm.authToken"
            type="password"
            show-password
            placeholder="Worker 预共享令牌"
          />
        </el-form-item>
        <template>
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
          <el-divider content-position="left">Code Server（可选）</el-divider>
          <el-form-item label="公网地址">
            <el-input v-model="addForm.codeServerPublicUrl" placeholder="如 https://code.example.com" />
          </el-form-item>
          <el-form-item label="内网地址">
            <el-input v-model="addForm.codeServerInternalUrl" placeholder="如 http://192.168.1.100:18443" />
          </el-form-item>
          <el-form-item label="密码">
            <el-input v-model="addForm.codeServerPassword" type="password" show-password placeholder="code-server 登录密码" />
          </el-form-item>
          <el-form-item label="Folder 前缀">
            <el-input v-model="addForm.codeServerFolderPrefix" placeholder="如 /mnt/{drive}（{drive} 替换为盘符）" />
          </el-form-item>
        </template>
        <el-divider content-position="left">Codex 配置（可选）</el-divider>
        <el-form-item label="Codex 地址">
          <el-input v-model="addForm.codexBaseUrl" placeholder="如：http://localhost:3032" />
        </el-form-item>
        <el-form-item label="Codex 认证令牌">
          <el-input v-model="addForm.codexAuthToken" type="password" show-password placeholder="Codex Worker 令牌" />
        </el-form-item>
        <el-form-item label="Codex 默认模型">
          <el-input v-model="addForm.codexModel" placeholder="如：codex-mini-latest" />
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
        <el-divider content-position="left">Code Server（可选）</el-divider>
        <el-form-item label="公网地址">
          <el-input v-model="editForm.codeServerPublicUrl" placeholder="如 https://code.example.com" />
        </el-form-item>
        <el-form-item label="内网地址">
          <el-input v-model="editForm.codeServerInternalUrl" placeholder="如 http://192.168.1.100:18443" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="editForm.codeServerPassword"
            type="password"
            show-password
            :placeholder="selectedWorkerEntity?.codeServerPasswordConfigured ? '已保存，留空不改' : 'code-server 登录密码'"
          />
        </el-form-item>
        <el-form-item label="Folder 前缀">
          <el-input v-model="editForm.codeServerFolderPrefix" placeholder="如 /mnt/{drive}（{drive} 替换为盘符）" />
        </el-form-item>
        <el-divider content-position="left">Codex 配置（可选）</el-divider>
        <el-form-item label="Codex 地址">
          <el-input v-model="editForm.codexBaseUrl" placeholder="如：http://localhost:3032" />
        </el-form-item>
        <el-form-item label="Codex 认证令牌">
          <el-input
            v-model="editForm.codexAuthToken"
            type="password"
            show-password
            :placeholder="selectedWorkerEntity?.codexAuthTokenConfigured ? '已保存，留空不改' : 'Codex Worker 令牌'"
          />
        </el-form-item>
        <el-form-item label="Codex 默认模型">
          <el-input v-model="editForm.codexModel" placeholder="如：codex-mini-latest" />
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
        <el-form-item label="Agent Teams 配置">
          <div class="agent-teams-configs-panel">
            <div v-if="agentTeamsConfigs.length === 0" class="form-tip" style="margin-bottom: 8px">
              暂无配置，点击下方按钮创建。
            </div>
            <div v-for="cfg in agentTeamsConfigs" :key="cfg.configId" class="atc-row">
              <el-tag v-if="cfg.isDefault" size="small" type="success" style="margin-right: 4px">默认</el-tag>
              <span class="atc-name">{{ cfg.name }}</span>
              <span class="atc-agents">{{ cfg.agentNames.join(', ') }}</span>
              <el-button size="small" link @click="openEditAgentTeamsConfig(cfg)">编辑</el-button>
              <el-button size="small" link type="danger" @click="handleDeleteAgentTeamsConfig(cfg.configId)">删除</el-button>
            </div>
            <el-button size="small" type="primary" @click="openCreateAgentTeamsConfig">+ 新建配置</el-button>
          </div>
          <div class="form-tip">
            JSON 格式定义子 Agent 团队。可创建多套配置，任务启动时选择。
          </div>
        </el-form-item>

        <!-- Agent Teams Config 编辑子对话框 -->
        <el-dialog
          v-model="showAgentTeamsConfigDialog"
          :title="agentTeamsConfigDialogMode === 'create' ? '新建 Agent Teams 配置' : '编辑 Agent Teams 配置'"
          width="520px"
          append-to-body
        >
          <el-form label-position="top">
            <el-form-item label="配置名称" required>
              <el-input v-model="agentTeamsConfigForm.name" placeholder="如：代码审查团队" />
            </el-form-item>
            <el-form-item label="Agent Teams JSON" required>
              <el-input
                v-model="agentTeamsConfigForm.config"
                type="textarea"
                :rows="8"
                placeholder='{"reviewer": {"description": "...", "prompt": "..."}}'
              />
            </el-form-item>
            <el-form-item>
              <el-checkbox v-model="agentTeamsConfigForm.isDefault">设为默认配置</el-checkbox>
            </el-form-item>
          </el-form>
          <template #footer>
            <el-button @click="showAgentTeamsConfigDialog = false">取消</el-button>
            <el-button type="primary" @click="handleSaveAgentTeamsConfig">保存</el-button>
          </template>
        </el-dialog>
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
        <el-divider content-position="left">里程碑</el-divider>
        <div class="form-tip" style="margin-bottom: 12px">
          里程碑已移至独立管理面板。
          <el-button type="primary" text size="small" @click="showEditDirectoryDialog = false; openMilestoneManager()">管理里程碑</el-button>
        </div>
        <el-divider content-position="left">Auth 默认配置</el-divider>
        <el-form-item v-if="platformModels.length > 0" label="LLM 配置">
          <el-select
            v-model="editDirForm.defaultModelConfigId"
            clearable
            placeholder="（不使用平台配置）"
            style="width: 100%"
            @change="onEditDirModelConfigChange"
          >
            <el-option
              v-for="m in platformModels"
              :key="m.id"
              :label="`${m.name} (${m.modelName})`"
              :value="m.id"
            />
          </el-select>
          <div class="form-tip">
            选择平台 LLM 配置后，将使用其对应的后端鉴权与连接配置，无需手动填写。
          </div>
        </el-form-item>
        <template v-if="!editDirForm.defaultModelConfigId">
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
        </template>
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
          <div class="form-tip" style="margin-top: 4px">选择后将作为后续任务的默认 LLM 配置</div>
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

    <el-dialog v-model="showMilestoneDialog" title="设置里程碑" width="460px">
      <el-form :model="milestoneForm" label-position="top">
        <el-form-item label="所属里程碑">
          <el-select v-model="milestoneForm.milestoneId" clearable placeholder="未设置" style="width: 100%">
            <el-option
              v-for="milestone in milestoneDialogOptions"
              :key="milestone.id"
              :label="`${milestone.name} (${milestoneStatusLabel(milestone.status)})`"
              :value="milestone.id"
            />
          </el-select>
          <div class="form-tip">
            里程碑定义来自当前会话所属的工作目录；清空后该会话会归入“未设置里程碑”。
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showMilestoneDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSaveMilestone">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="showForwardDialog" :title="forwardDialogTitle" width="640px">
      <el-form :model="forwardForm" label-position="top">
        <el-form-item label="源回复">
          <div class="forward-source-preview">{{ forwardSourcePreview || '暂无内容' }}</div>
        </el-form-item>
        <el-form-item label="转发方式">
          <el-radio-group v-model="forwardForm.targetMode">
            <el-radio-button label="NEW_SESSION" value="NEW_SESSION">创建新会话</el-radio-button>
            <el-radio-button
              label="EXISTING_SESSION"
              value="EXISTING_SESSION"
              :disabled="forwardExistingTargets.length === 0"
            >
              转发到已有会话
            </el-radio-button>
          </el-radio-group>
          <div v-if="forwardExistingTargets.length === 0" class="form-tip">
            仅支持转发到之前由当前会话转发创建过的子会话。
          </div>
        </el-form-item>
        <el-form-item label="转发内容">
          <el-input
            v-model="forwardForm.prompt"
            type="textarea"
            :rows="8"
            :placeholder="forwardForm.targetMode === 'EXISTING_SESSION'
              ? '可直接转发当前回复，也可以先修改后再追加到已有会话'
              : '可直接转发当前回复，也可以先修改后再创建新会话'"
          />
        </el-form-item>
        <template v-if="forwardForm.targetMode === 'NEW_SESSION'">
          <el-row :gutter="12">
            <el-col :span="12">
              <el-form-item label="Worker">
                <el-select v-model="forwardForm.workerId" style="width: 100%" placeholder="选择 Worker">
                  <el-option
                    v-for="worker in workerState.workers.value"
                    :key="worker.workerId"
                    :label="worker.name"
                    :value="worker.workerId"
                  />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="工作目录">
                <el-select v-model="forwardForm.directoryId" clearable style="width: 100%" placeholder="选择工作目录">
                  <el-option
                    v-for="dir in forwardDirectories"
                    :key="dir.directoryId"
                    :label="dir.projectName + (dir.gitBranch ? ' (' + dir.gitBranch + ')' : '')"
                    :value="dir.directoryId"
                  />
                </el-select>
              </el-form-item>
            </el-col>
          </el-row>
          <el-form-item label="工作路径">
            <el-input v-model="forwardForm.cwd" placeholder="未选择目录时可手动输入路径" />
          </el-form-item>
          <el-row :gutter="12">
            <el-col :span="12">
              <el-form-item label="LLM 配置">
                <el-select v-model="forwardForm.modelConfigId" clearable style="width: 100%" placeholder="使用目录默认配置">
                  <el-option
                    v-for="modelConfig in forwardPlatformModels"
                    :key="modelConfig.id"
                    :label="modelConfig.name"
                    :value="modelConfig.id"
                  />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="模型">
                <el-select v-model="forwardForm.model" style="width: 100%" placeholder="选择模型">
                  <el-option
                    v-for="modelOption in forwardModelOptions"
                    :key="modelOption.value"
                    :label="modelOption.label"
                    :value="modelOption.value"
                  />
                </el-select>
              </el-form-item>
            </el-col>
          </el-row>
          <el-row :gutter="12">
            <el-col :span="12">
              <el-form-item label="权限模式">
                <el-select v-model="forwardForm.permissionMode" style="width: 100%">
                  <el-option label="默认" value="default" />
                  <el-option label="接受编辑" value="acceptEdits" />
                  <el-option label="放行权限" value="bypassPermissions" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="里程碑">
                <el-select
                  v-model="forwardForm.milestoneId"
                  clearable
                  style="width: 100%"
                  placeholder="不带里程碑"
                  :disabled="forwardMilestoneOptions.length === 0"
                >
                  <el-option
                    v-for="milestone in forwardMilestoneOptions"
                    :key="milestone.id"
                    :label="`${milestone.name} (${milestoneStatusLabel(milestone.status)})`"
                    :value="milestone.id"
                  />
                </el-select>
                <div class="form-tip">
                  同目录默认继承原会话里程碑；切换到其他目录后可重新指定。
                </div>
              </el-form-item>
            </el-col>
          </el-row>
        </template>
        <template v-else>
          <el-form-item label="目标会话">
            <el-select v-model="forwardForm.targetSessionId" style="width: 100%" placeholder="选择已转发子会话">
              <el-option
                v-for="conv in forwardExistingTargets"
                :key="conv.sessionId"
                :label="forwardConversationOptionLabel(conv)"
                :value="conv.sessionId"
              />
            </el-select>
          </el-form-item>
          <div v-if="forwardSelectedExistingConversation" class="forward-target-summary">
            <div class="forward-target-row">
              <span class="forward-target-label">会话</span>
              <span class="forward-target-value">{{ forwardSelectedExistingConversation.firstPrompt || '-' }}</span>
            </div>
            <div class="forward-target-row">
              <span class="forward-target-label">Worker</span>
              <span class="forward-target-value">{{ forwardExistingWorkerName }}</span>
            </div>
            <div class="forward-target-row">
              <span class="forward-target-label">目录</span>
              <span class="forward-target-value">{{ forwardExistingDirectoryName }}</span>
            </div>
            <div class="forward-target-row">
              <span class="forward-target-label">Provider</span>
              <span class="forward-target-value">{{ forwardExistingProviderLabel }}</span>
            </div>
            <div class="forward-target-row">
              <span class="forward-target-label">LLM 配置</span>
              <span class="forward-target-value">{{ forwardExistingModelConfigLabel }}</span>
            </div>
            <div class="forward-target-row">
              <span class="forward-target-label">模型</span>
              <span class="forward-target-value">{{ forwardExistingModelLabel }}</span>
            </div>
            <div class="forward-target-row">
              <span class="forward-target-label">里程碑</span>
              <span class="forward-target-value">{{ forwardExistingMilestoneLabel }}</span>
            </div>
          </div>
          <div v-else class="form-tip">
            请选择已有子会话，目录、Provider、模型配置会沿用目标会话当前上下文。
          </div>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="showForwardDialog = false">取消</el-button>
        <el-button
          type="primary"
          :loading="forwardSubmitting"
          :disabled="forwardSubmitDisabled"
          @click="handleSubmitForward"
        >
          {{ forwardSubmitButtonText }}
        </el-button>
      </template>
    </el-dialog>

    <!-- Milestone Management Dialog -->
    <el-dialog v-model="showMilestoneManageDialog" title="管理里程碑" width="600px">
      <div class="milestone-manage-list">
        <div v-if="milestoneManageList.length === 0" class="form-tip" style="margin-bottom: 8px">
          暂无里程碑。可按版本建立如 v3.0.0、1.0.0-SNAPSHOT。
        </div>
        <div v-for="ms in milestoneManageList" :key="ms.id" class="milestone-manage-row">
          <template v-if="milestoneEditingId === ms.id">
            <el-input v-model="milestoneEditForm.name" placeholder="名称" size="small" />
            <el-select v-model="milestoneEditForm.status" size="small" style="width: 110px">
              <el-option label="规划中" value="PLANNED" />
              <el-option label="进行中" value="ACTIVE" />
              <el-option label="已完成" value="COMPLETED" />
              <el-option label="已归档" value="ARCHIVED" />
            </el-select>
            <el-input v-model="milestoneEditForm.docPath" placeholder="docs/version-tracker/..." size="small" />
            <el-button size="small" type="primary" :loading="saving" @click="handleSaveMilestoneEdit(ms.id)">保存</el-button>
            <el-button size="small" @click="milestoneEditingId = ''">取消</el-button>
          </template>
          <template v-else>
            <span class="ms-name">{{ ms.name }}</span>
            <el-tag size="small" :type="milestoneTagType(ms.status)">{{ milestoneStatusLabel(ms.status) }}</el-tag>
            <code v-if="ms.docPath" class="ms-doc">{{ ms.docPath }}</code>
            <span class="ms-actions">
              <el-button size="small" text @click="startEditMilestone(ms)">编辑</el-button>
              <el-button size="small" text type="danger" :loading="milestoneDeleting === ms.id" @click="handleDeleteMilestoneManage(ms)">删除</el-button>
            </span>
          </template>
        </div>
      </div>
      <div style="margin-top: 12px">
        <template v-if="milestoneAddMode">
          <div class="milestone-manage-row">
            <el-input v-model="milestoneAddForm.name" placeholder="名称" size="small" />
            <el-select v-model="milestoneAddForm.status" size="small" style="width: 110px">
              <el-option label="规划中" value="PLANNED" />
              <el-option label="进行中" value="ACTIVE" />
              <el-option label="已完成" value="COMPLETED" />
              <el-option label="已归档" value="ARCHIVED" />
            </el-select>
            <el-input v-model="milestoneAddForm.docPath" placeholder="docs/version-tracker/..." size="small" />
            <el-button size="small" type="primary" :loading="saving" @click="handleAddMilestoneManage">保存</el-button>
            <el-button size="small" @click="milestoneAddMode = false">取消</el-button>
          </div>
        </template>
        <el-button v-else size="small" type="primary" plain @click="milestoneAddMode = true; milestoneAddForm = { name: '', status: 'PLANNED', docPath: '' }">+ 添加里程碑</el-button>
      </div>
      <template #footer>
        <el-button @click="showMilestoneManageDialog = false">关闭</el-button>
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
        <el-descriptions-item label="里程碑">
          {{ resolveConversationMilestone(detailConv)?.name || '未设置' }}
        </el-descriptions-item>
        <el-descriptions-item label="Auth 模式">
          {{ detailConv.config?.authBound ? detailAuthModeLabel : '未绑定' }}
        </el-descriptions-item>
        <el-descriptions-item v-if="resolveConversationMilestone(detailConv)?.docPath" label="文档目录" :span="2">
          <code>{{ resolveConversationMilestone(detailConv)?.docPath }}</code>
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

    <!-- Context Repair / Compact Dialog (修复上下文 / 手动压缩) -->
    <el-dialog v-model="contextRepairVisible" title="修复上下文" width="560px">
      <div v-if="contextRepairFetching" style="text-align: center; padding: 24px 0">
        <el-icon class="is-loading" :size="20"><Loading /></el-icon>
        <span style="margin-left: 8px; color: #909399">正在获取会话信息…</span>
      </div>
      <template v-else>
        <p style="margin-bottom: 12px; color: #606266">
          会话共 <strong>{{ contextRepairInfo.totalMessages }}</strong> 条消息
          （用户 {{ contextRepairInfo.userCount }} / 助手 {{ contextRepairInfo.assistantCount }}），
          将全部压缩为一条续接摘要。
        </p>

        <!-- Original task (auto-extracted) -->
        <details open style="margin-bottom: 12px">
          <summary style="font-weight: 500; cursor: pointer; margin-bottom: 4px">原始任务（自动提取）</summary>
          <div style="background: #f5f7fa; border-radius: 4px; padding: 8px 12px; font-size: 13px; max-height: 120px; overflow-y: auto; white-space: pre-wrap; word-break: break-word">
            {{ contextRepairInfo.originalTask || '（未提取到）' }}
          </div>
        </details>

        <!-- Recent conversation (auto-extracted) -->
        <details open style="margin-bottom: 12px">
          <summary style="font-weight: 500; cursor: pointer; margin-bottom: 4px">最近对话（自动提取）</summary>
          <div style="background: #f5f7fa; border-radius: 4px; padding: 8px 12px; font-size: 13px; max-height: 200px; overflow-y: auto">
            <div
              v-for="(msg, idx) in contextRepairInfo.recentMessages"
              :key="idx"
              style="margin-bottom: 6px; white-space: pre-wrap; word-break: break-word"
            >
              <strong :style="{ color: msg.role === 'user' ? '#409EFF' : '#67C23A' }">
                [{{ msg.role === 'user' ? 'User' : 'Assistant' }}]
              </strong>
              {{ msg.content }}
            </div>
            <div v-if="contextRepairInfo.recentMessages.length === 0" style="color: #909399">
              （未提取到最近对话）
            </div>
          </div>
        </details>

        <!-- Continuation prompt (editable) -->
        <p style="margin-bottom: 8px; font-weight: 500">续接提示词：</p>
        <el-input
          v-model="contextRepairPrompt"
          type="textarea"
          :rows="8"
          placeholder="描述之前的工作进度和接下来要做的事…"
        />
      </template>
      <template #footer>
        <el-button @click="contextRepairVisible = false">取消</el-button>
        <el-button
          type="primary"
          :disabled="contextRepairFetching || !contextRepairPrompt.trim()"
          :loading="contextRepairLoading"
          @click="executeContextRepair"
        >
          确认压缩并继续
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
        <el-form-item v-if="platformModels.length > 0" label="默认 LLM 配置">
          <el-select v-model="agentForm.defaultModelConfigId" style="width: 100%" placeholder="使用目录默认配置" clearable>
            <el-option v-for="m in platformModels" :key="m.id" :value="m.id" :label="m.name" />
          </el-select>
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
        <el-form-item v-if="platformModels.length > 0" label="默认 LLM 配置">
          <el-select v-model="agentForm.defaultModelConfigId" style="width: 100%" placeholder="使用目录默认配置" clearable>
            <el-option v-for="m in platformModels" :key="m.id" :value="m.id" :label="m.name" />
          </el-select>
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
        <el-option label="规划" value="规划" />
        <el-option label="开发" value="开发" />
        <el-option label="测试" value="测试" />
        <el-option label="用户体验" value="用户体验" />
        <el-option label="代码评审" value="代码评审" />
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

    <!-- Session Search Dialog -->
    <SessionSearchDialog
      :visible="showSessionSearch"
      :workers="workerState.workers.value"
      :directories="workerState.directories.value"
      @close="showSessionSearch = false"
      @select="handleSearchSelect"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, triggerRef, computed, reactive, onMounted, onUnmounted, onActivated, onDeactivated, watch, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowDown, Back, Loading } from '@element-plus/icons-vue'
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
  getAllPanes,
  suspendOtherWorkspaces,
  resumeWorkspace,
  isDirectorySynced,
  markDirectorySynced,
  restoreTerminalTabs,
} from '@/composables/useWorkspaceContext'
import type { WorkspaceContext, SshTerminalTab } from '@/composables/useWorkspaceContext'
import TaskPaneGrid from '@/components/worker/TaskPaneGrid.vue'
import SshTerminalPanel from '@/components/worker/SshTerminalPanel.vue'
import SshTerminal from '@/components/worker/SshTerminal.vue'
import SlashCommandInput from '@/components/worker/SlashCommandInput.vue'
import SessionSearchDialog from '@/components/worker/SessionSearchDialog.vue'
import PencilCanvas from '@/components/ipad/PencilCanvas.vue'
import ScreenshotAnnotator from '@/components/ipad/ScreenshotAnnotator.vue'
import { useForwardSession, type ConversationGroup as ForwardConversationGroup } from '@/composables/useForwardSession'
import { useSessionFullscreen } from '@/composables/useSessionFullscreen'
import { useUserPreferences } from '@/composables/useUserPreferences'
import * as dirApi from '@/api/claudeWorker'
import {
  reconnectTaskUnified,
  resyncTaskUnified,
  rewindTaskUnified,
  scanCheckpointsUnified,
  searchSessionsUnified,
  listTasksByDirectoryUnified,
  listTasksByDirectoryPagedUnified,
} from '@/api/unifiedTask'
import * as sshApi from '@/api/ssh'
import { searchFiles } from '@/api/fileBrowser'
import { listAgentModelOverrides, listModelConfigs } from '@/api/platform'
import * as agentApi from '@/api/codingAgent'
import { resolveChatLinkTarget } from '@/utils/chatLinkResolver'
import type { ClaudeTask, WorkingDirectory, SkillInfo, ConversationConfig, LlmModelConfig, CodingAgent, DirectorySummary, AgentTeamsConfig, SessionSearchResult, CliProcessListResponse, DirectoryMilestone } from '@/types'
import type { AipMessageType, ChatMessage } from '@foggy/chat'

const MAX_PANES = 1

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
const agentForm = ref({ name: '', description: '', defaultDirectoryId: '', defaultBranch: '', projectSummary: '', defaultModelConfigId: '' })
const generatingSummary = ref(false)
const bindDirectoryId = ref('')

const agentsForWorker = computed(() =>
  agentState.agents.value.filter(a => a.workerId === selectedWorkerId.value),
)

/** All agents as lightweight items for @mention panel */
const allAgentItems = computed(() =>
  agentState.agents.value.map(a => ({
    agentId: a.agentId,
    name: a.name,
    description: a.description,
  })),
)

/** Per-pane @agent consultation context — consumed on next resume */
const paneAgentContext = ref<Map<string, Array<{ agentName: string; question: string; answer: string }>>>(new Map())

/** Per-pane per-agent contextId tracking for multi-turn A2A conversations */
const paneAgentContextIds = ref<Map<string, Map<string, string>>>(new Map())

// --- Worker tabs state (Agents / CLI Processes) ---
const workerActiveTab = ref('processes')
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
    const workerId = selectedWorkerId.value
    const worker = selectedWorkerEntity.value
    const requests: Array<{
      type: 'claude' | 'codex'
      promise: Promise<CliProcessListResponse>
    }> = [
      {
        type: 'claude',
        promise: dirApi.listCliProcesses(workerId),
      },
    ]

    if (worker?.codexBaseUrl?.trim()) {
      requests.push({
        type: 'codex',
        promise: dirApi.listCodexCliProcesses(workerId, { suppressErrorMessage: true }),
      })
    }

    const results = await Promise.allSettled(requests.map(request => request.promise))

    const merged: CliProcessInfo[] = []
    let activeTaskCount = 0
    const failedTypes: Array<'claude' | 'codex'> = []

    results.forEach((result, index) => {
      const request = requests[index]
      if (!request) return

      if (result.status === 'fulfilled') {
        merged.push(...result.value.processes.map(processInfo => ({
          ...processInfo,
          process_type: processInfo.process_type || request.type,
        })))
        activeTaskCount += result.value.active_task_count
        return
      }

      failedTypes.push(request.type)
    })

    cliProcesses.value = merged.sort((a, b) => b.pid - a.pid)
    cliProcessActiveTaskCount.value = activeTaskCount

    if (merged.length === 0 && failedTypes.length === requests.length) {
      const labels = failedTypes.map(type => (type === 'codex' ? 'Codex' : 'Claude')).join(' / ')
      ElMessage.error(`获取 ${labels} CLI 进程失败`)
    }
  } catch {
    cliProcesses.value = []
    cliProcessActiveTaskCount.value = 0
  } finally {
    loadingProcesses.value = false
  }
}

async function handleKillProcess(processInfo: CliProcessInfo, force = false) {
  if (!selectedWorkerId.value) return
  const action = force ? '强制终止' : '终止'
  const typeLabel = processInfo.process_type === 'codex' ? 'Codex CLI' : 'Claude CLI'
  try {
    await ElMessageBox.confirm(
      `确定要${action}${typeLabel} 进程 PID ${processInfo.pid} 吗？`,
      `${action}进程`,
      { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return // cancelled
  }
  try {
    const result = processInfo.process_type === 'codex'
      ? await dirApi.killCodexCliProcess(selectedWorkerId.value, processInfo.pid, force)
      : await dirApi.killCliProcess(selectedWorkerId.value, processInfo.pid, force)
    if (result.status === 'killed') {
      ElMessage.success(`进程 ${processInfo.pid} 已${action}`)
    } else {
      ElMessage.warning(result.message)
    }
    await loadCliProcesses()
  } catch (e: unknown) {
    ElMessage.error(`${action}失败: ${e instanceof Error ? e.message : e}`)
  }
}

/** 重新同步已失败但 CLI 还在运行的任务 */
async function handleResyncTask(taskId: string) {
  if (!selectedWorkerId.value) return
  try {
    await ElMessageBox.confirm(
      '确定要重新同步此任务吗？\n\n这将恢复任务状态并拉取 CLI 产生的新事件。',
      '重新同步任务',
      { confirmButtonText: '确定', cancelButtonText: '取消', type: 'info' },
    )
  } catch {
    return // cancelled
  }
  try {
    const result = await resyncTaskUnified(taskId) as Record<string, unknown>
    if (result?.status === 'RESYNCED') {
      ElMessage.success('任务已重新同步')
      // 不需要刷新进程列表，因为任务状态改变不影响 CLI 进程
    }
  } catch (e: unknown) {
    ElMessage.error(`重新同步失败: ${e instanceof Error ? e.message : e}`)
  }
}

/** 返回孤儿进程距自动杀死的剩余时间描述，如 "8分钟后自动关闭" */
function orphanCountdown(process: CliProcessInfo): string {
  if (!process.orphan_auto_kill_at) return '孤儿'
  const msLeft = new Date(process.orphan_auto_kill_at).getTime() - Date.now()
  if (msLeft <= 0) return '即将关闭'
  const minsLeft = Math.ceil(msLeft / 60000)
  return `${minsLeft}分钟后自动关闭`
}

/** 孤儿进程 tooltip 内容 */
function orphanTooltip(process: CliProcessInfo): string {
  const firstSeen = process.orphan_first_seen_at
    ? new Date(process.orphan_first_seen_at).toLocaleString()
    : '-'
  const autoKill = process.orphan_auto_kill_at
    ? new Date(process.orphan_auto_kill_at).toLocaleString()
    : '-'
  return `首次发现：${firstSeen}\n预计自动关闭：${autoKill}`
}

/** Format model name to short display form (e.g. "claude-sonnet-4-20250514" → "Sonnet 4") */
function formatModelShort(model: string): string {
  if (!model) return '-'
  const lower = model.toLowerCase()
  if (lower.includes('opus')) return 'Opus'
  if (lower.includes('sonnet')) {
    const m4 = lower.match(/sonnet[- ]?(\d)/)
    return m4 ? `Sonnet ${m4[1]}` : 'Sonnet'
  }
  if (lower.includes('haiku')) return 'Haiku'
  // Fallback: return last meaningful segment
  const parts = model.split('-')
  return parts.length > 1 ? parts.slice(0, 2).join('-') : model
}

function navigateToProcessSession(process: CliProcessInfo) {
  if (!process.foggy_session_id) return
  const task = workerState.tasks.value.find(
    (t) => t.sessionId === process.foggy_session_id,
  )
  if (task) {
    if (task.directoryId && task.directoryId !== selectedDirectoryId.value) {
      selectDirectory(task.workerId, task.directoryId)
    }
    viewTask(task)
  } else {
    ElMessage.info('未找到关联的会话记录')
  }
}

const selectedWorkerId = ref<string | null>(null)
const selectedDirectoryId = ref<string | null>(null)

// ── Agent-centric navigation ──
// selectedAgentId is the primary key for task operations (create/resume/respond).
// resolvedWorkerId derives workerId from the agent when available, falling back to selectedWorkerId.
const selectedAgentId = ref<string | null>(null)
const resolvedWorkerId = computed(() => {
  if (selectedAgentId.value) {
    const agent = agentState.agents.value.find(a => a.agentId === selectedAgentId.value)
    return agent?.workerId || selectedWorkerId.value
  }
  return selectedWorkerId.value
})
const expandedWorkerIds = reactive(new Set<string>())
const expandedProjectIds = reactive(new Set<string>())
const { prefs } = useUserPreferences()

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

// Milestone dialog state (session-level)
const showMilestoneDialog = ref(false)
const milestoneDialogSessionId = ref('')
const milestoneDialogOptions = ref<DirectoryMilestone[]>([])
const milestoneForm = ref({ milestoneId: '' })

// Milestone management dialog state (directory-level)
const showMilestoneManageDialog = ref(false)
const milestoneManageList = ref<DirectoryMilestone[]>([])
const milestoneEditingId = ref('')
const milestoneEditForm = ref({ name: '', status: '', docPath: '' })
const milestoneAddMode = ref(false)
const milestoneAddForm = ref({ name: '', status: 'PLANNED', docPath: '' })
const milestoneDeleting = ref('')

// Session search dialog state
const showSessionSearch = ref(false)

// Batch selection state
const batchSelectMode = ref(false)
const selectedConvIds = ref<Set<string>>(new Set())

// Multi-select filter: interaction states (default: 待回复 + 处理中)
const ALL_STATES = ['AWAITING_REPLY', 'PROCESSING', 'ON_HOLD', 'ARCHIVED'] as const
const interactionStateFilters = ref<Set<string>>(new Set(['AWAITING_REPLY', 'PROCESSING']))

// Multi-select filter: session sources (default: 平台)
const ALL_SOURCES = ['PLATFORM', 'SYNCED'] as const
const sessionSourceFilters = ref<Set<string>>(new Set(['PLATFORM']))

/** Toggle a state filter value */
function toggleStateFilter(value: string) {
  const s = new Set(interactionStateFilters.value)
  if (s.has(value)) {
    if (s.size > 1) s.delete(value) // 至少保留一个选中
  } else {
    s.add(value)
  }
  interactionStateFilters.value = s
}
/** Select all state filters */
function toggleAllStates() {
  if (ALL_STATES.every((v) => interactionStateFilters.value.has(v))) return
  interactionStateFilters.value = new Set(ALL_STATES)
}
/** Toggle a source filter value */
function toggleSourceFilter(value: string) {
  const s = new Set(sessionSourceFilters.value)
  if (s.has(value)) {
    if (s.size > 1) s.delete(value)
  } else {
    s.add(value)
  }
  sessionSourceFilters.value = s
}
/** Select all source filters */
function toggleAllSources() {
  if (ALL_SOURCES.every((v) => sessionSourceFilters.value.has(v))) return
  sessionSourceFilters.value = new Set(ALL_SOURCES)
}

/** Compute the backend state param from the current multi-select filter */
function currentStateParam(): string | undefined {
  // 全部选中 → 无过滤
  if (ALL_STATES.every((s) => interactionStateFilters.value.has(s))) return undefined
  return [...interactionStateFilters.value].join(',')
}

/** Reload worker-level tasks respecting the current filter */
function reloadWorkerTasks() {
  return workerState.loadTasks(currentStateParam())
}

/** Reload history tasks using the current interactionState filter */
function reloadFilteredTasks() {
  if (selectedDirectoryId.value) {
    dirTaskPage.value = 0
    loadDirectoryTasks()
  } else {
    workerState.loadTasksPage(0, undefined, currentStateParam())
  }
}

// When state filter changes, reload from page 0 with new filter
watch(interactionStateFilters, () => {
  reloadFilteredTasks()
}, { deep: true })

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

// Context repair / compact dialog state (修复上下文 / 手动压缩)
const contextRepairVisible = ref(false)
const contextRepairFetching = ref(false)
const contextRepairLoading = ref(false)
const contextRepairTaskId = ref('')
const contextRepairPrompt = ref('')
const contextRepairInfo = ref<{
  userCount: number
  assistantCount: number
  totalMessages: number
  maxTurn: number
  originalTask: string
  recentMessages: { role: string; content: string }[]
}>({ userCount: 0, assistantCount: 0, totalMessages: 0, maxTurn: 0, originalTask: '', recentMessages: [] })
const contextRepairConv = ref<ConversationGroup | null>(null)

// Scan checkpoints state
const scanningTaskId = ref('')

// Resync state
const resyncingTaskId = ref('')

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

// --- Pane label + focus tracking ---
const PANE_LABELS = ['A', 'B', 'C', 'D']
const focusedPaneId = ref<string | null>(null)

const paneSessionMap = computed(() => {
  const map = new Map<string, { label: string; index: number }>()
  panes.value.forEach((p, i) => {
    const sid = p.task.value?.sessionId
    if (sid) map.set(sid, { label: PANE_LABELS[i]!, index: i })
  })
  return map
})

const focusedSessionId = computed(() => {
  const pane = panes.value.find(p => p.paneId === focusedPaneId.value)
  return pane?.task.value?.sessionId ?? null
})

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
  codeServerPublicUrl: '',
  codeServerInternalUrl: '',
  codeServerPassword: '',
  codeServerFolderPrefix: '',
  codexBaseUrl: '',
  codexAuthToken: '',
  codexModel: '',
})

const editForm = ref({
  name: '',
  baseUrl: '',
  authToken: '',
  authMode: 'SUBSCRIPTION',
  sshUsername: '',
  sshPort: 22 as number,
  sshPassword: '',
  codeServerPublicUrl: '',
  codeServerInternalUrl: '',
  codeServerPassword: '',
  codeServerFolderPrefix: '',
  codexBaseUrl: '',
  codexAuthToken: '',
  codexModel: '',
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
  defaultModelConfigId: '' as string,
})

const ALL_MODELS: { value: string; label: string; backend: string }[] = [
  // Claude models
  { value: 'opus[1m]', label: 'Opus (1M)', backend: 'CLAUDE_CODE' },
  { value: 'opus', label: 'Opus', backend: 'CLAUDE_CODE' },
  { value: 'sonnet[1m]', label: 'Sonnet (1M)', backend: 'CLAUDE_CODE' },
  { value: 'sonnet', label: 'Sonnet', backend: 'CLAUDE_CODE' },
  { value: 'haiku', label: 'Haiku', backend: 'CLAUDE_CODE' },
  // Codex models
  { value: 'gpt-5.4:low', label: '5.4 Low', backend: 'OPENAI_CODEX' },
  { value: 'gpt-5.4', label: '5.4 Medium', backend: 'OPENAI_CODEX' },
  { value: 'gpt-5.4:high', label: '5.4 High', backend: 'OPENAI_CODEX' },
  { value: 'gpt-5.4:extra-high', label: '5.4 Extra High', backend: 'OPENAI_CODEX' },
  { value: 'gpt-5.4-mini', label: '5.4 Mini', backend: 'OPENAI_CODEX' },
  { value: 'gpt-5.3-codex', label: '5.3 Codex', backend: 'OPENAI_CODEX' },
  { value: 'gpt-5.3-codex-spark', label: 'Spark', backend: 'OPENAI_CODEX' },
]

// ── Forward Session composable ──
const forwardState = useForwardSession({
  workerState,
  directoryTasks,
  groupTasksToConversations,
  resolveConversationMilestone,
  shortModel,
  milestoneStatusLabel,
  formatTime,
  ALL_MODELS,
})
const {
  showForwardDialog, forwardSubmitting, forwardForm, forwardSource, forwardPlatformModels,
  forwardSourcePreview, forwardDialogTitle, forwardConversationPool, forwardExistingTargets,
  forwardSelectedExistingConversation, forwardDirectories, forwardSelectedDirectory,
  forwardMilestoneOptions, forwardSelectedModelConfig, forwardModelOptions,
  forwardExistingWorkerName, forwardExistingDirectoryName, forwardExistingProviderLabel,
  forwardExistingModelConfigLabel, forwardExistingModelLabel, forwardExistingMilestoneLabel,
  forwardSubmitButtonText, forwardSubmitDisabled,
  openForwardDialog, submitForward, resetForwardDialog, forwardConversationOptionLabel,
} = forwardState

const creatingTask = ref(false)
const taskForm = ref({
  prompt: '',
  cwd: '',
  model: 'opus[1m]' as string,
  maxTurns: null as number | null,
  permissionMode: 'bypassPermissions' as string,
})

// --- Agent Teams 多配置支持 ---
const agentTeamsConfigs = ref<AgentTeamsConfig[]>([])
const selectedAgentTeamsConfigId = ref<string | null>(null)
const showAgentTeamsConfigDialog = ref(false)
const agentTeamsConfigDialogMode = ref<'create' | 'edit'>('create')
const agentTeamsConfigForm = ref({
  configId: '',
  name: '',
  config: '',
  isDefault: false,
})

// --- 平台模型配置（供任务创建时选择 API 凭证） ---
const platformModels = ref<LlmModelConfig[]>([])
const platformModelConfigId = ref('')
const platformModelConfig = computed(() =>
  platformModels.value.find((m) => m.id === platformModelConfigId.value) || null,
)

function providerTypeFromWorkerBackend(workerBackend?: string | null): string | undefined {
  if (workerBackend === 'OPENAI_CODEX') return 'codex-worker'
  if (workerBackend === 'CLAUDE_CODE') return 'claude-worker'
  return undefined
}

function providerTypeFromAgentType(agentType?: string | null): string | undefined {
  if (agentType === 'LOCAL_CODEX_WORKER') return 'codex-worker'
  if (agentType === 'LOCAL_CLAUDE_WORKER') return 'claude-worker'
  return undefined
}

const selectedAgentProviderType = computed(() => {
  if (!selectedAgentId.value) return undefined
  const agent = agentState.agents.value.find(a => a.agentId === selectedAgentId.value)
  return providerTypeFromAgentType(agent?.agentType)
})

// --- Per-worker LLM 选择缓存：切换 Worker 时记住每个 Worker 的 API 凭证 + 模型选择 ---
const LLM_CACHE_KEY = 'foggy:workerLlmSelection'
type LlmSelection = { apiConfigId: string; model: string }

function loadLlmCacheFromStorage(): Map<string, LlmSelection> {
  try {
    const raw = localStorage.getItem(LLM_CACHE_KEY)
    if (raw) return new Map(Object.entries(JSON.parse(raw)))
  } catch { /* ignore */ }
  return new Map()
}

function persistLlmCache() {
  try {
    localStorage.setItem(LLM_CACHE_KEY, JSON.stringify(Object.fromEntries(workerLlmSelectionCache)))
  } catch { /* ignore */ }
}

const workerLlmSelectionCache = loadLlmCacheFromStorage()
let suppressModelAutoSelect = false
let loadPlatformModelConfigSeq = 0 // 防止异步竞态：只有最新一次调用的结果才生效
let sessionRestoreVersion = 0 // 会话级恢复版本号：防止 loadPlatformModelConfig 异步覆盖会话模型

// --- Per-session 模型缓存：task.model 会被 CLI 响应覆盖为完整模型名，无法映射回下拉选项 ---
// 在发送任务时记录 sessionId → 用户实际选择的短模型名，恢复时优先使用
const SESSION_MODEL_CACHE_KEY = 'foggy:sessionModelSelection'
const sessionModelCache: Map<string, string> = (() => {
  try {
    const raw = localStorage.getItem(SESSION_MODEL_CACHE_KEY)
    if (raw) return new Map(Object.entries(JSON.parse(raw)))
  } catch { /* ignore */ }
  return new Map()
})()
function saveSessionModel(sessionId: string | undefined | null, model: string) {
  if (!sessionId || !model) return
  sessionModelCache.set(sessionId, model)
  try {
    // 限制缓存大小，保留最近 200 条
    if (sessionModelCache.size > 200) {
      const keys = [...sessionModelCache.keys()]
      for (let i = 0; i < keys.length - 200; i++) sessionModelCache.delete(keys[i]!)
    }
    localStorage.setItem(SESSION_MODEL_CACHE_KEY, JSON.stringify(Object.fromEntries(sessionModelCache)))
  } catch { /* ignore */ }
}

function saveWorkerLlmSelection(workerId: string | null) {
  if (!workerId || !platformModelConfigId.value) return
  workerLlmSelectionCache.set(workerId, {
    apiConfigId: platformModelConfigId.value,
    model: taskForm.value.model,
  })
  persistLlmCache()
}

function restoreWorkerLlmSelection(workerId: string | null): boolean {
  if (!workerId) return false
  const cached = workerLlmSelectionCache.get(workerId)
  if (!cached) return false
  // 仅当缓存的 API 凭证仍在可用列表中时才恢复
  if (platformModels.value.some((m) => m.id === cached.apiConfigId)) {
    suppressModelAutoSelect = true
    platformModelConfigId.value = cached.apiConfigId
    // Vue 3 computed 同步更新，claudeModelOptions 此时已是最新值，可直接恢复模型
    const opts = claudeModelOptions.value
    if (opts.some((o) => o.value === cached.model)) {
      taskForm.value.model = cached.model
    }
    suppressModelAutoSelect = false
    return true
  }
  return false
}

// --- 根据平台模型配置过滤可用模型 ---
function isSelectablePlatformModel(model: LlmModelConfig): boolean {
  return model.hasApiKey || model.workerBackend === 'OPENAI_CODEX'
}


const claudeModelOptions = computed(() => {
  const backend = platformModelConfig.value?.workerBackend ?? 'CLAUDE_CODE'
  const backendModels = ALL_MODELS.filter(m => m.backend === backend)
  const allowed = platformModelConfig.value?.availableModels
  if (!allowed || allowed.length === 0) return backendModels
  return backendModels.filter(opt => allowed.includes(opt.value))
})

// 当可用模型列表变化时，若当前选中的模型不在列表中则自动回退到第一个
watch(claudeModelOptions, (opts) => {
  if (suppressModelAutoSelect) return
  if (opts.length > 0 && !opts.some(o => o.value === taskForm.value.model)) {
    taskForm.value.model = opts[0].value
  }
})

// 切换 LLM 配置时，仅当当前模型不在新配置可用列表中时才回退到默认模型，并持久化选择
watch(platformModelConfigId, () => {
  if (suppressModelAutoSelect) return
  const opts = claudeModelOptions.value
  if (opts.length > 0 && !opts.some(o => o.value === taskForm.value.model)) {
    taskForm.value.model = opts[0].value
  }
  // 用户手动切换配置时立即持久化（suppress 时是恢复缓存，不需要重复保存）
  saveWorkerLlmSelection(selectedWorkerId.value)
})

// 用户手动切换模型时立即持久化
watch(() => taskForm.value.model, () => {
  if (suppressModelAutoSelect) return
  saveWorkerLlmSelection(selectedWorkerId.value)
})

/**
 * 切换会话时，根据会话最新任务的 modelConfigId 和 model 恢复表单选择。
 * 这样点击不同会话时，"API: xxx" 和 "模型: xxx" 标签会跟随切换。
 */
function restoreSessionModelSelection(task: ClaudeTask) {
  sessionRestoreVersion++ // 递增版本号，使进行中的 loadPlatformModelConfig 跳过模型选择
  // 优先使用 modelConfigId 恢复 API 配置
  const configId = task.modelConfigId
  if (configId && platformModels.value.some((m) => m.id === configId)) {
    suppressModelAutoSelect = true
    platformModelConfigId.value = configId
    // 恢复模型：优先 per-session 缓存（用户实际选择），再尝试 task.model 匹配
    const opts = claudeModelOptions.value
    const sessionId = task.sessionId || ''
    const cachedModel = sessionModelCache.get(sessionId)
    if (cachedModel && opts.some((o) => o.value === cachedModel)) {
      taskForm.value.model = cachedModel
    } else {
      const taskModel = task.model || ''
      if (taskModel && opts.some((o) => o.value === taskModel)) {
        taskForm.value.model = taskModel
      } else {
        // 尝试用 shortModel 匹配（task.model 可能是完整名如 "claude-opus-4-20250514"）
        const matched = opts.find((o) => taskModel.includes(o.value.replace(/\[.*\]/, '')))
        if (matched) {
          taskForm.value.model = matched.value
        } else if (opts.length > 0) {
          taskForm.value.model = opts[0].value
        }
      }
    }
    suppressModelAutoSelect = false
    // 同步更新 per-worker 缓存
    saveWorkerLlmSelection(selectedWorkerId.value)
  }
}

async function loadPlatformModelConfig() {
  const seq = ++loadPlatformModelConfigSeq
  const restoreVer = sessionRestoreVersion // 捕获当前会话恢复版本
  try {
    const [overrides, models] = await Promise.all([
      listAgentModelOverrides(),
      listModelConfigs(selectedWorkerId.value || undefined),
    ])
    // 防止竞态：如果在 await 期间又发起了新的调用，丢弃本次过期结果
    if (seq !== loadPlatformModelConfigSeq) return
    platformModels.value = models.filter(isSelectablePlatformModel)
    // 如果在 await 期间已发生会话级恢复，跳过模型选择（会话优先级高于 Worker 级默认）
    if (restoreVer !== sessionRestoreVersion) return
    // 优先从 per-worker 缓存恢复选择
    if (restoreWorkerLlmSelection(selectedWorkerId.value)) {
      return
    }
    // 自动选择 claude-worker 的 agent-model override（如有）
    const override = overrides.find((o) => o.agentId === 'claude-worker')
    if (override && platformModels.value.some((m) => m.id === override.modelConfigId)) {
      platformModelConfigId.value = override.modelConfigId
    } else if (platformModels.value.length > 0) {
      platformModelConfigId.value = platformModels.value[0]!.id
    } else {
      platformModelConfigId.value = ''
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

function providerTypeLabel(providerType?: string): string {
  if (providerType === 'claude-worker') return 'Claude Worker'
  if (providerType === 'codex-worker') return 'Codex Worker'
  return providerType || '-'
}



function dirBranchById(dirId?: string): string | undefined {
  if (!dirId) return undefined
  return workerState.directories.value.find(d => d.directoryId === dirId)?.gitBranch || undefined
}

function openAgentRegisterDialog() {
  agentForm.value = { name: '', description: '', defaultDirectoryId: '', defaultBranch: '', projectSummary: '', defaultModelConfigId: '' }
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
    defaultModelConfigId: agent.defaultModelConfigId || '',
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
      defaultModelConfigId: agentForm.value.defaultModelConfigId || undefined,
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
      defaultModelConfigId: agentForm.value.defaultModelConfigId ?? undefined,
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

// --- Attachment state (images + files) ---
interface Attachment {
  name: string
  base64: string
  mimeType: string
  previewUrl: string
  size: number  // original file size in bytes
  isImage: boolean
}
const attachments = ref<Attachment[]>([])
const fileInputRef = ref<HTMLInputElement | null>(null)

// --- Session fullscreen + iPad tools ---
const { isSessionFullscreen, toggle: toggleFullscreen, exit: exitFullscreen } = useSessionFullscreen()
const showPencilCanvas = ref(false)
const showAnnotator = ref(false)
const annotatorImageUrl = ref('')
const annotatorImageIndex = ref(0)
const previewAttachmentIdx = ref(-1)

function openAnnotator(idx: number) {
  const img = attachments.value[idx]
  if (!img || !img.isImage) return
  annotatorImageUrl.value = img.previewUrl
  annotatorImageIndex.value = idx
  showAnnotator.value = true
}

async function handleCanvasSave(blob: Blob) {
  showPencilCanvas.value = false
  const file = new File([blob], `drawing-${Date.now()}.webp`, { type: 'image/webp' })
  const attachment = await compressImage(file)
  attachments.value.push(attachment)
}

async function handleAnnotatorSave(blob: Blob, idx: number) {
  showAnnotator.value = false
  const file = new File([blob], `annotated-${Date.now()}.webp`, { type: 'image/webp' })
  const attachment = await compressImage(file)
  // Replace the original image at the same index
  const old = attachments.value[idx]
  if (old) URL.revokeObjectURL(old.previewUrl)
  attachments.value.splice(idx, 1, attachment)
}

// Refs for SlashCommandInput instances (used by insertDotSlash)
const dirFullInputRef = ref<InstanceType<typeof SlashCommandInput> | null>(null)
const dirMiniInputRef = ref<InstanceType<typeof SlashCommandInput> | null>(null)

/** Insert "./" at cursor position to trigger file search */
function insertDotSlash(comp: InstanceType<typeof SlashCommandInput> | null) {
  if (!comp) return
  const textarea = comp.getTextareaEl()
  if (!textarea) return
  const start = textarea.selectionStart ?? textarea.value.length
  const text = taskForm.value.prompt
  const newText = text.slice(0, start) + './' + text.slice(start)
  taskForm.value.prompt = newText
  nextTick(() => {
    textarea.focus()
    const newCursor = start + 2
    textarea.setSelectionRange(newCursor, newCursor)
  })
}
const MAX_ATTACHMENTS = 10
const MAX_IMAGE_SIZE = 50 * 1024 * 1024  // 50MB per image before compression (iPad screenshots can be 10-30MB)
const MAX_FILE_SIZE = 20 * 1024 * 1024   // 20MB per non-image file (no compression)

async function compressImage(file: File): Promise<Attachment> {
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
              isImage: true,
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

async function readFileAsBase64(file: File): Promise<Attachment> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => {
      const base64 = (reader.result as string).split(',')[1]!
      resolve({
        name: file.name,
        base64,
        mimeType: file.type || 'application/octet-stream',
        previewUrl: '',
        size: file.size,
        isImage: false,
      })
    }
    reader.onerror = reject
    reader.readAsDataURL(file)
  })
}

function fileIcon(mimeType: string): string {
  if (mimeType.includes('pdf')) return '📑'
  if (mimeType.includes('zip') || mimeType.includes('tar') || mimeType.includes('gzip') || mimeType.includes('compressed')) return '📦'
  if (mimeType.includes('sheet') || mimeType.includes('csv')) return '📊'
  return '📄'
}

async function addFiles(files: FileList | File[]) {
  for (const file of Array.from(files)) {
    if (attachments.value.length >= MAX_ATTACHMENTS) {
      ElMessage.warning(`最多附加 ${MAX_ATTACHMENTS} 个文件`)
      break
    }
    const isImage = file.type.startsWith('image/')
    const maxSize = isImage ? MAX_IMAGE_SIZE : MAX_FILE_SIZE
    if (file.size > maxSize) {
      ElMessage.warning(`"${file.name}" 超过 ${isImage ? '50MB' : '20MB'}，已跳过`)
      continue
    }
    try {
      const attachment = isImage ? await compressImage(file) : await readFileAsBase64(file)
      attachments.value.push(attachment)
    } catch {
      ElMessage.warning(`"${file.name}" 处理失败`)
    }
  }
}

function removeAttachment(index: number) {
  const removed = attachments.value.splice(index, 1)
  removed.forEach((img) => URL.revokeObjectURL(img.previewUrl))
}

function handlePaste(e: ClipboardEvent) {
  const items = e.clipboardData?.items
  if (!items) return
  const pastedFiles: File[] = []
  for (const item of Array.from(items)) {
    if (item.kind === 'file') {
      const file = item.getAsFile()
      if (file) {
        if (file.type.startsWith('image/')) {
          // Generate a meaningful name for clipboard images (usually screenshots)
          const ext = file.type.split('/')[1] || 'png'
          pastedFiles.push(new File([file], `screenshot-${Date.now()}.${ext}`, { type: file.type }))
        } else {
          pastedFiles.push(file)
        }
      }
    }
  }
  if (pastedFiles.length > 0) {
    e.preventDefault()
    addFiles(pastedFiles)
  }
}

function handleDrop(e: DragEvent) {
  e.preventDefault()
  const files = e.dataTransfer?.files
  if (files && files.length > 0) {
    addFiles(files)
  }
}

function handleDragOver(e: DragEvent) {
  e.preventDefault()
}

function handleFileSelect(e: Event) {
  const input = e.target as HTMLInputElement
  if (input.files && input.files.length > 0) {
    addFiles(input.files)
    input.value = '' // reset so same file can be selected again
  }
}

function clearAttachments(preserveUrls?: Set<string>) {
  attachments.value.forEach((img) => {
    if (img.previewUrl && (!preserveUrls || !preserveUrls.has(img.previewUrl))) {
      URL.revokeObjectURL(img.previewUrl)
    }
  })
  attachments.value = []
}

const selectedWorkerEntity = computed(() => {
  return workerState.workers.value.find((w) => w.workerId === selectedWorkerId.value)
})

const selectedDirectory = computed(() =>
  workerState.directories.value.find((d) => d.directoryId === selectedDirectoryId.value),
)

const parsedAgentTeams = computed(() => {
  if (selectedAgentTeamsConfigId.value) {
    const config = agentTeamsConfigs.value.find(c => c.configId === selectedAgentTeamsConfigId.value)
    return config?.agentNames ?? []
  }
  // Legacy fallback
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
  parentSessionId?: string
  claudeSessionId: string
  codexThreadId: string
  latestTask: ClaudeTask
  tasks: ClaudeTask[]
  totalCost: number
  firstPrompt: string
  config?: ConversationConfig
}

interface MilestoneConversationGroup {
  key: string
  label: string
  milestone?: DirectoryMilestone
  conversations: ConversationGroup[]
}

/** 会话是否可以 resume（有 sessionId 即可，provider 内部状态由后端恢复） */
function getTaskResumeRef(task: Pick<ClaudeTask, 'sessionId'>): string {
  return task.sessionId || ''
}

function getConversationResumeRef(conv: ConversationGroup): string {
  return conv.sessionId || ''
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
      parentSessionId: tasks[0]!.parentSessionId,
      claudeSessionId: tasks.find((t) => t.claudeSessionId)?.claudeSessionId || '',
      codexThreadId: tasks.find((t) => t.codexThreadId)?.codexThreadId || '',
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
const allConversations = computed(() =>
  selectedDirectoryId.value ? directoryConversations.value : workerConversations.value,
)
const conversationBySessionId = computed(() => {
  const map = new Map<string, ConversationGroup>()
  for (const conv of allConversations.value) {
    map.set(conv.sessionId, conv)
  }
  return map
})
const childConversationMap = computed(() => {
  const map = new Map<string, ConversationGroup[]>()
  for (const conv of allConversations.value) {
    if (!conv.parentSessionId) continue
    const existing = map.get(conv.parentSessionId)
    if (existing) {
      existing.push(conv)
    } else {
      map.set(conv.parentSessionId, [conv])
    }
  }
  for (const children of map.values()) {
    children.sort((a, b) =>
      new Date(b.latestTask.createdAt).getTime() - new Date(a.latestTask.createdAt).getTime(),
    )
  }
  return map
})

function childConversations(conv: ConversationGroup): ConversationGroup[] {
  return childConversationMap.value.get(conv.sessionId) || []
}

function parentConversation(conv: ConversationGroup): ConversationGroup | undefined {
  return conv.parentSessionId ? conversationBySessionId.value.get(conv.parentSessionId) : undefined
}

const activeConversations = computed(() => {
  let list = allConversations.value

  // Source filter (client-side, multi-select)
  const allSourcesSelected = ALL_SOURCES.every((s) => sessionSourceFilters.value.has(s))
  if (!allSourcesSelected) {
    list = list.filter((conv) => {
      const isPlatform = conv.tasks.some((t) =>
        t.source === 'PLATFORM' || (t.source == null && t.fileCheckpointingEnabled === true),
      )
      if (sessionSourceFilters.value.has('PLATFORM') && !sessionSourceFilters.value.has('SYNCED')) return isPlatform
      if (sessionSourceFilters.value.has('SYNCED') && !sessionSourceFilters.value.has('PLATFORM')) return !isPlatform
      return true
    })
  }

  // interactionState filtering is done by the backend API (multi-select via comma-separated param)

  return list
})

const directoryMilestoneMap = computed(() => {
  const map = new Map<string, DirectoryMilestone[]>()
  for (const dir of workerState.directories.value) {
    if (dir.milestones?.length) map.set(dir.directoryId, dir.milestones)
  }
  return map
})

function milestonesForDirectory(directoryId?: string): DirectoryMilestone[] {
  if (!directoryId) return []
  return directoryMilestoneMap.value.get(directoryId) || []
}

const conversationMilestoneMap = computed(() => {
  const cache = new Map<string, DirectoryMilestone>()
  for (const conv of activeConversations.value) {
    const milestoneId = conv.config?.milestoneId
    if (!milestoneId) continue
    const milestone = milestonesForDirectory(conv.latestTask.directoryId).find(m => m.id === milestoneId)
    if (milestone) cache.set(conv.sessionId, milestone)
  }
  return cache
})

function resolveConversationMilestone(conv: ConversationGroup): DirectoryMilestone | undefined {
  return conversationMilestoneMap.value.get(conv.sessionId)
}

const groupedActiveConversations = computed<MilestoneConversationGroup[]>(() => {
  if (!selectedDirectoryId.value) return []
  const milestoneOrder = new Map(
    (selectedDirectory.value?.milestones || []).map((milestone, index) => [milestone.id, index]),
  )
  const grouped = new Map<string, MilestoneConversationGroup>()
  for (const conv of activeConversations.value) {
    const milestone = resolveConversationMilestone(conv)
    const key = milestone?.id || '__none__'
    const existing = grouped.get(key)
    if (existing) {
      existing.conversations.push(conv)
    } else {
      grouped.set(key, {
        key,
        label: milestone?.name || '未设置里程碑',
        milestone,
        conversations: [conv],
      })
    }
  }
  return Array.from(grouped.values()).sort((left, right) => {
    if (left.key === '__none__') return 1
    if (right.key === '__none__') return -1
    return (milestoneOrder.get(left.key) ?? Number.MAX_SAFE_INTEGER)
      - (milestoneOrder.get(right.key) ?? Number.MAX_SAFE_INTEGER)
  })
})

// Active sessions: group activeTasks into ConversationGroups
const activeSessionConvs = computed(() => groupTasksToConversations(workerState.activeTasks.value))

// AWAITING_REPLY conversations: global (not filtered by worker), from backend API
const awaitingReplyConvs = computed(() => {
  const activeSessionIds = new Set(activeSessionConvs.value.map(c => c.sessionId))
  // Use the new awaitingReplyTasks from backend API (global, not worker-filtered)
  const awaitingConvs = groupTasksToConversations(workerState.awaitingReplyTasks.value)
  // Exclude sessions that are already in activeSessionConvs to avoid duplicates
  return awaitingConvs.filter(conv => !activeSessionIds.has(conv.sessionId))
})

// Combined "needs attention" section: pinned + active tasks + awaiting reply (excluding ON_HOLD)
const globalAttentionConvs = computed(() => {
  const activeAndAwaiting = [...activeSessionConvs.value, ...awaitingReplyConvs.value]
    .filter(conv => {
      // Exclude ON_HOLD sessions from attention (even if they have active tasks)
      const state = workerState.conversationConfigs.value.get(conv.sessionId)?.interactionState
      return state !== 'ON_HOLD'
    })
  const existingIds = new Set(activeAndAwaiting.map(c => c.sessionId))
  // Add pinned conversations from history that are not already in active/awaiting
  const pinnedFromHistory = allConversations.value.filter(
    c => c.config?.pinned && !existingIds.has(c.sessionId) && c.config?.interactionState !== 'ON_HOLD',
  )
  // Pinned first (sorted by pinnedAt desc), then active+awaiting
  return [...pinnedFromHistory, ...activeAndAwaiting]
})

// --- Attention list: pinned flat + grouped by worker ---

type AttentionDisplayItem =
  | { type: 'conv'; conv: ConversationGroup }
  | { type: 'worker-header'; workerId: string; workerName: string; count: number }

/** Pinned conversations in the attention list (flat, no grouping) */
const attentionPinnedConvs = computed<ConversationGroup[]>(() => {
  return globalAttentionConvs.value.filter(c => c.config?.pinned)
})

/** Non-pinned conversations grouped by workerId, sorted by most recent activity */
const attentionWorkerGroups = computed(() => {
  const nonPinned = globalAttentionConvs.value.filter(c => !c.config?.pinned)

  const groupMap = new Map<string, ConversationGroup[]>()
  for (const conv of nonPinned) {
    const wid = conv.latestTask.workerId || '__unknown__'
    const existing = groupMap.get(wid)
    if (existing) {
      existing.push(conv)
    } else {
      groupMap.set(wid, [conv])
    }
  }

  const groups: { workerId: string; workerName: string; conversations: ConversationGroup[]; latestActivity: number }[] = []
  for (const [wid, convs] of groupMap) {
    const worker = workerState.workers.value.find(w => w.workerId === wid)
    const latestActivity = Math.max(...convs.map(c => new Date(c.latestTask.createdAt).getTime()))
    groups.push({
      workerId: wid,
      workerName: worker?.name || '未知',
      conversations: convs,
      latestActivity,
    })
  }

  groups.sort((a, b) => b.latestActivity - a.latestActivity)
  return groups
})

/** Flattened display list: pinned items + (worker-header + conversations)... */
const attentionDisplayList = computed<AttentionDisplayItem[]>(() => {
  const items: AttentionDisplayItem[] = []

  // Pinned items first (no group header)
  for (const conv of attentionPinnedConvs.value) {
    items.push({ type: 'conv', conv })
  }

  // Worker groups with headers
  for (const wg of attentionWorkerGroups.value) {
    items.push({
      type: 'worker-header',
      workerId: wg.workerId,
      workerName: wg.workerName,
      count: wg.conversations.length,
    })
    for (const conv of wg.conversations) {
      items.push({ type: 'conv', conv })
    }
  }

  return items
})

// Set of provider session refs that currently have a RUNNING task (for concurrency protection)
const runningConversationRefs = computed(() => {
  const refs = new Set<string>()
  for (const task of workerState.activeTasks.value) {
    if (task.status !== 'RUNNING') continue
    const ref = getTaskResumeRef(task)
    if (ref) {
      refs.add(ref)
    }
  }
  return refs
})

function canResumeConversation(conv: ConversationGroup): boolean {
  return !['RUNNING', 'AWAITING_PERMISSION'].includes(conv.latestTask.status)
    && !!getConversationResumeRef(conv)
}

/** Check if a conversation session is currently busy (has a RUNNING task) */
function isSessionBusy(conv: ConversationGroup): boolean {
  const ref = getConversationResumeRef(conv)
  return !!ref && runningConversationRefs.value.has(ref)
}

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

function toggleAttentionCollapsed() {
  prefs.attentionCollapsed = !prefs.attentionCollapsed
}

// Active tasks polling (fallback) + SSE-driven refresh
let activeTasksInterval: ReturnType<typeof setInterval> | null = null

function handleTaskUpdateEvent(event: Event) {
  const detail = (event as CustomEvent).detail
  if (['task_status_change', 'task_completion'].includes(detail?.type) && detail?.taskId) {
    const newStatus = detail.status as string
    // Sync interactionState from SSE
    if (detail.sessionId && detail.interactionState) {
      workerState.updateInteractionStateFromSSE(detail.sessionId, detail.interactionState)
    }
    if (['RUNNING', 'AWAITING_PERMISSION'].includes(newStatus)) {
      // Active state — update in-place or refresh list
      const existing = workerState.activeTasks.value.find(t => t.taskId === detail.taskId)
      if (existing) {
        existing.status = newStatus as any
      } else {
        workerState.loadActiveTasks()
        workerState.loadAwaitingReplyTasks()
      }
      // Also sync to history task list — prevents status mismatch between
      // attention section (from activeTasks) and history list (from tasks)
      const inTasks = workerState.tasks.value.find(t => t.taskId === detail.taskId)
      if (inTasks) {
        inTasks.status = newStatus as any
      }
    } else {
      // Terminal state — remove from active list + update task list
      workerState.activeTasks.value = workerState.activeTasks.value.filter(
        t => t.taskId !== detail.taskId
      )
      const inTasks = workerState.tasks.value.find(t => t.taskId === detail.taskId)
      if (inTasks) {
        inTasks.status = newStatus as any
        if (detail.errorMessage) {
          inTasks.errorMessage = detail.errorMessage
        } else if (newStatus === 'FAILED' && detail.summary) {
          inTasks.errorMessage = String(detail.summary)
        }
      }
      workerState.loadAwaitingReplyTasks()
    }
  } else {
    workerState.loadActiveTasks() // Fallback for legacy format
    workerState.loadAwaitingReplyTasks()
  }
}

function handleNotificationReconnected() {
  workerState.loadActiveTasks()
  workerState.loadAwaitingReplyTasks()
}

onMounted(async () => {
  window.addEventListener('message', handleFileBrowserMessage)
  document.addEventListener('click', closeFavScriptCtx)
  // Listen for SSE-driven task updates (from useNotifications)
  window.addEventListener('task-update', handleTaskUpdateEvent)
  window.addEventListener('notification-reconnected', handleNotificationReconnected)
  await Promise.all([workerState.loadWorkers(), reloadWorkerTasks(), workerState.loadActiveTasks(), workerState.loadAwaitingReplyTasks(), loadPlatformModelConfig(), agentState.loadAgents()])
  // Load conversation configs for all loaded tasks (including active and awaiting reply)
  const allSessionIds = [
    ...workerState.tasks.value.map((t) => t.sessionId),
    ...workerState.activeTasks.value.map((t) => t.sessionId),
    ...workerState.awaitingReplyTasks.value.map((t) => t.sessionId),
  ]
  const sessionIds = [...new Set(allSessionIds)]
  if (sessionIds.length > 0) {
    await workerState.loadConversationConfigs(sessionIds)
  }
  // Start polling active tasks every 30s (fallback for SSE disconnects)
  activeTasksInterval = setInterval(async () => {
    // 1. Sync task lists from backend
    await Promise.all([workerState.loadActiveTasks(), workerState.loadAwaitingReplyTasks()])
    // 2. Sync open pane task statuses (catches missed SSE task_status_change events)
    for (const pane of getAllPanes()) {
      pane.syncTaskStatus()
    }
    // 3. Refresh interactionState for active + awaiting sessions (catches missed SSE interactionState updates)
    const sessionIds = [
      ...workerState.activeTasks.value.map((t) => t.sessionId),
      ...workerState.awaitingReplyTasks.value.map((t) => t.sessionId),
    ]
    const unique = [...new Set(sessionIds)]
    if (unique.length > 0) {
      workerState.loadConversationConfigs(unique)
    }
  }, 30000)
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

// keep-alive: suspend all SSE when navigating away to free browser connections
onDeactivated(() => {
  suspendOtherWorkspaces(null) // null = suspend ALL workspaces
  if (activeTasksInterval) {
    clearInterval(activeTasksInterval)
    activeTasksInterval = null
  }
})

// keep-alive: re-sync all pane task statuses + resume SSE when view is activated
onActivated(() => {
  // Resume SSE for the currently active workspace
  const key = activeWorkspaceKey.value
  if (key) {
    resumeWorkspace(key)
  }
  for (const pane of getAllPanes()) {
    pane.syncTaskStatus()
  }
  workerState.loadActiveTasks()
  workerState.loadAwaitingReplyTasks()
  if (selectedDirectoryId.value) {
    loadDirectoryTasks()
  }
  // 重新激活时刷新模型列表，确保 Worker 授权变更后下拉框数据是最新的
  loadPlatformModelConfig()
  // Restart polling
  if (!activeTasksInterval) {
    activeTasksInterval = setInterval(() => {
      workerState.loadActiveTasks()
      workerState.loadAwaitingReplyTasks()
    }, 30000)
  }
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
  reloadWorkerTasks()
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

/** 处理搜索结果选中：构造最小 ConversationGroup 并跳转 */
function handleSearchSelect(result: SessionSearchResult) {
  showSessionSearch.value = false
  const minimalTask = {
    taskId: result.latestTaskId,
    sessionId: result.sessionId,
    workerId: result.workerId,
    directoryId: result.directoryId,
    prompt: result.firstPrompt,
    status: result.latestStatus,
    createdAt: result.createdAt,
    updatedAt: result.updatedAt,
  } as ClaudeTask
  navigateToActiveSession({
    sessionId: result.sessionId,
    parentSessionId: result.parentSessionId,
    claudeSessionId: '',
    codexThreadId: '',
    latestTask: minimalTask,
    tasks: [minimalTask],
    totalCost: result.totalCost || 0,
    firstPrompt: result.firstPrompt,
    config: result.customTitle
      ? ({
          sessionId: result.sessionId,
          customTitle: result.customTitle,
          pinned: false,
          authBound: false,
          interactionState: result.interactionState,
        } as ConversationConfig)
      : undefined,
  })
}

function selectWorker(workerId: string) {
  // 保存当前 Worker 的 LLM 选择，切回时恢复
  saveWorkerLlmSelection(selectedWorkerId.value)
  selectedWorkerId.value = workerId
  selectedDirectoryId.value = null
  selectedAgentId.value = null
  directorySkills.value = []
  cliProcesses.value = []
  workerActiveTab.value = 'processes'
  focusedPaneId.value = null
  exitBatchSelectMode()

  // Load CLI processes when selecting a worker
  loadCliProcesses()
  // Suspend SSE on other workspaces to free browser connections (HTTP/1.1 limit: 6)
  const newKey = `worker:${workerId}`
  suspendOtherWorkspaces(newKey)
  resumeWorkspace(newKey)
  // Auto-expand
  if (!expandedWorkerIds.has(workerId)) {
    expandedWorkerIds.add(workerId)
    workerState.loadDirectories(workerId)
  }
  // 切换 Worker 时刷新可用模型列表
  loadPlatformModelConfig()
}

function selectDirectory(workerId: string, directoryId: string) {
  const previousWorkerId = selectedWorkerId.value
  selectedWorkerId.value = workerId
  selectedDirectoryId.value = directoryId
  // Auto-resolve agent: 直接从目录数据获取（后端已批量解析）
  const dir = workerState.directories.value.find(d => d.directoryId === directoryId)
  selectedAgentId.value = dir?.agentId ?? null
  focusedPaneId.value = null
  exitBatchSelectMode()
  // 切换到不同 Worker 的目录时，保存旧 Worker 选择并刷新可用模型列表
  if (workerId !== previousWorkerId) {
    saveWorkerLlmSelection(previousWorkerId)
    loadPlatformModelConfig()
  }
  // Suspend SSE on other workspaces to free browser connections (HTTP/1.1 limit: 6)
  suspendOtherWorkspaces(directoryId)
  resumeWorkspace(directoryId)
  taskForm.value.prompt = ''
  taskForm.value.cwd = ''
  dirTaskPage.value = 0
  loadDirectoryTasks()
  loadDirectorySkills()
  loadAgentTeamsConfigs()
  // Auto-sync SSH sessions from backend (once per directory per page load)
  syncSshSessions()
  // 切换目录后，恢复新 workspace 已有 pane 的会话模型选择
  const restoreVer = sessionRestoreVersion
  nextTick(() => {
    // 如果在 nextTick 之前 viewTask 已执行了会话级恢复，跳过以免覆盖
    if (restoreVer !== sessionRestoreVersion) return
    const ws = getOrCreateWorkspace(directoryId)
    const targetPane = ws.panes.value[0]
    if (targetPane?.task.value) {
      restoreSessionModelSelection(targetPane.task.value)
    } else {
      // 无活跃 pane，从 per-worker 缓存恢复模型选择
      restoreWorkerLlmSelection(workerId)
    }
  })
}

async function loadDirectoryTasks() {
  if (!selectedDirectoryId.value) return
  const stateParam = currentStateParam()
  try {
    const result = await listTasksByDirectoryPagedUnified(
      selectedDirectoryId.value,
      dirTaskPage.value,
      dirTaskSize.value,
      stateParam,
    )
    directoryTasks.value = result.content
    dirTaskTotal.value = result.totalSessions
  } catch {
    try {
      const unified = await listTasksByDirectoryUnified(selectedDirectoryId.value)
      directoryTasks.value = unified as unknown as ClaudeTask[]
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

const defaultAddForm = () => ({ name: '', baseUrl: '', authToken: '', authMode: 'SUBSCRIPTION', sshUsername: '', sshPort: 22 as number, sshPassword: '', codeServerPublicUrl: '', codeServerInternalUrl: '', codeServerPassword: '', codeServerFolderPrefix: '', codexBaseUrl: '', codexAuthToken: '', codexModel: '' })

async function handleAdd() {
  if (!addForm.value.name || !addForm.value.baseUrl || !addForm.value.authToken) {
    ElMessage.warning('请填写完整信息')
    return
  }
  saving.value = true
  try {
    const { codexBaseUrl, codexAuthToken, codexModel, ...baseForm } = addForm.value
    await workerState.registerWorker({
      ...baseForm,
      ...(codexBaseUrl ? { codexConfig: { baseUrl: codexBaseUrl, authToken: codexAuthToken || undefined, model: codexModel || undefined } } : {}),
    })
    showAddDialog.value = false
    addForm.value = defaultAddForm()
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
      codeServerPublicUrl: editForm.value.codeServerPublicUrl || null,
      codeServerInternalUrl: editForm.value.codeServerInternalUrl || null,
      codeServerFolderPrefix: editForm.value.codeServerFolderPrefix || null,
    }
    // 密码类字段：有值才发送，空串不发（避免清空已保存的密码）
    if (editForm.value.authToken) {
      form.authToken = editForm.value.authToken
    }
    if (editForm.value.sshPassword) {
      form.sshPassword = editForm.value.sshPassword
    }
    if (editForm.value.codeServerPassword) {
      form.codeServerPassword = editForm.value.codeServerPassword
    }
    // codexConfig：有值 → 发送完整对象；原来有值现在清空 → 发送 {baseUrl:''} 清除
    const { codexBaseUrl, codexAuthToken, codexModel } = editForm.value
    if (codexBaseUrl) {
      form.codexConfig = { baseUrl: codexBaseUrl, authToken: codexAuthToken || undefined, model: codexModel || undefined }
    } else if (selectedWorkerEntity.value?.codexBaseUrl) {
      form.codexConfig = { baseUrl: '' }
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
    disposeWorkspace(`worker:${selectedWorkerId.value}`)
    const dirs = workerState.directories.value.filter(d => d.workerId === selectedWorkerId.value)
    for (const dir of dirs) {
      disposeWorkspace(dir.directoryId)
      taskMemory.deleteDraft('task-' + dir.directoryId)
    }
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

const syncingSkills = ref(false)
async function handleSyncSkills() {
  if (!selectedWorkerId.value) return
  syncingSkills.value = true
  try {
    await workerState.syncSkills(selectedWorkerId.value)
    ElMessage.success('Skills 已同步')
  } catch {
    ElMessage.error('同步失败')
  } finally {
    syncingSkills.value = false
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

function onEditDirModelConfigChange(val: string) {
  if (val) {
    // 选中平台配置后清空手动 auth
    editDirForm.value.defaultAuthMode = ''
    editDirForm.value.defaultAuthToken = ''
    editDirForm.value.defaultBaseUrl = ''
  }
}

async function handleEditDirectory() {
  if (!selectedDirectoryId.value) return
  saving.value = true
  try {
    const form: Parameters<typeof dirApi.updateDirectory>[1] = {
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
    // Platform model config
    form.defaultModelConfigId = editDirForm.value.defaultModelConfigId || ''
    // Auth config (only when no platform model selected)
    if (!editDirForm.value.defaultModelConfigId) {
      form.defaultAuthMode = editDirForm.value.defaultAuthMode
      if (editDirForm.value.defaultAuthToken) {
        form.defaultAuthToken = editDirForm.value.defaultAuthToken
      }
      if (editDirForm.value.defaultAuthMode === 'CUSTOM_ENDPOINT') {
        form.defaultBaseUrl = editDirForm.value.defaultBaseUrl
      }
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

/**
 * 聊天链接点击处理 — 识别工作区内文件路径并打开文件浏览器 deeplink
 */
async function handleLinkClick(paneId: string, payload: { href: string; text: string }) {
  // 从 pane 的 task 中获取 directoryId 和 workerId
  const pane = panes.value.find(p => p.paneId === paneId)
  const task = pane?.task.value
  const dirId = task?.directoryId || selectedDirectoryId.value
  const wkId = task?.workerId || selectedWorkerId.value

  if (!dirId) {
    ElMessage.warning('当前未选择工作目录，无法定位文件')
    return
  }

  // 查找目录的根路径
  const dir = workerState.directories.value.find(d => d.directoryId === dirId)
  const dirRootPath = dir?.path
  if (!dirRootPath) {
    ElMessage.warning('无法获取工作目录路径')
    return
  }

  try {
    const resolution = await resolveChatLinkTarget({
      href: payload.href,
      text: payload.text,
      origin: window.location.origin,
      directoryId: dirId,
      workerId: wkId,
      directoryRoot: dirRootPath,
      searchFiles,
    })

    if (resolution.kind === 'warn') {
      ElMessage.warning(resolution.message)
      return
    }

    window.open(resolution.url, '_blank', 'width=1400,height=900')
  } catch (error) {
    console.error('Failed to resolve chat link:', error)
    ElMessage.warning('链接定位失败，请稍后重试')
  }
}

async function openCodeServer(network: 'internal' | 'public') {
  if (!selectedDirectoryId.value) return
  const worker = selectedWorkerEntity.value
  if (!worker) return

  const dir = selectedDirectory.value
  let folderPath = dir?.path || ''

  // Windows 路径转换（如 D:\foo\bar → /mnt/d/foo/bar）
  // 优先使用 worker 配置的 codeServerFolderPrefix，否则本地自动检测
  if (/^[A-Za-z]:[\\\/]/.test(folderPath)) {
    const drive = folderPath[0]!.toLowerCase()
    const rest = folderPath.slice(2).replace(/\\/g, '/')
    if (worker.codeServerFolderPrefix) {
      // 使用配置的前缀，{drive} 占位符替换为实际盘符
      const prefix = worker.codeServerFolderPrefix.replace('{drive}', drive)
      folderPath = prefix + rest
    } else {
      // 回退：本地开发时自动转换
      const isLocal = window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1'
      if (isLocal) {
        folderPath = '/mnt/' + drive + rest
      }
    }
  }

  const folder = folderPath ? encodeURIComponent(folderPath) : ''

  // 根据用户选择的网络类型选择公网/内网 URL
  const base = network === 'internal'
    ? (worker.codeServerInternalUrl?.replace(/\/+$/, '') || '')
    : (worker.codeServerPublicUrl?.replace(/\/+$/, '') || '')
    || `${window.location.origin}/code`

  if (!base) {
    ElMessage.warning(`未配置${network === 'internal' ? '内网' : '公网'}地址，请先在编辑中配置`)
    return
  }

  // 密码 → 复制到剪贴板
  if (worker.codeServerPasswordConfigured && selectedWorkerId.value) {
    try {
      const data = await dirApi.getCodeServerPassword(selectedWorkerId.value)
      await navigator.clipboard.writeText(data.password)
      ElMessage.success('VS Code 密码已复制到剪贴板，登录时粘贴即可')
    } catch { /* ignore */ }
  }

  window.open(`${base}/?folder=${folder}`, '_blank')
}

function isInternalNetwork(): boolean {
  const h = window.location.hostname
  return h === 'localhost' || h === '127.0.0.1'
    || h.startsWith('192.168.') || h.startsWith('10.')
    || /^172\.(1[6-9]|2\d|3[01])\./.test(h)
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
    await reloadWorkerTasks()
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

function handleEditMilestone(conv: ConversationGroup) {
  milestoneDialogSessionId.value = conv.sessionId
  milestoneDialogOptions.value = milestonesForDirectory(conv.latestTask.directoryId)
  milestoneForm.value.milestoneId = conv.config?.milestoneId || ''
  showMilestoneDialog.value = true
}

async function handleSaveMilestone() {
  saving.value = true
  try {
    await workerState.setMilestone(
      milestoneDialogSessionId.value,
      milestoneForm.value.milestoneId || undefined,
    )
    showMilestoneDialog.value = false
    ElMessage.success('里程碑已更新')
  } catch {
    ElMessage.error('更新里程碑失败')
  } finally {
    saving.value = false
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
    case '规划': return 'warning'
    case '开发': return 'success'
    case '测试': return 'danger'
    case '用户体验': return ''
    case '代码评审': return 'warning'
    default: return 'info'
  }
}

function milestoneStatusLabel(status?: string): string {
  switch (status) {
    case 'ACTIVE': return '进行中'
    case 'COMPLETED': return '已完成'
    case 'ARCHIVED': return '已归档'
    case 'PLANNED':
    default:
      return '规划中'
  }
}

function milestoneTagType(status?: string): '' | 'success' | 'warning' | 'info' {
  switch (status) {
    case 'ACTIVE': return 'warning'
    case 'COMPLETED': return 'success'
    case 'ARCHIVED': return 'info'
    case 'PLANNED':
    default:
      return ''
  }
}

// ===== Milestone Management (independent panel) =====

async function openMilestoneManager() {
  if (!selectedDirectoryId.value) return
  try {
    milestoneManageList.value = await dirApi.listMilestones(selectedDirectoryId.value)
  } catch {
    milestoneManageList.value = selectedDirectory.value?.milestones ? [...selectedDirectory.value.milestones] : []
  }
  milestoneEditingId.value = ''
  milestoneAddMode.value = false
  showMilestoneManageDialog.value = true
}

function startEditMilestone(ms: DirectoryMilestone) {
  milestoneEditingId.value = ms.id
  milestoneEditForm.value = { name: ms.name, status: ms.status, docPath: ms.docPath || '' }
}

async function handleSaveMilestoneEdit(milestoneId: string) {
  if (!selectedDirectoryId.value) return
  saving.value = true
  try {
    await dirApi.updateMilestoneApi(selectedDirectoryId.value, milestoneId, milestoneEditForm.value)
    milestoneEditingId.value = ''
    await refreshMilestoneList()
    ElMessage.success('里程碑已更新')
  } catch {
    ElMessage.error('更新失败')
  } finally {
    saving.value = false
  }
}

async function handleAddMilestoneManage() {
  if (!selectedDirectoryId.value || !milestoneAddForm.value.name.trim()) return
  saving.value = true
  try {
    await dirApi.addMilestoneApi(selectedDirectoryId.value, milestoneAddForm.value)
    milestoneAddMode.value = false
    await refreshMilestoneList()
    ElMessage.success('里程碑已添加')
  } catch {
    ElMessage.error('添加失败')
  } finally {
    saving.value = false
  }
}

async function handleDeleteMilestoneManage(ms: DirectoryMilestone) {
  if (!selectedDirectoryId.value) return
  milestoneDeleting.value = ms.id
  try {
    const result = await dirApi.deleteMilestoneApi(selectedDirectoryId.value, ms.id, false)
    if (!result.deleted && result.sessionCount > 0) {
      try {
        await ElMessageBox.confirm(
          `该里程碑被 ${result.sessionCount} 个会话引用，删除后这些会话的里程碑设置将被清除。`,
          '确认删除',
          { confirmButtonText: '强制删除', cancelButtonText: '取消', type: 'warning' },
        )
        await dirApi.deleteMilestoneApi(selectedDirectoryId.value, ms.id, true)
        await refreshMilestoneList()
        ElMessage.success('里程碑已删除')
      } catch (e) {
        if (e !== 'cancel') ElMessage.error('删除失败')
      }
    } else {
      await refreshMilestoneList()
      ElMessage.success('里程碑已删除')
    }
  } catch {
    ElMessage.error('删除失败')
  } finally {
    milestoneDeleting.value = ''
  }
}

async function refreshMilestoneList() {
  if (!selectedDirectoryId.value) return
  const updated = await dirApi.listMilestones(selectedDirectoryId.value)
  milestoneManageList.value = updated
  // sync to directories state so computed caches update
  const dir = workerState.directories.value.find(d => d.directoryId === selectedDirectoryId.value)
  if (dir) dir.milestones = updated
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
    await reloadWorkerTasks()
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
    reloadWorkerTasks()
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

/** After a permission response succeeds, sync sidebar state immediately (don't wait for SSE) */
function syncSidebarAfterRespond(pane: ReturnType<typeof createPane>, decision: string) {
  if (!pane.task.value) return
  const sessionId = pane.task.value.sessionId
  if (decision === 'allow') {
    pane.task.value.status = 'RUNNING'
    // Update activeTasks — may be a different object if task was created via API
    const activeTask = workerState.activeTasks.value.find(t => t.taskId === pane.task.value!.taskId)
    if (activeTask) {
      activeTask.status = 'RUNNING'
    }
    workerState.updateInteractionStateFromSSE(sessionId, 'PROCESSING')
  }
  if (activeWorkspace.value) triggerRef(activeWorkspace.value.panes)
  // Force-reload from backend to guarantee sidebar consistency
  // (reactive deep-property mutation may not trigger computed re-evaluation)
  workerState.loadActiveTasks()
  workerState.loadAwaitingReplyTasks()
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
    syncSidebarAfterRespond(pane, decision)
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
    syncSidebarAfterRespond(pane, 'allow')
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
    syncSidebarAfterRespond(pane, decision)
  } catch {
    ElMessage.error('Plan response failed')
  }
}

async function handlePaneForward(paneId: string, message: ChatMessage | { id: string; content: string }) {
  const pane = panes.value.find((item) => item.paneId === paneId)
  const task = pane?.task.value
  if (!task?.sessionId || !message?.id) {
    ElMessage.warning('当前消息暂不支持转发')
    return
  }
  const sourceContent = message.content?.trim()
  if (!sourceContent) {
    ElMessage.warning('回复内容为空，无法转发')
    return
  }
  await openForwardDialog({
    task,
    messageId: message.id,
    sourceContent,
    selectedWorkerId: selectedWorkerId.value || '',
    defaultModel: taskForm.value.model || '',
    defaultModelConfigId: platformModelConfigId.value || '',
    defaultPermissionMode: taskForm.value.permissionMode || 'bypassPermissions',
  })
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
    const result = await rewindTaskUnified(task.taskId, {
      checkpointId: cpId || undefined,
      mode,
      turnIndex: paneRewindTurnIndex.value,
    }) as { status: string; checkpointId?: string; taskId?: string; userPrompt?: string } | null
    if (!result) {
      ElMessage.error('回退失败：服务端未返回有效数据')
      return
    }
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
      // file_rewind mode: files rolled back + conversation also rolled back
      const userPrompt = result.userPrompt || ''
      const pane = panes.value.find((p) => p.paneId === paneRewindPaneId.value)
      if (pane) {
        // Remove messages from turn X onwards in the chat UI (same as conversation_fork)
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
      ElMessage.success('文件和会话已回退，请编辑提示词后发送')
      reloadWorkerTasks()
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

// ===== Agent Teams Config CRUD =====

async function loadAgentTeamsConfigs() {
  if (!selectedDirectoryId.value) {
    agentTeamsConfigs.value = []
    selectedAgentTeamsConfigId.value = null
    return
  }
  try {
    agentTeamsConfigs.value = await dirApi.listAgentTeamsConfigs(selectedDirectoryId.value)
    // 自动选中默认配置
    const defaultConfig = agentTeamsConfigs.value.find(c => c.isDefault)
    selectedAgentTeamsConfigId.value = defaultConfig?.configId ?? null
  } catch {
    agentTeamsConfigs.value = []
    selectedAgentTeamsConfigId.value = null
  }
}

function openCreateAgentTeamsConfig() {
  agentTeamsConfigDialogMode.value = 'create'
  agentTeamsConfigForm.value = { configId: '', name: '', config: '', isDefault: false }
  showAgentTeamsConfigDialog.value = true
}

function openEditAgentTeamsConfig(cfg: AgentTeamsConfig) {
  agentTeamsConfigDialogMode.value = 'edit'
  agentTeamsConfigForm.value = {
    configId: cfg.configId,
    name: cfg.name,
    config: cfg.config,
    isDefault: cfg.isDefault,
  }
  showAgentTeamsConfigDialog.value = true
}

async function handleSaveAgentTeamsConfig() {
  if (!selectedDirectoryId.value) return
  const f = agentTeamsConfigForm.value
  if (!f.name.trim() || !f.config.trim()) {
    ElMessage.warning('请填写名称和 JSON 配置')
    return
  }
  try {
    if (agentTeamsConfigDialogMode.value === 'create') {
      await dirApi.createAgentTeamsConfig(selectedDirectoryId.value, {
        name: f.name,
        config: f.config,
        isDefault: f.isDefault,
      })
      ElMessage.success('配置已创建')
    } else {
      await dirApi.updateAgentTeamsConfig(selectedDirectoryId.value, f.configId, {
        name: f.name,
        config: f.config,
        isDefault: f.isDefault,
      })
      ElMessage.success('配置已更新')
    }
    showAgentTeamsConfigDialog.value = false
    await loadAgentTeamsConfigs()
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : String(e)
    ElMessage.error('保存失败: ' + msg)
  }
}

async function handleDeleteAgentTeamsConfig(configId: string) {
  if (!selectedDirectoryId.value) return
  try {
    await ElMessageBox.confirm('确定删除此配置？已使用此配置的会话不受影响。', '删除确认', { type: 'warning' })
    await dirApi.deleteAgentTeamsConfig(selectedDirectoryId.value, configId)
    ElMessage.success('配置已删除')
    await loadAgentTeamsConfigs()
  } catch {
    // cancelled
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
  // Auto-close oldest pane when limit reached
  while (panes.value.length >= MAX_PANES) {
    closePane(panes.value[0]!.paneId)
  }
  creatingTask.value = true
  try {
    const form: {
      workerId: string; prompt: string; cwd?: string; directoryId?: string
      model?: string; maxTurns?: number; agentTeamsJson?: string; agentTeamsConfigId?: string
      images?: string; permissionMode?: string; modelConfigId?: string; agentId?: string
    } = {
      workerId: selectedWorkerId.value,
      prompt,
    }
    // provider 不一致时不要带 agentId，避免 A2A 路由与 modelConfigId 指向不同后端。
    const modelProviderType = providerTypeFromWorkerBackend(platformModelConfig.value?.workerBackend)
    if (selectedAgentId.value && (!modelProviderType || modelProviderType === selectedAgentProviderType.value)) {
      form.agentId = selectedAgentId.value
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
    if (selectedAgentTeamsConfigId.value) {
      form.agentTeamsConfigId = selectedAgentTeamsConfigId.value
      const config = agentTeamsConfigs.value.find(c => c.configId === selectedAgentTeamsConfigId.value)
      if (config) form.agentTeamsJson = config.config
    }
    if (taskForm.value.permissionMode) {
      form.permissionMode = taskForm.value.permissionMode
    }
    if (platformModelConfigId.value) {
      form.modelConfigId = platformModelConfigId.value
    }
    // Attach files/images as JSON string
    if (attachments.value.length > 0) {
      form.images = JSON.stringify(
        attachments.value.map((img) => ({
          name: img.name,
          data: img.base64,
          mime_type: img.mimeType,
        })),
      )
    }

    const task = await workerState.createTask(form)
    // 记录 session → 用户选择的短模型名，供后续 restoreSessionModelSelection 恢复
    saveSessionModel(task.sessionId, taskForm.value.model)
    taskMemory.addToHistory(prompt)
    taskMemory.clearDraft()
    taskForm.value.prompt = ''
    // Capture image URLs before clearing (for display in chat)
    const chatImages = attachments.value
      .filter(a => a.isImage && a.previewUrl)
      .map(a => ({ name: a.name, url: a.previewUrl }))
    // Don't revoke blob URLs that will be displayed in chat messages
    const preserveUrls = new Set(chatImages.map(ci => ci.url))
    clearAttachments(preserveUrls)

    const pane = createPane(task)
    await pane.connect(task.sessionId, chatImages.length > 0 ? chatImages : undefined)
  } catch (e: unknown) {
    ElMessage.error('创建任务失败: ' + ((e as Error).message || '未知错误'))
  } finally {
    creatingTask.value = false
  }
}

async function handleSubmitForward() {
  try {
    const { result, sourceSessionId, targetMode } = await submitForward()
    const newTask = result.task as unknown as ClaudeTask
    if (targetMode === 'NEW_SESSION' && forwardForm.value.model) {
      saveSessionModel(newTask.sessionId, forwardForm.value.model)
    }

    if (newTask.directoryId) {
      selectDirectory(newTask.workerId, newTask.directoryId)
    } else if (newTask.workerId) {
      selectWorker(newTask.workerId)
    }
    await nextTick()

    while (panes.value.length >= MAX_PANES) {
      closePane(panes.value[0]!.paneId)
    }

    const pane = createPane(newTask)
    focusedPaneId.value = pane.paneId
    await Promise.all([
      reloadWorkerTasks(),
      newTask.directoryId && selectedDirectoryId.value === newTask.directoryId ? loadDirectoryTasks() : Promise.resolve(),
      workerState.loadConversationConfigs([sourceSessionId, newTask.sessionId]),
      pane.connect(newTask.sessionId),
    ])
    ElMessage.success(targetMode === 'EXISTING_SESSION' ? '已转发到已有会话' : '已转发为新会话')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '转发失败')
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
  if (focusedPaneId.value === paneId) focusedPaneId.value = null
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
    await ElMessageBox.confirm('确认中止该任务？', '提示', {
      type: 'warning',
      confirmButtonText: '确认',
      cancelButtonText: '取消',
    })
    const taskId = pane.task.value.taskId
    await workerState.abortTask(taskId)
    pane.task.value.status = 'ABORTED'
    // Immediately remove from activeTasks (don't wait for SSE)
    workerState.activeTasks.value = workerState.activeTasks.value.filter(t => t.taskId !== taskId)
    workerState.loadAwaitingReplyTasks()
    if (activeWorkspace.value) triggerRef(activeWorkspace.value.panes)
    ElMessage.info('任务已中止')
  } catch (e) {
    if (e !== 'cancel') ElMessage.error('中止失败')
  }
}

async function handlePaneReconnect(paneId: string, taskId: string) {
  const pane = panes.value.find((p) => p.paneId === paneId)
  if (!pane) return
  try {
    await reconnectTaskUnified(taskId)
    if (pane.task.value) {
      pane.task.value.status = 'RUNNING'
    }
    // Clear the reconnectable flag on the error message
    const errorMsg = pane.chatState.messages.value.find(
      (m) => m.reconnectable && (m.raw as Record<string, unknown>)?.taskId === taskId,
    )
    if (errorMsg) {
      errorMsg.reconnectable = false
    }
    pane.chatState.messages.value.push({
      id: `reconnect-${Date.now()}`,
      type: 'STATE_SYNC' as AipMessageType,
      sender: 'system',
      content: '正在重连任务...',
      raw: { subtype: 'reconnected' },
      timestamp: Date.now(),
    })
    if (activeWorkspace.value) triggerRef(activeWorkspace.value.panes)
  } catch {
    ElMessage.error('重连失败')
  }
}

// ==================== Resync 任务重新同步 ====================

/**
 * 核心 resync 处理函数，从 TaskPane header 或侧栏列表共用。
 */
async function doResync(taskId: string, pane?: TaskPaneState) {
  resyncingTaskId.value = taskId
  try {
    const result = await resyncTaskUnified(taskId) as Record<string, unknown>

    switch (result?.action) {
      case 'RECONNECTED': {
        // 策略 A：CLI 活着，已重连 SSE
        if (pane) {
          if (pane.task.value) pane.task.value.status = 'RUNNING'
          // 清除可重连的错误消息标记
          const errorMsg = pane.chatState.messages.value.find(
            (m) => m.reconnectable && (m.raw as Record<string, unknown>)?.taskId === taskId,
          )
          if (errorMsg) errorMsg.reconnectable = false
          pane.chatState.messages.value.push({
            id: `resync-reconnected-${Date.now()}`,
            type: 'STATE_SYNC' as AipMessageType,
            sender: 'system',
            content: '已重新连接 Worker（CLI 仍存活）',
            raw: { subtype: 'reconnected' },
            timestamp: Date.now(),
          })
          pane.reconnectSse()
          if (activeWorkspace.value) triggerRef(activeWorkspace.value.panes)
        }
        workerState.loadActiveTasks()
        workerState.loadAwaitingReplyTasks()
        ElMessage.success('已重新连接（CLI 存活）')
        break
      }
      case 'MESSAGES_SYNCED': {
        // 策略 B：CLI 已退出，从 Worker JSONL 补齐了消息
        const imported = result.messageSync?.imported ?? 0
        if (pane) {
          if (pane.task.value) pane.task.value.status = 'COMPLETED'
          await reloadPaneMessages(pane)
          if (activeWorkspace.value) triggerRef(activeWorkspace.value.panes)
        }
        workerState.loadActiveTasks()
        workerState.loadAwaitingReplyTasks()
        workerState.loadTasksPage(0, undefined, currentStateParam())
        ElMessage.success(`已同步 ${imported} 条消息`)
        break
      }
      case 'ALREADY_ALIGNED': {
        if (pane && pane.task.value) pane.task.value.status = 'COMPLETED'
        workerState.loadActiveTasks()
        workerState.loadAwaitingReplyTasks()
        workerState.loadTasksPage(0, undefined, currentStateParam())
        ElMessage.info('消息已完全一致，任务已标记为完成')
        break
      }
      case 'NO_SESSION_DATA':
        ElMessage.warning('Worker 中没有该会话的记录')
        break
      case 'WORKER_UNREACHABLE':
        ElMessage.error('Worker 不可达：' + (result.cliStatus?.detail ?? ''))
        break
      default:
        ElMessage.warning('未知操作: ' + result.action)
    }
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : '重新同步失败'
    ElMessage.error(msg)
  } finally {
    resyncingTaskId.value = ''
  }
}

/** 从 TaskPane header-extra 按钮触发 */
async function handlePaneResync(paneState: TaskPaneState) {
  const taskId = paneState.task.value?.taskId
  if (!taskId) return
  await doResync(taskId, paneState)
}

/** 从侧栏会话列表 dropdown 触发 */
async function handleResyncFromList(conv: ConversationGroup) {
  const taskId = conv.latestTask.taskId
  // 查找已打开的 pane
  const pane = panes.value.find((p) => p.task.value?.sessionId === conv.sessionId)
  await doResync(taskId, pane)
}

/**
 * 策略 B 同步后重新加载 pane 中的消息。
 * 从 Worker JSONL 读取最新消息并替换 ChatPanel 显示。
 */
async function reloadPaneMessages(pane: TaskPaneState) {
  const task = pane.task.value
  if (!task?.claudeSessionId || !task?.workerId) return
  try {
    // Load only the latest PAGE_SIZE messages to avoid performance issues
    const pageSize = 50
    const countResult = await dirApi.getWorkerSessionMessageCount(task.workerId, task.claudeSessionId)
    const total = countResult.total
    const offset = Math.max(0, total - pageSize)
    const workerMessages = await dirApi.getWorkerSessionMessagesPaged(
      task.workerId, task.claudeSessionId, offset, pageSize,
    )
    if (!workerMessages || workerMessages.length === 0) return

    // 转换为 ChatMessage 格式
    const chatMessages = workerMessages.map((m, idx) => ({
      id: `resync-msg-${idx}-${Date.now()}`,
      type: 'TEXT_COMPLETE' as AipMessageType,
      sender: (m.role === 'user' ? 'user' : 'assistant') as 'user' | 'assistant',
      content: m.content || '',
      raw: { resyncImported: true },
      timestamp: m.timestamp ? new Date(m.timestamp).getTime() : Date.now(),
    }))
    // 替换消息列表
    pane.chatState.messages.value = chatMessages
    // If there are older messages, mark hasMoreHistory
    pane.hasMoreHistory.value = offset > 0
  } catch (e) {
    console.warn('Failed to reload pane messages after resync:', e)
  }
}

// ==================== End Resync ====================

/** Handle @agent ask — send question to an Agent via A2A, show response in pane chat */
async function handleAskAgent(paneId: string, agent: { agentId: string; name: string }, question: string) {
  const pane = panes.value.find((p) => p.paneId === paneId)
  if (!pane) return

  const sessionId = pane.task.value?.sessionId

  // Lookup existing contextId for this pane+agent (multi-turn)
  const existingContextId = paneAgentContextIds.value.get(paneId)?.get(agent.agentId)

  // Show the user's @mention as a user message
  pane.chatState.addUserMessage(`@${agent.name} ${question}`)

  try {
    const task = await agentState.askAgent(agent.agentId, question, sessionId, existingContextId)
    const responseText = task.artifacts?.[0]?.parts?.[0]?.text || '(无回答)'
    const failed = task.status.state === 'FAILED'

    pane.chatState.messages.value.push({
      id: `agent-ask-${Date.now()}`,
      type: 'TEXT_COMPLETE' as AipMessageType,
      sender: 'assistant',
      content: failed
        ? `**${agent.name}** 查询失败: ${task.status.description || '未知错误'}`
        : `**${agent.name}**:\n\n${responseText}`,
      timestamp: Date.now(),
    })

    // Save returned contextId for subsequent multi-turn asks
    if (task.contextId) {
      if (!paneAgentContextIds.value.has(paneId)) {
        paneAgentContextIds.value.set(paneId, new Map())
      }
      paneAgentContextIds.value.get(paneId)!.set(agent.agentId, task.contextId)
    }

    // Store context for injection into next resume
    if (!failed && responseText) {
      const ctx = paneAgentContext.value.get(paneId) || []
      ctx.push({ agentName: agent.name, question, answer: responseText })
      paneAgentContext.value.set(paneId, ctx)
    }
  } catch (e: unknown) {
    const errMsg = e instanceof Error ? e.message : '网络错误'
    pane.chatState.messages.value.push({
      id: `agent-ask-err-${Date.now()}`,
      type: 'ERROR' as AipMessageType,
      sender: 'system',
      content: '',
      error: `@${agent.name} 查询失败: ${errMsg}`,
      timestamp: Date.now(),
    })
  }
}

/** Handle inline send from pane input (replaces dialog-based resume) */
async function handlePaneSend(paneId: string, content: string) {
  // Intercept @agent mentions — supports both:
  //   @agentName(agentId:xxx) question  (new format with embedded agentId)
  //   @agentName question               (legacy format, name-based lookup)
  const atMatchNew = content.match(/^@(\S+?)\(agentId:([^)]+)\)\s+([\s\S]+)/)
  if (atMatchNew && atMatchNew[1] && atMatchNew[2] && atMatchNew[3]) {
    const agentName = atMatchNew[1]
    const agentId = atMatchNew[2]
    const question = atMatchNew[3].trim()
    await handleAskAgent(paneId, { agentId, name: agentName }, question)
    return
  }
  const atMatch = content.match(/^@(\S+)\s+([\s\S]+)/)
  if (atMatch && atMatch[1] && atMatch[2]) {
    const agentName = atMatch[1]
    const question = atMatch[2].trim()
    const agent = agentState.agents.value.find(a => a.name === agentName)
    if (agent) {
      await handleAskAgent(paneId, { agentId: agent.agentId, name: agent.name }, question)
      return
    }
  }

  const pane = panes.value.find((p) => p.paneId === paneId)
  const oldTask = pane?.task.value
  const workerId = oldTask?.workerId || selectedWorkerId.value
  if (!pane || !oldTask || !workerId) return
  if (!oldTask.sessionId) return

  // Inject @agent context into prompt if available
  let finalPrompt = content
  const ctx = paneAgentContext.value.get(paneId)
  if (ctx && ctx.length > 0) {
    const contextBlock = ctx.map(c =>
      `[来自 ${c.agentName} 的参考信息]\nQ: ${c.question}\nA: ${c.answer}`,
    ).join('\n\n')
    finalPrompt = `${contextBlock}\n\n---\n\n${content}`
    paneAgentContext.value.delete(paneId)
  }

  // Capture image URLs before API call (input is already cleared by the child component)
  const chatImages = attachments.value
    .filter(a => a.isImage && a.previewUrl)
    .map(a => ({ name: a.name, url: a.previewUrl }))

  // Optimistically add user message to chat BEFORE API call,
  // so the message is preserved even if the server is unavailable
  pane.chatState.addUserMessage(content, undefined, chatImages.length > 0 ? chatImages : undefined)

  try {
    const resumeForm: Parameters<typeof workerState.resumeTask>[0] = {
      workerId,
      prompt: finalPrompt,
      cwd: oldTask.cwd,
      directoryId: oldTask.directoryId,
      sessionId: oldTask.sessionId,
    }
    // Pass logical agent when available, otherwise keep the existing provider context explicit.
    if (selectedAgentId.value) {
      resumeForm.agentId = selectedAgentId.value
    }
    // providerType / claudeSessionId / codexThreadId 由后端从 session 恢复，前端不再传递
    if (taskForm.value.model) {
      resumeForm.model = taskForm.value.model
    }
    if (taskForm.value.maxTurns != null) {
      resumeForm.maxTurns = taskForm.value.maxTurns
    }
    // Pass agent teams config on resume (mac27/dev fix: 0f3148c)
    if (selectedAgentTeamsConfigId.value) {
      const config = agentTeamsConfigs.value.find(c => c.configId === selectedAgentTeamsConfigId.value)
      if (config) resumeForm.agentTeamsJson = config.config
    }
    if (taskForm.value.permissionMode) {
      (resumeForm as Record<string, unknown>).permissionMode = taskForm.value.permissionMode
    }
    if (platformModelConfigId.value) {
      resumeForm.modelConfigId = platformModelConfigId.value
    }
    // Attach files/images as JSON string
    if (attachments.value.length > 0) {
      resumeForm.images = JSON.stringify(
        attachments.value.map((img) => ({
          name: img.name,
          data: img.base64,
          mime_type: img.mimeType,
        })),
      )
    }
    const newTask = await workerState.resumeTask(resumeForm)
    if (!newTask?.sessionId) {
      throw new Error('继续会话失败：服务端未返回有效任务数据')
    }
    // 记录 session → 用户选择的短模型名，供后续 restoreSessionModelSelection 恢复
    saveSessionModel(newTask.sessionId, taskForm.value.model)
    // Don't revoke blob URLs that will be displayed in chat messages
    const preserveUrls = new Set(chatImages.map(ci => ci.url))
    clearAttachments(preserveUrls)

    // Resume SSE without re-adding user message (already added above)
    pane.resumeInPlaceNoMessage(newTask)

    reloadWorkerTasks()
    if (selectedDirectoryId.value) {
      loadDirectoryTasks()
    }
  } catch (e) {
    const msg = e instanceof Error ? e.message : ''
    ElMessage.error(msg ? `${msg}（消息已保留）` : '发送失败，消息已保留')
  }
}

function handlePageChange(page: number) {
  workerState.loadTasksPage(page - 1, undefined, currentStateParam())
}

function handleDirPageChange(page: number) {
  dirTaskPage.value = page - 1
  loadDirectoryTasks()
}

async function viewTask(task: ClaudeTask) {
  // 恢复会话的模型选择（API 配置 + 模型）
  const conv = allConversations.value.find(c => c.sessionId === task.sessionId)
  restoreSessionModelSelection(conv?.latestTask ?? task)

  // Per-conversation: match by sessionId (same conversation = same pane)
  const existing = panes.value.find((p) => p.task.value?.sessionId === task.sessionId)
  if (existing) {
    const wasFocused = focusedPaneId.value === existing.paneId
    focusedPaneId.value = existing.paneId
    // When switching back to a previously unfocused pane, do a full reload
    // to catch up on messages and task status changes that occurred while
    // the pane's SSE was suspended (e.g. task completed in the background).
    if (!wasFocused) {
      await existing.connect(task.sessionId)
      existing.syncTaskStatus()
    }
    return
  }

  // Auto-close oldest pane when limit reached
  while (panes.value.length >= MAX_PANES) {
    closePane(panes.value[0]!.paneId)
  }

  const pane = createPane(task)
  focusedPaneId.value = pane.paneId
  await pane.connect(task.sessionId)
  // Sync task metadata — the `task` param may be stale from the cached conversation list
  pane.syncTaskStatus()

  // For synced tasks: if Navigator session was empty, load JSONL history from Worker
  // Load only the latest PAGE_SIZE messages to avoid performance issues with long sessions.
  if (pane.chatState.messages.value.length === 0 && task.claudeSessionId && task.workerId) {
    try {
      // Get total count first, then load only the latest batch
      const countResult = await dirApi.getWorkerSessionMessageCount(task.workerId, task.claudeSessionId)
      const total = countResult.total
      const pageSize = 50
      const offset = Math.max(0, total - pageSize)
      const history = await dirApi.getWorkerSessionMessagesPaged(task.workerId, task.claudeSessionId, offset, pageSize)
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
        // If there are older messages, mark hasMoreHistory (best-effort for JSONL-only sessions)
        if (offset > 0) {
          pane.hasMoreHistory.value = true
        }
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
    // Sync abort status to any open pane showing this task
    for (const pane of getAllPanes()) {
      if (pane.task.value?.taskId === taskId) {
        pane.task.value.status = 'ABORTED'
      }
    }
    ElMessage.success('任务已中止')
    // Immediately remove from activeTasks (don't wait for SSE)
    workerState.activeTasks.value = workerState.activeTasks.value.filter(t => t.taskId !== taskId)
    // Refresh task lists
    reloadWorkerTasks()
    workerState.loadAwaitingReplyTasks()
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
    await rewindTaskUnified(rewindTaskId.value, {
      checkpointId: rewindSelectedId.value,
      mode: rewindMode.value,
      turnIndex: selectedCp?.turnIndex,
    })
    if (rewindMode.value === 'conversation_fork') {
      ElMessage.success('会话已 fork，新任务已创建')
      // Refresh task list to show new task
      reloadWorkerTasks()
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
    const result = await scanCheckpointsUnified(task.taskId)
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

// ─── Context Repair / Compact (修复上下文 / 手动压缩) ──────────────

function canRepairContext(conv: ConversationGroup): boolean {
  return !!conv.latestTask.claudeSessionId
    && conv.latestTask.status !== 'RUNNING'
    && conv.latestTask.status !== 'AWAITING_PERMISSION'
}

function truncateText(text: string, maxLen: number): string {
  if (text.length <= maxLen) return text
  return text.slice(0, maxLen) + '…'
}

function buildCompactPrompt(
  originalTask: string,
  recentMessages: { role: string; content: string }[],
): string {
  const recentPart = recentMessages.length > 0
    ? recentMessages.map(m => `[${m.role === 'user' ? 'User' : 'Assistant'}] ${m.content}`).join('\n')
    : '[请补充最近的工作进度]'

  return `This session is being continued from a previous conversation that ran out of context.

## Original Task
${originalTask || '[请补充原始任务描述]'}

## Recent Progress
${recentPart}

## Instructions
Please continue from where we left off. Review the above context and proceed with the next steps.`
}

async function handleRepairContext(conv: ConversationGroup) {
  contextRepairConv.value = conv
  contextRepairTaskId.value = conv.latestTask.taskId
  contextRepairLoading.value = false
  contextRepairFetching.value = true
  contextRepairVisible.value = true
  contextRepairInfo.value = { userCount: 0, assistantCount: 0, totalMessages: 0, maxTurn: 0, originalTask: '', recentMessages: [] }
  contextRepairPrompt.value = ''

  try {
    const workerId = conv.latestTask.workerId
    const claudeSessionId = conv.latestTask.claudeSessionId!

    // Phase 1: Fetch message-count + first message + scan checkpoints in parallel
    const [msgCount, firstMessages, cpResult] = await Promise.all([
      dirApi.getWorkerSessionMessageCount(workerId, claudeSessionId),
      dirApi.getWorkerSessionMessagesPaged(workerId, claudeSessionId, 0, 1),
      scanCheckpointsUnified(conv.latestTask.taskId),
    ])

    // Update task's checkpoint data for the rewind dialog
    if (cpResult.count > 0) {
      conv.latestTask.checkpoints = cpResult.checkpoints
    }

    const checkpoints: { id: string; turnIndex: number; timestamp: string }[] =
      cpResult.checkpoints ? JSON.parse(cpResult.checkpoints) : []
    const maxTurn = checkpoints.length > 0 ? checkpoints.length : msgCount.user_count

    // Phase 2: Fetch last few messages (need total from phase 1)
    const lastOffset = Math.max(0, msgCount.total - 6)
    const lastMessages = msgCount.total > 1
      ? await dirApi.getWorkerSessionMessagesPaged(workerId, claudeSessionId, lastOffset, 6)
      : []

    // Extract original task
    const originalTask = firstMessages[0]?.content || conv.firstPrompt || ''

    // Truncate recent messages for display
    const recentMessages = lastMessages.map(m => ({
      role: m.role,
      content: truncateText(m.content, 300),
    }))

    contextRepairInfo.value = {
      userCount: msgCount.user_count,
      assistantCount: msgCount.assistant_count,
      totalMessages: msgCount.total,
      maxTurn,
      originalTask: truncateText(originalTask, 500),
      recentMessages,
    }

    // Auto-generate compact prompt
    contextRepairPrompt.value = buildCompactPrompt(originalTask, recentMessages)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '获取会话信息失败')
    contextRepairVisible.value = false
  } finally {
    contextRepairFetching.value = false
  }
}

async function executeContextRepair() {
  const conv = contextRepairConv.value
  if (!conv || !selectedWorkerId.value) return

  contextRepairLoading.value = true
  try {
    // Step 1: Rewind to turn 1 — marks ALL messages as sidechain (full compaction)
    await rewindTaskUnified(contextRepairTaskId.value, {
      mode: 'conversation_fork',
      turnIndex: 1,
    })

    // Step 2: Resume the session with the compact summary prompt
    const task = conv.latestTask
    const resumeForm: Parameters<typeof workerState.resumeTask>[0] = {
      workerId: selectedWorkerId.value,
      claudeSessionId: task.claudeSessionId!,
      prompt: contextRepairPrompt.value,
      cwd: task.cwd,
      directoryId: task.directoryId,
      sessionId: task.sessionId,
    }
    // Pass logical agent when available, otherwise keep the provider context explicit.
    if (selectedAgentId.value) {
      resumeForm.agentId = selectedAgentId.value
    }
    // providerType 由后端从 modelConfigId 推导，前端不再传递
    if (taskForm.value.model) {
      resumeForm.model = taskForm.value.model
    }
    if (selectedAgentTeamsConfigId.value) {
      const config = agentTeamsConfigs.value.find(c => c.configId === selectedAgentTeamsConfigId.value)
      if (config) resumeForm.agentTeamsJson = config.config
    }
    const newTask = await workerState.resumeTask(resumeForm)

    // Step 3: Open or reuse pane
    const existingPane = panes.value.find((p) => p.task.value?.sessionId === task.sessionId)
    if (existingPane) {
      existingPane.resumeInPlace(newTask)
    } else {
      while (panes.value.length >= MAX_PANES) {
        closePane(panes.value[0]!.paneId)
      }
      const newPane = createPane(newTask)
      await newPane.connect(newTask.sessionId)
    }

    ElMessage.success('上下文已压缩，会话已继续')
    contextRepairVisible.value = false
    reloadWorkerTasks()
    if (selectedDirectoryId.value) loadDirectoryTasks()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '压缩失败')
  } finally {
    contextRepairLoading.value = false
  }
}

// ─── Delete Conversation ──────────────────────────────────────────

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
    reloadWorkerTasks()
    if (selectedDirectoryId.value) {
      loadDirectoryTasks()
    }
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error('删除失败')
    }
  }
}

async function handleArchiveConversation(conv: ConversationGroup) {
  try {
    await ElMessageBox.confirm(
      '确认归档该会话？归档后默认不在列表中显示，可通过"已归档"筛选查看。',
      '归档会话',
      { type: 'info', confirmButtonText: '确认归档', cancelButtonText: '取消' },
    )
    await workerState.archiveConversation(conv.sessionId)
    ElMessage.success('已归档')
    reloadFilteredTasks()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error('归档失败')
  }
}

async function handlePaneArchive(sessionId?: string) {
  if (!sessionId) return
  try {
    await ElMessageBox.confirm(
      '确认归档该会话？归档后默认不在列表中显示，可通过"已归档"筛选查看。',
      '归档会话',
      { type: 'info', confirmButtonText: '确认归档', cancelButtonText: '取消' },
    )
    await workerState.archiveConversation(sessionId)
    ElMessage.success('已归档')
    reloadFilteredTasks()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error('归档失败')
  }
}

async function handlePaneUnarchive(sessionId?: string) {
  if (!sessionId) return
  try {
    await workerState.unarchiveConversation(sessionId)
    ElMessage.success('已取消归档')
    reloadFilteredTasks()
  } catch {
    ElMessage.error('取消归档失败')
  }
}

async function handlePaneDelete(sessionId?: string) {
  if (!sessionId) return
  const conv = [...activeSessionConvs.value, ...activeConversations.value]
    .find(c => c.sessionId === sessionId)
  if (conv) {
    await handleDeleteConversation(conv)
  }
}

async function handleUnarchiveConversation(conv: ConversationGroup) {
  try {
    await workerState.unarchiveConversation(conv.sessionId)
    ElMessage.success('已取消归档')
    reloadFilteredTasks()
  } catch {
    ElMessage.error('取消归档失败')
  }
}

async function handleHoldConversation(conv: ConversationGroup) {
  try {
    await ElMessageBox.confirm(
      '确认搁置该会话？搁置后不再出现在"需要关注"区域，可通过"已搁置"筛选查看。',
      '搁置会话',
      { type: 'info', confirmButtonText: '确认搁置', cancelButtonText: '取消' },
    )
    await workerState.holdConversation(conv.sessionId)
    ElMessage.success('已搁置')
    reloadFilteredTasks()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error('搁置失败')
  }
}

async function handlePaneHold(sessionId?: string) {
  if (!sessionId) return
  try {
    await ElMessageBox.confirm(
      '确认搁置该会话？搁置后不再出现在"需要关注"区域，可通过"已搁置"筛选查看。',
      '搁置会话',
      { type: 'info', confirmButtonText: '确认搁置', cancelButtonText: '取消' },
    )
    await workerState.holdConversation(sessionId)
    ElMessage.success('已搁置')
    reloadFilteredTasks()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error('搁置失败')
  }
}

async function handleUnholdConversation(conv: ConversationGroup) {
  try {
    await workerState.unholdConversation(conv.sessionId)
    ElMessage.success('已取消搁置')
    reloadFilteredTasks()
  } catch {
    ElMessage.error('取消搁置失败')
  }
}

function getInteractionState(sessionId?: string): string | undefined {
  if (!sessionId) return undefined
  return workerState.conversationConfigs.value.get(sessionId)?.interactionState
}

/** Derive interaction state from task.status (real-time) with interactionState fallback (may lag). */
function paneInteractionState(paneState: TaskPaneState): string | undefined {
  const status = paneState.task.value?.status
  if (status === 'RUNNING') return 'PROCESSING'
  if (status === 'AWAITING_PERMISSION') return 'AWAITING_REPLY'
  // Terminal task statuses always mean AWAITING_REPLY, regardless of DB interactionState
  // (defends against backend race where reconnect overwrites interactionState after completion)
  if (status === 'COMPLETED' || status === 'FAILED' || status === 'ABORTED') return 'AWAITING_REPLY'
  return getInteractionState(paneState.task.value?.sessionId)
}

function interactionStateLabel(state: string): string {
  switch (state) {
    case 'PROCESSING': return '处理中'
    case 'AWAITING_REPLY': return '待回复'
    case 'ON_HOLD': return '已搁置'
    case 'ARCHIVED': return '已归档'
    default: return state
  }
}

function attentionStatusLabel(conv: ConversationGroup): string {
  // Task-level status takes priority (more specific than interactionState)
  const status = conv.latestTask.status
  if (status === 'RUNNING') return '运行中'
  if (status === 'AWAITING_PERMISSION') return '等待授权'
  // Terminal task statuses always mean 待回复, regardless of DB interactionState
  if (status === 'COMPLETED' || status === 'FAILED' || status === 'ABORTED') return '待回复'
  // Fallback to interactionState for non-running tasks
  const state = conv.config?.interactionState
  if (state === 'AWAITING_REPLY') return '待回复'
  return interactionStateLabel(state || 'PROCESSING')
}

async function handleResumeFromHistory(task: ClaudeTask) {
  const workerId = task.workerId || selectedWorkerId.value
  if (!workerId) return
  if (!task.claudeSessionId && !task.codexThreadId) return

  try {
    const { value: prompt } = (await ElMessageBox.prompt('输入后续指令', '继续对话', {
      confirmButtonText: '发送',
      cancelButtonText: '取消',
      inputType: 'textarea',
      inputPlaceholder: '请输入后续任务描述...',
    })) as { value: string }
    if (!prompt) return

    const resumeForm: Parameters<typeof workerState.resumeTask>[0] = {
      workerId,
      prompt,
      cwd: task.cwd,
      directoryId: task.directoryId,
      sessionId: task.sessionId,  // per-conversation: reuse session
    }
    // Pass logical agent when available, otherwise keep the provider context explicit.
    if (selectedAgentId.value) {
      resumeForm.agentId = selectedAgentId.value
    }
    // providerType / claudeSessionId / codexThreadId 由后端从 session 恢复，前端不再传递
    if (taskForm.value.model) {
      resumeForm.model = taskForm.value.model
    }
    // Pass agent teams config on resume (mac27/dev fix: 0f3148c)
    if (selectedAgentTeamsConfigId.value) {
      const config = agentTeamsConfigs.value.find(c => c.configId === selectedAgentTeamsConfigId.value)
      if (config) resumeForm.agentTeamsJson = config.config
    }
    const newTask = await workerState.resumeTask(resumeForm)
    if (!newTask?.sessionId) {
      throw new Error('继续会话失败：服务端未返回有效任务数据')
    }

    // Per-conversation: find existing pane by sessionId
    const existingPane = panes.value.find((p) => p.task.value?.sessionId === task.sessionId)
    if (existingPane) {
      existingPane.resumeInPlace(newTask)
    } else {
      // Auto-close oldest pane when limit reached
      while (panes.value.length >= MAX_PANES) {
        closePane(panes.value[0]!.paneId)
      }
      const newPane = createPane(newTask)
      await newPane.connect(newTask.sessionId)  // load full history
    }

    reloadWorkerTasks()
    if (selectedDirectoryId.value) {
      loadDirectoryTasks()
    }
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error(e instanceof Error ? e.message : '继续会话失败')
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
      codeServerPublicUrl: selectedWorkerEntity.value.codeServerPublicUrl || '',
      codeServerInternalUrl: selectedWorkerEntity.value.codeServerInternalUrl || '',
      codeServerPassword: '',
      codeServerFolderPrefix: selectedWorkerEntity.value.codeServerFolderPrefix || '',
      codexBaseUrl: selectedWorkerEntity.value.codexBaseUrl || '',
      codexAuthToken: '',
      codexModel: selectedWorkerEntity.value.codexModel || '',
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
      defaultModelConfigId: selectedDirectory.value.defaultModelConfigId || '',
    }
    // 确保编辑对话框中加载最新的 Agent Teams 配置列表
    loadAgentTeamsConfigs()
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
  const has1m = /\[1m\]/i.test(model)
  const clean = model.replace(/\[1m\]/gi, '')
  const match = clean.match(/(sonnet|opus|haiku)[\w-]*/i)
  const base = match ? match[0] : clean.split('-').slice(1, 3).join('-')
  return has1m ? base + ' (1M)' : base
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
  height: 100%;
}

.worker-sidebar {
  width: 260px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  background: #f7f8fa;
  border-right: 1px solid #e4e7ed;
  transition: width 0.3s ease;
  overflow: hidden;
}

.worker-sidebar.collapsed {
  width: 0;
  border-right: none;
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

.sidebar-header-actions {
  display: flex;
  align-items: center;
  gap: 6px;
}

/* Panel collapse / expand buttons */
.panel-collapse-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border-radius: 4px;
  cursor: pointer;
  color: #909399;
  font-size: 14px;
  user-select: none;
  flex-shrink: 0;
  transition: color 0.2s, background-color 0.2s;
}

.panel-collapse-btn:hover {
  color: #409eff;
  background: #ecf5ff;
}

.panel-expand-btn {
  position: absolute;
  top: 50%;
  transform: translateY(-50%);
  z-index: 10;
  width: 20px;
  height: 48px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f0f2f5;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  cursor: pointer;
  color: #909399;
  font-size: 14px;
  user-select: none;
  transition: color 0.2s, background-color 0.2s;
}

.panel-expand-btn:hover {
  color: #409eff;
  background: #ecf5ff;
  border-color: #409eff;
}

.left-expand {
  left: 0;
  border-left: none;
  border-radius: 0 4px 4px 0;
}

.right-expand {
  right: 0;
  border-right: none;
  border-radius: 4px 0 0 4px;
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
  position: relative;
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
  transition: width 0.3s ease;
  overflow: hidden;
}

.worker-history.collapsed {
  width: 0;
  border-left: none;
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

.history-filter-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 12px;
  border-bottom: 1px solid #ebeef5;
  background: #fafafa;
  gap: 8px;
  flex-wrap: wrap;
}
.filter-group {
  display: flex;
  align-items: center;
  gap: 2px;
}
.filter-tag {
  font-size: 12px;
  color: #909399;
  cursor: pointer;
  padding: 2px 6px;
  border-radius: 4px;
  transition: all 0.15s;
  white-space: nowrap;
}
.filter-tag:hover { color: #409eff; background: #ecf5ff; }
.filter-tag.active { color: #409eff; background: #ecf5ff; font-weight: 600; }
.filter-tag.awaiting.active { color: #e6a23c; background: #fdf6ec; }

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
  flex-wrap: wrap;
  justify-content: flex-end;
  flex-shrink: 0;
}

.task-form {
  background: #fafafa;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 16px;
  margin-bottom: 12px;
  flex-shrink: 0;
}

.form-label {
  display: block;
  font-size: 13px;
  font-weight: 500;
  color: #606266;
  margin-bottom: 6px;
}

.form-action-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
  flex-wrap: wrap;
}

.form-action-bar .el-button--primary {
  border-radius: 16px;
  padding: 0 20px;
  height: 32px;
}

.toolbar-select {
  flex-shrink: 0;
}


/* Compact directory header */
.dir-compact-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  flex-wrap: wrap;
  gap: 8px;
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

/* (agent-teams-bar removed — integrated into mini-toolbar / form-action-bar) */

.agent-teams-configs-panel {
  width: 100%;
}

.atc-row {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 0;
  border-bottom: 1px solid #f0f0f0;
}

.atc-name {
  font-weight: 500;
  font-size: 13px;
  min-width: 80px;
}

.atc-agents {
  flex: 1;
  font-size: 12px;
  color: #909399;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.milestone-manage-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.milestone-manage-row {
  display: grid;
  grid-template-columns: 1fr 80px 1.2fr auto auto;
  gap: 8px;
  align-items: center;
  padding: 4px 0;
}

.milestone-manage-row .ms-name {
  font-size: 13px;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.milestone-manage-row .ms-doc {
  font-size: 11px;
  color: #909399;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.milestone-manage-row .ms-actions {
  display: flex;
  gap: 4px;
  white-space: nowrap;
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
  align-items: stretch;
  gap: 6px;
}

.mini-input-row .slash-input-wrap {
  flex: 1;
  min-width: 0;
}

.mini-run-btn {
  border-radius: 16px !important;
  padding: 0 18px !important;
  flex-shrink: 0;
  align-self: stretch;
  min-height: 32px;
}

/* --- Mini toolbar: second row with utility buttons, selects, tags --- */
.mini-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding-top: 6px;
  flex-wrap: wrap;
}

.mini-tool-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 0 12px;
  height: 32px;
  font-size: 13px;
  border: 1px solid #e4e7ed;
  border-radius: 16px;
  background: #f5f7fa;
  color: #606266;
  cursor: pointer;
  white-space: nowrap;
  touch-action: manipulation;
  -webkit-user-select: none;
  user-select: none;
  transition: background 0.15s;
}

.mini-tool-btn:hover {
  background: #ecf0f5;
  border-color: #c0c4cc;
}

.mini-tool-btn:active {
  background: #e4e7ed;
}

.mini-tool-btn .btn-icon {
  font-size: 14px;
}

.mini-tool-btn .btn-label {
  font-size: 12px;
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

.conv-row-subtitle {
  display: flex;
  align-items: center;
  margin-top: 1px;
  min-width: 0;
}

.conv-latest-prompt {
  font-size: 11px;
  color: #909399;
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

.conv-interaction-badge {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}
.conv-interaction-badge.processing { background: #409eff; animation: convPulse 1.5s infinite; }
.conv-interaction-badge.awaiting_reply { background: #e6a23c; }
.conv-interaction-badge.on_hold { background: #b88230; }
.conv-interaction-badge.archived { background: #909399; }

.pane-interaction-tag {
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 3px;
  flex-shrink: 0;
  white-space: nowrap;
}
.pane-interaction-tag.processing { background: #ecf5ff; color: #409eff; }
.pane-interaction-tag.awaiting_reply { background: #fdf6ec; color: #e6a23c; }
.pane-interaction-tag.on_hold { background: #fdf0e0; color: #b88230; }
.pane-interaction-tag.archived { background: #f4f4f5; color: #909399; }

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

.grouped-conv-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.milestone-group {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.milestone-group-header {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 0 2px;
}

.milestone-group-main {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.milestone-group-title {
  font-size: 12px;
  font-weight: 600;
  color: #303133;
}

.milestone-group-count {
  font-size: 11px;
  color: #909399;
}

.milestone-group-doc {
  font-size: 11px;
  color: #909399;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
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

.image-preview-item.file-item {
  width: auto;
  max-width: 160px;
  height: auto;
  min-height: 48px;
  background: #f5f7fa;
}

.image-preview-item.small.file-item {
  min-height: 36px;
  max-width: 120px;
}

.file-preview {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 6px 24px 6px 8px;
  height: 100%;
}

.file-preview .file-icon {
  font-size: 20px;
  flex-shrink: 0;
}

.small .file-preview .file-icon {
  font-size: 16px;
}

.file-preview .file-name {
  font-size: 11px;
  color: #606266;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  line-height: 1.3;
  word-break: break-all;
}

.small .file-preview .file-name {
  font-size: 10px;
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
  cursor: zoom-in;
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

.image-preview-item:hover .image-remove,
.image-preview-item:hover .image-annotate {
  opacity: 1;
}

.image-annotate {
  position: absolute;
  top: 2px;
  left: 2px;
  width: 18px;
  height: 18px;
  background: rgba(64, 158, 255, 0.7);
  color: #fff;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 10px;
  cursor: pointer;
  opacity: 0;
  transition: opacity 0.15s;
}

/* --- Session fullscreen mode --- */
.worker-layout.fullscreen-active {
  position: fixed;
  inset: 0;
  z-index: 1000;
  background: #fff;
}

.worker-layout.fullscreen-active .worker-sidebar,
.worker-layout.fullscreen-active .worker-history {
  display: none !important;
}

.fullscreen-active .dir-compact-header,
.fullscreen-active .fav-scripts-bar,
.fullscreen-active .task-form,
.fullscreen-active .new-task-mini {
  display: none !important;
}

.fullscreen-exit-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 16px;
  flex-shrink: 0;
  border-bottom: 1px solid #e4e7ed;
  background: #fafafa;
}

.fullscreen-exit-bar-right {
  display: flex;
  align-items: center;
  gap: 8px;
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

.agent-model-tag {
  color: #409eff;
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
  cursor: pointer;
  user-select: none;
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

.active-conv-pinned {
  background: #fdf6ec;
  border-left: 3px solid #e6a23c;
}

.active-conv-pinned:hover {
  background: #faecd8;
}

.attention-worker-header {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 6px 10px 2px;
  margin-top: 6px;
}

.attention-worker-name {
  font-size: 12px;
  font-weight: 600;
  color: #606266;
}

.attention-worker-count {
  font-size: 11px;
  color: #909399;
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

.conv-rel-tag {
  cursor: pointer;
  margin-left: 4px;
  flex-shrink: 0;
}

.child-session-popover {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.child-session-title {
  font-size: 12px;
  color: #606266;
  font-weight: 600;
}

.child-session-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 10px;
  border-radius: 6px;
  cursor: pointer;
  transition: background-color 0.15s ease;
}

.child-session-item:hover {
  background: #f5f7fa;
}

.child-session-name {
  flex: 1;
  min-width: 0;
  font-size: 12px;
  color: #303133;
}

.child-session-time {
  flex-shrink: 0;
  font-size: 11px;
  color: #909399;
}

.forward-source-preview {
  max-height: 160px;
  overflow: auto;
  padding: 10px 12px;
  border-radius: 8px;
  background: #f5f7fa;
  color: #606266;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

.forward-target-summary {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  background: #fafafa;
}

.forward-target-row {
  display: flex;
  gap: 12px;
  align-items: flex-start;
}

.forward-target-label {
  width: 72px;
  flex-shrink: 0;
  color: #909399;
}

.forward-target-value {
  color: #303133;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
}

.milestone-tag {
  max-width: 140px;
}

.sidebar-pane-letter {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  border-radius: 3px;
  font-size: 10px;
  font-weight: 700;
  color: #fff;
  margin-right: 4px;
  flex-shrink: 0;
}

.sidebar-pane-letter.pane-letter-a { background: #409eff; }
.sidebar-pane-letter.pane-letter-b { background: #67c23a; }
.sidebar-pane-letter.pane-letter-c { background: #e6a23c; }
.sidebar-pane-letter.pane-letter-d { background: #a855f6; }

.conv-pane-focused {
  background: rgba(64, 158, 255, 0.08);
  border-left: 2px solid #409eff;
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

/* Attachment lightbox — unscoped because it's teleported to body */
.att-lightbox {
  position: fixed;
  inset: 0;
  z-index: 9999;
  background: rgba(0, 0, 0, 0.7);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: zoom-out;
}

.att-lightbox-img {
  max-width: 90vw;
  max-height: 90vh;
  object-fit: contain;
  border-radius: 8px;
  box-shadow: 0 4px 24px rgba(0, 0, 0, 0.4);
  cursor: default;
}

.att-lightbox-close {
  position: absolute;
  top: 16px;
  right: 24px;
  font-size: 32px;
  color: #fff;
  cursor: pointer;
  line-height: 1;
  opacity: 0.8;
  transition: opacity 0.15s;
}

.att-lightbox-close:hover {
  opacity: 1;
}
</style>
