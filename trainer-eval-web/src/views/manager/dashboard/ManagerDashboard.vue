<template>
  <div class="page-container">
    <h2 style="margin-bottom:16px">管理仪表盘</h2>
    <el-row :gutter="16">
      <el-col :span="6"><el-card><div style="text-align:center"><h1 style="color:#409eff">{{ stats.totalUsers }}</h1><p>总用户数</p></div></el-card></el-col>
      <el-col :span="6"><el-card><div style="text-align:center"><h1 style="color:#67c23a">{{ stats.totalCourses }}</h1><p>总课程数</p></div></el-card></el-col>
      <el-col :span="6"><el-card><div style="text-align:center"><h1 style="color:#e6a23c">{{ stats.totalExams }}</h1><p>考试总次数</p></div></el-card></el-col>
      <el-col :span="6"><el-card><div style="text-align:center"><h1 style="color:#f56c6c">{{ stats.passRate }}%</h1><p>通过率</p></div></el-card></el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref } from 'vue'
const stats = ref({ totalUsers: 0, totalCourses: 0, totalExams: 0, passRate: 0 })
</script>
