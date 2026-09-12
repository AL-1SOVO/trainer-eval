<template>
  <div class="page-container">
    <el-alert
      v-if="result.aiPending"
      title="主观题正在进行 AI 评分，页面会自动刷新。"
      type="warning"
      :closable="false"
      style="margin-bottom: 16px"
    />

    <el-card>
      <h2 style="text-align: center; margin-bottom: 24px">考试成绩</h2>
      <el-row :gutter="16" style="text-align: center; margin-bottom: 24px">
        <el-col :span="8">
          <h1 :style="{ color: result.isPass ? '#52c41a' : '#ff4d4f' }">{{ displayTotalScore }} / {{ result.totalMaxScore || 0 }}</h1>
          <p>总分</p>
        </el-col>
        <el-col :span="8">
          <h3>{{ result.objectiveScore || 0 }}</h3>
          <p>客观题得分</p>
        </el-col>
        <el-col :span="8">
          <h3>{{ result.aiPending ? 'AI评分中' : (result.subjectiveScore || 0) }}</h3>
          <p>主观题得分</p>
        </el-col>
      </el-row>

      <el-tag
        :type="result.isPass ? 'success' : 'danger'"
        size="large"
        style="display: block; width: 100px; margin: 0 auto; text-align: center"
      >
        {{ result.isPass ? '通过' : '未通过' }}
      </el-tag>

      <p style="margin-top: 8px; color: #999; text-align: center">
        及格线 {{ result.passScore || 0 }} 分 | 用时: {{ result.duration || '-' }}
      </p>
    </el-card>

    <el-card v-for="(question, index) in result.answers" :key="question.questionId || index" style="margin-top: 16px">
      <div style="display: flex; justify-content: space-between; gap: 16px">
        <h4>{{ index + 1 }}. {{ question.questionContent }}</h4>
        <el-tag v-if="isPendingSubjective(question)" type="warning">AI评分中</el-tag>
        <el-tag v-else :type="question.isCorrect ? 'success' : 'danger'">
          {{ question.score || 0 }}/{{ question.questionScore || 0 }}分
        </el-tag>
      </div>

      <p v-if="!isSubjective(question)" style="margin-top: 8px">
        你的答案:
        <span :style="{ color: question.isCorrect ? '#52c41a' : '#ff4d4f', fontWeight: 'bold' }">
          {{ question.userAnswer || '未作答' }}
        </span>
        <span v-if="!question.isCorrect" style="margin-left: 16px; color: #52c41a">
          正确答案: {{ question.referenceAnswer }}
        </span>
      </p>

      <div v-else style="margin-top: 8px">
        <p><strong>你的答案:</strong> {{ question.userAnswer || '未作答' }}</p>

        <p v-if="isPendingSubjective(question)" style="margin-top: 8px; padding: 12px; background: #fff7e6; border-radius: 4px">
          <strong>状态:</strong> AI 正在自动评分，请稍候。
        </p>

        <p v-else-if="question.aiFeedback" style="margin-top: 8px; padding: 12px; background: #f6ffed; border-radius: 4px">
          <strong>AI评语:</strong> {{ question.aiFeedback }}
        </p>

        <p v-if="question.errorAnalysis" style="margin-top: 8px; padding: 12px; background: #fff7e6; border-radius: 4px">
          <strong>错题分析:</strong> {{ question.errorAnalysis }}
        </p>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { getResult } from '@/api/exam'

const route = useRoute()

const result = ref({
  totalScore: 0,
  totalMaxScore: 0,
  objectiveScore: 0,
  subjectiveScore: 0,
  passScore: 0,
  isPass: false,
  aiPending: false,
  answers: []
})

const displayTotalScore = computed(() => {
  if (typeof result.value.totalScore === 'number') {
    return result.value.totalScore
  }
  return (result.value.objectiveScore || 0) + (result.value.subjectiveScore || 0)
})

let pollTimer = null

function isSubjective(question) {
  return ['ESSAY', 'SHORT_ANSWER'].includes(question.questionType)
}

function isPendingSubjective(question) {
  return isSubjective(question) && question.aiGraded !== 1
}

function clearPollTimer() {
  if (pollTimer) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
}

async function loadResult() {
  try {
    result.value = await getResult(route.params.recordId)
    clearPollTimer()
    if (result.value.aiPending) {
      pollTimer = setTimeout(loadResult, 3000)
    }
  } catch (error) {
    clearPollTimer()
  }
}

onMounted(loadResult)
onUnmounted(clearPollTimer)
</script>
