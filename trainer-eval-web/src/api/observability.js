import request from '@/api/request'

/**
 * AI 链路追踪接口（仅管理员）。
 *
 * 链路里含用户提问原文，属于运营数据，所以权限收紧到 MANAGER。
 */

/** 链路状态：本地表是否可用、Langfuse 是否配置、导出计数与最近一次错误。 */
export function getObservabilityStatus() {
  return request.get('/observability/status')
}

/** 最近链路列表。name 可选，如 agent.chat / agent.multi / rag.retrieve。 */
export function listTraces({ name, limit = 30 } = {}) {
  return request.get('/observability/traces', { params: { name, limit } })
}

/** 单条链路明细（含每一步的类型/耗时/token/输入输出）。 */
export function getTraceDetail(id) {
  return request.get(`/observability/traces/${id}`)
}

/**
 * Langfuse 连通性自检：同步发一条合成链路并把真实错误返回。
 * 异步导出是黑盒，"配了但没数据"只能靠这个接口定位。
 */
export function testLangfuse() {
  return request.post('/observability/test')
}
