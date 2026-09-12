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
</style>
