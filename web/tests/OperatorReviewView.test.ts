import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import OperatorReviewView from '@/views/OperatorReviewView.vue'
import { useAuthStore } from '@/stores/auth'

const { campaignRequestMock } = vi.hoisted(() => ({ campaignRequestMock: vi.fn() }))

vi.mock('@/api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/client')>()
  return { ...actual, campaignRequest: campaignRequestMock }
})

const pending = { id: 301, name: '用户活动', startsAt: '2026-10-01T00:00:00Z', endsAt: '2026-10-08T00:00:00Z',
  status: 'PENDING_REVIEW' as const, ownerUserId: 22, ownerUsername: 'creator', submittedAt: '2026-09-25T12:00:00Z' }

function mountView() {
  const pinia = createPinia()
  setActivePinia(pinia)
  useAuthStore(pinia).token = 'operator-token'
  return mount(OperatorReviewView, {
    global: { plugins: [pinia] },
  })
}

describe('operator campaign review desk', () => {
  beforeEach(() => { campaignRequestMock.mockReset() })

  it('requires a reason to reject and removes the item after saving the decision', async () => {
    campaignRequestMock.mockImplementation((path: string, _token: string, init?: RequestInit) => {
      if (path === '/ops/reviews/campaigns?limit=50') {
        return Promise.resolve({ items: campaignRequestMock.mock.calls.some(([, , options]) => options?.method === 'POST') ? [] : [pending], nextCursor: null })
      }
      if (path === '/ops/campaigns/301/configuration') return Promise.resolve({ dailyLimit: 3, prizes: [{ id: 1, name: '礼盒', totalStock: 2, probability: 0.25 }] })
      if (path === '/ops/reviews/campaigns/301/reject' && init?.method === 'POST') return Promise.resolve(null)
      return Promise.reject(new Error(`unexpected request: ${path}`))
    })
    const view = mountView()
    await flushPromises()
    await view.get('.review-row').trigger('click')
    await flushPromises()
    await view.get('.review-actions .button.secondary').trigger('click')
    expect(view.get('[role="alert"]').text()).toContain('至少 3 个字符')
    expect(campaignRequestMock.mock.calls.some(([path]) => path.endsWith('/reject'))).toBe(false)

    await view.get('.review-comment textarea').setValue('改')
    await view.get('.review-actions .button.secondary').trigger('click')
    expect(view.get('[role="alert"]').text()).toContain('至少 3 个字符')
    expect(campaignRequestMock.mock.calls.some(([path]) => path.endsWith('/reject'))).toBe(false)

    await view.get('.review-comment textarea').setValue('请补充活动规则')
    await view.get('.review-actions .button.secondary').trigger('click')
    await flushPromises()

    const reject = campaignRequestMock.mock.calls.find(([path]) => path.endsWith('/reject'))
    expect(reject?.[2]).toMatchObject({ method: 'POST', body: JSON.stringify({ comment: '请补充活动规则' }) })
    expect(view.text()).toContain('当前没有待审核活动')
  })
})
