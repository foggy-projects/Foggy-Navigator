import { createRouter, createWebHashHistory } from 'vue-router'
import { isLoggedIn } from '@/utils/auth'
import { getSetupStatus } from '@/api/platform'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/Login.vue'),
    meta: { public: true },
  },
  {
    path: '/',
    name: 'Home',
    component: () => import('@/views/ChatView.vue'),
    meta: { requireAuth: true },
  },
  {
    path: '/c/:id',
    name: 'Chat',
    component: () => import('@/views/ChatView.vue'),
    meta: { requireAuth: true },
  },
  {
    path: '/workers',
    name: 'Workers',
    component: () => import('@/views/ClaudeWorkerView.vue'),
    meta: { requireAuth: true },
  },
  {
    path: '/tasks',
    name: 'Tasks',
    component: () => import('@/views/TasksView.vue'),
    meta: { requireAuth: true },
  },
  {
    path: '/cross-tasks',
    name: 'CrossProjectTasks',
    component: () => import('@/views/CrossProjectTaskView.vue'),
    meta: { requireAuth: true },
  },
  {
    path: '/files',
    name: 'FileBrowser',
    component: () => import('@/views/FileBrowserView.vue'),
    meta: { requireAuth: true, skipSetupCheck: true },
  },
  {
    path: '/setup',
    name: 'Setup',
    component: () => import('@/views/SetupView.vue'),
    meta: { requireAuth: true, skipSetupCheck: true },
  },
  {
    path: '/settings',
    name: 'Settings',
    component: () => import('@/views/SettingsView.vue'),
    meta: { requireAuth: true, skipSetupCheck: true },
  },
]

const router = createRouter({
  history: createWebHashHistory(),
  routes,
})

// Setup 状态缓存：避免每次路由切换都请求后端
let setupComplete: boolean | null = null

export function resetSetupStatus() {
  setupComplete = null
}

router.beforeEach(async (to, _from, next) => {
  // 1. 未登录 → 去登录页
  if (to.meta.requireAuth && !isLoggedIn()) {
    next('/login')
    return
  }
  // 2. 已登录访问登录页 → 去首页
  if (to.path === '/login' && isLoggedIn()) {
    next('/')
    return
  }
  // 3. 已登录 + 需要检查 setup 状态的页面
  if (isLoggedIn() && !to.meta.skipSetupCheck && !to.meta.public) {
    if (setupComplete === null) {
      try {
        const status = await getSetupStatus()
        setupComplete = status.setupComplete
      } catch {
        // API 失败时放行，避免阻塞用户
        setupComplete = true
      }
    }
    if (!setupComplete) {
      next('/setup')
      return
    }
  }
  next()
})

export default router
