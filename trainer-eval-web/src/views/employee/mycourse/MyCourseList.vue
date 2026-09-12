<template>
  <div class="page-container">
    <div class="page-header">
      <div>
        <h2>我的课程</h2>
        <p>查看已报名课程，继续学习并跟踪学习进度。</p>
      </div>
    </div>

    <el-card shadow="never" class="table-card">
      <el-table :data="enrollments" v-loading="loading" style="width: 100%">
        <el-table-column prop="courseName" label="课程名称" min-width="220" />
        <el-table-column label="学习进度" width="220">
          <template #default="{ row }">
            <el-progress :percentage="Math.round(Number(row.progress || 0))" :status="row.status === 1 ? 'success' : ''" />
          </template>
        </el-table-column>
        <el-table-column label="课程状态" width="130">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'warning'">
              {{ row.status === 1 ? '已完成' : '学习中' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="enrollTime" label="报名时间" width="180" />
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="router.push(`/employee/course-learn/${row.courseId}`)">
              {{ row.status === 1 ? '回顾课程' : '继续学习' }}
            </el-button>
            <el-button link type="primary" @click="router.push(`/employee/courses/${row.courseId}`)">
              查看详情
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!loading && !enrollments.length" description="还没有报名任何课程" />
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getMyEnrollments } from '@/api/course'

const router = useRouter()
const enrollments = ref([])
const loading = ref(false)

async function fetchData() {
  loading.value = true
  try {
    enrollments.value = await getMyEnrollments()
  } finally {
    loading.value = false
  }
}

onMounted(fetchData)
</script>

<style scoped>
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.page-header h2 {
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
</style>
