<template>
  <div class="page-container">
    <div class="search-bar flex-between">
      <div>
        <el-input v-model="query.username" placeholder="用户名" clearable style="width:160px;margin-right:8px" />
        <el-input v-model="query.realName" placeholder="姓名" clearable style="width:160px;margin-right:8px" />
        <el-button type="primary" @click="fetchData">搜索</el-button>
        <el-button @click="query={};fetchData()">重置</el-button>
      </div>
      <el-button type="primary" @click="handleAdd">新增用户</el-button>
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
        <el-table-column label="操作" width="200">
          <template #default="{ row }">
            <el-button size="small" @click="handleEdit(row)">编辑</el-button>
            <el-button size="small" :type="row.status === 1 ? 'warning' : 'success'" @click="toggleStatus(row)">{{ row.status === 1 ? '禁用' : '启用' }}</el-button>
            <el-button size="small" type="danger" @click="handleDelete(row.id)">删除</el-button>
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
import { getUsers, createUser, updateUser, deleteUser } from '@/api/user'
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
