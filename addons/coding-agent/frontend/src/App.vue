<template>
  <!-- 登录页面：全屏显示 -->
  <router-view v-if="isLoginPage" />

  <!-- 主应用：带侧边栏和头部 -->
  <el-container v-else class="app-container">
    <el-aside width="200px">
      <el-menu
        :default-active="$route.path"
        router
        background-color="#545c64"
        text-color="#fff"
        active-text-color="#ffd04b"
      >
        <div class="logo-container">
          <h2>Coding Agent</h2>
        </div>
        <el-menu-item index="/dashboard">
          <span>系统监控</span>
        </el-menu-item>
        <el-menu-item index="/conversations">
          <span>会话管理</span>
        </el-menu-item>
        <el-menu-item index="/containers">
          <span>容器管理</span>
        </el-menu-item>
        <el-menu-item index="/events">
          <span>事件日志</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header>
        <div class="header-content">
          <h3>{{ $route.meta.title || 'Coding Agent 管理控制台' }}</h3>
          <div class="user-info">
            <el-dropdown @command="handleCommand">
              <span class="user-dropdown">
                <el-icon><User /></el-icon>
                {{ userInfo?.username || '用户' }}
                <el-icon class="el-icon--right"><ArrowDown /></el-icon>
              </span>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="logout">退出登录</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>
        </div>
      </el-header>

      <el-main>
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { User, ArrowDown } from '@element-plus/icons-vue'
import { getUserInfo, clearAuth } from '@/utils/auth'

const router = useRouter()
const route = useRoute()

const userInfo = getUserInfo()

// 判断是否是登录页面
const isLoginPage = computed(() => route.path === '/login')

// 处理下拉菜单命令
const handleCommand = async (command: string) => {
  if (command === 'logout') {
    await ElMessageBox.confirm('确认退出登录？', '提示', {
      type: 'warning',
      confirmButtonText: '确认',
      cancelButtonText: '取消'
    })

    clearAuth()
    ElMessage.success('已退出登录')
    router.push('/login')
  }
}
</script>

<style>
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
}

.app-container {
  height: 100vh;
}

.logo-container {
  padding: 20px;
  text-align: center;
  color: #fff;
}

.logo-container h2 {
  font-size: 18px;
  margin: 0;
}

.el-header {
  background-color: #fff;
  border-bottom: 1px solid #e4e7ed;
  display: flex;
  align-items: center;
  padding: 0 20px;
}

.header-content {
  width: 100%;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.header-content h3 {
  margin: 0;
  font-size: 20px;
  color: #303133;
}

.user-info {
  display: flex;
  align-items: center;
}

.user-dropdown {
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border-radius: 4px;
  transition: background-color 0.3s;
}

.user-dropdown:hover {
  background-color: #f5f7fa;
}

.el-main {
  background-color: #f0f2f5;
  padding: 0;
}

.el-aside {
  background-color: #545c64;
}
</style>
