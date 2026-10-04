import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import OperatorAiView from '@/views/OperatorAiView.vue'
import { useAuthStore } from '@/stores/auth'

const api = vi.hoisted(() => ({
  cancelAgentRun: vi.fn(),
  createAgentRun: vi.fn(),
  createAgentSession: vi.fn(),
  deleteAgentSession: vi.fn(),
  getAgentReadiness: vi.fn(),
  getAgentRun: vi.fn(),
  getAgentSession: vi.fn(),
  resumeAgentRun: vi.fn(),
  streamAgentRun: vi.fn(),
}))

vi.mock('@/api/agent', () => api)

const createdSession = {
  sessionId: 'session-abc123', version: 0, activeRunId: null,
  createdAt: '2026-09-25T10:00:00Z', updatedAt: '2026-09-25T10:00:00Z',
}
const emptySnapshot = { session: createdSession, history: [] }

function mountView() {
  const pinia = createPinia()
  setActivePinia(pinia)
  useAuthStore(pinia).token = 'operator-token'
  return mount(OperatorAiView, { global: { plugins: [pinia] } })
}

async function startSession(view: ReturnType<typeof mountView>) {
  api.createAgentSession.mockResolvedValueOnce(createdSession)
  await view.get('button.new-session').trigger('click')
  await flushPromises()
}

function acceptedRun(overrides: Record<string, unknown> = {}) {
  return {
    runId: 'run-123', sessionId: createdSession.sessionId, input: '查询活动 1005',
    status: 'ACCEPTED', answer: null, safeErrorCode: null, cancelRequested: false,
    createdAt: '2026-09-25T10:00:00Z', startedAt: null, finishedAt: null,
    knowledgeCitations: [],
    ...overrides,
  }
}

describe('Python Harness operator assistant', () => {
  beforeEach(() => {
    sessionStorage.clear()
    for (const mock of Object.values(api)) mock.mockReset()
    api.getAgentReadiness.mockResolvedValue({ status: 'READY', model_provider: 'fake' })
    api.streamAgentRun.mockImplementation(() => new Promise<void>(() => {}))
  })

  it('uses the Python run API once and renders the persisted answer transparently', async () => {
    const view = mountView()
    await flushPromises()
    await startSession(view)
    api.createAgentRun.mockResolvedValueOnce({ runId: 'run-123', status: 'ACCEPTED', replayed: false })
    api.getAgentRun.mockResolvedValueOnce(acceptedRun({
      status: 'COMPLETED', answer: 'Fake Model 仅用于验证控制流程。', finishedAt: '2026-09-25T10:01:00Z',
      knowledgeCitations: [{
        citationId: '00000000-0000-4000-8000-000000000001',
        indexVersion: 'a'.repeat(64), documentId: 'operator-guide', documentHash: 'b'.repeat(64),
        title: '运营指南', section: '审核流程', scope: 'OPERATOR',
      }],
    }))

    await view.get('textarea').setValue('请查询活动 1005')
    await view.get('form').trigger('submit')
    await flushPromises()

    expect(api.createAgentRun).toHaveBeenCalledTimes(1)
    expect(api.createAgentRun).toHaveBeenCalledWith('operator-token', createdSession.sessionId,
      expect.objectContaining({ expectedVersion: 0, input: '请查询活动 1005' }))
    expect(view.text()).toContain('FAKE MODEL · 离线演示')
    expect(view.text()).toContain('Fake Model 仅用于验证控制流程。')
    expect(view.text()).toContain('本轮检索到的资料')
    expect(view.text()).toContain('运营指南')
    expect(view.text()).toContain('不含原文')
    expect(view.text()).toContain('Python 服务持久化')
    expect(view.find('[v-html]').exists()).toBe(false)
  })

  it('restores an accepted run without dispatching it automatically', async () => {
    sessionStorage.setItem('prizeflux.agent.session-index', JSON.stringify({
      sessionIds: [createdSession.sessionId], activeSessionId: createdSession.sessionId,
      lastRunBySession: { [createdSession.sessionId]: 'run-123' },
    }))
    api.getAgentSession.mockResolvedValueOnce({
      session: { ...createdSession, activeRunId: 'run-123' }, history: [],
    })
    api.getAgentRun.mockResolvedValueOnce(acceptedRun())

    const view = mountView()
    await flushPromises()

    expect(view.text()).toContain('原任务已持久化受理')
    expect(view.text()).toContain('继续执行原任务')
    expect(api.resumeAgentRun).not.toHaveBeenCalled()
    expect(api.createAgentRun).not.toHaveBeenCalled()
  })

  it('only resumes the original accepted run after an explicit click', async () => {
    sessionStorage.setItem('prizeflux.agent.session-index', JSON.stringify({
      sessionIds: [createdSession.sessionId], activeSessionId: createdSession.sessionId,
      lastRunBySession: { [createdSession.sessionId]: 'run-123' },
    }))
    api.getAgentSession.mockResolvedValueOnce({
      session: { ...createdSession, activeRunId: 'run-123' }, history: [],
    })
    api.getAgentRun
      .mockResolvedValueOnce(acceptedRun())
      .mockResolvedValueOnce(acceptedRun({ status: 'RUNNING', startedAt: '2026-09-25T10:00:03Z' }))

    const view = mountView()
    await flushPromises()
    await view.get('.run-actions button').trigger('click')
    await flushPromises()

    expect(api.resumeAgentRun).toHaveBeenCalledOnce()
    expect(api.resumeAgentRun).toHaveBeenCalledWith('operator-token', 'run-123')
    expect(api.createAgentRun).not.toHaveBeenCalled()
    expect(view.text()).toContain('任务正在执行')
  })

  it('never offers resume for an interrupted run and explains possible provider cost', async () => {
    sessionStorage.setItem('prizeflux.agent.session-index', JSON.stringify({
      sessionIds: [createdSession.sessionId], activeSessionId: createdSession.sessionId,
      lastRunBySession: { [createdSession.sessionId]: 'run-123' },
    }))
    api.getAgentSession.mockResolvedValueOnce({ session: createdSession, history: [] })
    api.getAgentRun.mockResolvedValueOnce(acceptedRun({ status: 'INTERRUPTED' }))

    const view = mountView()
    await flushPromises()

    expect(view.text()).toContain('本轮已中断，没有自动重跑')
    expect(view.text()).toContain('可能已经开始或产生费用')
    expect(view.text()).not.toContain('继续执行原任务')
    expect(api.resumeAgentRun).not.toHaveBeenCalled()
  })

  it('hides a session immediately when the service reports deferred deletion', async () => {
    const view = mountView()
    await flushPromises()
    await startSession(view)
    api.deleteAgentSession.mockResolvedValueOnce(true)

    await view.get('button.delete-session').trigger('click')
    await flushPromises()

    expect(api.deleteAgentSession).toHaveBeenCalledWith('operator-token', createdSession.sessionId)
    expect(view.text()).toContain('正在安全停止任务并清理数据')
    expect(view.text()).toContain('还没有会话')
  })
})
