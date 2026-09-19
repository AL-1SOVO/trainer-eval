<template>
  <div class="page-container">
    <h3 style="margin-bottom:20px">错题分析</h3>

    <!-- Summary Cards -->
    <el-row :gutter="16" style="margin-bottom:20px">
      <el-col :span="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-value">{{ stats.totalWrong || 0 }}</div>
          <div class="stat-label">错题总数</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-value">{{ categoryTypes.length }}</div>
          <div class="stat-label">错误类型</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-value">{{ courseDistribution.length }}</div>
          <div class="stat-label">涉及课程</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-value">{{ topWrongQuestions.length }}</div>
          <div class="stat-label">高频错题</div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" style="margin-bottom:20px">
      <!-- Error Category Chart -->
      <el-col :span="12">
        <el-card shadow="hover">
          <template #header><span>错误类型分布</span></template>
          <v-chart v-if="categoryChartOption" :option="categoryChartOption" style="height:280px" autoresize />
          <el-empty v-else description="暂无数据" style="height:280px" />
        </el-card>
      </el-col>
      <!-- Question Type Chart -->
      <el-col :span="12">
        <el-card shadow="hover">
          <template #header><span>题型错误分布</span></template>
          <v-chart v-if="typeChartOption" :option="typeChartOption" style="height:280px" autoresize />
          <el-empty v-else description="暂无数据" style="height:280px" />
        </el-card>
      </el-col>
    </el-row>

    <!-- Filters -->
    <el-card shadow="hover" style="margin-bottom:20px">
      <el-form :inline="true" :model="filters">
        <el-form-item label="课程">
          <el-select v-model="filters.courseId" placeholder="全部课程" clearable style="width:200px" @change="fetchAnalyses">
            <el-option v-for="c in courseList" :key="c.courseId" :label="c.courseName" :value="c.courseId" />
          </el-select>
        </el-form-item>
        <el-form-item label="题型">
          <el-select v-model="filters.questionType" placeholder="全部题型" clearable style="width:180px" @change="fetchAnalyses">
            <el-option label="单选题" value="SINGLE_CHOICE" />
            <el-option label="多选题" value="MULTI_CHOICE" />
            <el-option label="判断题" value="TRUE_FALSE" />
            <el-option label="简答题" value="SHORT_ANSWER" />
            <el-option label="论述题" value="ESSAY" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="fetchAnalyses">查询</el-button>
          <el-button @click="fetchStats">刷新统计</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- Wrong Answer List -->
    <el-card shadow="hover">
      <template #header>
        <span>错题列表</span>
      </template>
      <el-empty v-if="!loading && analyses.length === 0" description="暂无错题记录" />
      <div v-else v-loading="loading">
        <div v-for="item in analyses" :key="item.analysis?.id" class="analysis-item">
          <div class="question-header" @click="item.expanded = !item.expanded">
            <div class="question-info">
              <el-tag :type="questionTypeTag(item.questionType)" size="small">{{ questionTypeName(item.questionType) }}</el-tag>
              <el-tag v-if="item.courseName" size="small" type="info" style="margin-left:8px">{{ item.courseName }}</el-tag>
              <span class="question-preview">{{ truncateText(item.questionContent, 60) }}</span>
            </div>
            <div class="question-actions">
              <el-tag v-if="item.analysis?.errorCategory" :type="errorCategoryTag(item.analysis.errorCategory)" size="small">
                {{ errorCategoryName(item.analysis.errorCategory) }}
              </el-tag>
              <el-icon style="margin-left:8px;transition:transform 0.3s" :style="{ transform: item.expanded ? 'rotate(180deg)' : '' }">
                <ArrowDown />
              </el-icon>
            </div>
          </div>

          <div v-show="item.expanded" class="analysis-detail">
            <el-descriptions :column="2" border size="small">
              <el-descriptions-item label="你的答案">{{ item.userAnswer || '未作答' }}</el-descriptions-item>
              <el-descriptions-item label="参考答案">{{ item.referenceAnswer || '无' }}</el-descriptions-item>
              <el-descriptions-item label="难度">
                <el-rate :model-value="item.difficulty || 1" disabled show-score text-color="#ff9900" />
              </el-descriptions-item>
              <el-descriptions-item label="标签">{{ item.tags || '-' }}</el-descriptions-item>
            </el-descriptions>

            <div v-if="item.analysis?.errorAnalysis" class="analysis-content">
              <h4>错误分析</h4>
              <p>{{ item.analysis.errorAnalysis }}</p>
            </div>
            <div v-if="item.analysis?.recommendation" class="analysis-content">
              <h4>改进建议</h4>
              <p>{{ item.analysis.recommendation }}</p>
            </div>
            <div v-if="item.questionAnalysis && !item.analysis?.errorAnalysis" class="analysis-content">
              <h4>题目解析</h4>
              <p>{{ item.questionAnalysis }}</p>
            </div>
            <!-- 检索到的课程资料：渲染成可点击的资料条目。
                 原来这里直接输出 relatedMaterialIds 原文，用户看到的是 "[1]" 这种原始 JSON，
                 既看不懂也点不进去。现在用后端解析好的 relatedMaterials（含标题与所属课程/章节）。

                 标题用「检索到的」而不是「相关」：这些是检索层返回的候选资料，
                 模型可能已判定它们与本题关系不大（例如题目问 VPN，而语料里只有数据保护原则）。
                 叫「相关学习资源」等于把"系统找到了"说成"这些就是对的"。 -->
            <div v-if="item.relatedMaterials?.length" class="analysis-content">
              <h4>检索到的课程资料</h4>
              <div class="material-links">
                <el-tag
                  v-for="mat in item.relatedMaterials"
                  :key="mat.materialId"
                  class="material-link"
                  type="primary"
                  effect="plain"
                  @click="openMaterial(mat)"
                >
                  <el-icon><Reading /></el-icon>
                  {{ mat.title }}
                  <span v-if="mat.courseName" class="material-course">（{{ mat.courseName }}）</span>
                </el-tag>
              </div>
            </div>

            <div style="margin-top:12px">
              <el-button v-if="!item.analysis?.errorAnalysis || item.analysis?.errorCategory === 'OTHER'" type="warning" size="small" :loading="item.analyzing" @click.stop="doAnalyze(item)">
                <el-icon><MagicStick /></el-icon> AI智能分析
              </el-button>
              <el-tag v-else type="success" size="small">已分析</el-tag>
            </div>
          </div>
        </div>

        <div style="margin-top:16px;text-align:center">
          <el-pagination
            v-model:current-page="pagination.page"
            v-model:page-size="pagination.pageSize"
            :total="pagination.total"
            :page-sizes="[5, 10, 20]"
            layout="total, sizes, prev, pager, next"
            @size-change="fetchAnalyses"
            @current-change="fetchAnalyses"
          />
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { getUserAnalyses, getAnalysisStats, triggerAnalysis } from '@/api/exam'
import { ElMessage } from 'element-plus'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { PieChart, BarChart } from 'echarts/charts'
import { TitleComponent, TooltipComponent, LegendComponent, GridComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

const router = useRouter()

/** 跳到资料所属课程的学习页。资料实体本身没有 courseId，后端已从章节反查补上。 */
function openMaterial(mat) {
  if (!mat || !mat.courseId) {
    ElMessage.info('该资料暂未关联课程，无法跳转')
    return
  }
  router.push('/employee/course-learn/' + mat.courseId)
}

use([PieChart, BarChart, TitleComponent, TooltipComponent, LegendComponent, GridComponent, CanvasRenderer])

const loading = ref(false)
const analyses = ref([])
const stats = reactive({
  totalWrong: 0,
  errorCategoryDistribution: [],
  questionTypeDistribution: [],
  courseDistribution: [],
  topWrongQuestions: []
})

const filters = reactive({
  courseId: null,
  questionType: ''
})

const pagination = reactive({
  page: 1,
  pageSize: 10,
  total: 0
})

const categoryTypes = computed(() => stats.errorCategoryDistribution || [])
const courseDistribution = computed(() => stats.courseDistribution || [])
const topWrongQuestions = computed(() => stats.topWrongQuestions || [])
const courseList = computed(() => stats.courseDistribution || [])

const categoryChartOption = computed(() => {
  const data = stats.errorCategoryDistribution || []
  if (data.length === 0) return null
  return {
    tooltip: { trigger: 'item' },
    legend: { bottom: 0 },
    series: [{
      type: 'pie',
      radius: ['40%', '70%'],
      center: ['50%', '45%'],
      label: { formatter: '{b}\n{d}%' },
      data: data.map(d => ({ name: errorCategoryName(d.error_category), value: d.count }))
    }]
  }
})

const typeChartOption = computed(() => {
  const data = stats.questionTypeDistribution || []
  if (data.length === 0) return null
  return {
    tooltip: { trigger: 'item' },
    legend: { bottom: 0 },
    series: [{
      type: 'pie',
      radius: '60%',
      center: ['50%', '45%'],
      label: { formatter: '{b}\n{d}%' },
      data: data.map(d => ({ name: questionTypeName(d.question_type), value: d.count }))
    }]
  }
})

function questionTypeName(type) {
  const map = { SINGLE_CHOICE: '单选题', MULTI_CHOICE: '多选题', TRUE_FALSE: '判断题', SHORT_ANSWER: '简答题', ESSAY: '论述题' }
  return map[type] || type
}

function questionTypeTag(type) {
  const map = { SINGLE_CHOICE: '', MULTI_CHOICE: 'success', TRUE_FALSE: 'warning', SHORT_ANSWER: 'info', ESSAY: 'danger' }
  return map[type] || ''
}

function errorCategoryName(cat) {
  const map = { MISUNDERSTANDING: '概念误解', CALCULATION_ERROR: '计算错误', KNOWLEDGE_GAP: '知识盲区', OTHER: '其他' }
  return map[cat] || cat
}

function errorCategoryTag(cat) {
  const map = { MISUNDERSTANDING: 'warning', CALCULATION_ERROR: 'danger', KNOWLEDGE_GAP: '', OTHER: 'info' }
  return map[cat] || 'info'
}

function truncateText(text, max) {
  if (!text) return ''
  return text.length > max ? text.slice(0, max) + '...' : text
}

async function fetchStats() {
  try {
    const data = await getAnalysisStats()
    Object.assign(stats, data)
  } catch (e) {
    // stats fail silently
  }
}

async function fetchAnalyses() {
  loading.value = true
  try {
    const params = {
      page: pagination.page,
      size: pagination.pageSize
    }
    if (filters.courseId) params.courseId = filters.courseId
    if (filters.questionType) params.questionType = filters.questionType

    const result = await getUserAnalyses(params)
    analyses.value = (result.records || []).map(item => ({ ...item, expanded: false, analyzing: false }))
    pagination.total = result.total || 0
  } catch (e) {
    ElMessage.error('获取错题列表失败')
  } finally {
    loading.value = false
  }
}

async function doAnalyze(item) {
  item.analyzing = true
  try {
    const result = await triggerAnalysis(item.analysis.answerId)
    item.analysis = result
    ElMessage.success('AI分析完成')
  } catch (e) {
    ElMessage.error('AI分析失败：' + (e.message || '未知错误'))
  } finally {
    item.analyzing = false
  }
}

onMounted(() => {
  fetchStats()
  fetchAnalyses()
})
</script>

<style scoped>
.stat-card { text-align: center; }
.stat-value { font-size: 32px; font-weight: bold; color: #409eff; }
.stat-label { font-size: 14px; color: #909399; margin-top: 4px; }

.analysis-item {
  border: 1px solid #ebeef5;
  border-radius: 8px;
  margin-bottom: 12px;
  overflow: hidden;
}
.question-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  cursor: pointer;
  background: #fafafa;
  transition: background 0.2s;
}
.question-header:hover { background: #f0f2f5; }
.question-info { display: flex; align-items: center; gap: 8px; flex: 1; min-width: 0; }
.question-preview { color: #606266; margin-left: 8px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.question-actions { display: flex; align-items: center; flex-shrink: 0; }

.analysis-detail {
  padding: 16px;
  border-top: 1px solid #ebeef5;
}
.material-links {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.material-link {
  cursor: pointer;
}

.material-course {
  color: #94a3b8;
  font-size: 12px;
}

.analysis-content {
  margin-top: 12px;
  padding: 12px;
  background: #f5f7fa;
  border-radius: 6px;
}
.analysis-content h4 {
  margin: 0 0 8px 0;
  font-size: 14px;
  color: #303133;
}
.analysis-content p {
  margin: 0;
  font-size: 13px;
  color: #606266;
  line-height: 1.8;
  white-space: pre-wrap;
}
</style>
