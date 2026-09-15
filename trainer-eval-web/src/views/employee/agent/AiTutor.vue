<template>
  <div class="page-container tutor-page">
    <el-card shadow="never" class="chat-card">
      <template #header>
        <div class="chat-header">
          <div>
            <div class="chat-title">AI 学习助手</div>
            <div class="chat-sub">基于你的考试成绩、错题记录和课程资料，给出个性化学习建议</div>
          </div>
          <el-button size="small" @click="resetSession">新对话</el-button>
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
          <div v-else-if="msg.role === 'user'" class="bubble-row user">
            <div class="bubble user-bubble">{{ msg.content }}</div>
          </div>
          <div v-else class="bubble-row ai">
            <div class="bubble ai-bubble"><span class="pre-wrap">{{ msg.content }}</span></div>
          </div>
        </template>
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
import { nextTick, ref } from 'vue'
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
  messages.value.push({ role: 'user', content: text })
  scrollBottom()

  const onEvent = (event, data) => {
    if (event === 'tool_call') {
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
