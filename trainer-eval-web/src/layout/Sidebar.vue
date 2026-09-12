<template>
  <div style="color:#fff;text-align:center;padding:16px 0;font-size:18px;font-weight:bold;white-space:nowrap">
    <span v-if="!appStore.sidebarCollapsed">智能培训考核系统</span>
    <span v-else style="font-size:14px">培训</span>
  </div>
  <el-menu :default-active="route.path" background-color="#304156" text-color="#bfcbd9" active-text-color="#409eff"
           :collapse="appStore.sidebarCollapsed" router style="border-right:none">
    <template v-for="menu in appStore.menuTree" :key="menu.id">
      <el-sub-menu v-if="menu.children && menu.children.length" :index="menu.id + ''">
        <template #title><el-icon v-if="menu.icon"><component :is="menu.icon" /></el-icon><span>{{ menu.menuName }}</span></template>
        <el-menu-item v-for="child in menu.children" :key="child.id" :index="resolvePath(menu.path, child.path)">{{ child.menuName }}</el-menu-item>
      </el-sub-menu>
      <el-menu-item v-else :index="menu.path">
        <el-icon v-if="menu.icon"><component :is="menu.icon" /></el-icon><span>{{ menu.menuName }}</span>
      </el-menu-item>
    </template>
  </el-menu>
</template>

<script setup>
import { useRoute } from 'vue-router'
import { useAppStore } from '@/stores/app'
const route = useRoute()
const appStore = useAppStore()

function resolvePath(parentPath, childPath) {
  if (childPath && childPath.startsWith('/')) return childPath
  return (parentPath || '') + '/' + (childPath || '')
}
</script>
