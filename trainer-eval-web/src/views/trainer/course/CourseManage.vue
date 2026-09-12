<template>
  <div class="page-container">
    <div class="page-header">
      <div>
        <h3>课程管理</h3>
        <p>创建课程、维护内容并管理课程发布状态。</p>
      </div>
      <el-button type="primary" @click="$router.push('/trainer/course-editor')">新建课程</el-button>
    </div>

    <el-card shadow="never" class="table-card">
      <el-table :data="courses" v-loading="loading">
        <el-table-column label="封面" width="140">
          <template #default="{ row }">
            <div class="cover-cell">
              <img v-if="row.coverImage" :src="resolveFileUrl(row.coverImage)" alt="课程封面" />
              <div v-else class="cover-empty">暂无封面</div>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="courseName" label="课程名称" min-width="220" />
        <el-table-column prop="enrollCount" label="报名人数" width="100" />
        <el-table-column prop="totalChapters" label="章节数" width="90" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : row.status === 2 ? 'info' : 'warning'">
              {{ row.status === 1 ? '已发布' : row.status === 2 ? '已归档' : '草稿' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="320" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="$router.push(`/trainer/course-editor/${row.id}`)">编辑</el-button>
            <el-button v-if="row.status === 0" size="small" type="success" @click="handleStatus(row.id, 1)">发布</el-button>
            <el-button v-if="row.status === 1" size="small" type="warning" @click="handleStatus(row.id, 2)">归档</el-button>
            <el-button v-if="row.status === 0" size="small" type="danger" @click="handleDelete(row.id)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { deleteCourse, getManagedCourses, updateCourseStatus } from '@/api/course'

const courses = ref([])
const loading = ref(false)

function resolveFileUrl(fileUrl) {
  if (!fileUrl) return ''
  if (fileUrl.startsWith('http')) return fileUrl
  return `${window.location.origin}${fileUrl}`
}

async function fetchData() {
  loading.value = true
  try {
    const data = await getManagedCourses({ page: 1, size: 100 })
    courses.value = data.records || []
  } finally {
    loading.value = false
  }
}

async function handleStatus(id, status) {
  await updateCourseStatus(id, status)
  ElMessage.success(status === 1 ? '课程已发布' : '课程已归档')
  await fetchData()
}

async function handleDelete(id) {
  await ElMessageBox.confirm('确认删除这门课程吗？删除后不可恢复。', '提示', { type: 'warning' })
  await deleteCourse(id)
  ElMessage.success('课程已删除')
  await fetchData()
}

onMounted(fetchData)
</script>

<style scoped>
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.page-header h3 {
  margin: 0 0 8px;
  color: #0f172a;
}

.page-header p {
  margin: 0;
  color: #64748b;
}

.table-card {
  border-radius: 18px;
}

.cover-cell {
  width: 96px;
  height: 54px;
  border-radius: 10px;
  overflow: hidden;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
}

.cover-cell img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.cover-empty {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  color: #94a3b8;
}
</style>
