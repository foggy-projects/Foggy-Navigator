<template>
  <el-container class="app-container">
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
        </div>
      </el-header>

      <el-main>
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
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

.header-content h3 {
  margin: 0;
  font-size: 20px;
  color: #303133;
}

.el-main {
  background-color: #f0f2f5;
  padding: 0;
}

.el-aside {
  background-color: #545c64;
}
</style>
