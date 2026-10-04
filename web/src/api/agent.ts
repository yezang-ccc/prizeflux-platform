import { ApiError, notifyUnauthorizedResponse } from '@/api/client'

const API_ROOT = '/agent-api'
const AGENT_ROOT = '/api/ai/v2'

export type AgentRunStatus =
  | 'ACCEPTED'
  | 'RUNNING'
  | 'COMPLETED'
  | 'FAILED'
  | 'CANCELLED'
  | 'INTERRUPTED'

export interface AgentSession {
  sessionId: string
  version: number
  activeRunId: string | null
  createdAt: string
  updatedAt: string
}

export interface AgentTurn { sequence: number; input: string; answer: string }
export interface AgentSessionSnapshot { session: AgentSession; history: AgentTurn[] }
export interface AgentAcceptedRun { runId: string; status: AgentRunStatus; replayed: boolean }
export interface AgentResumeRun { runId: string; status: AgentRunStatus; resumed: boolean }
export interface AgentKnowledgeCitation {
  citationId: string
  indexVersion: string
  documentId: string
  documentHash: string
  title: string
  section: string
  scope: 'PUBLIC' | 'OPERATOR'
}
export interface AgentCreatorFinding { code: string; field: string | null; message: string }
export interface AgentCreatorSuggestion {
  field: string | null
  kind: 'CLARIFICATION' | 'EXPLANATION' | 'OPTIONAL_IMPROVEMENT'
  text: string
  rationale: string
  evidenceRefs: string[]
}
export interface AgentCreatorReport {
  schemaVersion: 1
  mode: 'CREATOR_DRAFT_REVIEW'
  draftFingerprint: string
  checkedAt: string
  summary: string
  authoritativeFindings: AgentCreatorFinding[]
  suggestions: AgentCreatorSuggestion[]
  openQuestions: string[]
}
export interface AgentCreatorDraft {
  name: string
  startsAt: string
  endsAt: string
  dailyLimit: number
  prizes: { id: number; name: string; totalStock: number; probability: number }[]
}
export interface AgentRunRequest {
  clientMessageId: string
  expectedVersion: number
  input: string
  mode?: 'CHAT' | 'CREATOR_DRAFT_REVIEW'
  context?: { schemaVersion: 1; draft: AgentCreatorDraft }
}
export interface AgentRun {
  runId: string
  sessionId: string
  input: string
  status: AgentRunStatus
  answer: string | null
  safeErrorCode: string | null
  cancelRequested: boolean
  createdAt: string
  startedAt: string | null
  finishedAt: string | null
  knowledgeCitations: AgentKnowledgeCitation[]
  creatorReport: AgentCreatorReport | null
}
export interface AgentReadiness {
  status: 'READY'
  model_provider: 'fake' | 'openai-compatible'
  knowledge_retrieval_enabled: boolean
}
export interface AgentEvent { sequence: number; eventType: string; payload: Record<string, unknown> }

function friendlyError(status: number, detail: unknown): string {
  const code = typeof detail === 'string' ? detail : ''
  if (status === 401) return '登录状态已失效，请重新登录。'
  if (status === 403) return '当前账号没有执行此操作的权限。'
  if (status === 404) return '会话或本轮运行不存在，可能已过期。'
  if (status === 409 && code === 'run_not_accepted') return '本轮已不在待执行状态，系统没有重复启动。'
  if (status === 409 && code === 'session_version_conflict') return '会话刚刚发生变化，请刷新后重新检查。'
  if (status === 409) return '当前会话有另一轮任务正在处理，请稍后刷新。'
  if (status === 422) return '输入未通过校验，请检查问题内容后再试。'
  if (status === 503) return 'Agent 服务或数据库暂时不可用，请确认服务已启动后重试。'
  if (status >= 500) return '助手暂时不可用，请稍后重试。'
  return '请求未完成，请检查输入后重试。'
}

