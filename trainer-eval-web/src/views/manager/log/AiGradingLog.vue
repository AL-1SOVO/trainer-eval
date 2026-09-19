<template>
  <div class="page-container">
    <div class="search-bar flex-between">
      <div>
        <el-select v-model="query.status" clearable placeholder="按状态筛选" style="width: 180px; margin-right: 8px" @change="fetchData">
          <el-option label="成功" value="SUCCESS" />
          <el-option label="解析失败" value="PARSE_ERROR" />
          <el-option label="调用失败" value="FAILED" />
        </el-select>
        <el-button type="primary" @click="fetchData">查询</el-button>
      </div>
      <el-button :loading="consistencyLoading" @click="loadConsistency">
        AI 与教师评分一致性
      </el-button>
    </div>
    <div class="table-container">
      <el-table :data="logs" v-loading="loading">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="recordId" label="考试记录" width="90" />
        <el-table-column prop="answerId" label="答题ID" width="90" />
        <el-table-column prop="model" label="模型" min-width="140" />
        <el-table-column label="AI 分数" width="90">
          <template #default="{ row }">{{ row.score ?? '-' }}</template>
        </el-table-column>
        <el-table-column label="Token (提问/回答/合计)" width="190">
          <template #default="{ row }">{{ row.tokensPrompt ?? 0 }} / {{ row.tokensCompletion ?? 0 }} / {{ row.tokensTotal ?? 0 }}</template>
        </el-table-column>
        <el-table-column label="耗时" width="90">
          <template #default="{ row }">{{ row.latencyMs != null ? row.latencyMs + 'ms' : '-' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="时间" width="170">
          <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="showDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        style="margin-top:16px;text-align:right"
        v-model:current-page="query.page"
        :page-size="query.size"
        :total="total"
        layout="total, prev, pager, next"
        @current-change="fetchData"
      />
    </div>

    <el-drawer v-model="drawerVisible" title="AI 评分日志详情" size="45%">
      <template v-if="current">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="ID">{{ current.id }}</el-descriptions-item>
          <el-descriptions-item label="模型">{{ current.model }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ statusText(current.status) }}</el-descriptions-item>
          <el-descriptions-item label="AI 分数">{{ current.score ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="耗时">{{ current.latencyMs }}ms</el-descriptions-item>
          <el-descriptions-item label="成本">${{ current.cost }}</el-descriptions-item>
          <el-descriptions-item label="Token" :span="2">{{ current.tokensPrompt }} / {{ current.tokensCompletion }} / {{ current.tokensTotal }}</el-descriptions-item>
          <el-descriptions-item v-if="current.errorMsg" label="错误信息" :span="2">{{ current.errorMsg }}</el-descriptions-item>
        </el-descriptions>

        <h4 style="margin:16px 0 8px">System Prompt</h4>
        <pre class="prompt-block">{{ current.promptSystem || '-' }}</pre>
        <h4 style="margin:16px 0 8px">User Prompt</h4>
        <pre class="prompt-block">{{ current.promptUser || '-' }}</pre>
        <h4 style="margin:16px 0 8px">模型返回</h4>
        <pre class="prompt-block">{{ current.responseRaw || '-' }}</pre>
        <h4 v-if="current.reasoning" style="margin:16px 0 8px">评分理由</h4>
        <p v-if="current.reasoning" style="color:#334155;line-height:1.8">{{ current.reasoning }}</p>
      </template>
    </el-drawer>

    <el-dialog v-model="consistencyVisible" title="AI 评分与教师评分一致性" width="76%">
      <template v-if="consistency">
        <el-alert
          v-if="!consistency.available"
          type="info"
          :closable="false"
          show-icon
          :title="consistency.message"
        />
        <template v-else>
          <el-space style="margin-bottom: 12px">
            <span class="muted">统计区间：</span>
            <el-select v-model="consistencyDays" clearable placeholder="全部" style="width: 150px" @change="loadConsistency">
              <el-option label="近 7 天" :value="7" />
              <el-option label="近 30 天" :value="30" />
              <el-option label="近 90 天" :value="90" />
            </el-select>
            <el-tag type="info">样本 {{ consistency.sampleSize }} 条</el-tag>
          </el-space>

          <el-alert
            v-if="consistency.sampleWarning"
            type="warning"
            :closable="false"
            show-icon
            style="margin-bottom: 12px"
            :title="consistency.sampleWarning"
          />

          <el-row :gutter="12" style="margin-bottom: 12px">
            <el-col :span="4">
              <el-card shadow="never">
                <!--
                  相关系数在"样本不足"或"某一侧无方差"时是【无定义】，不是 0。
                  渲染成 0.000 会被读成"完全无关"，与下面的说明自相矛盾 ——
                  宁可显示"—"，也不显示一个会误导人的数字。
                -->
                <el-statistic v-if="consistency.pearson != null" title="Pearson" :value="consistency.pearson" :precision="3" />
                <div v-else class="stat-na">
                  <div class="stat-na-title">Pearson</div>
                  <div class="stat-na-value">—</div>
                </div>
              </el-card>
            </el-col>
            <el-col :span="4"><el-card shadow="never"><el-statistic title="MAE" :value="consistency.summary.mae" :precision="3" /></el-card></el-col>
            <el-col :span="4"><el-card shadow="never"><el-statistic title="RMSE" :value="consistency.summary.rmse" :precision="3" /></el-card></el-col>
            <el-col :span="4"><el-card shadow="never"><el-statistic title="平均偏差" :value="consistency.summary.meanBias" :precision="3" /></el-card></el-col>
            <el-col :span="4"><el-card shadow="never"><el-statistic title="±10% 一致率" :value="consistency.summary.within10PercentRate * 100" :precision="1" suffix="%" /></el-card></el-col>
            <el-col :span="4"><el-card shadow="never"><el-statistic title="±20% 一致率" :value="consistency.summary.within20PercentRate * 100" :precision="1" suffix="%" /></el-card></el-col>
          </el-row>

          <el-descriptions :column="1" border size="small" style="margin-bottom: 12px">
            <el-descriptions-item label="指标含义">{{ consistency.metricDefinition }}</el-descriptions-item>
            <el-descriptions-item label="偏差倾向">{{ consistency.summary.biasDirection }}</el-descriptions-item>
            <el-descriptions-item label="一致度解读">{{ consistency.interpretation || consistency.pearsonNote }}</el-descriptions-item>
            <el-descriptions-item label="决策建议">{{ consistency.decisionHint }}</el-descriptions-item>
          </el-descriptions>

          <el-divider content-position="left">偏离最大的样本（复核时优先看这些）</el-divider>
          <el-table :data="consistency.outliers || []" size="small" style="margin-bottom: 12px">
            <el-table-column prop="answerId" label="答题ID" width="90" />
            <el-table-column prop="questionType" label="题型" width="110" />
            <el-table-column prop="fullScore" label="满分" width="80" />
            <el-table-column prop="aiScore" label="AI 分" width="80" />
            <el-table-column prop="manualScore" label="教师分" width="90" />
            <el-table-column prop="diff" label="差值(AI-教师)" width="120" />
          </el-table>

          <el-divider content-position="left">按题型拆分</el-divider>
          <el-table :data="Object.entries(consistency.byQuestionType || {}).map(([k, v]) => ({ type: k, ...v }))" size="small">
            <el-table-column prop="type" label="题型" width="140" />
            <el-table-column prop="sampleSize" label="样本" width="80" />
            <el-table-column prop="mae" label="MAE" width="100" />
            <el-table-column prop="meanBias" label="平均偏差" width="110" />
            <el-table-column label="±10% 一致率" width="130">
              <template #default="{ row }">{{ rateText(row.within10PercentRate) }}</template>
            </el-table-column>
          </el-table>
        </template>
      </template>
      <el-empty v-else description="正在加载" />
    </el-dialog>
  </div>
</template>

<script setup>
import { reactive, ref, onMounted } from 'vue'
import request from '@/api/request'
import { formatDateTime } from '@/utils/datetime'

const logs = ref([])
const loading = ref(false)
const total = ref(0)
const query = reactive({ page: 1, size: 10, status: '' })
const drawerVisible = ref(false)
const current = ref(null)

// ---- AI 与教师评分一致性 ----
// 回答的是一个可决策的问题："AI 评分能不能先自动给分、教师只复核？"
const consistencyVisible = ref(false)
const consistency = ref(null)
const consistencyLoading = ref(false)
const consistencyDays = ref(null)

async function loadConsistency() {
  consistencyLoading.value = true
  try {
    consistency.value = await request.get('/ai/grading-consistency', {
      params: { sinceDays: consistencyDays.value || undefined }
    })
    consistencyVisible.value = true
  } catch (e) {
    // 拦截器已提示
  } finally {
    consistencyLoading.value = false
  }
}

function rateText(value) {
  return value == null ? '-' : `${(value * 100).toFixed(1)}%`
}

function statusText(status) {
  if (status === 'SUCCESS') return '成功'
  if (status === 'PARSE_ERROR') return '解析失败'
  if (status === 'FAILED') return '调用失败'
  return status || '-'
}

function statusTag(status) {
  if (status === 'SUCCESS') return 'success'
  if (status === 'PARSE_ERROR') return 'warning'
  return 'danger'
}

async function fetchData() {
  loading.value = true
  try {
    const data = await request.get('/ai/logs', { params: { page: query.page, size: query.size, status: query.status || undefined } })
    logs.value = data.records || []
    total.value = data.total || 0
  } catch (e) {
  } finally {
    loading.value = false
  }
}

function showDetail(row) {
  current.value = row
  drawerVisible.value = true
}

onMounted(fetchData)
</script>

<style scoped>
.prompt-block {
  margin: 0;
  padding: 12px;
  border-radius: 8px;
  background: #f8fafc;
  color: #334155;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}
.muted {
  color: #909399;
  font-size: 13px;
}
.stat-na {
  text-align: center;
}
.stat-na-title {
  font-size: 13px;
  color: #909399;
  margin-bottom: 6px;
}
.stat-na-value {
  font-size: 22px;
  color: #c0c4cc;
  line-height: 1.4;
}
</style>
