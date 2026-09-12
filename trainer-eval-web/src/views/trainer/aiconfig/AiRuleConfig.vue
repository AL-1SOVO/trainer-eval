<template>
  <div class="page-container ai-config-page">
    <div class="page-header">
      <div>
        <h3>AI 评分配置</h3>
        <p>为主观题配置 AI 评分规则，并调节评分严格度。</p>
      </div>
      <el-button type="primary" @click="openDialog()">新增规则</el-button>
    </div>

    <el-card shadow="never" class="table-card">
      <el-table :data="rules" v-loading="loading">
        <el-table-column prop="ruleName" label="规则名称" min-width="180" />
        <el-table-column label="课程ID" width="100">
          <template #default="{ row }">{{ row.courseId }}</template>
        </el-table-column>
        <el-table-column label="题型" width="120">
          <template #default="{ row }">{{ questionTypeLabel(row.questionType) }}</template>
        </el-table-column>
        <el-table-column label="评分风格" width="120">
          <template #default="{ row }">
            <el-tag :type="leniencyTag(row.leniencyLevel)">{{ leniencyText(row.leniencyLevel) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="model" label="模型" width="160" />
        <el-table-column prop="temperature" label="温度" width="100" />
        <el-table-column prop="maxTokens" label="最大输出" width="110" />
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="openDialog(row)">编辑</el-button>
            <el-button size="small" type="danger" @click="handleDelete(row.id)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-if="!loading && !rules.length" description="暂未配置 AI 评分规则" />
    </el-card>

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑评分规则' : '新增评分规则'" width="720px">
      <el-form ref="formRef" :model="form" :rules="rulesConfig" label-width="100px">
        <el-form-item label="规则名称" prop="ruleName">
          <el-input v-model="form.ruleName" maxlength="60" show-word-limit />
        </el-form-item>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="课程ID" prop="courseId">
              <el-input-number v-model="form.courseId" :min="1" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="题目类型" prop="questionType">
              <el-select v-model="form.questionType" style="width: 100%">
                <el-option label="简答题" value="SHORT_ANSWER" />
                <el-option label="论述题" value="ESSAY" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>

        <el-form-item label="评分宽松度">
          <div class="leniency-panel">
            <el-slider
              v-model="leniencyValue"
              :min="0"
              :max="2"
              :step="1"
              show-stops
              :marks="leniencyMarks"
            />
            <div class="leniency-tip">
              {{ leniencyDescription }}
            </div>
          </div>
        </el-form-item>

        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="模型">
              <el-input v-model="form.model" placeholder="默认 deepseek-v4-flash" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="Temperature">
              <el-input-number v-model="form.temperature" :min="0" :max="1" :step="0.05" :precision="2" style="width: 100%" />
            </el-form-item>
          </el-col>
        </el-row>

        <el-form-item label="最大输出">
          <el-input-number v-model="form.maxTokens" :min="256" :max="4000" :step="64" style="width: 100%" />
        </el-form-item>

        <el-form-item label="提示词">
          <el-input
            v-model="form.promptTemplate"
            type="textarea"
            :rows="6"
            maxlength="2000"
            show-word-limit
            placeholder="不填写时，系统会根据宽松度自动生成默认评分提示词"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存规则</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { createAiRule, deleteAiRule, getAiRules, updateAiRule } from '@/api/exam'

const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)
const editingId = ref(null)
const formRef = ref()
const rules = ref([])
const leniencyValue = ref(1)

const leniencyMarks = {
  0: '严格',
  1: '标准',
  2: '宽松'
}

const form = reactive({
  ruleName: '',
  courseId: null,
  questionType: 'SHORT_ANSWER',
  leniencyLevel: 'STANDARD',
  model: 'deepseek-v4-flash',
  temperature: 0.3,
  maxTokens: 2000,
  promptTemplate: ''
})

const rulesConfig = {
  ruleName: [{ required: true, message: '请输入规则名称', trigger: 'blur' }],
  courseId: [{ required: true, message: '请输入课程 ID', trigger: 'change' }],
  questionType: [{ required: true, message: '请选择题目类型', trigger: 'change' }]
}

const leniencyDescription = computed(() => {
  if (leniencyValue.value === 0) {
    return '严格模式更看重关键得分点是否完整覆盖，适合高要求考试。'
  }
  if (leniencyValue.value === 2) {
    return '宽松模式会更关注核心意思是否正确，适合训练型或启发式考核。'
  }
  return '标准模式在准确性与表达容错之间保持均衡，适合作为默认评分方式。'
})

function questionTypeLabel(type) {
  return type === 'ESSAY' ? '论述题' : type === 'SHORT_ANSWER' ? '简答题' : type
}

function leniencyText(value) {
  if (value === 'STRICT') return '严格'
  if (value === 'LENIENT') return '宽松'
  return '标准'
}

function leniencyTag(value) {
  if (value === 'STRICT') return 'danger'
  if (value === 'LENIENT') return 'success'
  return 'info'
}

function leniencyToValue(level) {
  if (level === 'STRICT') return 0
  if (level === 'LENIENT') return 2
  return 1
}

function valueToLeniency(value) {
  if (value === 0) return 'STRICT'
  if (value === 2) return 'LENIENT'
  return 'STANDARD'
}

function resetForm() {
  editingId.value = null
  form.ruleName = ''
  form.courseId = null
  form.questionType = 'SHORT_ANSWER'
  form.leniencyLevel = 'STANDARD'
  form.model = 'deepseek-v4-flash'
  form.temperature = 0.3
  form.maxTokens = 2000
  form.promptTemplate = ''
  leniencyValue.value = 1
}

async function fetchData() {
  loading.value = true
  try {
    rules.value = await getAiRules()
  } finally {
    loading.value = false
  }
}

function openDialog(row = null) {
  resetForm()
  if (row) {
    editingId.value = row.id
    form.ruleName = row.ruleName
    form.courseId = row.courseId
    form.questionType = row.questionType
    form.leniencyLevel = row.leniencyLevel || 'STANDARD'
    form.model = row.model || 'deepseek-v4-flash'
    form.temperature = row.temperature ?? 0.3
    form.maxTokens = row.maxTokens ?? 2000
    form.promptTemplate = row.promptTemplate || ''
    leniencyValue.value = leniencyToValue(form.leniencyLevel)
  }
  dialogVisible.value = true
}

async function handleSave() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  saving.value = true
  try {
    form.leniencyLevel = valueToLeniency(leniencyValue.value)
    const payload = { ...form }
    if (editingId.value) {
      await updateAiRule(editingId.value, payload)
      ElMessage.success('评分规则已更新')
    } else {
      await createAiRule(payload)
      ElMessage.success('评分规则已创建')
    }
    dialogVisible.value = false
    await fetchData()
  } finally {
    saving.value = false
  }
}

async function handleDelete(id) {
  await ElMessageBox.confirm('确认删除这条 AI 评分规则吗？', '提示', { type: 'warning' })
  await deleteAiRule(id)
  ElMessage.success('评分规则已删除')
  await fetchData()
}

onMounted(fetchData)
</script>

<style scoped>
.ai-config-page {
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

.leniency-panel {
  width: 100%;
  padding: 8px 8px 0;
}

.leniency-tip {
  margin-top: 12px;
  color: #64748b;
  line-height: 1.7;
}
</style>
