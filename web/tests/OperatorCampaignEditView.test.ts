import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import OperatorCampaignEditView from '@/views/OperatorCampaignEditView.vue'
import { ApiError } from '@/api/client'
import { useAuthStore } from '@/stores/auth'

const { campaignRequestMock } = vi.hoisted(() => ({ campaignRequestMock: vi.fn() }))

vi.mock('@/api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/client')>()
  return { ...actual, campaignRequest: campaignRequestMock }
})

vi.mock('vue-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue-router')>()
  return {
    ...actual,
    useRoute: () => ({ params: { id: '1005' } }),
    useRouter: () => ({ push: vi.fn() }),
  }
})

const draft = {
  id: 1005,
  name: '秋日惊喜季',
  startsAt: '2026-09-25T12:00:00Z',
  endsAt: '2026-10-01T12:00:00Z',
  status: 'DRAFT' as const,
}

function mountView() {
  const pinia = createPinia()
  setActivePinia(pinia)
  useAuthStore(pinia).token = 'operator-token'
  return mount(OperatorCampaignEditView, {
    global: { plugins: [pinia], stubs: { RouterLink: { template: '<a><slot /></a>' } } },
  })
}

describe('operator campaign configuration', () => {
  beforeEach(() => { campaignRequestMock.mockReset() })

  it('saves changed draft configuration before publishing it', async () => {
    campaignRequestMock.mockImplementation((path: string, _token: string, init?: RequestInit) => {
      if (path === '/campaigns/1005' && !init) return Promise.resolve(draft)
      if (path === '/ops/campaigns/1005/configuration') {
        return Promise.reject(new ApiError(404, 'missing configuration'))
      }
      if (path === '/campaigns/1005/configuration' && init?.method === 'POST') return Promise.resolve(null)
      if (path === '/campaigns/1005/publish') return Promise.resolve({ ...draft, status: 'PUBLISHED' })
      return Promise.reject(new Error(`unexpected request: ${path}`))
    })
    const view = mountView()
    await flushPromises()

    await view.get('.add-prize').trigger('click')
    await view.get('.prize-name-input input').setValue('品牌礼盒')
    await view.get('.publish-actions .button.primary').trigger('click')
    await flushPromises()

    const saveCall = campaignRequestMock.mock.calls.findIndex(([path, , init]) =>
      path === '/campaigns/1005/configuration' && init?.method === 'POST')
    const publishCall = campaignRequestMock.mock.calls.findIndex(([path]) => path === '/campaigns/1005/publish')
    expect(saveCall).toBeGreaterThan(-1)
    expect(publishCall).toBeGreaterThan(saveCall)
    expect(JSON.parse(campaignRequestMock.mock.calls[saveCall]?.[2]?.body as string)).toMatchObject({
      dailyLimit: 3,
      prizes: [{ id: 1, name: '品牌礼盒', totalStock: 1, probability: 0.1 }],
    })
    expect(view.text()).toContain('活动已发布')
    expect(view.get('.state-pill.large').text()).toContain('已发布')
  })

  it('locks a published configuration and can close the campaign', async () => {
    const published = { ...draft, status: 'PUBLISHED' as const }
    campaignRequestMock.mockImplementation((path: string, _token: string, init?: RequestInit) => {
      if (path === '/campaigns/1005' && !init) return Promise.resolve(published)
      if (path === '/ops/campaigns/1005/configuration') {
        return Promise.resolve({ dailyLimit: 3, prizes: [{ id: 1, name: '品牌礼盒', totalStock: 5, probability: 0.25 }] })
      }
      if (path === '/campaigns/1005/close' && init?.method === 'POST') {
        return Promise.resolve({ ...published, status: 'CLOSED' })
      }
      return Promise.reject(new Error(`unexpected request: ${path}`))
    })
    const view = mountView()
    await flushPromises()

    expect((view.get('.prize-name-input input').element as HTMLInputElement).disabled).toBe(true)
    expect(view.find('.add-prize').exists()).toBe(false)
    await view.get('.button.danger').trigger('click')
    await flushPromises()

    expect(campaignRequestMock.mock.calls.some(([path, , init]) => path === '/campaigns/1005/close' && init?.method === 'POST')).toBe(true)
    expect(view.get('.state-pill.large').text()).toContain('已关闭')
  })
})
