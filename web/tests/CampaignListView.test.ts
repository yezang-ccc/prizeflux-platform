import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import CampaignListView from '@/views/CampaignListView.vue'
import { useAuthStore } from '@/stores/auth'

const { campaignRequestMock } = vi.hoisted(() => ({ campaignRequestMock: vi.fn() }))

vi.mock('@/api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/client')>()
  return { ...actual, campaignRequest: campaignRequestMock }
})

const campaign = {
  id: 1005,
  name: '秋日惊喜季',
  startsAt: '2026-09-24T00:00:00Z',
  endsAt: '2026-10-01T00:00:00Z',
  status: 'PUBLISHED' as const,
}

function mountView() {
  const pinia = createPinia()
  setActivePinia(pinia)
  useAuthStore(pinia).token = 'user-token'
  return mount(CampaignListView, {
    global: { plugins: [pinia], stubs: { RouterLink: { template: '<a><slot /></a>' } } },
  })
}

describe('user campaign list', () => {
  beforeEach(() => { campaignRequestMock.mockReset() })

  it('renders server-provided campaigns and the empty state', async () => {
    campaignRequestMock.mockResolvedValueOnce({ items: [campaign], nextCursor: null })
    const populated = mountView()
    await flushPromises()

    expect(populated.text()).toContain('秋日惊喜季')
    expect(populated.text()).toContain('实际抽奖资格、库存与结果由服务端确认')

    campaignRequestMock.mockResolvedValueOnce({ items: [], nextCursor: null })
    const empty = mountView()
    await flushPromises()
    expect(empty.text()).toContain('目前没有开放参与的活动')
  })

  it('shows a safe error and retries the read when asked', async () => {
    campaignRequestMock
      .mockRejectedValueOnce(new Error('服务暂时不可用，请稍后重试。'))
      .mockResolvedValueOnce({ items: [campaign], nextCursor: null })
    const view = mountView()
    await flushPromises()

    expect(view.get('[role="alert"]').text()).toContain('服务暂时不可用')
    await view.get('[role="alert"] button').trigger('click')
    await flushPromises()
    expect(view.text()).toContain('秋日惊喜季')
    expect(campaignRequestMock).toHaveBeenCalledTimes(2)
  })
})
