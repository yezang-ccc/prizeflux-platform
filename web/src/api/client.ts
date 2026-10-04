import type { ApiEnvelope } from '@/types'

export class ApiError extends Error {
  constructor(readonly status: number, message: string, readonly code?: string,
    readonly retryAfterSeconds?: number) { super(message) }
}

const TOKEN_KEY = 'prizeflux.access-token'
let unauthorizedHandler: (() => void) | undefined

export function setUnauthorizedHandler(handler: () => void) {
  unauthorizedHandler = handler
}

export function notifyUnauthorizedResponse() {
  if (unauthorizedHandler) unauthorizedHandler()
  else sessionStorage.removeItem(TOKEN_KEY)
}

function friendlyError(status: number, serverMessage?: string): string {
  if (status === 401) return '登录状态已失效，请重新登录。'
  if (status === 403) return '当前账号没有执行此操作的权限。'
  if (status === 404) return '内容不存在或当前不可见。'
  if (status === 409) return '内容刚刚发生变化，请刷新后继续。'
  if (status >= 500) return '服务暂时不可用，请稍后重试。'
  return serverMessage || '请求未完成，请检查输入后重试。'
}

async function request<T>(base: string, path: string, token?: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  headers.set('Accept', 'application/json')
  if (init.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')
  if (token) headers.set('Authorization', `Bearer ${token}`)
  let response: Response
  try {
    response = await fetch(`${base}${path}`, { ...init, headers })
  } catch {
    throw new ApiError(0, '无法连接服务，请确认后端已启动。')
  }
  if (response.status === 401) notifyUnauthorizedResponse()
  const payload = await response.json().catch(() => null) as ApiEnvelope<T> | null
  if (!response.ok || payload?.code !== 'PFX-0000') {
    const retryAfterHeader = response.headers.get('Retry-After')
    const parsedRetryAfter = retryAfterHeader === null ? undefined : Number(retryAfterHeader)
    const retryAfterSeconds = parsedRetryAfter !== undefined && Number.isFinite(parsedRetryAfter)
      && parsedRetryAfter >= 0 ? parsedRetryAfter : undefined
    throw new ApiError(response.status, friendlyError(response.status, payload?.message),
      payload?.code, retryAfterSeconds)
  }
  return payload.data
}

export function campaignRequest<T>(path: string, token?: string, init?: RequestInit): Promise<T> {
  return request<T>('/api', path, token, init)
}

export function aiRequest<T>(path: string, token: string, init?: RequestInit): Promise<T> {
  return request<T>('/ai-api', path, token, init)
}

export function jsonBody(value: unknown): string { return JSON.stringify(value) }
