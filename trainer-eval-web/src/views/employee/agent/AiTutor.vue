<template>
  <div class="page-container tutor-page">
    <el-card shadow="never" class="chat-card">
      <template #header>
        <div class="chat-header">
          <div>
            <div class="chat-title">AI 学习助手</div>
            <div class="chat-sub">基于你的考试成绩、错题记录和课程资料，给出个性化学习建议</div>
          </div>
          <div class="chat-header-actions">
            <el-radio-group v-model="mode" size="small" :disabled="streaming">
              <el-radio-button value="single">单智能体</el-radio-button>
              <el-radio-button value="multi">多智能体协作</el-radio-button>
            </el-radio-group>
            <el-tooltip
              content="多智能体：资料员负责查证、复核员用独立上下文核对证据、汇报员只基于已核实内容作答。更可靠，但耗时与成本约为单智能体的 3~4 倍。"
              placement="bottom"
            >
              <el-icon class="mode-help"><QuestionFilled /></el-icon>
            </el-tooltip>
            <el-button size="small" @click="resetSession">新对话</el-button>
          </div>
        </div>
      </template>

      <div class="chat-body" ref="bodyRef">
        <div v-if="!messages.length" class="chat-empty">
          <p>你好，我是你的 AI 学习助手。你可以问我：</p>
          <div class="suggestions">
            <el-button v-for="s in suggestions" :key="s" size="small" plain @click="send(s)">{{ s }}</el-button>
          </div>
        </div>

        <template v-for="(msg, i) in messages" :key="i">
          <div v-if="msg.role === 'tool'" class="tool-step">
            <el-tag size="small" type="info" effect="plain">
              <el-icon><Loading v-if="msg.running" /><CircleCheck v-else /></el-icon>
              {{ msg.label }}
            </el-tag>
          </div>
          <div v-else-if="msg.role === 'reflection'" class="tool-step">
            <el-tag size="small" type="warning" effect="plain">
              <el-icon><Refresh /></el-icon>
              {{ msg.label }}
            </el-tag>
          </div>
          <div v-else-if="msg.role === 'agent'" class="tool-step">
            <el-tag size="small" :type="msg.running ? 'primary' : 'success'" effect="plain">
              <el-icon><Loading v-if="msg.running" /><CircleCheck v-else /></el-icon>
              {{ msg.label }}{{ msg.note ? ' · ' + msg.note : '' }}
            </el-tag>
          </div>
          <div v-else-if="msg.role === 'user'" class="bubble-row user">
            <div class="bubble user-bubble">{{ msg.content }}</div>
          </div>
          <div v-else class="bubble-row ai">
            <div class="bubble ai-bubble"><span class="pre-wrap">{{ msg.content }}</span></div>
          </div>
        </template>
      </div>

      <!-- 任务规划：多步任务时展示助手打算怎么做、做到哪一步（P0.2）。
           简单问题不会规划，这里就整块不出现，避免为展示而展示。 -->
      <div v-if="plan.length" class="plan-panel">
        <div class="plan-head">
          <el-icon><List /></el-icon>
          <span>执行计划</span>
          <span class="plan-progress">{{ planDoneCount }}/{{ plan.length }} 步完成</span>
        </div>
        <div v-for="step in plan" :key="step.index" class="plan-step" :class="'step-' + step.status.toLowerCase()">
          <el-icon class="step-icon">
            <CircleCheck v-if="step.status === 'DONE'" />
            <Loading v-else-if="step.status === 'RUNNING'" />
            <Warning v-else-if="step.status === 'FAILED'" />
            <Minus v-else />
          </el-icon>
          <span class="step-title">{{ step.title }}</span>
          <span v-if="step.tool" class="step-tool">{{ step.tool }}</span>
        </div>
      </div>

      <div v-if="sources.length" class="sources">
        <div class="sources-head">参考来源（{{ sources.length }}）</div>
        <div class="source-list">
          <div
            v-for="(s, i) in sources"
            :key="s.citation || i"
            class="source-item"
            :class="{ clickable: !!s.courseId }"
            @click="openSource(s)"
          >
            <el-tag size="small" effect="plain" class="source-cite">{{ s.citation }}</el-tag>
            <div class="source-text">
              <div class="source-title">{{ s.title }}</div>
              <div class="source-meta">
                {{ s.courseName }}<template v-if="s.chapterTitle"> · {{ s.chapterTitle }}</template>
              </div>
            </div>
            <span v-if="typeof s.score === 'number'" class="source-score">{{ (s.score * 100).toFixed(1) }}</span>
          </div>
        </div>
      </div>

      <div class="chat-input">
        <el-input
          v-model="input"
          type="textarea"
          :rows="2"
          resize="none"
          placeholder="输入你的问题，例如：帮我分析这次考试哪里薄弱"
          @keydown.enter.exact.prevent="send()"
          :disabled="streaming"
        />
        <el-button type="primary" :loading="streaming" @click="send()" class="send-btn">
          {{ streaming ? '回答中' : '发送' }}
        </el-button>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { computed, nextTick, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import { streamAgentChat } from '@/api/agent'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const userStore = useUserStore()
const input = ref('')
const streaming = ref(false)
const messages = ref([])
const bodyRef = ref(null)
// 本轮回答的引用来源（由后端 SSE 的 citation 事件推送）
const sources = ref([])
// 任务规划：多步任务时后端先下发步骤表，再逐步回报状态（P0.2）
const plan = ref([])
// 对话模式：single=单智能体（规划+工具+反思），multi=多智能体协作（资料员→复核员→汇报员）
const mode = ref('single')

let sessionId = localStorage.getItem('agent_session')
if (!sessionId) {
  sessionId = crypto.randomUUID()
  localStorage.setItem('agent_session', sessionId)
}

const suggestions = [
  '帮我分析最近的考试成绩',
  '我有哪些错题需要重点复习？',
  '推荐一些数据安全相关的学习资料'
]

function sessionIdForUser() {
  // 用户切换账号时换会话，避免历史串号
  return `${userStore.userInfo?.id || userStore.userInfo?.username || 'me'}:${sessionId}`
}

const planDoneCount = computed(() => plan.value.filter(s => s.status === 'DONE').length)

function scrollBottom() {
  nextTick(() => {
    if (bodyRef.value) bodyRef.value.scrollTop = bodyRef.value.scrollHeight
  })
}

function resetSession() {
  sessionId = crypto.randomUUID()
  localStorage.setItem('agent_session', sessionId)
  messages.value = []
}

function send(preset) {
  const text = (preset || input.value || '').trim()
  if (!text || streaming.value) return
  input.value = ''
  streaming.value = true
  sources.value = []
  plan.value = []
  messages.value.push({ role: 'user', content: text })
  scrollBottom()

  const onEvent = (event, data) => {
    if (event === 'plan') {
      // 计划下发 / 最终定稿都会走这个事件；finalized 表示本轮结束，用它替换整表
      plan.value = data.steps || []
      scrollBottom()
    } else if (event === 'step_status') {
      const step = plan.value.find(s => s.index === data.index)
      if (step) step.status = data.status
      scrollBottom()
    } else if (event === 'tool_call') {
      messages.value.push({
        role: 'tool',
        running: true,
        toolName: data.name,
        label: `正在调用 ${toolLabel(data.name)}…`
      })
      scrollBottom()
    } else if (event === 'tool_result') {
      const step = [...messages.value].reverse().find(m => m.role === 'tool' && m.running)
      if (step) {
        step.running = false
        step.label = `${toolLabel(step.toolName)} 完成`
      }
      scrollBottom()
    } else if (event === 'reflection') {
      // 反思：工具没拿到有用信息时的自纠。如实展示，让用户知道助手在重试而不是"没反应"
      messages.value.push({
        role: 'reflection',
        label: data.retrying
          ? `${toolLabel(data.tool)} 未命中（${data.reason}），正在换关键词重试…`
          : `${toolLabel(data.tool)} 仍无结果（${data.reason}），将如实告知`
      })
      scrollBottom()
    } else if (event === 'multi_start') {
      // 多智能体：先把角色分工亮出来，用户才知道"接下来看到的几段分别是谁在做什么"
      messages.value.push({
        role: 'agent',
        running: false,
        label: `多智能体协作：${(data.roles || []).map(r => `${r.label}（${r.duty}）`).join(' → ')}`
      })
      scrollBottom()
    } else if (event === 'role_start') {
      messages.value.push({ role: 'agent', running: true, agentKey: data.role, label: `${data.label}工作中…` })
      scrollBottom()
    } else if (event === 'role_done') {
      const step = [...messages.value].reverse().find(m => m.role === 'agent' && m.agentKey === data.role && m.running)
      if (step) {
        step.running = false
        step.label = `${data.label}完成（${data.latencyMs}ms）`
        step.note = data.note || ''
      }
      scrollBottom()
    } else if (event === 'role_delta') {
      // 只有最终汇报文本会被流式推送；复核员的意见属于内部工作记录，不展示
      const last = messages.value[messages.value.length - 1]
      if (last && last.role === 'assistant' && last.streaming) {
        last.content += data.text || ''
      } else {
        messages.value.push({ role: 'assistant', content: data.text || '', streaming: true })
      }
      scrollBottom()
    } else if (event === 'delta') {
      const last = messages.value[messages.value.length - 1]
      if (last && last.role === 'assistant' && last.streaming) {
        last.content += data.text || ''
      } else {
        messages.value.push({ role: 'assistant', content: data.text || '', streaming: true })
      }
      scrollBottom()
    } else if (event === 'citation') {
      // 检索命中的引用证据：渲染"参考来源"，让回答可溯源、可审计
      sources.value = data.sources || []
      scrollBottom()
    } else if (event === 'done') {
      const last = messages.value[messages.value.length - 1]
      if (last && last.role === 'assistant') last.streaming = false
      streaming.value = false
      scrollBottom()
    } else if (event === 'error') {
      ElMessage.error(data.message || '助手暂时不可用')
      const last = messages.value[messages.value.length - 1]
      if (last && last.streaming) last.streaming = false
      streaming.value = false
    }
  }

  streamAgentChat({
    sessionId: sessionIdForUser(),
    message: text,
    mode: mode.value,
    onEvent,
    onError: error => {
      ElMessage.error(error.message || '网络异常，请稍后重试')
      const last = messages.value[messages.value.length - 1]
      if (last && last.streaming) last.streaming = false
      streaming.value = false
    }
  }).catch(() => {
    streaming.value = false
  })
}

function openSource(source) {
  if (source && source.courseId) {
    router.push(`/employee/course-learn/${source.courseId}`)
  }
}

function toolLabel(name) {
  const map = {
    myExamRecords: '查询考试成绩',
    myWrongAnswers: '查询错题记录',
    searchCourseMaterials: '搜索课程资料',
    getMaterialDetail: '阅读资料详情'
  }
  return map[name] || name
}
</script>

<style scoped>
.tutor-page {
  height: calc(100vh - 120px);
}

.chat-card {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.chat-header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.mode-help {
  color: #909399;
  cursor: help;
}

.chat-card :deep(.el-card__body) {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  padding-top: 8px;
}

.chat-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.chat-title {
  font-size: 17px;
  font-weight: 600;
}

.chat-sub {
  margin-top: 4px;
  font-size: 12px;
  color: #64748b;
}

.chat-body {
  flex: 1;
  overflow-y: auto;
  padding: 8px 4px;
}

.chat-empty {
  text-align: center;
  color: #64748b;
  padding-top: 48px;
}

.suggestions {
  margin-top: 16px;
  display: flex;
  gap: 10px;
  justify-content: center;
  flex-wrap: wrap;
}

.tool-step {
  margin: 10px 0;
  text-align: center;
}

/* 执行计划面板：让"助手打算怎么做 / 做到哪一步"可见 */
.plan-panel {
  margin: 12px 0;
  padding: 12px 14px;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  background: #f8fafc;
}

.plan-head {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 600;
  color: #334155;
  margin-bottom: 8px;
}

.plan-progress {
  margin-left: auto;
  font-weight: 400;
  color: #94a3b8;
}

.plan-step {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 0;
  font-size: 13px;
  color: #475569;
}

.plan-step .step-icon {
  flex: none;
}

.plan-step .step-tool {
  margin-left: auto;
  font-size: 12px;
  color: #94a3b8;
}

.plan-step.step-done {
  color: #16a34a;
}

.plan-step.step-running {
  color: #2563eb;
}

.plan-step.step-failed {
  color: #dc2626;
}

.plan-step.step-skipped {
  color: #94a3b8;
  text-decoration: line-through;
}

.bubble-row {
  display: flex;
  margin: 10px 0;
}

.bubble-row.user {
  justify-content: flex-end;
}

.bubble {
  max-width: 78%;
  padding: 10px 14px;
  border-radius: 14px;
  line-height: 1.7;
  font-size: 14px;
}

.user-bubble {
  background: #409eff;
  color: #fff;
  border-bottom-right-radius: 4px;
}

.ai-bubble {
  background: #f4f6f9;
  color: #1e293b;
  border-bottom-left-radius: 4px;
}

.pre-wrap {
  white-space: pre-wrap;
  word-break: break-word;
}

.chat-input {
  border-top: 1px solid #eef1f5;
  padding-top: 12px;
  display: flex;
  gap: 10px;
  align-items: flex-end;
}

.send-btn {
  height: 52px;
}

/* ---- 参考来源（引用证据）---- */
.sources {
  border-top: 1px dashed #e2e8f0;
  padding: 10px 0 12px;
  max-height: 168px;
  overflow-y: auto;
}

.sources-head {
  font-size: 12px;
  color: #64748b;
  margin-bottom: 8px;
}

.source-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.source-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  border: 1px solid #eef1f5;
  border-radius: 8px;
  background: #fafbfc;
  transition: background 0.15s, border-color 0.15s;
}

.source-item.clickable {
  cursor: pointer;
}

.source-item.clickable:hover {
  background: #f0f7ff;
  border-color: #c6e0ff;
}

.source-cite {
  flex: none;
  font-family: Consolas, Monaco, monospace;
}

.source-text {
  flex: 1;
  min-width: 0;
}

.source-title {
  font-size: 13px;
  color: #1e293b;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.source-meta {
  font-size: 12px;
  color: #94a3b8;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.source-score {
  flex: none;
  font-size: 12px;
  color: #94a3b8;
  font-family: Consolas, Monaco, monospace;
}
</style>
