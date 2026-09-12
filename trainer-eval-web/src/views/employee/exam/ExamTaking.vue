<template>
  <div style="height:100vh;display:flex;flex-direction:column;background:#f5f5f5">
    <div style="background:#fff;padding:12px 24px;display:flex;justify-content:space-between;align-items:center;border-bottom:1px solid #e8e8e8">
      <h3>{{ paperName }} - 考试中</h3>
      <div>
        <el-icon><Timer /></el-icon>
        <span style="font-size:20px;font-weight:bold;color:#e6a23c">{{ formatTime(remainingSec) }}</span>
      </div>
    </div>

    <div style="flex:1;display:flex;overflow:hidden">
      <div style="flex:1;overflow-y:auto;padding:20px">
        <el-card
          v-for="(question, index) in questions"
          :key="question.questionId"
          :id="`q-${index}`"
          style="margin-bottom:16px"
          :class="{ 'question-active': currentIndex === index }"
        >
          <div style="display:flex;justify-content:space-between;margin-bottom:12px">
            <h4>{{ index + 1 }}. {{ getQuestionTypeLabel(question.questionType) }} {{ question.questionContent }}</h4>
            <span style="color:#999">{{ question.questionScore }}分</span>
          </div>

          <div v-if="isSingleChoice(question) || question.questionType === 'TRUE_FALSE'">
            <el-radio-group v-model="answers[index]" @change="() => autoSave(index)">
              <el-radio
                v-for="option in parseOptions(question)"
                :key="option.label"
                :value="option.value"
                style="display:block;margin:8px 0"
              >
                {{ option.display }}
              </el-radio>
            </el-radio-group>
          </div>

          <div v-else-if="question.questionType === 'MULTI_CHOICE'">
            <el-checkbox-group v-model="multiAnswers[index]" @change="() => autoSave(index)">
              <el-checkbox
                v-for="option in parseOptions(question)"
                :key="option.label"
                :value="option.value"
                style="display:block;margin:8px 0"
              >
                {{ option.display }}
              </el-checkbox>
            </el-checkbox-group>
          </div>

          <div v-else>
            <el-input
              v-model="answers[index]"
              type="textarea"
              :rows="4"
              placeholder="请输入答案..."
              @change="() => autoSave(index)"
            />
          </div>
        </el-card>
      </div>

      <div style="width:260px;background:#fff;padding:16px;overflow-y:auto;border-left:1px solid #e8e8e8">
        <h4 style="margin-bottom:12px">答题卡</h4>
        <div style="display:flex;flex-wrap:wrap;gap:8px">
          <div
            v-for="(question, index) in questions"
            :key="question.questionId"
            @click="scrollTo(index)"
            :style="{
              width: '36px',
              height: '36px',
              lineHeight: '36px',
              textAlign: 'center',
              borderRadius: '4px',
              cursor: 'pointer',
              background: isAnswered(index) ? '#409eff' : '#f0f0f0',
              color: isAnswered(index) ? '#fff' : '#333',
              border: currentIndex === index ? '2px solid #e6a23c' : '2px solid transparent'
            }"
          >
            {{ index + 1 }}
          </div>
        </div>
        <el-divider />
        <div style="font-size:12px;color:#999">已答: {{ answeredCount }} / {{ questions.length }}</div>
        <el-button type="primary" style="margin-top:16px;width:100%" :loading="submitting" @click="handleSubmit(false)">
          提交试卷
        </el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { startExam, submitExam, getSession, saveAnswer } from '@/api/exam'

const route = useRoute()
const router = useRouter()

const recordId = ref(Number(route.params.recordId))
const paperName = ref('')
const questions = ref([])
const answers = ref([])
const multiAnswers = ref([])
const currentIndex = ref(0)
const remainingSec = ref(3600)
const submitting = ref(false)

let timer = null
// 修复：用绝对截止时间计算剩余时长，避免浏览器后台节流把 60 分钟"拖长"
const deadline = ref(0)
let autoSubmitted = false

function tick() {
  const left = Math.max(0, Math.round((deadline.value - Date.now()) / 1000))
  remainingSec.value = left
  if (left <= 0 && !autoSubmitted) {
    autoSubmitted = true
    clearInterval(timer)
    ElMessage.warning('考试时间已到，正在自动交卷')
    handleSubmit(true)
  }
}

const answeredCount = computed(() => {
  let count = 0
  questions.value.forEach((question, index) => {
    if (question.questionType === 'MULTI_CHOICE') {
      if (multiAnswers.value[index]?.length) count++
    } else if (answers.value[index]) {
      count++
    }
  })
  return count
})

function getQuestionTypeLabel(type) {
  switch (type) {
    case 'SINGLE_CHOICE':
      return '[单选题]'
    case 'MULTI_CHOICE':
      return '[多选题]'
    case 'TRUE_FALSE':
      return '[判断题]'
    case 'SHORT_ANSWER':
      return '[简答题]'
    case 'ESSAY':
      return '[主观题]'
    default:
      return '[题目]'
  }
}

function isSingleChoice(question) {
  return question.questionType === 'SINGLE_CHOICE'
}

