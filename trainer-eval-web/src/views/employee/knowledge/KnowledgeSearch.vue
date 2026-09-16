<template>
  <div class="page-container">
    <div class="page-header">
      <div>
        <h2>知识搜索</h2>
        <p>直接在企业培训文档里找答案：输入关键词，返回文档原文片段与出处。</p>
      </div>
    </div>

    <el-card shadow="never" class="search-card">
      <div class="search-bar">
        <el-input
          v-model="query"
          size="large"
          clearable
          placeholder="例如：数据加密有哪些要求？最小权限原则是什么？"
          @keyup.enter="doSearch"
        >
          <template #prefix>
            <el-icon><Search /></el-icon>
          </template>
          <template #append>
            <el-button type="primary" :loading="loading" @click="doSearch">搜索</el-button>
          </template>
        </el-input>

        <el-select
          v-model="courseId"
          size="large"
          clearable
          placeholder="全部课程"
          class="course-select"
          @change="doSearch"
        >
          <el-option
            v-for="course in courses"
            :key="course.courseId"
            :label="course.courseName"
            :value="course.courseId"
          />
        </el-select>
      </div>

      <div class="hot-queries">
        <span class="hot-label">试试：</span>
        <el-tag
          v-for="sample in samples"
          :key="sample"
          class="hot-tag"
          effect="plain"
          @click="useSample(sample)"
        >
          {{ sample }}
        </el-tag>
      </div>
    </el-card>

    <!-- 降级提示：结果可能不完整，但不能因此静默，否则用户会以为"公司没这份文档" -->
    <el-alert
      v-if="degraded"
      type="warning"
      show-icon
      :closable="false"
      class="degraded-alert"
      title="当前为降级检索，结果可能不完整"
      description="检索服务暂时不可用，已切换为本地检索。请稍后重试以获取更完整的结果。"
    />

    <el-card v-if="searched" shadow="never" class="result-card">
      <div class="result-head">
        <span>共找到 <b>{{ total }}</b> 条相关片段</span>
        <span v-if="usedQuery" class="result-query">关键词：{{ usedQuery }}</span>
      </div>

      <div v-loading="loading">
        <div v-for="(item, index) in items" :key="item.citation || index" class="hit">
          <div class="hit-head">
            <el-tag size="small" effect="plain" class="hit-cite">{{ item.citation }}</el-tag>
            <span class="hit-title">{{ item.title }}</span>
            <span class="hit-score">
              <el-progress
                :percentage="Math.round((item.relevance || 0) * 100)"
                :stroke-width="6"
                :show-text="false"
                class="hit-bar"
              />
              <span class="hit-score-text">相关度 {{ ((item.relevance || 0) * 100).toFixed(1) }}</span>
            </span>
          </div>

          <div class="hit-meta">
            {{ item.courseName }}<template v-if="item.chapterTitle"> · {{ item.chapterTitle }}</template>
          </div>

          <!-- excerpt 来自用户上传的文档正文，先转义再高亮，避免文档里的 HTML 被当成页面结构执行 -->
          <div class="hit-excerpt" v-html="highlight(item.excerpt)"></div>

          <div class="hit-actions">
            <el-button
              v-if="item.courseId"
              link
              type="primary"
              @click="goCourse(item.courseId)"
            >
              去课程里看原文
            </el-button>
          </div>
        </div>
      </div>

      <el-empty v-if="!loading && !items.length" :description="emptyText" />
    </el-card>

    <el-card v-else shadow="never" class="result-card intro-card">
      <el-empty description="输入关键词开始搜索" />
      <div class="intro-tips">
        <p>· 支持问句，例如"密码应该多久更换一次"</p>
        <p>· 只返回你有权限查看的课程资料</p>
        <p>· 文档由系统管理员或内训师上传，上传后即可被检索到</p>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Search } from '@element-plus/icons-vue'
import { listSearchableCourses, searchKnowledge } from '@/api/knowledge'

const router = useRouter()

const query = ref('')
const usedQuery = ref('')
const courseId = ref(null)
const courses = ref([])
const items = ref([])
const total = ref(0)
const loading = ref(false)
const searched = ref(false)
const degraded = ref(false)
const emptyText = ref('没有找到相关资料')

