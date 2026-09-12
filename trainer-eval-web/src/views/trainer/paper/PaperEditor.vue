<template>
  <div class="page-container">
    <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:20px">
      <h3 style="margin:0">{{ isEdit ? '编辑试卷' : '创建试卷' }}</h3>
      <el-button @click="router.back()">返回</el-button>
    </div>

    <!-- Basic Info -->
    <el-card shadow="hover" style="margin-bottom:16px">
      <template #header><span style="font-weight:600">试卷基本信息</span></template>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item label="试卷名称" prop="paperName">
              <el-input v-model="form.paperName" placeholder="请输入试卷名称" maxlength="100" show-word-limit />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="所属课程" prop="courseId">
              <el-select v-model="form.courseId" placeholder="请选择课程" style="width:100%" filterable @change="onCourseChange">
                <el-option v-for="c in courses" :key="c.id" :label="c.courseName" :value="c.id" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="20">
          <el-col :span="6">
            <el-form-item label="总分" prop="totalScore">
              <el-input-number v-model="form.totalScore" :min="1" :max="999" style="width:100%" />
            </el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item label="及格分" prop="passScore">
              <el-input-number v-model="form.passScore" :min="1" :max="999" style="width:100%" />
            </el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item label="考试时长(分)" prop="duration">
              <el-input-number v-model="form.duration" :min="1" :max="999" style="width:100%" />
            </el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item label="最大次数" prop="maxAttempts">
              <el-input-number v-model="form.maxAttempts" :min="1" :max="99" style="width:100%" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="20">
          <el-col :span="8">
            <el-form-item label="题目顺序">
              <el-switch v-model="form.shuffleQuestions" :active-value="1" :inactive-value="0" active-text="随机" inactive-text="固定" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="选项顺序">
              <el-switch v-model="form.shuffleOptions" :active-value="1" :inactive-value="0" active-text="随机" inactive-text="固定" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="允许重考">
              <el-switch v-model="form.allowRetake" :active-value="1" :inactive-value="0" active-text="允许" inactive-text="禁止" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="试卷说明">
          <el-input v-model="form.description" type="textarea" :rows="2" placeholder="试卷描述或考试须知（可选）" maxlength="500" show-word-limit />
        </el-form-item>
      </el-form>
    </el-card>

    <!-- Question Selection -->
    <el-card shadow="hover">
      <template #header>
        <div style="display:flex;justify-content:space-between;align-items:center">
          <span style="font-weight:600">题目管理</span>
          <div style="display:flex;align-items:center;gap:12px">
            <span style="font-size:13px;color:#606266">
              已选 <b style="color:#409eff">{{ selectedQuestions.length }}</b> 题，
              已设总分 <b style="color:#409eff">{{ computedTotalScore }}</b> 分
            </span>
          </div>
        </div>
      </template>

      <div v-if="!form.courseId" style="text-align:center;padding:40px;color:#909399">
        <el-icon style="font-size:48px"><InfoFilled /></el-icon>
        <p style="margin-top:12px">请先在基本信息中选择所属课程，再添加题目</p>
      </div>

      <div v-else style="display:flex;gap:16px;min-height:400px">
        <!-- Question Pool -->
        <div style="flex:1;border:1px solid #ebeef5;border-radius:4px;display:flex;flex-direction:column">
          <div style="padding:12px;border-bottom:1px solid #ebeef5;background:#fafafa">
            <div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap">
              <span style="font-size:13px;font-weight:600;white-space:nowrap">题库筛选</span>
              <el-select v-model="poolFilter.questionType" placeholder="题型" clearable size="small" style="width:110px" @change="fetchPool">
                <el-option label="单选题" value="SINGLE_CHOICE" />
                <el-option label="多选题" value="MULTI_CHOICE" />
                <el-option label="判断题" value="TRUE_FALSE" />
                <el-option label="简答题" value="SHORT_ANSWER" />
                <el-option label="论述题" value="ESSAY" />
              </el-select>
              <el-select v-model="poolFilter.difficulty" placeholder="难度" clearable size="small" style="width:100px" @change="fetchPool">
                <el-option label="1-简单" :value="1" /><el-option label="2" :value="2" />
                <el-option label="3-中等" :value="3" /><el-option label="4" :value="4" />
                <el-option label="5-困难" :value="5" />
              </el-select>
              <el-input v-model="poolFilter.keyword" placeholder="搜索题目" size="small" style="width:140px" clearable @keyup.enter="fetchPool" />
              <el-button size="small" @click="fetchPool">刷新</el-button>
            </div>
          </div>
          <div style="flex:1;overflow-y:auto;padding:8px">
            <div v-if="poolLoading" style="text-align:center;padding:20px"><el-icon class="is-loading"><Loading /></el-icon></div>
            <el-empty v-else-if="poolQuestions.length === 0" description="暂无符合条件的题目" :image-size="60" />
            <div v-else v-for="q in poolQuestions" :key="q.id"
                 style="padding:8px 10px;margin-bottom:4px;border:1px solid #ebeef5;border-radius:4px;cursor:pointer;
                        display:flex;justify-content:space-between;align-items:center;
                        transition:all 0.2s"
                 :style="isSelected(q.id) ? 'border-color:#409eff;background:#ecf5ff' : ''"
                 @mouseenter="hoveredQ = q.id" @mouseleave="hoveredQ = null">
              <div style="flex:1;min-width:0">
                <div style="display:flex;gap:6px;align-items:center;margin-bottom:2px">
                  <el-tag size="small" :type="typeTag(q.questionType)">{{ typeName(q.questionType) }}</el-tag>
                  <el-rate :model-value="q.difficulty || 1" disabled size="small" />
                  <span style="font-size:12px;color:#909399">{{ q.defaultScore }}分</span>
                </div>
                <div style="font-size:13px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">{{ q.content }}</div>
              </div>
              <el-button v-if="isSelected(q.id)" size="small" type="danger" text @click.stop="removeQuestion(q.id)" style="margin-left:8px;flex-shrink:0">
                <el-icon><Delete /></el-icon>
              </el-button>
              <el-button v-else-if="hoveredQ === q.id" size="small" type="primary" text @click.stop="addQuestion(q)" style="margin-left:8px;flex-shrink:0">
                <el-icon><Plus /></el-icon>
              </el-button>
            </div>
            <div style="text-align:center;padding:8px">
              <el-pagination
                v-model:current-page="poolPage.current"
                :page-size="poolPage.size"
                :total="poolPage.total"
                layout="prev, pager, next"
                small
                @current-change="fetchPool"
              />
            </div>
          </div>
        </div>

        <!-- Selected Questions -->
        <div style="flex:1;border:1px solid #ebeef5;border-radius:4px;display:flex;flex-direction:column">
          <div style="padding:12px;border-bottom:1px solid #ebeef5;background:#fafafa">
            <span style="font-size:13px;font-weight:600">已选题目 ({{ selectedQuestions.length }})</span>
            <el-button v-if="selectedQuestions.length > 0" size="small" text type="danger" style="float:right" @click="clearAll">清空全部</el-button>
          </div>
          <div style="flex:1;overflow-y:auto;padding:8px">
            <el-empty v-if="selectedQuestions.length === 0" description="从左侧题库点击题目添加" :image-size="60" />
            <div v-else v-for="(item, index) in selectedQuestions" :key="item.questionId"
                 style="padding:8px 10px;margin-bottom:4px;border:1px solid #ebeef5;border-radius:4px">
              <div style="display:flex;align-items:center;gap:8px">
                <span style="font-size:12px;color:#909399;flex-shrink:0">{{ index + 1 }}.</span>
                <div style="flex:1;min-width:0">
                  <div style="display:flex;gap:6px;align-items:center;margin-bottom:2px">
                    <el-tag size="small" :type="typeTag(item.questionType)">{{ typeName(item.questionType) }}</el-tag>
                  </div>
                  <div style="font-size:13px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">{{ item.content || item.questionContent }}</div>
                </div>
                <div style="display:flex;align-items:center;gap:6px;flex-shrink:0">
                  <span style="font-size:12px;color:#909399">分值</span>
                  <el-input-number v-model="item.score" :min="1" :max="999" size="small" style="width:80px" controls-position="right" />
                  <el-button size="small" text @click="moveUp(index)" :disabled="index === 0">
                    <el-icon><ArrowUp /></el-icon>
                  </el-button>
                  <el-button size="small" text @click="moveDown(index)" :disabled="index === selectedQuestions.length - 1">
                    <el-icon><ArrowDown /></el-icon>
                  </el-button>
                  <el-button size="small" text type="danger" @click="removeQuestion(item.questionId)">
                    <el-icon><Delete /></el-icon>
                  </el-button>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </el-card>

    <!-- Save Button -->
    <div style="margin-top:20px;text-align:center">
      <el-button @click="router.back()">取消</el-button>
      <el-button type="primary" :loading="saving" @click="handleSave">
        {{ isEdit ? '保存修改' : '创建试卷' }}
      </el-button>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getPaper, createPaper, updatePaper, getPaperQuestions } from '@/api/paper'
