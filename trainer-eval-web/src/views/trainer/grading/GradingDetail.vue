<template>
  <div class="page-container grading-detail-page" v-loading="loading">
    <el-page-header @back="router.back()">
      <template #content>
        <div class="header-title">
          <span>阅卷详情</span>
          <el-tag :type="statusTag(detail.record?.status)">{{ statusText(detail.record?.status) }}</el-tag>
        </div>
      </template>
    </el-page-header>

    <el-card shadow="never" class="summary-card">
      <div class="summary-grid">
        <div class="summary-item">
          <span class="summary-label">试卷</span>
          <strong>{{ detail.paper?.paperName || '-' }}</strong>
        </div>
        <div class="summary-item">
          <span class="summary-label">考生</span>
          <strong>{{ detail.user?.realName || detail.user?.username || '-' }}</strong>
        </div>
        <div class="summary-item">
          <span class="summary-label">客观题分数</span>
          <strong>{{ detail.record?.objectiveScore ?? 0 }}</strong>
        </div>
        <div class="summary-item">
          <span class="summary-label">主观题当前分数</span>
          <strong>{{ detail.record?.subjectiveScore ?? 0 }}</strong>
        </div>
        <div class="summary-item">
          <span class="summary-label">试卷总分</span>
          <strong>{{ detail.record?.totalScore ?? 0 }} / {{ detail.paper?.totalScore ?? '-' }}</strong>
        </div>
        <div class="summary-item">
          <span class="summary-label">AI 评分进度</span>
          <strong>{{ aiDoneCount }}/{{ subjectiveAnswers.length }}</strong>
        </div>
      </div>

      <div class="summary-actions">
        <el-button @click="fetchDetail">刷新数据</el-button>
        <el-button type="primary" :loading="finalizing" @click="handleFinalize">提交人工阅卷结果</el-button>
      </div>
    </el-card>

    <el-alert
      v-if="detail.record?.status === 'SUBMITTED'"
      type="warning"
      show-icon
      :closable="false"
      title="当前试卷可能仍在等待 AI 评分，若 AI 建议分未出现，可稍后刷新。"
    />

    <el-card
      v-for="(answer, index) in subjectiveAnswers"
      :key="answer.id"
      shadow="never"
      class="answer-card"
    >
      <template #header>
        <div class="answer-header">
          <div>
            <div class="answer-title">{{ index + 1 }}. {{ questionTypeLabel(answer.questionType) }}</div>
            <div class="answer-score">满分 {{ answer.questionScore }} 分</div>
          </div>
          <el-tag :type="answer.aiGraded === 1 ? 'success' : 'warning'">
            {{ answer.aiGraded === 1 ? 'AI 已评分' : 'AI 未完成' }}
          </el-tag>
        </div>
      </template>

      <div class="question-content">{{ answer.questionContent }}</div>

      <div class="answer-block">
        <div class="block-title">考生答案</div>
        <div class="block-content">{{ answer.userAnswer || '未作答' }}</div>
      </div>

      <div class="answer-block">
        <div class="block-title">参考答案</div>
        <div class="block-content">{{ answer.referenceAnswer || '未设置参考答案' }}</div>
      </div>

      <div class="answer-block" v-if="answer.scoringCriteria">
        <div class="block-title">评分标准</div>
        <div class="block-content">{{ answer.scoringCriteria }}</div>
      </div>

      <el-row :gutter="16" class="grading-grid">
        <el-col :xs="24" :md="12">
          <div class="score-box">
            <span>AI 建议分</span>
            <strong>{{ answer.aiScore ?? answer.score ?? 0 }}</strong>
          </div>
        </el-col>
        <el-col :xs="24" :md="12">
          <div class="score-box">
            <span>人工最终分</span>
            <strong>{{ scoreDrafts[answer.id]?.manualScore ?? answer.manualScore ?? answer.score ?? 0 }}</strong>
          </div>
        </el-col>
      </el-row>

      <el-form :model="scoreDrafts[answer.id]" label-width="90px" class="score-form">
        <el-form-item label="人工评分">
          <el-input-number
            v-model="scoreDrafts[answer.id].manualScore"
            :min="0"
            :max="Number(answer.questionScore || 0)"
            :precision="0"
            style="width: 220px"
          />
          <el-button
            type="primary"
            plain
            :loading="savingAnswerId === answer.id"
            @click="saveAnswerScore(answer)"
          >
            保存本题评分
          </el-button>
        </el-form-item>
        <el-form-item label="评分意见">
          <el-input
            v-model="scoreDrafts[answer.id].aiFeedback"
            type="textarea"
            :rows="4"
            maxlength="1000"
            show-word-limit
            placeholder="可直接采用 AI 反馈，也可手动调整为更适合教师批注的内容"
          />
        </el-form-item>
      </el-form>
    </el-card>

    <el-empty v-if="!loading && !subjectiveAnswers.length" description="这份试卷没有主观题，无需人工阅卷" />
  </div>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { finalizeGrading, getGradingDetail, updateManualScore } from '@/api/exam'

