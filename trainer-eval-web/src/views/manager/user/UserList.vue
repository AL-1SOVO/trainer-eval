<template>
  <div class="page-container">
    <div class="search-bar flex-between">
      <div>
        <el-input v-model="query.username" placeholder="用户名" clearable style="width:160px;margin-right:8px" />
        <el-input v-model="query.realName" placeholder="姓名" clearable style="width:160px;margin-right:8px" />
        <el-button type="primary" @click="fetchData">搜索</el-button>
        <el-button @click="query={};fetchData()">重置</el-button>
      </div>
      <!-- 按钮级权限：权限码来自 sys_menu 里的 F 类型菜单项
           （sys:user:add / sys:user:edit / sys:user:delete）。
           说明：这道防线是**界面粒度**的，不是安全边界 ——
           后端 SysUserController 的所有写接口都有 @PreAuthorize("hasRole('MANAGER')") 兜底，
           所以即使有人绕过界面直接调接口也会被挡住。加 v-permission 的意义在于：
           当将来出现"能进用户管理、但只该看不能改"的角色时，界面能如实反映权限，而不是把按钮摆出来点了报错。 -->
      <el-button v-permission="['sys:user:add']" type="primary" @click="handleAdd">新增用户</el-button>
    </div>
    <div class="table-container">
      <el-table :data="users" v-loading="loading">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="username" label="用户名" />
        <el-table-column prop="realName" label="姓名" />
        <el-table-column prop="email" label="邮箱" />
        <el-table-column prop="phone" label="手机号" width="130" />
        <el-table-column label="状态" width="80">
          <template #default="{ row }"><el-tag :type="row.status === 1 ? 'success' : 'danger'">{{ row.status === 1 ? '启用' : '禁用' }}</el-tag></template>
        </el-table-column>
        <el-table-column label="操作" width="290">
          <template #default="{ row }">
            <!-- 编辑 / 启用禁用 / 重置密码都归到 sys:user:edit。
                 它们本质都是"修改这个用户"，没有各自独立的权限项；
                 为它们分别造权限码只会让 sys_menu 里堆一堆实际不会单独授予的项。
                 等真的出现了"能改资料但不能停用账号"这类诉求，再拆也不迟。 -->
            <el-button v-permission="['sys:user:edit']" size="small" @click="handleEdit(row)">编辑</el-button>
            <el-button v-permission="['sys:user:edit']" size="small" :type="row.status === 1 ? 'warning' : 'success'" @click="toggleStatus(row)">{{ row.status === 1 ? '禁用' : '启用' }}</el-button>
            <el-button v-permission="['sys:user:edit']" size="small" @click="handleResetPassword(row)">重置密码</el-button>
            <el-button v-permission="['sys:user:delete']" size="small" type="danger" @click="handleDelete(row.id)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination style="margin-top:16px;text-align:right" v-model:current-page="query.page" :page-size="query.size" :total="total" @current-change="fetchData" />
    </div>

    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑用户' : '新增用户'" width="500px">
      <el-form :model="form" label-width="80px">
        <el-form-item label="用户名"><el-input v-model="form.username" :disabled="isEdit" /></el-form-item>
        <el-form-item v-if="!isEdit" label="密码"><el-input v-model="form.password" placeholder="默认123456" /></el-form-item>
        <el-form-item label="姓名"><el-input v-model="form.realName" /></el-form-item>
        <el-form-item label="邮箱"><el-input v-model="form.email" /></el-form-item>
        <el-form-item label="手机号"><el-input v-model="form.phone" /></el-form-item>
        <el-form-item label="部门"><el-select v-model="form.deptId"><el-option v-for="d in depts" :key="d.id" :label="d.deptName" :value="d.id" /></el-select></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialogVisible = false">取消</el-button><el-button type="primary" @click="handleSave" :loading="saving">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { getUsers, createUser, updateUser, deleteUser, resetPassword } from '@/api/user'
import { getDeptTree } from '@/api/dept'
import request from '@/api/request'
import { ElMessage, ElMessageBox } from 'element-plus'

const users = ref([])
const depts = ref([])
const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)
const isEdit = ref(false)
const total = ref(0)
const query = reactive({ page: 1, size: 10, username: '', realName: '' })
const form = reactive({ username: '', password: '', realName: '', email: '', phone: '', deptId: null })
let editingId = null

async function fetchData() {
  loading.value = true
  try { const data = await getUsers(query); users.value = data.records; total.value = data.total } catch(e) {} finally { loading.value = false }
}

function handleAdd() {
  isEdit.value = false; editingId = null
  Object.keys(form).forEach(k => form[k] = k === 'deptId' ? null : '')
  dialogVisible.value = true
}

function handleEdit(row) {
  isEdit.value = true; editingId = row.id
  Object.assign(form, { username: row.username, realName: row.realName, email: row.email, phone: row.phone, deptId: row.deptId })
  dialogVisible.value = true
}

/**
 * 管理员重置某用户的密码。
 *
 * 后端约定：不填新密码 = 重置为默认密码 123456。
 * 这里用 prompt 收集新密码，并在提示里写明"留空即重置为 123456"——
 * 让"留空"这件事是用户知道的，而不是他以为没填会报错、或者以为会保持原密码。
 *
 * 重置后该用户**所有已登录会话会立即失效**（后端自增会话代次），
 * 所以提示里也写清楚：对方需要用新密码重新登录。
 */
async function handleResetPassword(row) {
  let input
  try {
    const res = await ElMessageBox.prompt(
      `为用户「${row.realName || row.username}」设置新密码。留空则重置为默认密码 123456。`,
      '重置密码',
      {
        confirmButtonText: '确认重置',
        cancelButtonText: '取消',
        inputPlaceholder: '留空则重置为 123456',
        inputValidator: (value) => !value || value.length >= 8 || '新密码长度不得少于 8 位',
        inputType: 'password'
      }
    )
    input = res.value
  } catch (e) {
    return // 用户取消
  }

  try {
    await resetPassword(row.id, input || undefined)
    ElMessage.success(input ? '密码已重置，该用户需用新密码重新登录' : '已重置为默认密码 123456，该用户需重新登录')
  } catch (e) {
    // request.js 已统一提示
  }
}

async function handleSave() {
  saving.value = true
  try {
    if (isEdit.value) await updateUser(editingId, form)
    else await createUser(form)
    ElMessage.success(isEdit.value ? '已更新' : '已创建')
    dialogVisible.value = false; fetchData()
  } catch(e) {} finally { saving.value = false }
}

async function toggleStatus(row) {
  try { await request.put(`/sys/users/${row.id}/status`, { status: row.status === 1 ? 0 : 1 }); ElMessage.success('ok'); fetchData() } catch(e) {}
}

async function handleDelete(id) {
  try { await ElMessageBox.confirm('确认删除？') } catch(e) { return }
  try { await deleteUser(id); ElMessage.success('已删除'); fetchData() } catch(e) {}
}

onMounted(async () => {
  try { depts.value = await getDeptTree() } catch(e) {}
  fetchData()
})
</script>
