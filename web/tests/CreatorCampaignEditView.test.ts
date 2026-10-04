import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import CreatorCampaignEditView from '@/views/CreatorCampaignEditView.vue'
import { useAuthStore } from '@/stores/auth'

const { campaignRequestMock, agentApi } = vi.hoisted(() => ({
  campaignRequestMock: vi.fn(),
  agentApi: {
    cancelAgentRun: vi.fn(), createAgentRun: vi.fn(), createAgentSession: vi.fn(),
    getAgentRun: vi.fn(), getAgentSession: vi.fn(), resumeAgentRun: vi.fn(), streamAgentRun: vi.fn(),
  },
}))

vi.mock('@/api/agent', () => agentApi)

vi.mock('@/api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/client')>()
  return { ...actual, campaignRequest: campaignRequestMock }
})

vi.mock('vue-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue-router')>()
  return { ...actual, useRoute: () => ({ params: { id: '301' } }), useRouter: () => ({ push: vi.fn() }) }
})

const draft = {
  id: 301, name: '我的活动', startsAt: '2026-10-01T00:00:00Z', endsAt: '2026-10-08T00:00:00Z',
  status: 'DRAFT' as const, reviewComment: null, reviewedAt: null,
}
const config = { dailyLimit: 3, prizes: [{ id: 1, name: '礼盒', totalStock: 2, probability: 0.25 }] }
const creatorSession = {
  sessionId: 'creator-session-301', version: 0, activeRunId: null,
  createdAt: '2026-09-28T10:00:00Z', updatedAt: '2026-09-28T10:00:00Z',
}
const creatorReport = {
  schemaVersion: 1 as const, mode: 'CREATOR_DRAFT_REVIEW' as const,
  draftFingerprint: `sha256:${'a'.repeat(64)}`, checkedAt: '2026-09-28T10:01:00Z',
  summary: 'Java 规则校验发现 1 项需要处理。',
  authoritativeFindings: [{ code: 'PRIZE_PROBABILITY_SUM_EXCEEDED',
    field: 'prizes[0].probability', message: '奖项概率总和不能超过 1。' }],
  suggestions: [{ field: 'prizes[0].probability', kind: 'EXPLANATION' as const,
    text: '请检查各奖项概率。', rationale: 'Java 校验指出该字段需要处理。', evidenceRefs: [] }],
  openQuestions: [],
}

function mountView() {
  const pinia = createPinia()
  setActivePinia(pinia)
  useAuthStore(pinia).token = 'user-token'
  return mount(CreatorCampaignEditView, {
    global: { plugins: [pinia], stubs: { RouterLink: { template: '<a><slot /></a>' } } },
  })
}

