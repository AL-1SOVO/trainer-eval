import { defineStore } from 'pinia'
import { login as loginApi, logout as logoutApi, getUserInfo, register as registerApi, refreshToken as refreshTokenApi } from '@/api/auth'
import { setToken, setRefreshToken, removeToken, getToken, getRefreshToken } from '@/utils/auth'

export const useUserStore = defineStore('user', {
  state: () => ({
    token: getToken() || '',
    refreshToken: getRefreshToken() || '',
    userId: null,
    username: '',
    realName: '',
    avatar: '',
    roles: [],
    permissions: []
  }),
  actions: {
    async login(loginForm) {
      const data = await loginApi(loginForm)
      this.applyAuthPayload(data)
    },
    async register(registerForm) {
      await registerApi(registerForm)
    },
    async getUserInfo() {
      const userInfo = await getUserInfo()
      this.userId = userInfo.userId
      this.username = userInfo.username
      this.realName = userInfo.realName
      this.avatar = userInfo.avatar
      this.roles = userInfo.roles || []
      this.permissions = userInfo.permissions || []
    },
    async refreshAccessToken() {
      if (!this.refreshToken) throw new Error('No refresh token')
      const data = await refreshTokenApi({ refreshToken: this.refreshToken })
      this.applyAuthPayload(data, true)
      return data.token
    },
    async logout() {
      try { await logoutApi() } catch (e) { /* ignore */ }
      this.resetState()
      removeToken()
    },
    applyAuthPayload(data, keepProfile = false) {
      this.token = data.token
      this.refreshToken = data.refreshToken || this.refreshToken
      setToken(data.token)
      if (data.refreshToken) setRefreshToken(data.refreshToken)

      if (!keepProfile || data.userInfo) {
        this.userId = data.userInfo?.userId ?? this.userId
        this.username = data.userInfo?.username ?? this.username
        this.realName = data.userInfo?.realName ?? this.realName
        this.avatar = data.userInfo?.avatar ?? this.avatar
        this.roles = data.userInfo?.roles || this.roles
        this.permissions = data.userInfo?.permissions || this.permissions
      }
    },
    resetState() {
      this.token = ''
      this.refreshToken = ''
      this.userId = null
      this.username = ''
      this.realName = ''
      this.avatar = ''
      this.roles = []
      this.permissions = []
    }
  },
  persist: { enabled: true, strategies: [{ storage: localStorage, paths: ['roles', 'permissions', 'refreshToken'] }] }
})
