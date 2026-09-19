<template>
  <div class="page-container">
    <el-alert
      v-if="health && health.circuitOpen"
      type="error"
      show-icon
      :closable="false"
      style="margin-bottom: 12px"
      title="检索服务已熔断"
      :description="`MimirQ 连续失败已触发熔断，冷却剩余约 ${Math.ceil((health.circuitOpenRemainMs || 0) / 1000)} 秒。期间所有检索走本地混合通道（结果可用但不完整）。最近失败原因：${health.lastFailure || '未知'}`"
    />
    <el-alert
      v-else-if="health && health.enabled === false"
      type="warning"
      show-icon
      :closable="false"
      style="margin-bottom: 12px"
      title="知识库服务未启用"
      description="mimirq.enabled=false，检索只会走本地混合通道。要启用混合召回 + 重排，请配置并启动 MimirQ。"
    />

    <el-tabs v-model="activeTab" @tab-change="onTabChange">
      <!-- ============================ 概览 ============================ -->
      <el-tab-pane label="概览与运维" name="overview">
        <el-row :gutter="12">
          <el-col :span="12">
            <el-card shadow="never">
              <template #header>
                <div class="flex-between">
                  <span>检索链路健康</span>
                  <el-button size="small" @click="loadHealth">刷新</el-button>
                </div>
              </template>
              <el-descriptions v-if="health" :column="1" border size="small">
                <el-descriptions-item label="MimirQ 启用">
                  <el-tag :type="health.enabled ? 'success' : 'info'">{{ health.enabled ? '是' : '否' }}</el-tag>
                </el-descriptions-item>
                <el-descriptions-item label="服务地址">{{ health.baseUrl || '-' }}</el-descriptions-item>
                <el-descriptions-item label="熔断状态">
                  <el-tag :type="health.circuitOpen ? 'danger' : 'success'">
                    {{ health.circuitOpen ? '已熔断' : '正常' }}
                  </el-tag>
                </el-descriptions-item>
                <el-descriptions-item label="最近失败原因">{{ health.lastFailure || '无' }}</el-descriptions-item>
              </el-descriptions>
              <el-empty v-else description="暂无数据" />
            </el-card>
          </el-col>
          <el-col :span="12">
            <el-card shadow="never">
              <template #header>
                <div class="flex-between">
                  <span>知识库巡检</span>
                  <el-button size="small" @click="loadStatus">刷新</el-button>
                </div>
              </template>
              <el-descriptions v-if="status" :column="1" border size="small">
                <el-descriptions-item label="写入账号已配置">
                  <el-tag :type="status.configured ? 'success' : 'warning'">
                    {{ status.configured ? '是' : '否（资料仅进本地索引）' }}
                  </el-tag>
                </el-descriptions-item>
                <el-descriptions-item label="知识库侧库数">{{ (status.mimirqDatasets || []).length }}</el-descriptions-item>
                <el-descriptions-item label="本地应有课程数">{{ (status.expectedLocal || []).length }}</el-descriptions-item>
                <el-descriptions-item label="无课程归属的资料">{{ status.materialsWithoutCourse ?? 0 }}</el-descriptions-item>
              </el-descriptions>
              <el-empty v-else description="暂无数据" />
            </el-card>
          </el-col>
        </el-row>

        <el-card shadow="never" style="margin-top: 12px">
          <template #header><span>运维操作</span></template>
          <el-space wrap>
            <el-button type="primary" :loading="busy === 'resyncAll'" @click="doResyncAll">
              全量重推知识库
            </el-button>
            <el-button :loading="busy === 'reindex'" @click="doReindex">重建本地切块</el-button>
            <el-button :loading="busy === 'importGolden'" @click="doImportGolden">导入内置 Golden 题集</el-button>
          </el-space>
          <el-alert
            type="info"
            :closable="false"
            show-icon
            style="margin-top: 12px"
            title="两件容易混淆的事"
            description="「全量重推知识库」= 把资料重新灌进 MimirQ（换 embedding 模型后必须做；代价是每份文档先删后建，期间该课程的知识库会暂时变少）。「重建本地切块」= 只重建 rag_chunk，不碰 MimirQ。日常只改了一份资料时，请在「资料同步状态」里对它单条重推，不要用全量。"
          />
          <el-descriptions v-if="lastResult" :column="1" border size="small" style="margin-top: 12px">
            <el-descriptions-item label="上次操作结果">
              <pre class="json-block">{{ lastResult }}</pre>
            </el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-tab-pane>

      <!-- ======================== 资料同步状态 ======================== -->
      <el-tab-pane label="资料同步状态" name="materials">
        <div class="search-bar flex-between">
          <div>
            <el-input
              v-model="materialKeyword"
              placeholder="按资料标题筛选"
              clearable
              style="width: 240px; margin-right: 8px"
              @keyup.enter="loadMaterials"
            />
            <el-input-number v-model="materialCourseId" :min="1" placeholder="课程ID" style="margin-right: 8px" />
            <el-button type="primary" @click="loadMaterials">查询</el-button>
          </div>
          <el-tag v-if="materials" type="info">共 {{ materials.total }} 条</el-tag>
        </div>
        <el-table :data="materials ? materials.items : []" v-loading="loadingMaterials" max-height="560">
          <el-table-column prop="materialId" label="资料ID" width="80" />
          <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip />
          <el-table-column prop="courseName" label="课程" width="150" show-overflow-tooltip />
          <el-table-column label="正文字数" width="90">
            <template #default="{ row }">{{ row.contentChars }}</template>
          </el-table-column>
          <el-table-column label="本地切块" width="90">
            <template #default="{ row }">
              <el-tag :type="row.localChunks > 0 ? 'success' : 'danger'" size="small">{{ row.localChunks }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="已进知识库" width="110">
            <template #default="{ row }">
              <el-tag :type="row.inKnowledgeBase ? 'success' : 'warning'" size="small">
                {{ row.inKnowledgeBase ? '已同步' : '未同步' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="需处理" width="90">
            <template #default="{ row }">
              <el-tag v-if="row.needsAttention" type="danger" size="small">是</el-tag>
              <span v-else>-</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="190" fixed="right">
            <template #default="{ row }">
              <el-button size="small" :loading="busy === 'm' + row.materialId" @click="doResyncMaterial(row)">
                重推
              </el-button>
              <el-button size="small" @click="doPreviewChunks(row)">切块</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-alert
          v-if="materials && materials.hint"
          type="info"
          :closable="false"
          show-icon
          style="margin-top: 12px"
          :title="materials.hint"
        />
      </el-tab-pane>

      <!-- ========================== 漂移明细 ========================== -->
      <el-tab-pane label="漂移明细" name="drift">
        <div class="search-bar flex-between">
          <span>
            逐课程逐条核对「本地应有」与「知识库实有」。
            数量相等但内容不同（改名 / 改归属留下的幽灵文档）只有这里能发现。
          </span>
          <el-button type="primary" :loading="loadingDrift" @click="loadDrift">开始巡检</el-button>
        </div>
        <el-row v-if="drift" :gutter="12" style="margin-bottom: 12px">
          <el-col :span="6"><el-card shadow="never"><el-statistic title="缺失文档" :value="drift.totalMissing || 0" /></el-card></el-col>
          <el-col :span="6"><el-card shadow="never"><el-statistic title="多余文档" :value="drift.totalOrphanDocuments || 0" /></el-card></el-col>
          <el-col :span="6"><el-card shadow="never"><el-statistic title="不一致课程" :value="(drift.inconsistentCourses || []).length" /></el-card></el-col>
          <el-col :span="6"><el-card shadow="never"><el-statistic title="知识库空壳" :value="(drift.kbsWithoutLocalMaterials || []).length" /></el-card></el-col>
        </el-row>
        <el-alert
          v-if="drift && drift.hint"
          :type="(drift.totalMissing || 0) + (drift.totalOrphanDocuments || 0) === 0 ? 'success' : 'warning'"
          :closable="false"
          show-icon
          style="margin-bottom: 12px"
          :title="drift.hint"
        />
        <el-card v-for="course in drift ? drift.inconsistentCourses : []" :key="course.courseId" shadow="never" style="margin-bottom: 12px">
          <template #header>
            <div class="flex-between">
              <span>{{ course.knowledgeId }}（课程 {{ course.courseId }}） · 本地 {{ course.localMaterials }} 份 / 知识库 {{ course.remoteDocuments }} 份</span>
              <el-space>
                <el-tag v-if="course.missingCount" type="danger" size="small">缺 {{ course.missingCount }}</el-tag>
                <el-tag v-if="course.orphanCount" type="warning" size="small">多 {{ course.orphanCount }}</el-tag>
                <el-tag v-if="course.untaggedCount" type="info" size="small">未标记 {{ course.untaggedCount }}</el-tag>
              </el-space>
            </div>
          </template>
          <div v-if="course.advice" class="advice-line">{{ course.advice }}</div>
          <el-table v-if="course.missing && course.missing.length" :data="course.missing" size="small" style="margin-bottom: 8px">
            <el-table-column label="缺失（本地有、知识库无）" min-width="240">
              <template #default="{ row }">#{{ row.materialId }} {{ row.title }}</template>
            </el-table-column>
            <el-table-column label="操作" width="120">
              <template #default="{ row }">
                <el-button size="small" type="primary" @click="doResyncMaterial({ materialId: row.materialId, title: row.title })">补齐</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-table v-if="course.orphan && course.orphan.length" :data="course.orphan" size="small">
            <el-table-column label="多余（知识库有、本地已无）" min-width="240">
              <template #default="{ row }">{{ row.filename }}（资料 {{ row.materialId }}）</template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>

      <!-- ========================== 检索调试 ========================== -->
      <el-tab-pane label="检索调试" name="search">
        <el-card shadow="never">
          <el-space wrap style="margin-bottom: 12px">
            <el-input v-model="searchForm.query" placeholder="输入一个真实问题" style="width: 360px" @keyup.enter="doSearch" />
            <el-input-number v-model="searchForm.courseId" :min="1" placeholder="课程ID（可选）" />
            <el-input-number v-model="searchForm.topK" :min="1" :max="20" />
            <el-button type="primary" :loading="loadingSearch" @click="doSearch">检索</el-button>
          </el-space>
          <div v-if="searchResult">
            <el-space style="margin-bottom: 8px">
              <el-tag :type="searchResult.degraded ? 'warning' : 'success'">
                {{ searchResult.degraded ? '已降级' : '未降级' }}
              </el-tag>
              <el-tag type="info">通道：{{ searchResult.backend }}</el-tag>
              <span v-if="searchResult.note" class="muted">{{ searchResult.note }}</span>
            </el-space>
            <el-table :data="searchResult.hits || []" size="small">
              <el-table-column prop="citation" label="引用" width="90" />
              <el-table-column prop="title" label="资料" width="200" show-overflow-tooltip />
              <el-table-column label="相关度" width="100">
                <template #default="{ row }">{{ Number(row.score ?? 0).toFixed(4) }}</template>
              </el-table-column>
              <el-table-column label="命中通道" width="180">
                <template #default="{ row }">
                  <el-tag v-for="c in row.channels || []" :key="c" size="small" style="margin-right: 4px">{{ c }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="snippet" label="片段" min-width="300" show-overflow-tooltip />
            </el-table>
          </div>
        </el-card>
      </el-tab-pane>

      <!-- ========================== 检索轨迹 ========================== -->
      <el-tab-pane label="检索轨迹" name="traces">
        <div class="search-bar flex-between">
          <span>复盘「为什么没召回」：一次检索查了什么、走的哪个通道、留下哪几条。</span>
          <el-button type="primary" :loading="loadingTraces" @click="loadTraces">刷新</el-button>
        </div>
        <el-table :data="traces" v-loading="loadingTraces" max-height="560">
          <el-table-column prop="id" label="ID" width="70" />
          <el-table-column prop="queryText" label="查询" min-width="200" show-overflow-tooltip />
          <el-table-column prop="strategy" label="实际通道" width="200" />
          <el-table-column prop="candidateCount" label="候选" width="70" />
          <el-table-column prop="hitCount" label="命中" width="70" />
          <el-table-column label="耗时" width="90">
            <template #default="{ row }">{{ row.latencyMs }}ms</template>
          </el-table-column>
          <el-table-column label="时间" width="170">
            <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- ========================== 效果回归 ========================== -->
      <el-tab-pane label="效果回归" name="eval">
        <div class="search-bar flex-between">
          <el-space>
            <el-input-number v-model="evalTopK" :min="1" :max="20" />
            <el-button type="primary" :loading="busy === 'eval'" @click="doEval">运行 Golden 评测</el-button>
          </el-space>
          <el-tag type="info">固定题集 + 历史指标，用于对比改切块/换模型前后的效果</el-tag>
        </div>
        <el-table :data="evalRuns" v-loading="loadingEvalRuns" style="margin-bottom: 12px">
          <el-table-column prop="id" label="ID" width="70" />
          <el-table-column prop="topK" label="topK" width="70" />
          <el-table-column prop="hitRate" label="hitRate" width="100" />
          <el-table-column prop="recall" label="recall" width="100" />
          <el-table-column prop="mrr" label="MRR" width="100" />
          <el-table-column prop="missCount" label="未命中" width="90" />
          <el-table-column prop="avgLatencyMs" label="平均耗时" width="110" />
          <el-table-column label="时间" width="170">
            <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
          </el-table-column>
        </el-table>
        <el-card v-if="evalResult" shadow="never">
          <template #header><span>本次评测结果</span></template>
          <pre class="json-block">{{ evalResult }}</pre>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="chunkDialogVisible" title="切块预览" width="70%">
      <template v-if="chunkPreview">
        <p class="muted">{{ chunkPreview.title }} · 共 {{ chunkPreview.chunkCount }} 块</p>
        <el-input v-for="(c, i) in chunkPreview.chunks" :key="i" type="textarea" :rows="3" readonly :model-value="`[${i}] ${c}`" style="margin-bottom: 8px" />
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { formatDateTime } from '@/utils/datetime'
import {
  getRagHealth, getKnowledgeBaseStatus, getKnowledgeBaseDrift,
  listKnowledgeBaseMaterials, resyncMaterial, resyncKnowledgeBase,
  reindexLocalChunks, previewChunks, ragSearch, listRagTraces,
  importGoldenFromClasspath, runGoldenEval, listEvalRuns
} from '@/api/rag'

const activeTab = ref('overview')
const busy = ref('')
const lastResult = ref('')

const health = ref(null)
const status = ref(null)
const drift = ref(null)
const materials = ref(null)
const traces = ref([])
const evalRuns = ref([])
const evalResult = ref(null)

const loadingMaterials = ref(false)
const loadingDrift = ref(false)
const loadingTraces = ref(false)
const loadingEvalRuns = ref(false)
const loadingSearch = ref(false)

const materialKeyword = ref('')
const materialCourseId = ref(null)
const evalTopK = ref(5)
const searchForm = reactive({ query: '', courseId: null, topK: 5 })
const searchResult = ref(null)

const chunkDialogVisible = ref(false)
const chunkPreview = ref(null)

function showResult(payload) {
  lastResult.value = JSON.stringify(payload, null, 2)
}

async function loadHealth() {
  try {
    health.value = await getRagHealth()
  } catch (e) {
    // 拦截器已提示，这里保持页面可用
  }
}

async function loadStatus() {
  try {
    status.value = await getKnowledgeBaseStatus()
  } catch (e) { /* 已提示 */ }
}

async function loadMaterials() {
  loadingMaterials.value = true
  try {
    materials.value = await listKnowledgeBaseMaterials({
      keyword: materialKeyword.value || undefined,
      courseId: materialCourseId.value || undefined,
      limit: 200
    })
  } finally {
    loadingMaterials.value = false
  }
}

async function loadDrift() {
  loadingDrift.value = true
  try {
    drift.value = await getKnowledgeBaseDrift()
  } finally {
    loadingDrift.value = false
  }
}

async function loadTraces() {
  loadingTraces.value = true
  try {
    traces.value = await listRagTraces(50)
  } finally {
    loadingTraces.value = false
  }
}

async function loadEvalRuns() {
  loadingEvalRuns.value = true
  try {
    evalRuns.value = await listEvalRuns(20)
  } finally {
    loadingEvalRuns.value = false
  }
}

async function doResyncAll() {
  try {
    await ElMessageBox.confirm(
      '全量重推会把每份资料在知识库中先删除再重建（重新解析/切块/向量化）。资料较多时耗时明显，且期间这些课程的知识库会暂时变少。确认继续？',
      '确认全量重推',
      { type: 'warning' }
    )
  } catch (e) {
    return
  }
  busy.value = 'resyncAll'
  try {
    const result = await resyncKnowledgeBase()
    showResult(result)
    ElMessage.success('知识库重推完成')
    loadStatus()
  } finally {
    busy.value = ''
  }
}

async function doReindex() {
  busy.value = 'reindex'
  try {
    const result = await reindexLocalChunks()
    showResult(result)
    ElMessage.success('本地切块重建完成')
  } finally {
    busy.value = ''
  }
}

async function doResyncMaterial(row) {
  busy.value = 'm' + row.materialId
  try {
    const result = await resyncMaterial(row.materialId)
    ElMessage.success(`资料 #${row.materialId} 已重新灌入`)
    showResult(result)
    // 刷新当前视图，让"未同步"标记即时消失——否则用户会怀疑操作没生效
    if (activeTab.value === 'materials') loadMaterials()
    if (activeTab.value === 'drift') loadDrift()
  } finally {
    busy.value = ''
  }
}

async function doPreviewChunks(row) {
  try {
    chunkPreview.value = await previewChunks(row.materialId)
    chunkDialogVisible.value = true
  } catch (e) { /* 已提示 */ }
}

async function doSearch() {
  if (!searchForm.query) {
    ElMessage.warning('请先输入查询内容')
    return
  }
  loadingSearch.value = true
  try {
    searchResult.value = await ragSearch({
      query: searchForm.query,
      courseId: searchForm.courseId || undefined,
      topK: searchForm.topK
    })
  } finally {
    loadingSearch.value = false
  }
}

async function doImportGolden() {
  busy.value = 'importGolden'
  try {
    const result = await importGoldenFromClasspath()
    showResult(result)
    ElMessage.success('Golden 题集导入完成')
  } finally {
    busy.value = ''
  }
}

async function doEval() {
  busy.value = 'eval'
  try {
    evalResult.value = JSON.stringify(await runGoldenEval(evalTopK.value), null, 2)
    loadEvalRuns()
  } finally {
    busy.value = ''
  }
}

async function onTabChange(name) {
  if (name === 'materials' && !materials.value) loadMaterials()
  if (name === 'drift' && !drift.value) loadDrift()
  if (name === 'traces' && traces.value.length === 0) loadTraces()
  if (name === 'eval' && evalRuns.value.length === 0) loadEvalRuns()
}

onMounted(() => {
  loadHealth()
  loadStatus()
})
</script>

<style scoped>
.json-block {
  margin: 0;
  max-height: 320px;
  overflow: auto;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
}
.advice-line {
  margin-bottom: 8px;
  color: #b88230;
  font-size: 13px;
}
.muted {
  color: #909399;
  font-size: 13px;
}
</style>