describe('creator-owned campaign editing and copilot', () => {
  beforeEach(() => {
    sessionStorage.clear()
    campaignRequestMock.mockReset()
    for (const mock of Object.values(agentApi)) mock.mockReset()
    agentApi.getAgentSession.mockResolvedValue({ session: creatorSession, history: [] })
    agentApi.streamAgentRun.mockImplementation(() => new Promise<void>(() => {}))
  })

  it('submits the current snapshot to a persistent Java-first Agent run and shows its report', async () => {
    campaignRequestMock.mockImplementation((path: string) => {
      if (path === '/me/campaigns/301') return Promise.resolve(draft)
      if (path === '/me/campaigns/301/configuration') return Promise.resolve(config)
      return Promise.reject(new Error(`unexpected write: ${path}`))
    })
    agentApi.createAgentSession.mockResolvedValue(creatorSession)
    agentApi.createAgentRun.mockResolvedValue({ runId: 'creator-run-301', status: 'ACCEPTED', replayed: false })
    agentApi.getAgentRun.mockResolvedValue({
      runId: 'creator-run-301', sessionId: creatorSession.sessionId,
      input: '请指出需要优先处理的问题，并说明 Java 规则校验结果。',
      status: 'COMPLETED', answer: creatorReport.summary, safeErrorCode: null,
      cancelRequested: false, createdAt: creatorReport.checkedAt,
      startedAt: creatorReport.checkedAt, finishedAt: creatorReport.checkedAt,
      knowledgeCitations: [], creatorReport,
    })
    const view = mountView()
    await flushPromises()
    await view.get('.creator-run-actions .button.primary').trigger('click')
    await flushPromises()

    expect(agentApi.createAgentRun).toHaveBeenCalledWith('user-token', creatorSession.sessionId,
      expect.objectContaining({ mode: 'CREATOR_DRAFT_REVIEW', context: expect.objectContaining({
        schemaVersion: 1, draft: expect.objectContaining({ name: '我的活动', dailyLimit: 3 }),
      }) }))
    expect(view.text()).toContain('Java 权威校验')
    expect(view.text()).toContain('奖项概率总和不能超过 1。')
    expect(view.text()).toContain('非权威 · 不自动应用')
    expect(view.text()).toContain('当前建议没有知识资料引用')
    expect(sessionStorage.getItem('prizeflux.creator.run.301')).toContain('creator-run-301')
    expect(campaignRequestMock.mock.calls.some(([, , init]) => ['PUT', 'POST'].includes(init?.method ?? ''))).toBe(false)

    await view.get('#campaign-name').setValue('更新后的活动名称')
    expect(view.text()).toContain('草案已在检查后发生变化')
  })

  it('reuses the same idempotency request when the first submission response is ambiguous', async () => {
    campaignRequestMock.mockImplementation((path: string) => {
      if (path === '/me/campaigns/301') return Promise.resolve(draft)
      if (path === '/me/campaigns/301/configuration') return Promise.resolve(config)
      return Promise.reject(new Error(`unexpected write: ${path}`))
    })
    agentApi.createAgentSession.mockResolvedValue(creatorSession)
    agentApi.createAgentRun
      .mockRejectedValueOnce(new Error('连接中断'))
      .mockResolvedValueOnce({ runId: 'creator-run-replayed', status: 'ACCEPTED', replayed: true })
    agentApi.getAgentRun.mockResolvedValue({
      runId: 'creator-run-replayed', sessionId: creatorSession.sessionId,
      input: '请指出需要优先处理的问题，并说明 Java 规则校验结果。',
      status: 'COMPLETED', answer: creatorReport.summary, safeErrorCode: null,
      cancelRequested: false, createdAt: creatorReport.checkedAt,
      startedAt: creatorReport.checkedAt, finishedAt: creatorReport.checkedAt,
      knowledgeCitations: [], creatorReport,
    })
    const view = mountView()
    await flushPromises()
    const button = '.creator-run-actions .button.primary'
    await view.get(button).trigger('click')
    await flushPromises()
    expect(view.text()).toContain('沿用同一请求标识')

    await view.get(button).trigger('click')
    await flushPromises()

    expect(agentApi.createAgentRun).toHaveBeenCalledTimes(2)
    expect(agentApi.createAgentRun.mock.calls[0]?.[2])
      .toEqual(agentApi.createAgentRun.mock.calls[1]?.[2])
    expect(agentApi.createAgentRun.mock.calls[1]?.[2].clientMessageId)
      .toEqual(agentApi.createAgentRun.mock.calls[0]?.[2].clientMessageId)
  })

  it('restores an accepted run and only resumes it after the creator clicks', async () => {
    campaignRequestMock.mockImplementation((path: string) => {
      if (path === '/me/campaigns/301') return Promise.resolve(draft)
      if (path === '/me/campaigns/301/configuration') return Promise.resolve(config)
      return Promise.reject(new Error(`unexpected write: ${path}`))
    })
    sessionStorage.setItem('prizeflux.creator.run.301', JSON.stringify({
      sessionId: creatorSession.sessionId, runId: 'creator-run-accepted', draftSnapshot: 'saved snapshot',
    }))
    agentApi.getAgentRun
      .mockResolvedValueOnce({
        runId: 'creator-run-accepted', sessionId: creatorSession.sessionId, input: 'Check this draft',
        status: 'ACCEPTED', answer: null, safeErrorCode: null, cancelRequested: false,
        createdAt: creatorReport.checkedAt, startedAt: null, finishedAt: null,
        knowledgeCitations: [], creatorReport: null,
      })
      .mockResolvedValueOnce({
        runId: 'creator-run-accepted', sessionId: creatorSession.sessionId, input: 'Check this draft',
        status: 'RUNNING', answer: null, safeErrorCode: null, cancelRequested: false,
        createdAt: creatorReport.checkedAt, startedAt: creatorReport.checkedAt, finishedAt: null,
        knowledgeCitations: [], creatorReport: null,
      })
    agentApi.resumeAgentRun.mockResolvedValue({ runId: 'creator-run-accepted', status: 'ACCEPTED', resumed: true })

    const view = mountView()
    await flushPromises()
    expect(view.text()).toContain('等待执行')
    expect(agentApi.resumeAgentRun).not.toHaveBeenCalled()

    await view.get('.creator-run-actions .button.secondary').trigger('click')
    await flushPromises()

    expect(agentApi.resumeAgentRun).toHaveBeenCalledWith('user-token', 'creator-run-accepted')
    expect(agentApi.createAgentRun).not.toHaveBeenCalled()
    expect(view.text()).toContain('检查中')
    view.unmount()
  })

  it('saves changed owner details and configuration before submitting for review', async () => {
    campaignRequestMock.mockImplementation((path: string, _token: string, init?: RequestInit) => {
      if (path === '/me/campaigns/301' && !init) return Promise.resolve(draft)
      if (path === '/me/campaigns/301/configuration' && !init) return Promise.resolve(config)
      if (path === '/me/campaigns/301' && init?.method === 'PUT') return Promise.resolve({ ...draft, name: '已修改活动' })
      if (path === '/me/campaigns/301/configuration' && init?.method === 'PUT') return Promise.resolve(null)
      if (path === '/me/campaigns/301/submit' && init?.method === 'POST') return Promise.resolve({ ...draft, name: '已修改活动', status: 'PENDING_REVIEW' })
      return Promise.reject(new Error(`unexpected request: ${path}`))
    })
    const view = mountView()
    await flushPromises()
    await view.get('.creator-basics input').setValue('已修改活动')
    await view.get('.publish-actions .button.primary').trigger('click')
    await flushPromises()

    const calls = campaignRequestMock.mock.calls.map(([path, , init]) => `${init?.method ?? 'GET'} ${path}`)
    expect(calls.indexOf('PUT /me/campaigns/301')).toBeLessThan(calls.indexOf('PUT /me/campaigns/301/configuration'))
    expect(calls.indexOf('PUT /me/campaigns/301/configuration')).toBeLessThan(calls.indexOf('POST /me/campaigns/301/submit'))
    expect(view.text()).toContain('审核期间草稿已锁定')
    expect((view.get('.creator-basics input').element as HTMLInputElement).disabled).toBe(true)
  })
})
