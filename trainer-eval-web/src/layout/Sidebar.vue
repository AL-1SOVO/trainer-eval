<template>
  <div style="color:#fff;text-align:center;padding:16px 0;font-size:18px;font-weight:bold;white-space:nowrap">
    <span v-if="!appStore.sidebarCollapsed">智能培训考核系统</span>
    <span v-else style="font-size:14px">培训</span>
  </div>
  <el-menu ref="menuRef" :default-active="route.path" :default-openeds="openeds"
           background-color="#304156" text-color="#bfcbd9" active-text-color="#409eff"
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
import { computed, nextTick, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useAppStore } from '@/stores/app'
const route = useRoute()
const appStore = useAppStore()

const menuRef = ref(null)

function resolvePath(parentPath, childPath) {
  if (childPath && childPath.startsWith('/')) return childPath
  return (parentPath || '') + '/' + (childPath || '')
}

// 当前页面所在的分组 id。用于自动展开 —— 否则"只有一个分组"的账号（例如员工，
// 只授权了 AI学习助手 + 知识搜索）进来只看到一行"员工门户"，会以为入口不存在。
const openeds = computed(() => {
  const ids = []
  for (const menu of appStore.menuTree || []) {
    if (menu.children && menu.children.length) {
      if (menu.children.some(child => resolvePath(menu.path, child.path) === route.path)) {
        ids.push(String(menu.id))
      }
    }
  }
  return ids
})

// default-openeds 只在首次渲染生效，切换页面时要主动 open 一次，
// 这样从 学习中心 跳到 知识搜索 时分组仍然是展开的。
watch(
  () => [route.path, appStore.menuTree.length],
  () => {
    nextTick(() => {
      for (const id of openeds.value) {
        menuRef.value?.open(id)
      }
    })
  },
  { immediate: true }
)
</script>
