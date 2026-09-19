<template>
  <div class="page-container">
    <el-card shadow="never" style="margin-bottom: 12px">
      <template #header>
        <div class="flex-between">
          <span>链路追踪状态</span>
          <el-space>
            <el-button size="small" :loading="testing" @click="doTest">测试 Langfuse 连通</el-button>
            <el-button size="small" @click="loadStatus">刷新</el-button>
          </el-space>
        </div>
      </template>
      <el-row v-if="status" :gutter="12">
        <el-col :span="8">
          <el-descriptions :column="1" border size="small" title="本地存储（主路径，零依赖）">
            <el-descriptions-item label="表">
              {{ status.localStore.table }}
              <el-tag :type="status.localStore.available ? 'success' : 'danger'" size="small" style="margin-left: 6px">
                {{ status.localStore.available ? '可用' : '不可用' }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="累计链路">{{ status.localStore.total ?? '-' }}</el-descriptions-item>
            <el-descriptions-item label="记录内容">{{ status.captureContent ? '含提示词与回答' : '仅长度与摘要' }}</el-descriptions-item>
          </el-descriptions>
        </el-col>
        <el-col :span="16">
          <el-descriptions :column="2" border size="small" title="Langfuse 导出（可选）">
            <el-descriptions-item label="已配置">
              <el-tag :type="status.langfuse.configured ? 'success' : 'info'" size="small">
                {{ status.langfuse.configured ? '是' : '否（仅写本地表）' }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="协议">{{ status.langfuse.mode }}</el-descriptions-item>
            <el-descriptions-item label="端点" :span="2">{{ status.langfuse.endpoint }}</el-descriptions-item>
            <el-descriptions-item label="已导出链路">{{ status.langfuse.exportedTraces }}</el-descriptions-item>
            <el-descriptions-item label="已导出节点">{{ status.langfuse.exportedSpans }}</el-descriptions-item>
            <el-descriptions-item label="投递失败批次">{{ status.langfuse.failedBatches }}</el-descriptions-item>
            <el-descriptions-item label="队列">{{ status.langfuse.queueDepth }} / {{ status.langfuse.queueCapacity }}</el-descriptions-item>
            <el-descriptions-item v-if="status.langfuse.lastError" label="最近错误" :span="2">
              <span style="color: #f56c6c">{{ status.langfuse.lastError }}</span>
            </el-descriptions-item>
          </el-descriptions>
        </el-col>
      </el-row>
      <el-alert
        v-if="status && status.hint"
        type="info"
        :closable="false"
        show-icon
        style="margin-top: 12px"
        :title="status.hint"
      />
    </el-card>

    <div class="search-bar flex-between">
      <el-space>
        <el-select v-model="filterName" clearable placeholder="按链路类型筛选" style="width: 220px" @change="loadTraces">
          <el-option label="全部" value="" />
          <el-option label="Agent 对话（单智能体）" value="agent.chat" />
          <el-option label="Agent 对话（多智能体）" value="agent.multi" />
          <el-option label="知识检索" value="rag.retrieve" />
          <el-option label="AI 评分" value="ai.grade" />
          <el-option label="错因分析" value="error.analysis" />
        </el-select>
        <el-button type="primary" @click="loadTraces">查询</el-button>
      </el-space>
      <el-tag type="info">共 {{ traces.length }} 条</el-tag>
    </div>

    <el-table :data="traces" v-loading="loading" max-height="560" @row-click="openDetail">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="name" label="链路" width="140" />
      <el-table-column label="用户" width="80">
        <template #default="{ row }">{{ row.userId ?? '-' }}</template>
      </el-table-column>
      <el-table-column label="耗时" width="100" sortable :sort-method="(a, b) => (a.latencyMs || 0) - (b.latencyMs || 0)">
        <template #default="{ row }">
          <el-tag :type="row.latencyMs > 8000 ? 'danger' : row.latencyMs > 3000 ? 'warning' : 'success'" size="small">
            {{ row.latencyMs }}ms
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="Token(入/出)" width="130">
        <template #default="{ row }">{{ row.promptTokens ?? 0 }} / {{ row.completionTokens ?? 0 }}</template>
      </el-table-column>
      <el-table-column prop="observationCount" label="节点" width="70" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.level === 'ERROR' ? 'danger' : row.level === 'WARNING' ? 'warning' : 'success'" size="small">
            {{ row.level }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="inputBrief" label="输入" min-width="220" show-overflow-tooltip />
      <el-table-column label="时间" width="170">
        <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
      </el-table-column>
    </el-table>

    <el-drawer v-model="drawerVisible" title="链路明细" size="60%">
      <template v-if="detail">
        <el-descriptions :column="2" border size="small" style="margin-bottom: 12px">
          <el-descriptions-item label="traceId">{{ detail.traceId }}</el-descriptions-item>
          <el-descriptions-item label="链路">{{ detail.name }}</el-descriptions-item>
          <el-descriptions-item label="耗时">{{ detail.latencyMs }}ms</el-descriptions-item>
          <el-descriptions-item label="Token">{{ detail.promptTokens }} / {{ detail.completionTokens }}</el-descriptions-item>
          <el-descriptions-item label="输入" :span="2">{{ detail.inputBrief }}</el-descriptions-item>
          <el-descriptions-item label="输出" :span="2">{{ detail.outputBrief }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.statusMessage" label="异常" :span="2">
            <span style="color: #f56c6c">{{ detail.statusMessage }}</span>
          </el-descriptions-item>
        </el-descriptions>

        <el-alert
          type="info"
          :closable="false"
          show-icon
          style="margin-bottom: 12px"
          title="每一跳的耗时与偏移"
          description="offsetMs 是相对链路起点的偏移。看「检索占了多少」比看总耗时有用得多 —— 总耗时只告诉你慢，偏移告诉你慢在哪一跳。"
        />

        <el-table :data="detail.steps || []" size="small">
          <el-table-column label="类型" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="typeTag(row.type)">{{ row.type }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="name" label="名称" width="150" show-overflow-tooltip />
          <el-table-column label="起始偏移" width="100">
            <template #default="{ row }">{{ row.offsetMs }}ms</template>
          </el-table-column>
          <el-table-column label="本跳耗时" width="100">
            <template #default="{ row }">{{ row.latencyMs }}ms</template>
          </el-table-column>
          <el-table-column label="Token" width="110">
            <template #default="{ row }">
              {{ row.promptTokens != null ? `${row.promptTokens}/${row.completionTokens ?? 0}` : '-' }}
            </template>
          </el-table-column>
          <el-table-column label="模型" width="150" show-overflow-tooltip>
            <template #default="{ row }">{{ row.model || '-' }}</template>
          </el-table-column>
          <el-table-column label="输出/备注" min-width="220" show-overflow-tooltip>
            <template #default="{ row }">
              <span v-if="row.statusMessage" style="color: #f56c6c">{{ row.statusMessage }}</span>
              <span v-else>{{ row.output || row.input || '-' }}</span>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { formatDateTime } from '@/utils/datetime'
import { getObservabilityStatus, listTraces, getTraceDetail, testLangfuse } from '@/api/observability'

const status = ref(null)
const traces = ref([])
const detail = ref(null)
const filterName = ref('')
const loading = ref(false)
const testing = ref(false)
const drawerVisible = ref(false)

function typeTag(type) {
  const map = {
    chain: 'info',
    generation: 'success',
    retriever: 'warning',
    tool: 'primary',
    agent: ''
  }
  return map[type] ?? 'info'
}

async function loadStatus() {
  try {
    status.value = await getObservabilityStatus()
  } catch (e) { /* 拦截器已提示 */ }
}

async function loadTraces() {
  loading.value = true
  try {
    traces.value = await listTraces({ name: filterName.value || undefined, limit: 50 })
  } finally {
    loading.value = false
  }
}

async function openDetail(row) {
  try {
    detail.value = await getTraceDetail(row.id)
    drawerVisible.value = true
  } catch (e) { /* 已提示 */ }
}

async function doTest() {
  testing.value = true
  try {
    const result = await testLangfuse()
    ElMessage.success(`Langfuse 连通正常（endpoint: ${result.endpoint || '-'}）`)
    loadStatus()
  } catch (e) {
    // 失败原因由后端原样返回（endpoint 配错、密钥无效等），拦截器已弹出
  } finally {
    testing.value = false
  }
}

onMounted(() => {
  loadStatus()
  loadTraces()
})
</script>
