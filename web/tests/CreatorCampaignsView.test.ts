import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import CreatorCampaignsView from '@/views/CreatorCampaignsView.vue'
import { useAuthStore } from '@/stores/auth'

const { campaignRequestMock, pushMock } = vi.hoisted(() => ({ campaignRequestMock: vi.fn(), pushMock: vi.fn() }))

vi.mock('@/api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/client')>()
  return { ...actual, campaignRequest: campaignRequestMock }
})

vi.mock('vue-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue-router')>()
  return { ...actual, useRouter: () => ({ push: pushMock }) }
})

function mountView() {
  const pinia = createPinia()
  setActivePinia(pinia)
  useAuthStore(pinia).token = 'user-token'
  return mount(CreatorCampaignsView, {
    global: { plugins: [pinia], stubs: { RouterLink: { template: '<a><slot /></a>' } } },
  })
}

describe('creator campaign desk', () => {
  beforeEach(() => { campaignRequestMock.mockReset(); pushMock.mockReset() })

  it('creates an owner-scoped draft without sending an ID or role', async () => {
    campaignRequestMock.mockImplementation((path: string, _token: string, init?: RequestInit) => {
      if (path.startsWith('/me/campaigns?')) return Promise.resolve({ items: [], nextCursor: null })
      if (path === '/me/campaigns' && init?.method === 'POST') return Promise.resolve({ id: 301, status: 'DRAFT' })
      return Promise.reject(new Error(`unexpected request: ${path}`))
    })
    const view = mountView()
    await flushPromises()
    await view.get('input[placeholder="例如：秋日惊喜季"]').setValue('我的活动')
    await view.get('form').trigger('submit')
    await flushPromises()

    const [, , init] = campaignRequestMock.mock.calls.find(([path, , options]) =>
      path === '/me/campaigns' && options?.method === 'POST')!
    const body = JSON.parse(init.body as string)
    expect(body).toMatchObject({ name: '我的活动' })
    expect(body).not.toHaveProperty('id')
    expect(body).not.toHaveProperty('ownerUserId')
    expect(body).not.toHaveProperty('status')
    expect(pushMock).toHaveBeenCalledWith('/me/campaigns/301')
  })
})