async function request<T>(path: string, token?: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  headers.set('Accept', 'application/json')
  if (init.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')
  if (token) headers.set('Authorization', `Bearer ${token}`)

  let response: Response
  try {
    response = await fetch(`${API_ROOT}${path}`, { ...init, headers })
  } catch {
    throw new ApiError(0, '无法连接 Python Agent 服务，请确认本地服务已启动。')
  }
  if (response.status === 401) notifyUnauthorizedResponse()
  if (response.status === 204) return undefined as T

  const payload = await response.json().catch(() => null) as { detail?: unknown } | null
  if (!response.ok) {
    throw new ApiError(response.status, friendlyError(response.status, payload?.detail),
      typeof payload?.detail === 'string' ? payload.detail : undefined)
  }
  return payload as T
}

export function getAgentReadiness(): Promise<AgentReadiness> {
  return request<AgentReadiness>('/health/ready')
}

export function createAgentSession(token: string): Promise<AgentSession> {
  return request<AgentSession>(`${AGENT_ROOT}/sessions`, token, { method: 'POST' })
}

export function getAgentSession(token: string, sessionId: string): Promise<AgentSessionSnapshot> {
  return request<AgentSessionSnapshot>(
    `${AGENT_ROOT}/sessions/${encodeURIComponent(sessionId)}`, token,
  )
}

export function createAgentRun(
  token: string,
  sessionId: string,
  body: AgentRunRequest,
): Promise<AgentAcceptedRun> {
  return request<AgentAcceptedRun>(
    `${AGENT_ROOT}/sessions/${encodeURIComponent(sessionId)}/runs`, token,
    { method: 'POST', body: JSON.stringify(body) },
  )
}

export function getAgentRun(token: string, runId: string): Promise<AgentRun> {
  return request<AgentRun>(`${AGENT_ROOT}/runs/${encodeURIComponent(runId)}`, token)
}

export function resumeAgentRun(token: string, runId: string): Promise<AgentResumeRun> {
  return request<AgentResumeRun>(
    `${AGENT_ROOT}/runs/${encodeURIComponent(runId)}/resume`, token, { method: 'POST' },
  )
}

export function cancelAgentRun(token: string, runId: string): Promise<AgentRun> {
  return request<AgentRun>(
    `${AGENT_ROOT}/runs/${encodeURIComponent(runId)}/cancel`, token, { method: 'POST' },
  )
}

export async function deleteAgentSession(token: string, sessionId: string): Promise<boolean> {
  const response = await request<{ status?: string }>(
    `${AGENT_ROOT}/sessions/${encodeURIComponent(sessionId)}`, token, { method: 'DELETE' },
  )
  return response?.status === 'DELETION_PENDING'
}

export async function streamAgentRun(
  token: string,
  runId: string,
  signal: AbortSignal,
  onEvent: (event: AgentEvent) => void,
): Promise<void> {
  let response: Response
  try {
    response = await fetch(
      `${API_ROOT}${AGENT_ROOT}/runs/${encodeURIComponent(runId)}/events`,
      { headers: { Accept: 'text/event-stream', Authorization: `Bearer ${token}` }, signal },
    )
  } catch (cause) {
    if (signal.aborted) return
    throw new ApiError(0, cause instanceof Error ? '事件连接中断，可手动刷新运行状态。' : '事件连接失败。')
  }
  if (response.status === 401) notifyUnauthorizedResponse()
  if (!response.ok || !response.body) {
    const payload = await response.json().catch(() => null) as { detail?: unknown } | null
    throw new ApiError(response.status, friendlyError(response.status, payload?.detail))
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  try {
    while (true) {
      const { value, done } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      let separator = /\r?\n\r?\n/.exec(buffer)
      while (separator?.index !== undefined) {
        const boundary = separator.index
        const block = buffer.slice(0, boundary).replaceAll('\r\n', '\n')
        buffer = buffer.slice(boundary + separator[0].length)
        const data = block.split('\n').filter((line) => line.startsWith('data:'))
          .map((line) => line.slice(5).trimStart()).join('\n')
        if (data) {
          try { onEvent(JSON.parse(data) as AgentEvent) } catch { /* Ignore malformed event payloads. */ }
        }
        separator = /\r?\n\r?\n/.exec(buffer)
      }
    }
  } finally {
    reader.releaseLock()
  }
}
