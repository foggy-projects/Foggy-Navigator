import { createRouter, createWebHashHistory } from 'vue-router'

const routes = [
  {
    path: '/',
    redirect: '/dashboard'
  },
  {
    path: '/dashboard',
    name: 'Dashboard',
    component: () => import('@/views/Dashboard.vue'),
    meta: { title: '系统监控' }
  },
  {
    path: '/conversations',
    name: 'Conversations',
    component: () => import('@/views/Conversations.vue'),
    meta: { title: '会话管理' }
  },
  {
    path: '/conversations/:id',
    name: 'ConversationDetail',
    component: () => import('@/views/ConversationDetail.vue'),
    meta: { title: '会话详情' }
  },
  {
    path: '/containers',
    name: 'Containers',
    component: () => import('@/views/Containers.vue'),
    meta: { title: '容器管理' }
  },
  {
    path: '/events',
    name: 'Events',
    component: () => import('@/views/Events.vue'),
    meta: { title: '事件日志' }
  }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

export default router