const route = useRoute()
const router = useRouter()

const loading = ref(false)
const finalizing = ref(false)
const savingAnswerId = ref(null)
const detail = ref({
  record: null,
  paper: null,
  user: null,
  answers: []
})
const scoreDrafts = reactive({})
const savedDrafts = reactive({})

const subjectiveAnswers = computed(() =>
  (detail.value.answers || []).filter(item => ['SHORT_ANSWER', 'ESSAY'].includes(item.questionType))
)

const aiDoneCount = computed(() =>
  subjectiveAnswers.value.filter(item => item.aiGraded === 1).length
)

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

function questionTypeLabel(type) {
  if (type === 'SHORT_ANSWER') return '简答题'
  if (type === 'ESSAY') return '论述题'
  return type || '主观题'
}

function hydrateDrafts() {
  subjectiveAnswers.value.forEach(answer => {
    scoreDrafts[answer.id] = {
      manualScore: answer.manualScore ?? answer.score ?? answer.aiScore ?? 0,
      aiFeedback: answer.aiFeedback || ''
    }
    savedDrafts[answer.id] = {
      manualScore: scoreDrafts[answer.id].manualScore,
      aiFeedback: scoreDrafts[answer.id].aiFeedback
    }
  })
}

function isDraftDirty(answer) {
  const draft = scoreDrafts[answer.id]
  const saved = savedDrafts[answer.id]
  if (!draft || !saved) return false
  return draft.manualScore !== saved.manualScore || draft.aiFeedback !== saved.aiFeedback
}

async function fetchDetail() {
  loading.value = true
  try {
    detail.value = await getGradingDetail(route.params.recordId)
    // 保留尚未保存的草稿，避免刷新把用户正在编辑的内容冲掉
    const dirty = subjectiveAnswers.value.filter(isDraftDirty).map(answer => ({
      id: answer.id,
      manualScore: scoreDrafts[answer.id].manualScore,
      aiFeedback: scoreDrafts[answer.id].aiFeedback
    }))
    hydrateDrafts()
    dirty.forEach(item => {
      if (scoreDrafts[item.id]) {
        scoreDrafts[item.id].manualScore = item.manualScore
        scoreDrafts[item.id].aiFeedback = item.aiFeedback
      }
    })
  } finally {
    loading.value = false
  }
}

async function saveAnswerScore(answer) {
  savingAnswerId.value = answer.id
  try {
    await updateManualScore(answer.id, scoreDrafts[answer.id])
    savedDrafts[answer.id] = {
      manualScore: scoreDrafts[answer.id].manualScore,
      aiFeedback: scoreDrafts[answer.id].aiFeedback
    }
    ElMessage.success('本题评分已保存')
    await fetchDetail()
  } finally {
    savingAnswerId.value = null
  }
}

async function handleFinalize() {
  finalizing.value = true
  try {
    // 先保存所有修改过但未保存的草稿，避免提交时静默丢失
    const dirty = subjectiveAnswers.value.filter(isDraftDirty)
    for (const answer of dirty) {
      await updateManualScore(answer.id, scoreDrafts[answer.id])
    }
    await finalizeGrading(route.params.recordId)
    ElMessage.success(dirty.length ? `已自动保存 ${dirty.length} 题修改，人工阅卷结果已提交` : '人工阅卷结果已提交')
    await fetchDetail()
  } finally {
    finalizing.value = false
  }
}

fetchDetail()
</script>

<style scoped>
.grading-detail-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.header-title {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 18px;
  font-weight: 600;
}

.summary-card,
.answer-card {
  border-radius: 18px;
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 16px;
}

.summary-item {
  padding: 14px 16px;
  border-radius: 14px;
  background: #f8fafc;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.summary-label {
  font-size: 13px;
  color: #64748b;
}

.summary-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 18px;
}

.answer-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}

.answer-title {
  font-size: 16px;
  font-weight: 600;
  color: #0f172a;
}

.answer-score {
  margin-top: 6px;
  color: #64748b;
  font-size: 13px;
}

.question-content {
  font-size: 15px;
  line-height: 1.8;
  color: #1e293b;
}

.answer-block {
  margin-top: 16px;
  padding: 14px 16px;
  border-radius: 14px;
  background: #f8fafc;
}

.block-title {
  font-size: 13px;
  color: #64748b;
  margin-bottom: 8px;
}

.block-content {
  color: #334155;
  line-height: 1.8;
  white-space: pre-wrap;
}

.grading-grid {
  margin-top: 16px;
}

.score-box {
  padding: 14px 16px;
  border-radius: 14px;
  background: linear-gradient(135deg, #eff6ff, #f8fafc);
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.score-box span {
  color: #64748b;
}

.score-box strong {
  font-size: 24px;
  color: #0f172a;
}

.score-form {
  margin-top: 18px;
}
</style>
