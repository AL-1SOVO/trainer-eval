<template>
  <div class="page-container grading-page">
    <div class="page-header">
      <div>
        <h3>阅卷中心</h3>
        <p>查看主观题试卷、AI 评分进度，并进入人工复核。</p>
      </div>
      <el-select v-model="status" clearable placeholder="按状态筛选" style="width: 180px" @change="fetchData">
        <el-option label="待 AI 评分 / 待复核" value="SUBMITTED" />
        <el-option label="AI 已评分" value="GRADED" />
        <el-option label="已人工阅卷" value="REVIEWED" />
      </el-select>
    </div>

    <el-card shadow="never" class="table-card">
      <el-table :data="records" v-loading="loading">
        <el-table-column prop="recordId" label="记录ID" width="100" />
        <el-table-column prop="paperName" label="试卷名称" min-width="200" />
        <el-table-column prop="userName" label="考生" width="120" />
        <el-table-column label="AI 进度" width="180">
          <template #default="{ row }">
            <div class="progress-cell">
              <span>{{ row.aiDoneCount }}/{{ row.subjectiveCount }}</span>
              <el-tag size="small" :type="row.aiPending ? 'warning' : 'success'">
                {{ row.aiPending ? 'AI 处理中' : 'AI 已完成' }}
              </el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="人工复核" width="160">
          <template #default="{ row }">
            <span>{{ row.manualDoneCount }}/{{ row.subjectiveCount }}</span>
          </template>
        </el-table-column>
        <el-table-column label="当前总分" width="120">
          <template #default="{ row }">
            {{ row.totalScore ?? 0 }} / {{ row.paperTotalScore ?? '-' }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="submitTime" label="提交时间" width="180">
          <template #default="{ row }">{{ formatDateTime(row.submitTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" size="small" @click="goDetail(row.recordId)">
              {{ row.status === 'REVIEWED' ? '查看' : '去阅卷' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-if="!loading && !records.length" description="暂无需要处理的主观题试卷" />
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getGradingQueue } from '@/api/exam'
import { formatDateTime } from '@/utils/datetime'

const router = useRouter()
const loading = ref(false)
const records = ref([])
const status = ref('')

function statusText(value) {
  if (value === 'SUBMITTED') return '待处理'
  if (value === 'GRADED') return 'AI 已评分'
  if (value === 'REVIEWED') return '已人工阅卷'
  return value || '-'
}

function statusTag(value) {
  if (value === 'SUBMITTED') return 'warning'
  if (value === 'GRADED') return 'success'
  if (value === 'REVIEWED') return 'info'
  return ''
}

async function fetchData() {
  loading.value = true
  try {
    records.value = await getGradingQueue(status.value ? { status: status.value } : {})
  } finally {
    loading.value = false
  }
}

function goDetail(recordId) {
  router.push(`/trainer/grading/${recordId}`)
}

onMounted(fetchData)
</script>

<style scoped>
.grading-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
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

.progress-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}
</style>
