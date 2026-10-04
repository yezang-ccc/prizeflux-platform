import { afterEach, describe, expect, it, vi } from 'vitest'
import { campaignRequest } from '@/api/client'

afterEach(() => vi.unstubAllGlobals())

describe('campaignRequest', () => {
  it('sends a bearer token and unwraps the standard response', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      code: 'PFX-0000', message: '成功', data: { userId: 7, username: 'demo.user', role: 'USER' },
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    vi.stubGlobal('fetch', fetchMock)

    const result = await campaignRequest<{ userId: number }>('/auth/me', 'short-lived-token')

    expect(result.userId).toBe(7)
    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit]
    expect(url).toBe('/api/auth/me')
    expect(new Headers(init.headers).get('Authorization')).toBe('Bearer short-lived-token')
  })

  it('turns authorization failures into safe, actionable messages', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({
      code: 'PFX-1003', message: '无权限', data: null,
    }), { status: 403, headers: { 'Content-Type': 'application/json' } })))

    await expect(campaignRequest('/ops/campaigns', 'user-token'))
      .rejects.toMatchObject({ status: 403, message: '当前账号没有执行此操作的权限。' })
  })

  it('does not surface internal server details', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('stack trace secret', { status: 500 })))

    await expect(campaignRequest('/campaigns/1', 'token'))
      .rejects.toThrow('服务暂时不可用，请稍后重试。')
  })

  it('clears the tab token when the server rejects an expired session', async () => {
    sessionStorage.setItem('prizeflux.access-token', 'expired-token')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({
      code: 'PFX-1002', message: '未认证', data: null,
    }), { status: 401, headers: { 'Content-Type': 'application/json' } })))

    await expect(campaignRequest('/auth/me', 'expired-token')).rejects.toMatchObject({ status: 401 })
    expect(sessionStorage.getItem('prizeflux.access-token')).toBeNull()
  })

  it('preserves the retry-after header for anonymous rate-limit feedback', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({
      code: 'PFX-3001', message: '请求过于频繁', data: null,
    }), { status: 429, headers: { 'Content-Type': 'application/json', 'Retry-After': '52' } })))

    await expect(campaignRequest('/auth/register')).rejects.toMatchObject({
      status: 429,
      code: 'PFX-3001',
      retryAfterSeconds: 52,
    })
  })
})