function formatTime(seconds) {
  const minutes = Math.floor(seconds / 60)
  const secs = seconds % 60
  return `${String(minutes).padStart(2, '0')}:${String(secs).padStart(2, '0')}`
}

function parseOptions(question) {
  const rawOptions = question.questionOptions ?? question.options
  let parsed = []

  if (Array.isArray(rawOptions)) {
    parsed = rawOptions
  } else if (typeof rawOptions === 'string' && rawOptions.trim()) {
    try {
      parsed = JSON.parse(rawOptions)
    } catch (error) {
      parsed = []
    }
  }

  if ((!parsed || !parsed.length) && question.questionType === 'TRUE_FALSE') {
    parsed = ['正确', '错误']
  }

  return parsed.map((item, index) => {
    if (typeof item === 'object' && item !== null) {
      const label = item.label ?? String.fromCharCode(65 + index)
      const text = item.text ?? item.content ?? item.value ?? ''
      return {
        label,
        text,
        value: item.value ?? label,
        display: text ? `${label}. ${text}` : label
      }
    }

    const text = String(item)
    const match = text.match(/^([A-Z])[\.\、]\s*(.+)$/)
    if (match) {
      return {
        label: match[1],
        text: match[2],
        value: match[1],
        display: `${match[1]}. ${match[2]}`
      }
    }

    const label = String.fromCharCode(65 + index)
    return {
      label,
      text,
      // 修复：这里原来是 value: text —— 把「选项全文」当作答案提交。
      // 而题库里参考答案存的是字母（选项 ["SQL注入","定期备份"] 对应答案 "A"），
      // 后端用字符串比较 → 真实录入的题目必然判 0 分（只有带 "A. " 前缀的种子数据能过）。
      // 选择题统一提交字母标签；判断题的参考答案是"正确/错误"文本，保持原样。
      value: question.questionType === 'TRUE_FALSE' ? text : label,
      display: text
    }
  })
}

function isAnswered(index) {
  return Boolean(answers.value[index] || multiAnswers.value[index]?.length)
}

function scrollTo(index) {
  currentIndex.value = index
  document.getElementById(`q-${index}`)?.scrollIntoView({ behavior: 'smooth' })
}

async function autoSave(index) {
  const question = questions.value[index]
  if (!question) return

  const answer = question.questionType === 'MULTI_CHOICE'
    ? (multiAnswers.value[index]?.join(',') || '')
    : (answers.value[index] || '')

  if (!answer) return

  try {
    await saveAnswer(recordId.value, {
      questionId: question.questionId,
      answer
    })
  } catch (error) {
    // ignore autosave failures
  }
}

async function handleSubmit(auto = false) {
  if (!auto) {
    try {
      await ElMessageBox.confirm('确认提交试卷吗？提交后将不能继续修改答案。', '提示', {
        confirmButtonText: '确认提交',
        cancelButtonText: '继续作答'
      })
    } catch (error) {
      return
    }
  }

  submitting.value = true
  try {
    const answerList = questions.value.map((question, index) => ({
      questionId: question.questionId,
      userAnswer: question.questionType === 'MULTI_CHOICE'
        ? (multiAnswers.value[index]?.join(',') || '')
        : (answers.value[index] || '')
    }))

    const result = await submitExam({
      recordId: recordId.value,
      answers: answerList
    })

    clearInterval(timer)
    ElMessage.success(auto ? '考试时间已到，已自动交卷' : '提交成功')
    router.push(`/employee/exam-result/${result.id}`)
  } catch (error) {
    // 修复：原先固定提示"提交失败"，用户看不出原因（例如超时/已交卷）
    ElMessage.error(error?.response?.data?.message || error?.message || '提交失败')
  } finally {
    submitting.value = false
  }
}

onMounted(async () => {
  try {
    const paperId = route.query.paperId
    const data = paperId ? await startExam(Number(paperId)) : await getSession(recordId.value)

    if (data?.recordId) recordId.value = data.recordId
    questions.value = data?.questions || []
    paperName.value = data?.paperName || ''

    const remaining = data?.remainingSec ?? ((data?.duration || 60) * 60)
    remainingSec.value = remaining
    deadline.value = Date.now() + remaining * 1000

    // 修复：回填服务端已保存的作答。
    // 原实现一律初始化成空数组，而 getSession 又不返回 userAnswer，
    // 于是刷新页面后答案全空，交卷时会用空答案覆盖已保存答案 → 客观题全判 0。
    answers.value = new Array(questions.value.length).fill('')
    multiAnswers.value = new Array(questions.value.length).fill(null).map(() => [])
    questions.value.forEach((question, index) => {
      const saved = question.userAnswer
      if (saved === null || saved === undefined || saved === '') return
      if (question.questionType === 'MULTI_CHOICE') {
        multiAnswers.value[index] = String(saved)
          .split(',')
          .map(item => item.trim())
          .filter(Boolean)
      } else {
        answers.value[index] = String(saved)
      }
    })

    timer = setInterval(tick, 1000)
  } catch (error) {
    ElMessage.error('加载试卷失败')
    router.back()
  }
})

onUnmounted(() => {
  clearInterval(timer)
})
</script>