import { getAllQuestions, getQuestions } from '@/api/question'

const router = useRouter()
const route = useRoute()

const paperId = computed(() => {
  const id = route.params.id
  return id && id !== 'new' ? Number(id) : null
})
const isEdit = computed(() => !!paperId.value)

const formRef = ref(null)
const saving = ref(false)
const courses = ref([])
const poolLoading = ref(false)
const poolQuestions = ref([])
const hoveredQ = ref(null)

const form = reactive({
  courseId: null,
  paperName: '',
  description: '',
  totalScore: 100,
  passScore: 60,
  duration: 60,
  shuffleQuestions: 0,
  shuffleOptions: 0,
  allowRetake: 1,
  maxAttempts: 3
})

const rules = {
  paperName: [{ required: true, message: '请输入试卷名称', trigger: 'blur' }],
  courseId: [{ required: true, message: '请选择课程', trigger: 'change' }],
  totalScore: [{ required: true, message: '请输入总分', trigger: 'blur' }],
  passScore: [{ required: true, message: '请输入及格分', trigger: 'blur' }],
  duration: [{ required: true, message: '请输入考试时长', trigger: 'blur' }]
}

const poolFilter = reactive({ questionType: '', difficulty: null, keyword: '' })
const poolPage = reactive({ current: 1, size: 20, total: 0 })

