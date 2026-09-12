<template>
  <div class="page-container">
    <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:20px">
      <h3 style="margin:0">题库管理</h3>
      <div>
        <el-button type="success" @click="handleImport">
          <el-icon style="margin-right:4px"><Upload /></el-icon>导入试题
        </el-button>
        <el-button type="primary" @click="handleAdd">
          <el-icon style="margin-right:4px"><Plus /></el-icon>添加题目
        </el-button>
      </div>
    </div>

    <!-- Course + Filter Bar -->
    <el-card shadow="hover" style="margin-bottom:16px">
      <el-form :inline="true">
        <el-form-item label="选择课程">
          <el-select v-model="selectedCourseId" placeholder="请选择课程" style="width:280px" @change="onCourseChange" filterable>
            <el-option v-for="c in courses" :key="c.id" :label="c.courseName" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="题型">
          <el-select v-model="filters.questionType" placeholder="全部" clearable style="width:140px" @change="fetchList">
            <el-option label="单选题" value="SINGLE_CHOICE" />
            <el-option label="多选题" value="MULTI_CHOICE" />
            <el-option label="判断题" value="TRUE_FALSE" />
            <el-option label="简答题" value="SHORT_ANSWER" />
            <el-option label="论述题" value="ESSAY" />
          </el-select>
        </el-form-item>
        <el-form-item label="难度">
          <el-select v-model="filters.difficulty" placeholder="全部" clearable style="width:120px" @change="fetchList">
            <el-option label="1-简单" :value="1" /><el-option label="2" :value="2" />
            <el-option label="3-中等" :value="3" /><el-option label="4" :value="4" />
            <el-option label="5-困难" :value="5" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button @click="fetchList">查询</el-button>
          <el-button @click="downloadTemplateFile">下载模板</el-button>
        </el-form-item>
      </el-form>

      <!-- Quick stats -->
      <div v-if="stats && selectedCourseId" style="margin-top:8px">
        <el-tag v-for="(count, name) in stats.byType" :key="name" style="margin-right:8px" type="info">
          {{ name }}: {{ count }}
        </el-tag>
        <el-tag type="success">共 {{ stats.total }} 题</el-tag>
      </div>
    </el-card>

    <!-- Question Table -->
    <el-card shadow="hover">
      <el-empty v-if="!selectedCourseId" description="请先选择一个课程" />
      <el-empty v-else-if="!loading && tableData.length === 0" description="该课程下暂无题目，请添加或导入" />
      <div v-else v-loading="loading">
        <el-table :data="tableData" stripe style="width:100%">
          <el-table-column type="index" width="50" label="#" />
          <el-table-column prop="content" label="题目内容" min-width="240" show-overflow-tooltip />
          <el-table-column label="题型" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="typeTag(row.questionType)">{{ typeName(row.questionType) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="难度" width="80">
            <template #default="{ row }">
              <el-rate :model-value="row.difficulty || 1" disabled size="small" />
            </template>
          </el-table-column>
          <el-table-column prop="defaultScore" label="分值" width="60" />
          <el-table-column prop="tags" label="标签" width="120" show-overflow-tooltip />
          <el-table-column label="状态" width="70">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '启用' : '禁用' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="160" fixed="right">
            <template #default="{ row }">
              <el-button size="small" text type="primary" @click="handleEdit(row)">编辑</el-button>
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
      </div>
    </el-card>

    <!-- Import Dialog -->
    <el-dialog v-model="importDialogVisible" title="导入试题" width="560px" :close-on-click-modal="false">
      <el-steps :active="importStep" align-center style="margin-bottom:24px">
        <el-step title="下载模板" />
        <el-step title="上传文件" />
        <el-step title="完成导入" />
      </el-steps>

      <div v-if="importStep === 0" style="text-align:center;padding:20px">
        <p style="margin-bottom:16px">请先下载Excel导入模板，按照模板格式填写题目信息</p>
        <el-button type="primary" @click="downloadTemplateFile">
          <el-icon style="margin-right:4px"><Download /></el-icon>下载模板
        </el-button>
        <el-button style="margin-left:12px" @click="importStep = 1">已下载，下一步</el-button>
      </div>

      <div v-else-if="importStep === 1" style="padding:10px">
        <el-alert title="导入说明" type="info" :closable="false" style="margin-bottom:16px">
          <ul style="margin:4px 0;padding-left:18px;font-size:13px">
            <li>题型支持：单选题、多选题、判断题、简答题、论述题</li>
            <li>题目内容、题型、正确答案为必填项</li>
            <li>判断题答案填"正确"或"错误"，选择题答案填选项字母</li>
          </ul>
        </el-alert>
        <el-form label-width="90px">
          <el-form-item label="导入课程">
            <el-select v-model="importCourseId" placeholder="请选择课程" style="width:100%">
              <el-option v-for="c in courses" :key="c.id" :label="c.courseName" :value="c.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="选择文件">
            <el-upload ref="uploadRef" :auto-upload="false" :limit="1" accept=".xlsx,.xls"
                       :on-change="onFileChange" :on-exceed="() => ElMessage.warning('只能选择一个文件')"
                       drag>
              <el-icon style="font-size:40px;color:#c0c4cc"><UploadFilled /></el-icon>
              <div style="color:#606266;margin-top:8px">将Excel文件拖到此处或点击上传</div>
              <template #tip><div style="font-size:12px;color:#909399;margin-top:4px">仅支持 .xlsx 或 .xls 格式</div></template>
            </el-upload>
          </el-form-item>
        </el-form>
        <div style="text-align:center;margin-top:16px">
          <el-button @click="importStep = 0">上一步</el-button>
          <el-button type="primary" :loading="importing" @click="doImport" :disabled="!uploadFile || !importCourseId">
            开始导入
          </el-button>
        </div>
      </div>

      <div v-else-if="importStep === 2" style="text-align:center;padding:30px">
        <el-result icon="success" title="导入完成">
          <template #sub-title>成功导入 {{ importResult }} 道题目</template>
        </el-result>
        <el-button type="primary" @click="onImportDone">完成</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getQuestions, deleteQuestion, importQuestions, downloadTemplate, getQuestionStats } from '@/api/question'
import { getUserMenus as getMyCourses } from '@/api/menu'

const router = useRouter()
const loading = ref(false)
const tableData = ref([])
const courses = ref([])
const selectedCourseId = ref(null)
const stats = ref(null)

const filters = reactive({ questionType: '', difficulty: null })

const pagination = reactive({ page: 1, pageSize: 20, total: 0 })

const importDialogVisible = ref(false)
const importStep = ref(0)
const importCourseId = ref(null)
const uploadFile = ref(null)
const importResult = ref(0)
const importing = ref(false)
const uploadRef = ref()

function typeName(t) {
  const m = { SINGLE_CHOICE: '单选题', MULTI_CHOICE: '多选题', TRUE_FALSE: '判断题', SHORT_ANSWER: '简答题', ESSAY: '论述题' }
  return m[t] || t
}
function typeTag(t) {
  const m = { SINGLE_CHOICE: '', MULTI_CHOICE: 'success', TRUE_FALSE: 'warning', SHORT_ANSWER: 'info', ESSAY: 'danger' }
  return m[t] || ''
}

async function loadCourses() {
  try {
    const { data } = await getMyCourses()
    // menus endpoint returns menu tree; we need courses from the training section
    // Use a simple approach: get enrolled/managed courses from appropriate API
    import('@/api/course').then(async (api) => {
      try {
        const r1 = await api.getManagedCourses({ page: 1, size: 100 })
        courses.value = r1.records || []
      } catch (e) {
        // fallback
      }
    })
  } catch (e) { /* ignore */ }
}

async function fetchList() {
  if (!selectedCourseId.value) return
  loading.value = true
  try {
    const params = {
      courseId: selectedCourseId.value,
      page: pagination.page,
      size: pagination.pageSize
    }
    if (filters.questionType) params.questionType = filters.questionType
    if (filters.difficulty) params.difficulty = filters.difficulty

    const result = await getQuestions(params)
    tableData.value = result.records || []
    pagination.total = result.total || 0
  } catch (e) {
    ElMessage.error('获取题目列表失败')
  } finally {
    loading.value = false
  }
}

async function fetchStats() {
  if (!selectedCourseId.value) return
  try {
    const data = await getQuestionStats(selectedCourseId.value)
    stats.value = data
  } catch (e) { stats.value = null }
}

function onCourseChange() {
  pagination.page = 1
  fetchList()
  fetchStats()
}

function handleAdd() {
  if (!selectedCourseId.value) {
    ElMessage.warning('请先选择课程')
    return
  }
  router.push(`/trainer/question-editor/new?courseId=${selectedCourseId.value}`)
}

function handleEdit(row) {
  router.push(`/trainer/question-editor/${row.id}`)
}

async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(`确定删除题目"${row.content?.substring(0, 30)}..."吗？`, '确认删除', { type: 'warning' })
    await deleteQuestion(row.id)
    ElMessage.success('删除成功')
    fetchList()
    fetchStats()
  } catch (e) { /* cancelled */ }
}

