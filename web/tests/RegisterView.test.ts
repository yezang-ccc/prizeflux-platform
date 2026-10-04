import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { router } from '@/router'
import RegisterView from '@/views/RegisterView.vue'

describe('USER registration view', () => {
  let wrapper: ReturnType<typeof mount> | undefined

  beforeEach(async () => {
    setActivePinia(createPinia())
    await router.push('/register')
    await router.isReady()
    const auth = (await import('@/stores/auth')).useAuthStore()
    auth.checked = true
  })

  afterEach(() => {
    wrapper?.unmount()
    vi.unstubAllGlobals()
  })

  it('normalizes uppercase usernames and returns to login after registration', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      code: 'PFX-0000', message: '成功', data: { userId: 42, username: 'yezang', role: 'USER' },
    }), { status: 201 }))
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mount(RegisterView, { global: { plugins: [router] } })

    await wrapper.get('input[autocomplete="username"]').setValue('  Yezang  ')
    await wrapper.get('input[autocomplete="new-password"]').setValue('test-only-password-123')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith('/api/auth/register', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({ username: 'yezang', password: 'test-only-password-123' }),
    }))
    expect(router.currentRoute.value.path).toBe('/login')
    expect(router.currentRoute.value.query).toEqual({ registered: '1', username: 'yezang' })
  })

  it('shows field-specific validation without sending an invalid request', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mount(RegisterView, { global: { plugins: [router] } })

    await wrapper.get('input[autocomplete="username"]').setValue('bad name')
    await wrapper.get('input[autocomplete="new-password"]').setValue('short')
    await wrapper.get('form').trigger('submit')

    expect(wrapper.text()).toContain('用户名需为 3–64 位')
    expect(wrapper.text()).toContain('密码至少需要 12 个字符')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('explains when the chosen username is already taken', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({
      code: 'PFX-2003', message: '资源冲突', data: null,
    }), { status: 409 })))
    wrapper = mount(RegisterView, { global: { plugins: [router] } })

    await wrapper.get('input[autocomplete="username"]').setValue('taken.user')
    await wrapper.get('input[autocomplete="new-password"]').setValue('test-only-password-123')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('已经被使用')
    expect(router.currentRoute.value.path).toBe('/register')
  })

  it('shows the server retry-after time when registration is rate limited', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({
      code: 'PFX-3001', message: '请求过于频繁', data: null,
    }), { status: 429, headers: { 'Retry-After': '52' } })))
    wrapper = mount(RegisterView, { global: { plugins: [router] } })

    await wrapper.get('input[autocomplete="username"]').setValue('new.user')
    await wrapper.get('input[autocomplete="new-password"]').setValue('test-only-password-123')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('52 秒后再试')
  })
})
