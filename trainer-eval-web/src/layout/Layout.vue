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

    <!-- 个人信息 -->
    <el-dialog v-model="profileVisible" title="个人信息" width="560px">
      <div v-loading="profileLoading">
        <el-descriptions v-if="profile" :column="2" border>
          <el-descriptions-item label="用户名">{{ profile.username || '-' }}</el-descriptions-item>
          <el-descriptions-item label="姓名">{{ profile.realName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="部门">{{ profile.deptName || '未分配' }}</el-descriptions-item>
          <el-descriptions-item label="角色">{{ roleLabel }}</el-descriptions-item>
          <el-descriptions-item label="上次登录" :span="2">
            {{ profile.lastLoginTime ? formatDateTime(profile.lastLoginTime) : '-' }}
          </el-descriptions-item>
        </el-descriptions>
        <el-empty v-else-if="!profileLoading" description="未能加载个人信息，请稍后重试" :image-size="80" />

        <!-- 只让本人改联系方式。用户名/姓名/部门/角色属于组织与权限数据，自助修改等于自助提权，
             需要管理员在「用户管理」里改。这一点在界面上直接说明，避免用户以为功能缺失。 -->
        <el-alert type="info" :closable="false" show-icon class="profile-tip"
                  title="姓名、部门、角色由管理员维护；以下联系方式可自行修改。" />

        <el-form ref="profileFormRef" :model="profileForm" :rules="profileRules" label-width="80px" class="profile-form">
          <el-form-item label="邮箱" prop="email">
            <el-input v-model="profileForm.email" clearable placeholder="选填，用于接收通知" />
          </el-form-item>
          <el-form-item label="手机号" prop="phone">
            <el-input v-model="profileForm.phone" clearable placeholder="选填，11 位手机号" maxlength="11" />
          </el-form-item>
          <el-form-item label="性别">
            <el-radio-group v-model="profileForm.gender">
              <el-radio :value="1">男</el-radio>
              <el-radio :value="2">女</el-radio>
              <el-radio :value="0">保密</el-radio>
            </el-radio-group>
          </el-form-item>
        </el-form>
      </div>

      <template #footer>
        <el-button @click="profileVisible = false">关闭</el-button>
        <el-button type="primary" :loading="savingProfile" :disabled="!profile" @click="submitProfile">保存</el-button>
      </template>
    </el-dialog>

    <!-- 修改密码 -->
    <el-dialog v-model="pwdVisible" title="修改密码" width="460px" @closed="resetPwdForm">
      <el-alert type="warning" :closable="false" show-icon class="profile-tip"
                title="修改成功后当前登录会失效，需要用新密码重新登录。" />
      <el-form ref="pwdFormRef" :model="pwdForm" :rules="pwdRules" label-width="90px" class="profile-form">
        <el-form-item label="原密码" prop="oldPassword">
          <el-input v-model="pwdForm.oldPassword" type="password" show-password autocomplete="off" />
        </el-form-item>
        <el-form-item label="新密码" prop="newPassword">
          <el-input v-model="pwdForm.newPassword" type="password" show-password autocomplete="off"
                    placeholder="至少 8 位" />
        </el-form-item>
        <el-form-item label="确认新密码" prop="confirmPassword">
          <el-input v-model="pwdForm.confirmPassword" type="password" show-password autocomplete="off" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="pwdVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingPwd" @click="submitPwd">确认修改</el-button>
      </template>
    </el-dialog>
  </el-container>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { useAppStore } from '@/stores/app'
import { changePassword, getUserInfo, updateProfile } from '@/api/auth'
import { formatDateTime } from '@/utils/datetime'
import Sidebar from './Sidebar.vue'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()
const appStore = useAppStore()

// ------------------------------------------------------------------ 个人信息

const profileVisible = ref(false)
const savingProfile = ref(false)
const profileLoading = ref(false)
const profile = ref(null)
const profileFormRef = ref(null)
const profileForm = reactive({ email: '', phone: '', gender: 0 })

const ROLE_LABELS = {
  ROLE_EMPLOYEE: '员工',
  ROLE_TRAINER: '内训师',
  ROLE_MANAGER: '管理员'
}

const roleLabel = computed(() => {
  const roles = profile.value?.roles || userStore.roles || []
  if (!roles.length) return '-'
  return roles.map(r => ROLE_LABELS[r] || r).join('、')
})

// 正则放在模块级常量里，而不是写在 validator 内部的语句开头。
// 原因：`if (!value) return cb()` 之后紧跟一行以 `/` 开头的代码时，
// ASI 与"正则字面量 / 除法运算符"存在歧义，Babel 直接报 Unexpected token。
// 抽成常量既避开这个坑，也比内联正则更好读。
const EMAIL_RE = /^[^@\s]+@[^@\s]+\.[^@\s]+$/
const PHONE_RE = /^1[3-9]\d{9}$/

const profileRules = {
  // 邮箱/手机号都选填，但填了就要合法。后端也做了同样的校验
  // （前端校验可以被绕过，两边都做才是真的约束）。
  email: [{
    validator: (rule, value, cb) => {
      if (!value || EMAIL_RE.test(value)) {
        cb()
        return
      }
      cb(new Error('邮箱格式不正确'))
    },
    trigger: 'blur'
  }],
  phone: [{
    validator: (rule, value, cb) => {
      if (!value || PHONE_RE.test(value)) {
        cb()
        return
      }
      cb(new Error('手机号格式不正确'))
    },
    trigger: 'blur'
  }]
}

/**
 * 打开时向服务端取一次，而不是读 pinia。
 * 原因：store 只存了 userId/username/realName/avatar/roles/permissions —— 
 * 邮箱、手机号、部门、上次登录时间都不在里面。读 store 会得到一片空白。
 */
async function openProfile() {
  profileVisible.value = true
  profileLoading.value = true
  try {
    const info = await getUserInfo()
    profile.value = info || null
    profileForm.email = info?.email || ''
    profileForm.phone = info?.phone || ''
    profileForm.gender = info?.gender == null ? 0 : info.gender
  } catch (e) {
    profile.value = null
  } finally {
    profileLoading.value = false
  }
}

async function submitProfile() {
  const valid = await profileFormRef.value?.validate().catch(() => false)
  if (!valid) return
  savingProfile.value = true
  try {
    const updated = await updateProfile({
      email: profileForm.email,
      phone: profileForm.phone,
      gender: profileForm.gender
    })
    // 只更新本地展示：改动的这几个字段本来就不在 store 里，
    // 不需要（也不应该）为了同步去动 token —— applyAuthPayload 会把 data.token 写进 state，
    // 传一个假 token 反而会把登录态弄坏。
    if (updated) profile.value = updated
    ElMessage.success('个人信息已更新')
  } finally {
    savingProfile.value = false
  }
}

// ------------------------------------------------------------------ 修改密码

const pwdVisible = ref(false)
const savingPwd = ref(false)
const pwdFormRef = ref(null)
const pwdForm = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })

