import { defineStore } from 'pinia'
import { getUserMenus } from '@/api/menu'

export const useAppStore = defineStore('app', {
  state: () => ({
    sidebarCollapsed: false,
    menuTree: [],
    // 上次成功加载菜单的时间戳，用于节流刷新
    menuLoadedAt: 0,
    dynamicRoutesAdded: false
  }),
  actions: {
    toggleSidebar() { this.sidebarCollapsed = !this.sidebarCollapsed },
    async loadMenus() {
      try {
        const menus = await getUserMenus()
        this.menuTree = menus || []
        this.menuLoadedAt = Date.now()
        return true
      } catch (e) {
        // 关键：失败时**不动** menuTree，也不更新时间戳。
        // 否则一次网络抖动就会把侧边栏清空；不更新时间戳则意味着下次导航会自动重试。
        return false
      }
    },
    /**
     * 菜单由管理员在后台维护（也有运维直接改库的情况）。原来只在"登录"和"整页刷新"时拉取，
     * 于是改完菜单后老用户必须重新登录才看得见 —— 这会让人以为"功能没做"。
     * 这里做 60s 节流的补偿刷新；首次未加载成功时也会重试。
     */
    async refreshMenusIfStale(maxAgeMs = 60000) {
      const fresh = this.menuTree.length > 0 && Date.now() - this.menuLoadedAt < maxAgeMs
      if (fresh) return
      await this.loadMenus()
    }
  },
  persist: { enabled: true, strategies: [{ storage: localStorage, paths: ['sidebarCollapsed'] }] }
})
