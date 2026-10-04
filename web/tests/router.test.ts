import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { router } from '@/router'
import { useAuthStore } from '@/stores/auth'

describe('role-aware route navigation', () => {
  beforeEach(async () => {
    setActivePinia(createPinia())
    await router.push('/login')
  })

  it('redirects a USER away from operator pages', async () => {
    const auth = useAuthStore()
    auth.checked = true
    auth.user = { userId: 11, username: 'participant', role: 'USER' }

    await router.push('/ops/campaigns')

    expect(router.currentRoute.value.path).toBe('/campaigns')
  })

  it('redirects an OPERATOR away from personal user pages', async () => {
    const auth = useAuthStore()
    auth.checked = true
    auth.user = { userId: 12, username: 'operator', role: 'OPERATOR' }

    await router.push('/me/history')

    expect(router.currentRoute.value.path).toBe('/ops/reviews')
  })

  it('sends anonymous visitors to login and remembers the requested path', async () => {
    await router.push('/campaigns/1005')

    expect(router.currentRoute.value.path).toBe('/login')
    expect(router.currentRoute.value.query.redirect).toBe('/campaigns/1005')
  })
})
