import request from '@/api/request'

/**
 * RAG / 知识库运维接口（内训师 + 管理员）。
 *
 * 这些接口与员工端的「知识搜索」是两件事：
 *   · 员工端 /knowledge/search —— 面向使用者的检索
 *   · 这里 /rag/**            —— 面向维护者：知识库里到底有什么、有没有漏灌、检索为什么没召回
 * 分开的原因是两者的使用者、频率、以及"看到的东西"完全不同：
 * 员工不该看到切块与轨迹，维护者则必须看到。
 */

/** 检索链路健康状态：是否启用 MimirQ、熔断是否打开、最近一次失败原因。排查"为什么降级"先看这里。 */
export function getRagHealth() {
  return request.get('/rag/health')
}

/** 知识库巡检（总数视角）：MimirQ 侧有哪些库、各多少文档，与本地期望对比。 */
export function getKnowledgeBaseStatus() {
  return request.get('/rag/knowledge-base/status')
}

/** 漂移巡检（明细视角）：逐课程逐条核对，列出"缺了哪一份、多了哪一份"。 */
export function getKnowledgeBaseDrift() {
  return request.get('/rag/knowledge-base/drift')
}

/** 资料级同步状态清单：知识库管理页的主表格数据。 */
export function listKnowledgeBaseMaterials(params) {
  return request.get('/rag/knowledge-base/materials', { params })
}

/** 某课程在知识库里的实际文档清单。 */
export function listKnowledgeBaseDocuments(courseId) {
  return request.get('/rag/knowledge-base/documents', { params: { courseId } })
}

/** 单条资料重推（比全量便宜且安全）。 */
export function resyncMaterial(materialId) {
  return request.post(`/rag/knowledge-base/resync/${materialId}`)
}

/** 全量重推知识库。换 embedding 模型后必须执行；代价是每份文档先删后建。 */
export function resyncKnowledgeBase() {
  // 全量重推是重活（每份文档先删后建 + 重新向量化），默认 30s 超时不够
  return request.post('/rag/knowledge-base/resync', null, { timeout: 600000 })
}

/** 全量重建本地切块。 */
export function reindexLocalChunks() {
  return request.post('/rag/reindex', null, { timeout: 300000 })
}

/** 预览某资料的切块效果（不落库），用于调切块参数。 */
export function previewChunks(materialId) {
  return request.get('/rag/chunks/preview', { params: { materialId } })
}

/** 查看某资料已入库的切块。 */
export function listChunks(materialId) {
  return request.get('/rag/chunks', { params: { materialId } })
}

/** 检索调试：返回实际服务方、是否降级、降级原因与命中明细。 */
export function ragSearch({ query, courseId, topK }) {
  return request.post('/rag/search', { query, courseId, topK })
}

/** 最近检索轨迹：复盘"为什么没召回"。 */
export function listRagTraces(limit = 20) {
  return request.get('/rag/traces', { params: { limit } })
}

/** Golden 题集。 */
export function listGoldenItems() {
  return request.get('/rag/golden')
}

export function addGoldenItem(item) {
  return request.post('/rag/golden', item)
}

export function importGoldenFromClasspath() {
  return request.post('/rag/golden/import')
}

/** 运行 Golden 评测（可回归：固定题集 + 历史指标对比）。 */
export function runGoldenEval(topK = 5) {
  return request.post('/rag/eval', null, { params: { topK }, timeout: 300000 })
}

/** 历史评测结果，用于版本对比。 */
export function listEvalRuns(limit = 20) {
  return request.get('/rag/eval-runs', { params: { limit } })
}
