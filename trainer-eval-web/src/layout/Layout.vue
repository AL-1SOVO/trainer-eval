<template>
  <el-container style="height:100vh">
    <el-aside :width="appStore.sidebarCollapsed ? '64px' : '220px'" style="background:#304156;transition:width 0.3s;overflow:hidden">
      <Sidebar />
    </el-aside>
    <el-container>
      <el-header style="height:50px;background:#fff;border-bottom:1px solid #e8e8e8;padding:0 16px;display:flex;align-items:center;justify-content:space-between">
        <div style="display:flex;align-items:center">
          <el-icon @click="appStore.toggleSidebar()" style="cursor:pointer;font-size:20px;margin-right:16px"><Fold /></el-icon>
          <el-breadcrumb separator="/">
            <el-breadcrumb-item :to="{ path: '/' }">首页</el-breadcrumb-item>
            <el-breadcrumb-item v-if="route.meta.title">{{ route.meta.title }}</el-breadcrumb-item>
          </el-breadcrumb>
        </div>
        <el-dropdown @command="handleCommand">
          <div style="display:flex;align-items:center;cursor:pointer">
            <el-avatar :size="32" :src="userStore.avatar" />
            <span style="margin-left:8px">{{ userStore.realName }}</span>
            <el-icon><ArrowDown /></el-icon>
          </div>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="profile">个人信息</el-dropdown-item>
              <el-dropdown-item command="pwd">修改密码</el-dropdown-item>
              <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </el-header>
      <el-main style="background:#f0f2f5;padding:20px;overflow-y:auto">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { useRouter, useRoute } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { useAppStore } from '@/stores/app'
import Sidebar from './Sidebar.vue'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()
const appStore = useAppStore()

function handleCommand(cmd) {
  if (cmd === 'logout') { userStore.logout(); router.push('/login') }
  else if (cmd === 'pwd') { /* TODO */ }
  else if (cmd === 'profile') { /* TODO */ }
}
</script>