const pwdRules = {
  oldPassword: [{ required: true, message: '请输入原密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 8, message: '新密码长度不得少于 8 位', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    { validator: (rule, value, cb) => {
      value === pwdForm.newPassword ? cb() : cb(new Error('两次输入的新密码不一致'))
    }, trigger: 'blur' }
  ]
}

function openPwd() {
  pwdVisible.value = true
}

function resetPwdForm() {
  pwdFormRef.value?.resetFields()
  pwdForm.oldPassword = ''
  pwdForm.newPassword = ''
  pwdForm.confirmPassword = ''
}

async function submitPwd() {
  const valid = await pwdFormRef.value?.validate().catch(() => false)
  if (!valid) return
  savingPwd.value = true
  try {
    await changePassword({ oldPassword: pwdForm.oldPassword, newPassword: pwdForm.newPassword })
    pwdVisible.value = false
    ElMessage.success('密码修改成功，请用新密码重新登录')
    // 后端改密后会作废此前签发的会话（token:gen 自增），
    // 所以这里必须主动登出并回登录页 —— 否则用户停留在页面里，
    // 下一次请求才 401，体验上像是"系统坏了"。
    await userStore.logout()
    router.push('/login')
  } finally {
    savingPwd.value = false
  }
}

function handleCommand(cmd) {
  if (cmd === 'logout') {
    userStore.logout()
    router.push('/login')
  } else if (cmd === 'pwd') {
    openPwd()
  } else if (cmd === 'profile') {
    openProfile()
  }
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

.profile-tip {
  margin: 14px 0;
}

.profile-form {
  margin-top: 4px;
}
</style>