function handleImport() {
  if (!selectedCourseId.value) {
    ElMessage.warning('请先选择课程')
    return
  }
  importStep.value = 0
  importCourseId.value = selectedCourseId.value
  uploadFile.value = null
  importDialogVisible.value = true
}

function onFileChange(file) {
  uploadFile.value = file.raw
}

async function doImport() {
  if (!uploadFile.value || !importCourseId.value) return
  importing.value = true
  try {
    const fd = new FormData()
    fd.append('file', uploadFile.value)
    fd.append('courseId', importCourseId.value)
    const result = await importQuestions(fd)
    importResult.value = result.imported || 0
    importStep.value = 2
  } catch (e) {
    ElMessage.error(e.message || '导入失败')
  } finally {
    importing.value = false
  }
}

function onImportDone() {
  importDialogVisible.value = false
  if (importCourseId.value === selectedCourseId.value) {
    fetchList()
    fetchStats()
  }
}

async function downloadTemplateFile() {
  try {
    const blob = await downloadTemplate()
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = '试题导入模板.xlsx'
    a.click()
    URL.revokeObjectURL(url)
  } catch (e) {
    ElMessage.error('下载模板失败')
  }
}

onMounted(async () => {
  // Try to load managed courses for the trainer
  try {
    const api = await import('@/api/course')
    const r = await api.getManagedCourses({ page: 1, size: 100 })
    courses.value = r.records || []
    if (courses.value.length > 0) {
      selectedCourseId.value = courses.value[0].id
      fetchList()
      fetchStats()
    }
  } catch (e) {
    // will show empty state
  }
})
</script>
