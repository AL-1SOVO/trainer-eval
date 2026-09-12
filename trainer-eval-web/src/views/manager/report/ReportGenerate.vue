<template>
  <div class="page-container">
    <div class="report-header">
      <div>
        <h2>报表中心</h2>
        <p>支持生成培训与考试报表，并查看历史导出记录。</p>
      </div>
      <el-button @click="fetchHistory" :loading="loading">刷新记录</el-button>
    </div>

    <el-row :gutter="16">
      <el-col :xs="24" :lg="10">
        <el-card shadow="never">
          <template #header>
            <div class="card-title">生成报表</div>
          </template>
          <el-form ref="formRef" :model="form" :rules="rules" label-width="96px">
            <el-form-item label="报表类型" prop="reportType">
              <el-select v-model="form.reportType" style="width:100%">
                <el-option label="员工培训报表" value="USER_TRAINING" />
                <el-option label="考试成绩报表" value="USER_EXAM" />
                <el-option label="部门培训报表" value="DEPT_TRAINING" />
                <el-option label="课程统计报表" value="COURSE_STATS" />
              </el-select>
            </el-form-item>
            <el-form-item label="导出格式" prop="reportFormat">
              <el-radio-group v-model="form.reportFormat">
                <el-radio value="XLSX">Excel</el-radio>
                <el-radio value="PDF">PDF</el-radio>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="附加说明">
              <el-input v-model="form.params.keyword" type="textarea" :rows="3" placeholder="可填写筛选关键字、答辩演示备注等" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="submitting" @click="handleGenerate">生成报表</el-button>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>

      <el-col :xs="24" :lg="14">
        <el-card shadow="never">
          <template #header>
            <div class="card-title">导出历史</div>
          </template>

          <el-table :data="history" v-loading="loading">
            <el-table-column prop="id" label="ID" width="80" />
            <el-table-column prop="reportType" label="类型" min-width="140">
              <template #default="{ row }">{{ typeLabelMap[row.reportType] || row.reportType }}</template>
            </el-table-column>
            <el-table-column prop="reportFormat" label="格式" width="90" />
            <el-table-column prop="status" label="状态" width="110">
              <template #default="{ row }">
                <el-tag :type="statusTagMap[row.status] || 'info'">{{ statusTextMap[row.status] || row.status }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="fileName" label="文件名" min-width="220" show-overflow-tooltip />
            <el-table-column prop="createTime" label="创建时间" width="180" />
            <el-table-column label="操作" width="180" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" :disabled="row.status !== 'COMPLETED'" @click="download(row)">下载</el-button>
                <el-button link type="danger" @click="remove(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>

          <el-empty v-if="!loading && !history.length" description="暂无导出记录" />

          <div class="pagination-wrap">
            <el-pagination
              v-model:current-page="query.page"
              v-model:page-size="query.size"
              layout="total, prev, pager, next"
              :total="total"
              @current-change="fetchHistory"
            />
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { generateReport, getReportHistory, deleteReport } from '@/api/report'

const formRef = ref()
const loading = ref(false)
const submitting = ref(false)
const history = ref([])
const total = ref(0)

const form = reactive({
  reportType: 'USER_TRAINING',
  reportFormat: 'XLSX',
  params: {
    keyword: ''
  }
})

const query = reactive({
  page: 1,
  size: 10
})

const rules = {
  reportType: [{ required: true, message: '请选择报表类型', trigger: 'change' }],
  reportFormat: [{ required: true, message: '请选择导出格式', trigger: 'change' }]
}

const typeLabelMap = {
  USER_TRAINING: '员工培训报表',
  USER_EXAM: '考试成绩报表',
  DEPT_TRAINING: '部门培训报表',
  COURSE_STATS: '课程统计报表'
}

const statusTextMap = {
  PENDING: '排队中',
  GENERATING: '生成中',
  COMPLETED: '已完成',
  FAILED: '失败'
}

const statusTagMap = {
  PENDING: 'warning',
  GENERATING: 'primary',
  COMPLETED: 'success',
  FAILED: 'danger'
}

async function fetchHistory() {
  loading.value = true
  try {
    const data = await getReportHistory(query)
    history.value = data.records || []
    total.value = data.total || 0
  } catch (error) {
    history.value = []
  } finally {
    loading.value = false
  }
}

async function handleGenerate() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    await generateReport({
      reportType: form.reportType,
      reportFormat: form.reportFormat,
      params: { ...form.params }
    })
    ElMessage.success('报表生成任务已提交')
    await fetchHistory()
  } catch (error) {
    ElMessage.error(error.message || '报表生成失败')
  } finally {
    submitting.value = false
  }
}

function download(row) {
  window.open(`/api/v1/reports/${row.id}/download`, '_blank')
}

async function remove(row) {
  try {
    await ElMessageBox.confirm(`确认删除报表记录 #${row.id} 吗？`, '提示', {
      type: 'warning'
    })
  } catch (error) {
    return
  }

  try {
    await deleteReport(row.id)
    ElMessage.success('删除成功')
    await fetchHistory()
  } catch (error) {
    ElMessage.error(error.message || '删除失败')
  }
}

onMounted(fetchHistory)
</script>

<style scoped>
.report-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 16px;
}

.report-header h2 {
  margin-bottom: 8px;
}

.report-header p {
  color: #64748b;
}

.card-title {
  font-weight: 600;
  color: #0f172a;
}

.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>
