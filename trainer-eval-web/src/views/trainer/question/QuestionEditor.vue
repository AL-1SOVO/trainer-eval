<template>
  <div class="page-container">
    <el-page-header @back="$router.back()" :content="isEdit ? '编辑题目' : '新增题目'" style="margin-bottom:20px" />

    <el-card shadow="hover">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px" style="max-width:800px">
        <el-form-item label="所属课程" prop="courseId">
          <el-select v-model="form.courseId" placeholder="请选择课程" style="width:100%" :disabled="isEdit">
            <el-option v-for="c in courses" :key="c.id" :label="c.courseName" :value="c.id" />
          </el-select>
        </el-form-item>

        <el-form-item label="题型" prop="questionType">
          <el-radio-group v-model="form.questionType" @change="onTypeChange">
            <el-radio-button value="SINGLE_CHOICE">单选题</el-radio-button>
            <el-radio-button value="MULTI_CHOICE">多选题</el-radio-button>
            <el-radio-button value="TRUE_FALSE">判断题</el-radio-button>
            <el-radio-button value="SHORT_ANSWER">简答题</el-radio-button>
            <el-radio-button value="ESSAY">论述题</el-radio-button>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="题目内容" prop="content">
          <el-input v-model="form.content" type="textarea" :rows="3" placeholder="请输入题目内容" />
        </el-form-item>

        <!-- Options for choice / true-false questions -->
        <template v-if="hasOptions">
          <el-form-item v-for="(opt, idx) in optionLabels" :key="idx" :label="opt">
            <el-input v-model="form.optionsList[idx]" :placeholder="'选项' + opt" />
          </el-form-item>
        </template>

        <el-form-item label="正确答案" prop="referenceAnswer">
          <template v-if="form.questionType === 'SINGLE_CHOICE'">
            <el-select v-model="form.referenceAnswer" placeholder="选择正确答案" style="width:200px">
              <el-option v-for="(opt, idx) in optionLetters" :key="idx" :label="opt" :value="opt" />
            </el-select>
          </template>
          <template v-else-if="form.questionType === 'MULTI_CHOICE'">
            <el-checkbox-group v-model="form.referenceAnswerList">
              <el-checkbox v-for="(opt, idx) in optionLetters" :key="idx" :label="opt" :value="opt" />
            </el-checkbox-group>
          </template>
          <template v-else-if="form.questionType === 'TRUE_FALSE'">
            <el-radio-group v-model="form.referenceAnswer">
              <el-radio value="正确">正确</el-radio>
              <el-radio value="错误">错误</el-radio>
            </el-radio-group>
          </template>
          <template v-else>
            <el-input v-model="form.referenceAnswer" type="textarea" :rows="3" placeholder="请输入参考答案或评分要点" />
          </template>
        </el-form-item>

        <el-form-item label="分值" prop="defaultScore">
          <el-input-number v-model="form.defaultScore" :min="1" :max="100" />
        </el-form-item>

        <el-form-item label="难度">
          <el-rate v-model="form.difficulty" :max="5" show-score text-color="#ff9900" />
        </el-form-item>

        <el-form-item label="解析">
          <el-input v-model="form.analysis" type="textarea" :rows="2" placeholder="题目解析（可选）" />
        </el-form-item>

        <el-form-item label="标签">
          <el-input v-model="form.tags" placeholder="多个标签用逗号分隔（可选）" />
        </el-form-item>

        <el-form-item v-if="form.questionType === 'SHORT_ANSWER' || form.questionType === 'ESSAY'" label="评分标准">
          <el-input v-model="form.scoringCriteria" type="textarea" :rows="2" placeholder="详细评分标准（可选）" />
        </el-form-item>

        <el-form-item label="状态">
          <el-switch v-model="form.statusBool" active-text="启用" inactive-text="禁用" />
        </el-form-item>

        <el-form-item>
          <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
          <el-button @click="$router.back()">取消</el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getQuestion, createQuestion, updateQuestion } from '@/api/question'
import { getManagedCourses } from '@/api/course'