// Selected questions: { questionId, score, sortOrder, content, questionType, ... }
const selectedQuestions = ref([])

const computedTotalScore = computed(() => {
  return selectedQuestions.value.reduce((sum, q) => sum + (q.score || 0), 0)
})

function typeName(t) {
  const m = { SINGLE_CHOICE: '单选题', MULTI_CHOICE: '多选题', TRUE_FALSE: '判断题', SHORT_ANSWER: '简答题', ESSAY: '论述题' }
  return m[t] || t
}
function typeTag(t) {
  const m = { SINGLE_CHOICE: '', MULTI_CHOICE: 'success', TRUE_FALSE: 'warning', SHORT_ANSWER: 'info', ESSAY: 'danger' }
  return m[t] || ''
}

function isSelected(questionId) {
  return selectedQuestions.value.some(q => q.questionId === questionId)
}

function addQuestion(q) {
  if (isSelected(q.id)) return
  selectedQuestions.value.push({
    questionId: q.id,
    score: q.defaultScore || 5,
    sortOrder: selectedQuestions.value.length,
    content: q.content,
    questionContent: q.content,
    questionType: q.questionType,
    difficulty: q.difficulty
  })
}

function removeQuestion(questionId) {
  selectedQuestions.value = selectedQuestions.value.filter(q => q.questionId !== questionId)
  // re-index sort orders
  selectedQuestions.value.forEach((q, i) => { q.sortOrder = i })
}

function moveUp(index) {
  if (index === 0) return
  const arr = selectedQuestions.value
  ;[arr[index - 1], arr[index]] = [arr[index], arr[index - 1]]
  arr.forEach((q, i) => { q.sortOrder = i })
}

function moveDown(index) {
  if (index === selectedQuestions.value.length - 1) return
  const arr = selectedQuestions.value
  ;[arr[index], arr[index + 1]] = [arr[index + 1], arr[index]]
  arr.forEach((q, i) => { q.sortOrder = i })
}

function clearAll() {
  selectedQuestions.value = []
}

async function loadCourses() {
  try {
    const api = await import('@/api/course')
    const r = await api.getManagedCourses({ page: 1, size: 100 })
    courses.value = r.records || []
  } catch (e) { /* ignore */ }
}

function onCourseChange() {
  selectedQuestions.value = []
  poolPage.current = 1
  fetchPool()
}

async function fetchPool() {
  if (!form.courseId) return
  poolLoading.value = true
  try {
    const params = {
      courseId: form.courseId,
      page: poolPage.current,
      size: poolPage.size
    }
    if (poolFilter.questionType) params.questionType = poolFilter.questionType
    if (poolFilter.difficulty) params.difficulty = poolFilter.difficulty

    const result = await getQuestions(params)
    poolQuestions.value = result.records || []
    poolPage.total = result.total || 0
  } catch (e) {
    ElMessage.error('加载题库失败')
  } finally {
    poolLoading.value = false
  }
}

async function loadPaper() {
  if (!paperId.value) return
  try {
    const paper = await getPaper(paperId.value)
    Object.assign(form, {
      courseId: paper.courseId,
      paperName: paper.paperName,
      description: paper.description || '',
      totalScore: paper.totalScore,
      passScore: paper.passScore,
      duration: paper.duration,
      shuffleQuestions: paper.shuffleQuestions,
      shuffleOptions: paper.shuffleOptions,
      allowRetake: paper.allowRetake,
      maxAttempts: paper.maxAttempts
    })

    // Load questions
    if (paper.questions && paper.questions.length > 0) {
      selectedQuestions.value = paper.questions.map((q, i) => ({
        questionId: q.id,
        score: paper.questionScores ? paper.questionScores[i] : q.defaultScore || 5,
        sortOrder: i,
        content: q.content,
        questionContent: q.content,
        questionType: q.questionType,
        difficulty: q.difficulty
      }))
    }
  } catch (e) {
    ElMessage.error('加载试卷信息失败')
  }
}

async function handleSave() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  if (selectedQuestions.value.length === 0) {
    ElMessage.warning('请至少选择一道题目')
    return
  }

  saving.value = true
  try {
    const payload = {
      ...form,
      questions: selectedQuestions.value.map(q => ({
        questionId: q.questionId,
        score: q.score,
        sortOrder: q.sortOrder
      }))
    }

    if (isEdit.value) {
      await updatePaper(paperId.value, payload)
      ElMessage.success('保存成功')
    } else {
      const result = await createPaper(payload)
      ElMessage.success('创建成功')
      router.replace(`/trainer/paper-editor/${result.id}`)
    }
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  await loadCourses()
  if (isEdit.value) {
    await loadPaper()
    if (form.courseId) fetchPool()
  }
})
</script>
