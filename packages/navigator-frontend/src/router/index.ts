import { createRouter, createWebHashHistory } from 'vue-router'
import { isLoggedIn } from '@/utils/auth'

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
]

const router = createRouter({
  history: createWebHashHistory(),
  routes,
})

router.beforeEach((to, _from, next) => {
  if (to.meta.requireAuth && !isLoggedIn()) {
    next('/login')
  } else if (to.path === '/login' && isLoggedIn()) {
    next('/')
  } else {
    next()
  }
})

export default router
