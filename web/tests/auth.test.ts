import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from '@/stores/auth'

describe('authentication state', () => {
  afterEach(() => vi.unstubAllGlobals())
  beforeEach(() => {
    setActivePinia(createPinia())
    sessionStorage.clear()
  })

  it('gets the role from the authenticated server response and keeps the token for this tab only', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ code: 'PFX-0000', message: '成功', data: { token: 'token-1', expiresAt: '2026-09-25T10:00:00Z' } }), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ code: 'PFX-0000', message: '成功', data: { userId: 7, username: 'demo.user', role: 'USER' } }), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)
    const auth = useAuthStore()

    await auth.login('demo.user', 'correct-horse')

    expect(auth.user?.role).toBe('USER')
    expect(sessionStorage.getItem('prizeflux.access-token')).toBe('token-1')
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('removes the bearer token on logout', async () => {
    sessionStorage.setItem('prizeflux.access-token', 'token-1')
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')))
    const auth = useAuthStore()
    await auth.restore()

    auth.logout()

    expect(auth.user).toBeNull()
    expect(sessionStorage.getItem('prizeflux.access-token')).toBeNull()
    expect(auth.checked).toBe(true)
  })
})
