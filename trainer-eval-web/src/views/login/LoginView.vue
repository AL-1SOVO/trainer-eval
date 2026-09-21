<template>
  <div class="login-shell">
    <section class="login-hero">
      <div class="login-hero__content">
        <p class="login-kicker">Trainer Eval</p>
        <h1>企业级新员工岗前培训与智能考核系统</h1>
        <p>把课程、考试、AI 评分和结果分析串成一条完整的培训闭环，适合答辩演示也适合继续迭代。</p>
      </div>
    </section>

    <el-card class="login-card">
      <div class="login-card__header">
        <h2>{{ isRegister ? '注册学员账号' : '登录系统' }}</h2>
        <p>{{ isRegister ? '完成注册后可直接使用学员身份进入系统。' : '输入账号密码，进入对应角色工作台。' }}</p>
      </div>

      <el-form v-if="!isRegister" ref="loginFormRef" :model="loginForm" :rules="loginRules" size="large">
        <el-form-item prop="username">
          <el-input v-model="loginForm.username" placeholder="用户名" />
        </el-form-item>
        <el-form-item prop="password">
          <el-input v-model="loginForm.password" type="password" show-password placeholder="密码" @keyup.enter="handleLogin" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" class="login-action" :loading="loading" @click="handleLogin">登录</el-button>
        </el-form-item>
      </el-form>

      <el-form v-else ref="registerFormRef" :model="registerForm" :rules="registerRules" size="large">
        <el-form-item prop="username">
          <el-input v-model="registerForm.username" placeholder="用户名" />
        </el-form-item>
        <el-form-item prop="realName">
          <el-input v-model="registerForm.realName" placeholder="姓名" />
        </el-form-item>
        <el-form-item prop="password">
          <el-input v-model="registerForm.password" type="password" show-password placeholder="密码" />
        </el-form-item>
        <el-form-item prop="email">
          <el-input v-model="registerForm.email" placeholder="邮箱" />
        </el-form-item>
        <el-form-item prop="phone">
          <el-input v-model="registerForm.phone" placeholder="手机号" />
        </el-form-item>
        <el-form-item prop="deptId">
          <el-select v-model="registerForm.deptId" placeholder="所属部门" style="width:100%">
            <el-option v-for="dept in deptOptions" :key="dept.id" :label="dept.deptName" :value="dept.id" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" class="login-action" :loading="loading" @click="handleRegister">注册</el-button>
        </el-form-item>
      </el-form>

      <div class="login-switch">
        <span>{{ isRegister ? '已经有账号？' : '还没有账号？' }}</span>
        <el-button link type="primary" @click="toggleMode">{{ isRegister ? '返回登录' : '立即注册' }}</el-button>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { useAppStore } from '@/stores/app'
import { getDeptTree } from '@/api/dept'
import { homePath } from '@/utils/home'
import { ElMessage } from 'element-plus'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()
const appStore = useAppStore()

const loginFormRef = ref()
const registerFormRef = ref()
const loading = ref(false)
const isRegister = ref(false)
const deptOptions = ref([])
const deptLoading = ref(false)

// 刻意不预填密码：源码里写死默认口令，等于在公开仓库里公布演示账号密码。
// 演示账号改由 README 说明（admin / admin123）。
// 用户名保留预填只是为了少打几个字，不涉及敏感信息。
const loginForm = reactive({
  username: 'admin',
  password: ''
})

const registerForm = reactive({
  username: '',
  realName: '',
  password: '',
  email: '',
  phone: '',
  deptId: null
})

const loginRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

const registerRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  realName: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
  deptId: [{ required: true, message: '请选择部门', trigger: 'change' }]
}

async function handleLogin() {
  const valid = await loginFormRef.value.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    await userStore.login(loginForm)
    await appStore.loadMenus()
    ElMessage.success('登录成功')
    // 落地页按角色决定：管理员/内训师各自回自己的仪表盘，员工回学习仪表盘。
    // 原来固定跳 /employee/courses —— 对管理员来说是"别人的学习中心"，
    // 侧边栏里也不会有对应高亮项。
    const redirect = route.query.redirect || homePath(userStore.roles)
    router.push(redirect)
  } catch (error) {
    ElMessage.error(error.message || '登录失败')
  } finally {
    loading.value = false
  }
}

async function handleRegister() {
  const valid = await registerFormRef.value.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    await userStore.register(registerForm)
    ElMessage.success('注册成功，请登录')
    Object.assign(registerForm, {
      username: '',
      realName: '',
      password: '',
      email: '',
      phone: '',
      deptId: null
    })
    isRegister.value = false
  } catch (error) {
    ElMessage.error(error.message || '注册失败')
  } finally {
    loading.value = false
  }
}

function toggleMode() {
  isRegister.value = !isRegister.value
  if (isRegister.value) {
    loadDeptOptions()
  }
}

async function loadDeptOptions() {
  if (deptOptions.value.length || deptLoading.value) return
  deptLoading.value = true
  try {
    deptOptions.value = await getDeptTree({ skipAuthRedirect: true })
  } catch (error) {
    deptOptions.value = []
  } finally {
    deptLoading.value = false
  }
}

onMounted(() => {
  if (isRegister.value) {
    loadDeptOptions()
  }
})
</script>

<style scoped>
.login-shell {
  min-height: 100vh;
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) minmax(360px, 460px);
  background:
    radial-gradient(circle at top left, rgba(37, 99, 235, 0.16), transparent 30%),
    linear-gradient(140deg, #eef5ff 0%, #f8fbff 45%, #edf7f1 100%);
}

.login-hero {
  display: flex;
  align-items: center;
  padding: 64px 72px;
}

.login-hero__content {
  max-width: 560px;
}

.login-kicker {
  margin-bottom: 14px;
  color: #2563eb;
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 0.16em;
  text-transform: uppercase;
}

.login-hero h1 {
  margin: 0 0 18px;
  color: #0f172a;
  font-size: 44px;
  line-height: 1.15;
}

.login-hero p {
  color: #475569;
  font-size: 18px;
  line-height: 1.75;
}

.login-card {
  margin: auto 48px auto 0;
  border-radius: 24px;
  border: 1px solid rgba(148, 163, 184, 0.22);
  box-shadow: 0 28px 72px rgba(15, 23, 42, 0.12);
}

.login-card__header {
  margin-bottom: 24px;
}

.login-card__header h2 {
  margin-bottom: 8px;
  color: #111827;
  font-size: 28px;
}

.login-card__header p {
  color: #64748b;
  line-height: 1.6;
}

.login-action {
  width: 100%;
}

.login-switch {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 4px;
  margin-top: 8px;
  color: #64748b;
}

@media (max-width: 960px) {
  .login-shell {
    grid-template-columns: 1fr;
  }

  .login-hero {
    padding: 40px 24px 8px;
  }

  .login-hero h1 {
    font-size: 32px;
  }

  .login-card {
    margin: 0 24px 32px;
  }
}
</style>
