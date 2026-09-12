import { defineStore } from 'pinia'
import { getUserMenus } from '@/api/menu'

export const useAppStore = defineStore('app', {
  state: () => ({
    sidebarCollapsed: false,
    menuTree: [],
    dynamicRoutesAdded: false
  }),
  actions: {
    toggleSidebar() { this.sidebarCollapsed = !this.sidebarCollapsed },
    async loadMenus() {
      try { this.menuTree = await getUserMenus() } catch (e) { this.menuTree = [] }
    }
  },
  persist: { enabled: true, strategies: [{ storage: localStorage, paths: ['sidebarCollapsed'] }] }
})
