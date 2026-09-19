import { getToken } from '@/utils/auth'

/**
 * 学习辅导 Agent 的 SSE 流式请求。
 *
 * mode='single'（默认，单智能体）会依次收到：
 *   plan {steps, finalized?} / step_status {index, title, status}
 *   tool_call {iteration, name, arguments}
 *   tool_result {name, preview}
 *   reflection {tool, reason, retrying, attempt}
 *   citation {sources}
 *   delta {text} / done {sessionId, length} / error {message}
 *
 * mode='multi'（多智能体：资料员 → 复核员 → 汇报员）额外收到：
 *   multi_start {roles:[{key,label,duty}]}
 *   role_start {role, label}
 *   role_done  {role, label, latencyMs, note?}
 *   文本仍走 role_delta {role, text}（按角色区分，便于前端分区渲染）
 *   tool_call / tool_result 带 role 字段
 */
export function streamAgentChat({ sessionId, message, mode = 'single', onEvent, onError, signal }) {
  return fetch('/api/v1/agent/chat/stream', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${getToken()}`,
      Accept: 'text/event-stream'
    },
    body: JSON.stringify({ sessionId, message, mode }),
    signal
  }).then(async response => {
    if (!response.ok || !response.body) {
      throw new Error(`请求失败 (${response.status})`)
    }
    const reader = response.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''

    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })

      // SSE 以空行分隔事件块
      let sep
      while ((sep = buffer.indexOf('\n\n')) >= 0) {
        const block = buffer.slice(0, sep)
        buffer = buffer.slice(sep + 2)
        let event = 'message'
        const dataLines = []
        for (const line of block.split('\n')) {
          if (line.startsWith('event:')) event = line.slice(6).trim()
          else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim())
        }
        if (!dataLines.length) continue
        let payload = {}
        try { payload = JSON.parse(dataLines.join('\n')) } catch (e) { payload = { text: dataLines.join('\n') } }
        if (onEvent) onEvent(event, payload)
      }
    }
  }).catch(error => {
    if (error.name === 'AbortError') return
    if (onError) onError(error)
    throw error
  })
}
