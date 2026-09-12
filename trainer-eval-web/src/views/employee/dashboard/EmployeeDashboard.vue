<template>
  <div class="page-container">
    <h2 style="margin-bottom:16px">学习仪表盘</h2>
    <el-row :gutter="16">
      <el-col :span="6"><el-card><div style="text-align:center"><h1 style="color:#409eff">{{ stats.totalCourses }}</h1><p>已选课程</p></div></el-card></el-col>
      <el-col :span="6"><el-card><div style="text-align:center"><h1 style="color:#67c23a">{{ stats.completedCourses }}</h1><p>已完成</p></div></el-card></el-col>
      <el-col :span="6"><el-card><div style="text-align:center"><h1 style="color:#e6a23c">{{ stats.totalExams }}</h1><p>参加考试</p></div></el-card></el-col>
      <el-col :span="6"><el-card><div style="text-align:center"><h1 style="color:#f56c6c">{{ stats.avgScore }}</h1><p>平均成绩</p></div></el-card></el-col>
    </el-row>
    <el-card style="margin-top:16px"><h3>最近学习</h3><el-empty v-if="!recentCourses.length" description="暂无学习记录" /></el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { getMyRecords } from '@/api/exam'

const stats = ref({ totalCourses: 0, completedCourses: 0, totalExams: 0, avgScore: '-' })
const recentCourses = ref([])

onMounted(async () => {
  try { const data = await getMyRecords({ page: 1, size: 5 }); recentCourses.value = data?.records || [] } catch(e) {}
})
</script>
