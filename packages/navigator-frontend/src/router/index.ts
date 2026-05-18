import { createRouter, createWebHashHistory } from 'vue-router'
import { isLoggedIn } from '@/utils/auth'
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
    redirect: '/',
  },
  {
    path: '/',
    component: AppLayout,
    meta: { requireAuth: true },
    children: [
      {
        path: '',
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
      },
      {
        path: 'users',
        name: 'Users',
        component: () => import('@/views/UsersView.vue'),
      },
      {
        path: 'settings',
        name: 'Settings',
        component: () => import('@/views/SettingsView.vue'),
      },
    ],
  },
]

const router = createRouter({
  history: createWebHashHistory(),
  routes,
})

router.beforeEach((to, _from, next) => {
  // Collect meta from matched route chain (supports nested routes)
  const requireAuth = to.matched.some((r) => r.meta.requireAuth)

  // 1. 未登录 → 去登录页
  if (requireAuth && !isLoggedIn()) {
    next('/login')
    return
  }
  // 2. 已登录访问登录页 → 去首页
  if (to.path === '/login' && isLoggedIn()) {
    next('/')
    return
  }
  next()
})

export default router
