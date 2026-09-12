import { useUserStore } from '@/stores/user'

export default {
  mounted(el, binding) {
    const { value } = binding
    if (value && Array.isArray(value) && value.length > 0) {
      const userStore = useUserStore()
      const roles = userStore.roles || []
      const perms = userStore.permissions || []
      const hasPermission = value.some(v => roles.includes(v) || perms.includes(v))
      if (!hasPermission) {
        el.parentNode?.removeChild(el)
      }
    }
  }
}
