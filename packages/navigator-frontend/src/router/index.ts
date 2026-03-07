import { createRouter, createWebHashHistory } from 'vue-router'
import { isLoggedIn } from '@/utils/auth'
import { getSetupStatus } from '@/api/platform'
import AppLayout from '@/layouts/AppLayout.vue'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/Login.vue'),
    meta: { public: true },
  },
  {
    path: '/setup',
    name: 'Setup',
    component: () => import('@/views/SetupView.vue'),
    meta: { requireAuth: true, skipSetupCheck: true },
  },
  {
    path: '/',
    component: AppLayout,
    meta: { requireAuth: true },
    children: [
      {
        path: '',
        redirect: '/workers',
      },
      {
        path: 'workers',
        name: 'Workers',
        component: () => import('@/views/ClaudeWorkerView.vue'),
      },
      {
        path: 'chat',
        name: 'Home',
        component: () => import('@/views/ChatView.vue'),
      },
      {
        path: 'c/:id',
        name: 'Chat',
        component: () => import('@/views/ChatView.vue'),
      },
      {
        path: 'tasks',
        name: 'Tasks',
        component: () => import('@/views/TasksView.vue'),
      },
      {
        path: 'cross-tasks',
        name: 'CrossProjectTasks',
        component: () => import('@/views/CrossProjectTaskView.vue'),
      },
      {
        path: 'monitoring',
        name: 'Monitoring',
        component: () => import('@/views/MonitoringView.vue'),
      },
      {
        path: 'files',
        name: 'FileBrowser',
        component: () => import('@/views/FileBrowserView.vue'),
        meta: { skipSetupCheck: true },
      },
      {
        path: 'settings',
        name: 'Settings',
        component: () => import('@/views/SettingsView.vue'),
        meta: { skipSetupCheck: true },
      },
    ],
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
  // Collect meta from matched route chain (supports nested routes)
  const requireAuth = to.matched.some((r) => r.meta.requireAuth)
  const isPublic = to.matched.some((r) => r.meta.public)
  const skipSetupCheck = to.matched.some((r) => r.meta.skipSetupCheck)

  // 1. 未登录 → 去登录页
  if (requireAuth && !isLoggedIn()) {
    next('/login')
    return
  }
  // 2. 已登录访问登录页 → 去首页
  if (to.path === '/login' && isLoggedIn()) {
    next('/workers')
    return
  }
  // 3. 已登录 + 需要检查 setup 状态的页面
  if (isLoggedIn() && !skipSetupCheck && !isPublic) {
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
