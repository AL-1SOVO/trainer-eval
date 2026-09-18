<template>
  <el-container style="height:100vh">
    <el-aside :width="appStore.sidebarCollapsed ? '64px' : '220px'" class="app-aside">
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

<style scoped>
/* 侧边栏容器：用 flex 列交给 Sidebar 内部去切"标题固定 + 菜单滚动"。
   height 由外层 el-container(100vh) 拉伸决定，这里只需保证是一个确定高度的 flex 容器，
   否则 Sidebar 里的 height:100% 会算不出高度，滚动条不会出现。
   为什么不用原本的 overflow:auto：那会让整块侧边栏（含标题）一起滚，
   而且 el-menu 在折叠态会弹层，外层出现滚动条会带动整列抖动。 */
.app-aside {
  background: #304156;
  transition: width 0.3s;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
</style>
