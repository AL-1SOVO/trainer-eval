<template>
  <div class="page-container">
    <div class="search-bar flex-between">
      <el-input v-model="keyword" placeholder="搜索课程..." style="width:300px" clearable @change="fetchData" />
      <el-select v-model="deptId" placeholder="按部门筛选" clearable style="width:200px" @change="fetchData">
        <el-option v-for="d in depts" :key="d.id" :label="d.deptName" :value="d.id" />
      </el-select>
    </div>
    <el-row :gutter="16">
      <el-col v-for="course in courses" :key="course.id" :span="6" style="margin-bottom:16px">
        <el-card shadow="hover" @click="$router.push(`/employee/courses/${course.id}`)" style="cursor:pointer">
          <img :src="course.coverImage || 'https://via.placeholder.com/300x160'" style="width:100%;height:160px;object-fit:cover;border-radius:4px" />
          <h3 style="margin:12px 0 8px">{{ course.courseName }}</h3>
          <p style="color:#999;font-size:12px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">{{ course.description }}</p>
          <div style="margin-top:8px;color:#666;font-size:12px">{{ course.enrollCount || 0 }} 人已报名</div>
        </el-card>
      </el-col>
    </el-row>
    <el-empty v-if="!courses.length" description="暂无课程" />
    <el-pagination v-if="total > 0" style="margin-top:16px;text-align:right" v-model:current-page="page" :page-size="size" :total="total" @current-change="fetchData" />
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { getPublishedCourses } from '@/api/course'
import { getDeptTree } from '@/api/dept'

const keyword = ref('')
const deptId = ref(null)
const courses = ref([])
const depts = ref([])
const page = ref(1)
const size = ref(12)
const total = ref(0)

async function fetchData() {
  try {
    const data = await getPublishedCourses({ keyword: keyword.value, deptId: deptId.value, page: page.value, size: size.value })
    courses.value = data.records
    total.value = data.total
  } catch(e) {}
}

onMounted(async () => {
  try { depts.value = await getDeptTree() } catch(e) {}
  fetchData()
})
</script>
