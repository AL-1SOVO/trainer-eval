<template>
  <div class="page-container">
    <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:20px">
      <h3 style="margin:0">试卷管理</h3>
      <div>
        <el-button type="primary" @click="handleAdd">
          <el-icon style="margin-right:4px"><Plus /></el-icon>创建试卷
        </el-button>
      </div>
    </div>

    <!-- Filter Bar -->
    <el-card shadow="hover" style="margin-bottom:16px">
      <el-form :inline="true">
        <el-form-item label="选择课程">
          <el-select v-model="filters.courseId" placeholder="全部课程" clearable style="width:240px" @change="onFilterChange" filterable>
            <el-option v-for="c in courses" :key="c.id" :label="c.courseName" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="filters.status" placeholder="全部" clearable style="width:120px" @change="onFilterChange">
            <el-option label="启用" :value="1" />
            <el-option label="禁用" :value="0" />
          </el-select>
        </el-form-item>
        <el-form-item label="关键词">
          <el-input v-model="filters.keyword" placeholder="试卷名称" clearable style="width:200px" @keyup.enter="onFilterChange" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="onFilterChange">查询</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- Paper Table -->
    <el-card shadow="hover">
      <div v-loading="loading">
        <el-empty v-if="!loading && tableData.length === 0" description="暂无试卷，请创建试卷" />
        <template v-else>
          <el-table :data="tableData" stripe style="width:100%">
            <el-table-column type="index" width="50" label="#" />
            <el-table-column prop="paperName" label="试卷名称" min-width="200" show-overflow-tooltip />
            <el-table-column prop="courseName" label="所属课程" width="160" show-overflow-tooltip />
            <el-table-column label="题目数" width="80">
              <template #default="{ row }">
                <el-tag size="small" type="info">{{ row.questionScores ? row.questionScores[0] : 0 }}题</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="totalScore" label="总分" width="70" />
            <el-table-column prop="passScore" label="及格分" width="70" />
            <el-table-column label="时长" width="90">
              <template #default="{ row }">{{ row.duration }}分钟</template>
            </el-table-column>
            <el-table-column label="随机" width="110">
              <template #default="{ row }">
                <span v-if="row.shuffleQuestions === 1" style="color:#67c23a">题目随机</span>
                <span v-else style="color:#909399">固定顺序</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="70">
              <template #default="{ row }">
                <el-tag size="small" :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '启用' : '禁用' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="220" fixed="right">
              <template #default="{ row }">
                <el-button size="small" text type="primary" @click="handleEdit(row)">编辑</el-button>
                <el-button size="small" text :type="row.status === 1 ? 'warning' : 'success'"
                           @click="handleToggleStatus(row)">
                  {{ row.status === 1 ? '禁用' : '启用' }}
                </el-button>
                <el-button size="small" text type="danger" @click="handleDelete(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div style="margin-top:12px;text-align:center">
            <el-pagination
              v-model:current-page="pagination.page"
              v-model:page-size="pagination.pageSize"
              :total="pagination.total"
              :page-sizes="[10, 20, 50]"
              layout="total, sizes, prev, pager, next"
              @size-change="fetchList"
              @current-change="fetchList"
            />
          </div>
        </template>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getPapers, deletePaper, updatePaperStatus } from '@/api/paper'

const router = useRouter()
const loading = ref(false)
const tableData = ref([])
const courses = ref([])

const filters = reactive({ courseId: null, status: null, keyword: '' })

const pagination = reactive({ page: 1, pageSize: 20, total: 0 })

function onFilterChange() {
  pagination.page = 1
  fetchList()
}

async function fetchList() {
  loading.value = true
  try {
    const params = {
      page: pagination.page,
      size: pagination.pageSize
    }
    if (filters.courseId) params.courseId = filters.courseId
    if (filters.status !== null && filters.status !== '') params.status = filters.status
    if (filters.keyword) params.keyword = filters.keyword

    const result = await getPapers(params)
    tableData.value = result.records || []
    pagination.total = result.total || 0
  } catch (e) {
    ElMessage.error('获取试卷列表失败')
  } finally {
    loading.value = false
  }
}

async function loadCourses() {
  try {
    const api = await import('@/api/course')
    const r = await api.getManagedCourses({ page: 1, size: 100 })
    courses.value = r.records || []
  } catch (e) { /* ignore */ }
}

function handleAdd() {
  router.push('/trainer/paper-editor/new')
}

function handleEdit(row) {
  router.push(`/trainer/paper-editor/${row.id}`)
}

async function handleToggleStatus(row) {
  const newStatus = row.status === 1 ? 0 : 1
  const action = newStatus === 0 ? '禁用' : '启用'
  try {
    await ElMessageBox.confirm(`确定${action}试卷"${row.paperName}"吗？`, '确认操作', { type: 'warning' })
    await updatePaperStatus(row.id, newStatus)
    ElMessage.success(`${action}成功`)
    fetchList()
  } catch (e) { /* cancelled */ }
}

async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(`确定删除试卷"${row.paperName}"吗？删除后无法恢复。`, '确认删除', {
      type: 'warning',
      confirmButtonText: '确定删除',
      confirmButtonClass: 'el-button--danger'
    })
    await deletePaper(row.id)
    ElMessage.success('删除成功')
    fetchList()
  } catch (e) {
    if (e && e.message) ElMessage.error(e.message)
  }
}

onMounted(async () => {
  await loadCourses()
  fetchList()
})
</script>
