<template>
  <div class="page-container">
    <el-alert
      type="warning"
      :closable="false"
      show-icon
      style="margin-bottom: 12px"
      title="MCP server 只能通过配置文件声明"
      description="stdio 传输的本质是在本机执行一个命令。如果开放 API 动态添加 server，等于给系统装了一个远程命令执行入口。所以这里只有「查看状态 / 查看工具 / 重连 / 试调」四个动作，没有增删。"
    />

    <el-card shadow="never" style="margin-bottom: 12px">
      <template #header>
        <div class="flex-between">
          <span>接入概览</span>
          <el-button size="small" type="primary" :loading="reloading" @click="doReload">重连全部 server</el-button>
        </div>
      </template>
      <el-row v-if="overview" :gutter="12">
        <el-col :span="6"><el-statistic title="MCP 已启用" :value="overview.enabled ? 1 : 0" :formatter="() => (overview.enabled ? '是' : '否')" /></el-col>
        <el-col :span="6"><el-statistic title="已配置 server" :value="overview.configuredServers" /></el-col>
        <el-col :span="6"><el-statistic title="已连接 server" :value="overview.connectedServers" /></el-col>
        <el-col :span="6"><el-statistic title="已发现工具" :value="overview.discoveredTools" /></el-col>
      </el-row>
      <el-alert
        v-if="overview && overview.hint"
        type="info"
        :closable="false"
        show-icon
        style="margin-top: 12px"
        :title="overview.hint"
      />
    </el-card>

    <el-tabs v-model="activeTab">
      <el-tab-pane label="Server 状态" name="servers">
        <el-table :data="servers" v-loading="loadingServers">
          <el-table-column prop="name" label="名称" width="120" />
          <el-table-column prop="transport" label="传输" width="90" />
          <el-table-column label="连接状态" width="120">
            <template #default="{ row }">
              <!--
                顺序很重要：healthy === null 表示"还没尝试过连接"（MCP 是懒连接）。
                若先判 connected === false，刚打开页面时每个 server 都会显示"异常" ——
                把"没试过"误报成"坏了"，运维会白查一轮。
              -->
              <el-tag v-if="row.healthy === false" type="danger" size="small">异常</el-tag>
              <el-tag v-else-if="row.healthy === true" type="success" size="small">正常</el-tag>
              <el-tag v-else type="info" size="small">未连接</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="toolCount" label="工具数" width="90" />
          <el-table-column label="角色白名单" width="180">
            <template #default="{ row }">
              <template v-if="row.allowedRoles && row.allowedRoles.length">
                <el-tag v-for="r in row.allowedRoles" :key="r" size="small" style="margin-right: 4px">{{ r }}</el-tag>
              </template>
              <el-tag v-else type="info" size="small">仅管理员（默认拒绝）</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="端点 / 命令" min-width="240" show-overflow-tooltip>
            <template #default="{ row }">
              {{ row.url || [row.command, ...(row.args || [])].join(' ') }}
            </template>
          </el-table-column>
          <el-table-column prop="lastError" label="最近错误" min-width="220" show-overflow-tooltip />
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="工具清单（模型实际可见）" name="tools">
        <el-table :data="tools" v-loading="loadingTools">
          <el-table-column prop="server" label="来源 server" width="130" />
          <el-table-column prop="exposedName" label="暴露给模型的名字" width="230" />
          <el-table-column prop="name" label="服务端原名" width="170" />
          <el-table-column prop="description" label="说明" min-width="280" show-overflow-tooltip />
          <el-table-column label="参数 schema" width="110">
            <template #default="{ row }">
              <el-button size="small" @click="showSchema(row)">查看</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="试调" name="test">
        <el-card shadow="never">
          <el-space wrap style="margin-bottom: 12px">
            <el-select v-model="testForm.server" placeholder="server" style="width: 160px">
              <el-option v-for="s in servers" :key="s.name" :label="s.name" :value="s.name" />
            </el-select>
            <el-select v-model="testForm.tool" placeholder="工具（服务端原名）" style="width: 220px" filterable allow-create>
              <el-option v-for="t in toolsForServer" :key="t.name" :label="t.name" :value="t.name" />
            </el-select>
            <el-button type="primary" :loading="testing" @click="doTestCall">调用</el-button>
          </el-space>
          <el-input
            v-model="testForm.arguments"
            type="textarea"
            :rows="4"
            placeholder='参数 JSON，例如 {"keyword": "补考"}'
          />
          <el-card v-if="testResult" shadow="never" style="margin-top: 12px">
            <template #header><span>调用结果</span></template>
            <pre class="json-block">{{ testResult }}</pre>
          </el-card>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="schemaVisible" title="工具参数 schema" width="60%">
      <pre class="json-block">{{ schemaText }}</pre>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getMcpOverview, listMcpServers, listMcpTools, reloadMcp, testMcpCall } from '@/api/mcp'

const activeTab = ref('servers')
const overview = ref(null)
const servers = ref([])
const tools = ref([])
const loadingServers = ref(false)
const loadingTools = ref(false)
const reloading = ref(false)
const testing = ref(false)
const testResult = ref('')
const schemaVisible = ref(false)
const schemaText = ref('')

const testForm = reactive({ server: '', tool: '', arguments: '{}' })

const toolsForServer = computed(() => tools.value.filter(t => !testForm.server || t.server === testForm.server))

async function loadOverview() {
  try {
    overview.value = await getMcpOverview()
  } catch (e) { /* 已提示 */ }
}

async function loadServers() {
  loadingServers.value = true
  try {
    servers.value = await listMcpServers()
  } finally {
    loadingServers.value = false
  }
}

async function loadTools() {
  loadingTools.value = true
  try {
    tools.value = await listMcpTools()
  } finally {
    loadingTools.value = false
  }
}

async function doReload() {
  reloading.value = true
  try {
    const result = await reloadMcp()
    ElMessage.success('MCP 重连完成')
    const outcomes = result.servers || []
    const failed = outcomes.filter(o => !o.connected)
    if (failed.length) {
      ElMessage.warning(`${failed.length} 个 server 仍然连接失败，详见状态表`)
    }
    await Promise.all([loadOverview(), loadServers(), loadTools()])
  } finally {
    reloading.value = false
  }
}

function showSchema(row) {
  try {
    schemaText.value = JSON.stringify(JSON.parse(row.inputSchema), null, 2)
  } catch (e) {
    schemaText.value = row.inputSchema
  }
  schemaVisible.value = true
}

async function doTestCall() {
  if (!testForm.server || !testForm.tool) {
    ElMessage.warning('请先选择 server 与工具')
    return
  }
  let args = {}
  try {
    args = testForm.arguments ? JSON.parse(testForm.arguments) : {}
  } catch (e) {
    ElMessage.error('参数不是合法 JSON')
    return
  }
  testing.value = true
  try {
    const result = await testMcpCall({ server: testForm.server, tool: testForm.tool, arguments: args })
    testResult.value = typeof result.result === 'string' ? result.result : JSON.stringify(result, null, 2)
  } finally {
    testing.value = false
  }
}

onMounted(async () => {
  await Promise.all([loadOverview(), loadServers(), loadTools()])
  if (servers.value.length && !testForm.server) {
    testForm.server = servers.value[0].name
  }
})
</script>

<style scoped>
.json-block {
  margin: 0;
  max-height: 420px;
  overflow: auto;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
