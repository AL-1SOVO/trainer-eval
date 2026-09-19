import request from '@/api/request'

/**
 * MCP（Model Context Protocol）管理接口（仅管理员）。
 *
 * 注意这里**没有**新增/删除 server 的接口，这是有意的：
 * stdio 传输的本质是"在本机执行一个命令"，开放 API 动态添加 server
 * 等于给系统装了一个远程命令执行入口。server 列表只能来自 application.yml。
 */

/** 概览：是否启用、配了几个 server、连上了几个、发现了多少工具。 */
export function getMcpOverview() {
  return request.get('/mcp/overview')
}

/** 各 server 的连接状态、角色白名单与最近失败原因。 */
export function listMcpServers() {
  return request.get('/mcp/servers')
}

/** 已发现的工具清单 —— 即"模型实际能看到什么"。 */
export function listMcpTools() {
  return request.get('/mcp/tools')
}

/** 强制重连全部 server（改完配置或修好外部服务后用它，不必重启应用）。 */
export function reloadMcp() {
  return request.post('/mcp/reload', null, { timeout: 120000 })
}

/** 试调某个 MCP 工具，直接看原始返回。 */
export function testMcpCall({ server, tool, arguments: args }) {
  return request.post('/mcp/test-call', { server, tool, arguments: args }, { timeout: 120000 })
}
