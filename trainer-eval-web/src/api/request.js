import axios from 'axios'
import { getToken, removeToken } from '@/utils/auth'
import { useUserStore } from '@/stores/user'
import { ElMessage } from 'element-plus'

const service = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api/v1',
  timeout: 30000
})

service.interceptors.request.use(config => {
  const token = getToken()
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
}, error => Promise.reject(error))

function handleUnauthorized() {
  removeToken()
  localStorage.removeItem('user')
  if (window.location.pathname !== '/login') {
    window.location.replace('/login')
  }
}

let refreshingPromise = null

service.interceptors.response.use(response => {
  const { code, message, data } = response.data
  if (code === 200) return data

  if (code === 401) {
    const originalRequest = response.config || {}
    if (originalRequest.skipAuthRedirect) {
      return Promise.reject(new Error(message))
    }
    if (!originalRequest._retry) {
      const userStore = useUserStore()
      if (userStore.refreshToken) {
        originalRequest._retry = true
        refreshingPromise ||= userStore.refreshAccessToken().finally(() => { refreshingPromise = null })
        return refreshingPromise.then(token => {
          originalRequest.headers = originalRequest.headers || {}
          originalRequest.headers.Authorization = `Bearer ${token}`
          return service(originalRequest)
        }).catch(error => {
          handleUnauthorized()
          return Promise.reject(error)
        })
      }
    }
    handleUnauthorized()
    return Promise.reject(new Error(message))
  }

  ElMessage.error(message || '请求失败')
  return Promise.reject(new Error(message))
}, error => {
  if (error.config?.skipAuthRedirect) {
    return Promise.reject(error)
  }
  if (error.response?.status === 401) {
    handleUnauthorized()
  } else {
    ElMessage.error(error.message || '网络错误')
  }
  return Promise.reject(error)
})

export default service
