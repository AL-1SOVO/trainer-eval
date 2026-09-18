<template>
  <!--
    侧边栏布局：标题固定 + 菜单区独立滚动。
    为什么需要滚动：管理员的菜单树展开后有三组共 19 项，高度超过视口；
    而外层 el-aside 是 overflow:hidden，超出部分直接被裁掉 —— 下面的菜单项点不到。
    标题不跟着滚：它是身份标识，滚动时消失会让人不知道自己在哪个系统里。
  -->
  <div class="sidebar-root">
    <div class="sidebar-brand">
      <span v-if="!appStore.sidebarCollapsed">智能培训考核系统</span>
      <span v-else class="sidebar-brand-mini">培训</span>
    </div>

    <div class="sidebar-scroll">
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
    </div>
  </div>
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

<style scoped>
/* 用 flex 列布局把高度切成"标题固定 + 菜单区可滚动" */
.sidebar-root {
  display: flex;
  flex-direction: column;
  flex: 1 1 auto;
  min-height: 0;
  height: 100%;
  overflow: hidden;
}

.sidebar-brand {
  flex: none;
  color: #fff;
  text-align: center;
  padding: 16px 0;
  font-size: 18px;
  font-weight: bold;
  white-space: nowrap;
}

.sidebar-brand-mini {
  font-size: 14px;
}

/* 关键：min-height:0 不能省。flex 子项默认 min-height:auto，
   内容超出时不会收缩，滚动条就不会出现（这是 flex 里最常踩的一条）。 */
.sidebar-scroll {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
  /* Firefox */
  scrollbar-width: thin;
  scrollbar-color: rgba(255, 255, 255, 0.22) transparent;
}

/* WebKit：深色底上默认的浅色滚动条很扎眼，换成半透明白 */
.sidebar-scroll::-webkit-scrollbar {
  width: 6px;
}

.sidebar-scroll::-webkit-scrollbar-track {
  background: transparent;
}

.sidebar-scroll::-webkit-scrollbar-thumb {
  background: rgba(255, 255, 255, 0.22);
  border-radius: 3px;
}

.sidebar-scroll::-webkit-scrollbar-thumb:hover {
  background: rgba(255, 255, 255, 0.38);
}

/* el-menu 撑满宽度，否则折叠态下 64px 的图标对齐会偏 */
.sidebar-scroll :deep(.el-menu) {
  width: 100%;
  border-right: none;
}
</style>
