import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import HistoryView from '@/views/HistoryView.vue'
import { useAuthStore } from '@/stores/auth'

const { campaignRequestMock } = vi.hoisted(() => ({ campaignRequestMock: vi.fn() }))

vi.mock('@/api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/client')>()
  return { ...actual, campaignRequest: campaignRequestMock }
})

function mountView() {
  const pinia = createPinia()
  setActivePinia(pinia)
  useAuthStore(pinia).token = 'user-token'
  return mount(HistoryView, {
    global: { plugins: [pinia], stubs: { RouterLink: { template: '<a><slot /></a>' } } },
  })
}

describe('personal draw history', () => {
  beforeEach(() => { campaignRequestMock.mockReset() })

  it('shows persisted draw outcomes but never projects Worker fulfillment state', async () => {
    campaignRequestMock.mockResolvedValueOnce({
      items: [{
        drawId: 77,
        campaignId: 1005,
        outcome: 'WON',
        prizeId: 1,
        drawnAt: '2026-09-25T10:00:00Z',
        fulfillmentStatus: 'SUCCEEDED',
      }],
      nextCursor: null,
    })
    const view = mountView()
    await flushPromises()

    expect(view.text()).toContain('活动 #1005')
    expect(view.text()).toContain('中奖')
    expect(view.text()).not.toContain('SUCCEEDED')
    expect(view.text()).toContain('不等同于真实供应商交付状态')
  })
})