const samples = ['数据加密', '最小权限原则', '密码安全要求']

const EMPTY_HINT = '没有找到相关资料。可能是该知识点还没有上传培训文档，或换个说法再试试。'

function useSample(sample) {
  query.value = sample
  doSearch()
}

async function doSearch() {
  const keyword = (query.value || '').trim()
  if (!keyword) {
    searched.value = false
    return
  }
  loading.value = true
  searched.value = true
  usedQuery.value = keyword
  try {
    const data = await searchKnowledge({ query: keyword, courseId: courseId.value || undefined, topK: 8 })
    items.value = data?.items || []
    total.value = data?.total || 0
    degraded.value = !!data?.degraded
    emptyText.value = data?.message || EMPTY_HINT
  } catch (e) {
    items.value = []
    total.value = 0
    degraded.value = false
    emptyText.value = '搜索失败，请稍后重试。'
  } finally {
    loading.value = false
  }
}

function goCourse(id) {
  router.push(`/employee/course-learn/${id}`)
}

function escapeHtml(text) {
  return String(text == null ? '' : text).replace(/[&<>"']/g, ch => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
  }[ch]))
}

/** 关键词高亮。先转义文档正文，再对"转义后的关键词"做匹配，避免 XSS。 */
function highlight(text) {
  const safe = escapeHtml(text)
  const terms = Array.from(new Set(
    String(query.value || '').split(/\s+/).map(t => t.trim()).filter(Boolean)
  ))
  if (!terms.length) return safe
  const pattern = terms
    .map(t => escapeHtml(t).replace(/[.*+?^${}()|[\]\\]/g, '\\$&'))
    .join('|')
  try {
    return safe.replace(new RegExp(pattern, 'gi'), m => `<em>${m}</em>`)
  } catch (e) {
    return safe
  }
}

onMounted(async () => {
  try {
    courses.value = await listSearchableCourses() || []
  } catch (e) {
    courses.value = []
  }
})
</script>

<style scoped>
.page-header {
  margin-bottom: 16px;
}

.page-header h2 {
  margin: 0 0 8px;
  color: #0f172a;
}

.page-header p {
  margin: 0;
  color: #64748b;
}

.search-card,
.result-card {
  border-radius: 18px;
}

.search-bar {
  display: flex;
  gap: 12px;
  align-items: center;
}

.course-select {
  width: 220px;
  flex: none;
}

.hot-queries {
  margin-top: 14px;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.hot-label {
  color: #94a3b8;
  font-size: 13px;
}

.hot-tag {
  cursor: pointer;
}

.degraded-alert {
  margin-top: 16px;
  border-radius: 12px;
}

.result-card {
  margin-top: 16px;
}

.result-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  color: #475569;
  font-size: 13px;
  margin-bottom: 12px;
}

.result-query {
  color: #94a3b8;
}

.hit {
  padding: 14px 0;
  border-top: 1px solid #eef2f7;
}

.hit:first-child {
  border-top: none;
}

.hit-head {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.hit-cite {
  flex: none;
}

.hit-title {
  font-weight: 600;
  color: #0f172a;
}

.hit-score {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 8px;
  flex: none;
}

.hit-bar {
  width: 90px;
}

.hit-score-text {
  color: #94a3b8;
  font-size: 12px;
  white-space: nowrap;
}

.hit-meta {
  margin-top: 6px;
  color: #64748b;
  font-size: 13px;
}

.hit-excerpt {
  margin-top: 8px;
  color: #334155;
  line-height: 1.75;
  background: #f8fafc;
  border-radius: 10px;
  padding: 10px 12px;
}

.hit-excerpt :deep(em) {
  background: #fef3c7;
  color: #92400e;
  font-style: normal;
  border-radius: 3px;
  padding: 0 2px;
}

.hit-actions {
  margin-top: 6px;
}

.intro-tips {
  text-align: center;
  color: #94a3b8;
  font-size: 13px;
  padding-bottom: 8px;
}

.intro-tips p {
  margin: 4px 0;
}
</style>
