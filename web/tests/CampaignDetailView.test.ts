import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import CampaignDetailView from '@/views/CampaignDetailView.vue'
import { ApiError } from '@/api/client'
import { useAuthStore } from '@/stores/auth'

const { campaignRequestMock } = vi.hoisted(() => ({ campaignRequestMock: vi.fn() }))

vi.mock('@/api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/client')>()
  return { ...actual, campaignRequest: campaignRequestMock }
})

vi.mock('vue-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue-router')>()
  return { ...actual, useRoute: () => ({ params: { id: '1005' } }) }
})

const campaign = {
  id: 1005,
  name: '秋日惊喜季',
  startsAt: new Date(Date.now() - 60_000).toISOString(),
  endsAt: new Date(Date.now() + 60_000).toISOString(),
  status: 'PUBLISHED' as const,
}
const prizes = [{ id: 1, name: '品牌礼盒', probability: 0.25 }]

function mountView() {
  const pinia = createPinia()
  setActivePinia(pinia)
  useAuthStore(pinia).token = 'user-token'
  return mount(CampaignDetailView, {
    global: { plugins: [pinia], stubs: { RouterLink: { template: '<a><slot /></a>' } } },
  })
}

describe('user campaign detail and draw', () => {
  beforeEach(() => { campaignRequestMock.mockReset(); sessionStorage.clear() })

  it('reuses the same request key after an uncertain result', async () => {
    campaignRequestMock.mockImplementation((path: string, _token: string, init?: RequestInit) => {
      if (path === '/campaigns/1005') return Promise.resolve(campaign)
      if (path === '/campaigns/1005/prizes') return Promise.resolve(prizes)
      if (path === '/draws') {
        const body = JSON.parse(init?.body as string) as { requestKey: string }
        return body.requestKey
          ? drawResponses.shift()
          : Promise.reject(new Error('requestKey missing'))
      }
      return Promise.reject(new Error(`unexpected path: ${path}`))
    })
    const view = mountView()
    await flushPromises()
    const drawResponses = [
      Promise.reject(new ApiError(0, '无法连接服务，请确认后端已启动。')),
      Promise.resolve({ requestId: 77, campaignId: 1005, outcome: 'WON', prizeId: 1 }),
    ]

    const drawButton = view.get('.draw-button')
    await drawButton.trigger('click')
    await flushPromises()
    expect(view.get('[role="alert"]').text()).toContain('沿用同一请求编号')

    await drawButton.trigger('click')
    await flushPromises()

    const drawCalls = campaignRequestMock.mock.calls.filter(([path]) => path === '/draws')
    const requestKeys = drawCalls.map(([, , init]) => JSON.parse(init?.body as string).requestKey)
    expect(drawCalls).toHaveLength(2)
    expect(requestKeys[0]).toBe(requestKeys[1])
    expect(view.get('[role="status"]').text()).toContain('恭喜你，中奖啦')
  })

  it('accepts the high-peak queue and reuses its key after an uncertain response', async () => {
    campaignRequestMock.mockImplementation((path: string, _token: string, init?: RequestInit) => {
      if (path === '/campaigns/1005') return Promise.resolve(campaign)
      if (path === '/campaigns/1005/prizes') return Promise.resolve(prizes)
      if (path === '/draw-queue') {
        const body = JSON.parse(init?.body as string) as { campaignId: number; requestKey: string }
        return body.requestKey
          ? queueResponses.shift()
          : Promise.reject(new Error('requestKey missing'))
      }
      return Promise.reject(new Error(`unexpected path: ${path}`))
    })
    const view = mountView()
    await flushPromises()
    const queueResponses = [
      Promise.reject(new ApiError(0, '无法连接服务，请确认后端已启动。')),
      Promise.resolve({
        queueRequestId: '71', requestKey: 'stable-queue-key', status: 'QUEUED',
        result: null, errorCode: null, createdAt: new Date().toISOString(), completedAt: null,
      }),
    ]

    await view.get('.draw-mode-switch button:last-child').trigger('click')
    const queueButton = view.get('.queue-button')
    await queueButton.trigger('click')
    await flushPromises()
    expect(view.get('[role="alert"]').text()).toContain('复用同一个请求编号')

    await queueButton.trigger('click')
    await flushPromises()
    const queueCalls = campaignRequestMock.mock.calls.filter(([path]) => path === '/draw-queue')
    const requestKeys = queueCalls.map(([, , init]) => JSON.parse(init?.body as string).requestKey)
    expect(queueCalls).toHaveLength(2)
    expect(requestKeys[0]).toBe(requestKeys[1])
    expect(view.get('[role="status"]').text()).toContain('已受理，等待开奖')
  })

  it('keeps manual-review requests recoverable and offers an explicit status refresh', async () => {
    let acceptedKey = ''
    const reviewState = {
      queueRequestId: '72', requestKey: '', status: 'MANUAL_REVIEW',
      result: null, errorCode: 'PFX-9001', createdAt: new Date().toISOString(), completedAt: null,
    }
    campaignRequestMock.mockImplementation((path: string, _token: string, init?: RequestInit) => {
      if (path === '/campaigns/1005') return Promise.resolve(campaign)
      if (path === '/campaigns/1005/prizes') return Promise.resolve(prizes)
      if (path === '/draw-queue') {
        acceptedKey = (JSON.parse(init?.body as string) as { requestKey: string }).requestKey
        return Promise.resolve({ ...reviewState, requestKey: acceptedKey })
      }
      if (path === `/me/draw-queue/${acceptedKey}`) return Promise.resolve({ ...reviewState, requestKey: acceptedKey })
      return Promise.reject(new Error(`unexpected path: ${path}`))
    })
    const view = mountView()
    await flushPromises()

    await view.get('.draw-mode-switch button:last-child').trigger('click')
    await view.get('.queue-button').trigger('click')
    await flushPromises()

    expect(view.get('[role="status"]').text()).toContain('需要人工处理')
    expect(view.get('[role="status"]').text()).toContain('不会重复开奖')
    expect((view.get('.queue-button').element as HTMLButtonElement).disabled).toBe(true)
    expect(sessionStorage.getItem('prizeflux.pending-queue.1005')).toBe(acceptedKey)

    await view.get('.queue-status-card .text-button').trigger('click')
    await flushPromises()
    expect(campaignRequestMock).toHaveBeenCalledWith(`/me/draw-queue/${acceptedKey}`, 'user-token')
  })
})
