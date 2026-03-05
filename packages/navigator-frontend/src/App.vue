<script setup lang="ts">
</script>

<template>
  <router-view />
</template>

<style>
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
}

html, body, #app {
  height: 100%;
  overflow: hidden; /* prevent page-level scrollbar on iOS/iPadOS */
}

/* 强制 Tooltip 向下显示（包括表格 overflow-tooltip），不影响 Dropdown 等其他 Popper */
.el-tooltip__popper[data-popper-placement^='top'] {
  top: 100% !important;
  bottom: auto !important;
  transform: translateY(0) !important;
}

/* 调整箭头方向（仅 Tooltip） */
.el-tooltip__popper[data-popper-placement^='top'] .el-popper__arrow {
  top: -6px !important;
  bottom: auto !important;
  transform: rotate(180deg) !important;
}

/* 调整 data-popper-placement 属性以匹配实际显示位置（仅 Tooltip） */
.el-tooltip__popper[data-popper-placement^='top'][style*='top:'] {
  top: calc(100% + 10px) !important;
}
</style>