const router = useRouter()
const route = useRoute()
const formRef = ref()
const saving = ref(false)
const courses = ref([])

const isEdit = computed(() => route.params.id && route.params.id !== 'new')
const hasOptions = computed(() => ['SINGLE_CHOICE', 'MULTI_CHOICE', 'TRUE_FALSE'].includes(form.questionType))
const optionLabels = computed(() => form.questionType === 'TRUE_FALSE' ? ['正确', '错误'] : ['A', 'B', 'C', 'D'])
const optionLetters = ['A', 'B', 'C', 'D']

const form = reactive({
  id: null,
  courseId: null,
  questionType: 'SINGLE_CHOICE',
  content: '',
  optionsList: ['', '', '', ''],
  referenceAnswer: '',
  referenceAnswerList: [],
  defaultScore: 5,
  difficulty: 1,
  analysis: '',
  tags: '',
  scoringCriteria: '',
  statusBool: true
})

const rules = {
  courseId: [{ required: true, message: '请选择课程', trigger: 'change' }],
  questionType: [{ required: true, message: '请选择题型', trigger: 'change' }],
  content: [{ required: true, message: '请输入题目内容', trigger: 'blur' }],
  referenceAnswer: [{
    validator: (rule, value, callback) => {
      const ans = form.questionType === 'MULTI_CHOICE' ? form.referenceAnswerList.join(',') : value
      if (!ans) callback(new Error('请设置正确答案'))
      else callback()
    },
    trigger: 'change'
  }],
  defaultScore: [{ required: true, message: '请设置分值', trigger: 'blur' }]
}

function onTypeChange() {
  form.optionsList = ['', '', '', '']
  form.referenceAnswer = ''
  form.referenceAnswerList = []
}

async function loadCourses() {
  try {
    const r = await getManagedCourses({ page: 1, size: 100 })
    courses.value = r.records || []
  } catch (e) { /* ignore */ }
}

async function loadQuestion() {
  if (!isEdit.value) {
    const courseId = route.query.courseId
    if (courseId) form.courseId = Number(courseId)
    return
  }
  try {
    const q = await getQuestion(route.params.id)
    form.id = q.id
    form.courseId = q.courseId
    form.questionType = q.questionType
    form.content = q.content || ''
    form.defaultScore = q.defaultScore || 5
    form.difficulty = q.difficulty || 1
    form.analysis = q.analysis || ''
    form.tags = q.tags || ''
    form.scoringCriteria = q.scoringCriteria || ''
    form.statusBool = q.status === 1

    if (hasOptions.value && q.options) {
      try {
        const opts = JSON.parse(q.options)
        opts.forEach((o, i) => { if (i < 4) form.optionsList[i] = o })
      } catch (e) { /* ignore */ }
    }

    if (q.questionType === 'MULTI_CHOICE' && q.referenceAnswer) {
      form.referenceAnswerList = q.referenceAnswer.split(',').filter(Boolean)
    } else {
      form.referenceAnswer = q.referenceAnswer || ''
    }
  } catch (e) {
    ElMessage.error('获取题目信息失败')
    router.back()
  }
}

async function handleSave() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  saving.value = true
  try {
    const data = {
      courseId: form.courseId,
      questionType: form.questionType,
      content: form.content,
      defaultScore: form.defaultScore,
      difficulty: form.difficulty,
      analysis: form.analysis,
      tags: form.tags,
      scoringCriteria: form.scoringCriteria,
      status: form.statusBool ? 1 : 0
    }

    if (hasOptions.value) {
      const labels = optionLabels.value
      const filled = form.optionsList.slice(0, labels.length)
      data.options = JSON.stringify(filled)
    }

    if (form.questionType === 'MULTI_CHOICE') {
      data.referenceAnswer = form.referenceAnswerList.join(',')
    } else {
      data.referenceAnswer = form.referenceAnswer
    }

    if (isEdit.value) {
      await updateQuestion(form.id, data)
      ElMessage.success('更新成功')
    } else {
      await createQuestion(data)
      ElMessage.success('创建成功')
    }
    router.back()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  loadCourses()
  loadQuestion()
})
</script>
