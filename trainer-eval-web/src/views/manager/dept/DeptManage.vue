<template>
  <div class="page-container">
    <div class="search-bar flex-between"><h3>部门管理</h3><el-button type="primary" @click="handleAdd">新增部门</el-button></div>
    <div class="table-container">
      <el-table :data="depts" row-key="id" default-expand-all :tree-props="{children:'children'}">
        <el-table-column prop="deptName" label="部门名称"/>
        <el-table-column prop="leader" label="负责人"/>
        <el-table-column prop="phone" label="电话"/>
        <el-table-column label="操作" width="200">
          <template #default="{row}"><el-button size="small" @click="handleEdit(row)">编辑</el-button><el-button size="small" type="danger" @click="handleDelete(row.id)">删除</el-button></template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>
<script setup>
import {ref,onMounted} from 'vue'
import {getDeptTree,deleteDept} from '@/api/dept'
import {ElMessage} from 'element-plus'
const depts=ref([])
async function fetch(){try{depts.value=await getDeptTree()}catch(e){}}
async function handleDelete(id){try{await deleteDept(id);ElMessage.success('已删除');fetch()}catch(e){}}
function handleAdd(){ElMessage.info('新增部门（功能待完善）')}
function handleEdit(row){ElMessage.info('编辑部门（功能待完善）')}
onMounted(fetch)
</script>
