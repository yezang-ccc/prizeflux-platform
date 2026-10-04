import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createAgentRun, deleteAgentSession, getAgentRun, resumeAgentRun, streamAgentRun } from '@/api/agent'

describe('Python Agent HTTP adapter', () => {
  const fetchMock = vi.fn<typeof fetch>()

  beforeEach(() => {
    fetchMock.mockReset()
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => vi.unstubAllGlobals())

  it('sends owner-authenticated requests directly to the Python contract', async () => {
    fetchMock.mockResolvedValueOnce(new Response(JSON.stringify({
      runId: 'run-1', status: 'ACCEPTED', replayed: false,
    }), { status: 202, headers: { 'Content-Type': 'application/json' } }))

    await expect(createAgentRun('operator-token', 'session/1', {
      clientMessageId: 'message-1', expectedVersion: 2, input: '查询活动',
    })).resolves.toMatchObject({ runId: 'run-1', status: 'ACCEPTED' })

    const firstCall = fetchMock.mock.calls[0]
    expect(firstCall).toBeDefined()
    const request = firstCall?.[1] as RequestInit
    expect(firstCall?.[0]).toBe('/agent-api/api/ai/v2/sessions/session%2F1/runs')
    expect(request.method).toBe('POST')
    expect(new Headers(request.headers).get('Authorization')).toBe('Bearer operator-token')
    expect(request.body).toBe(JSON.stringify({
      clientMessageId: 'message-1', expectedVersion: 2, input: '查询活动',
    }))
  })

  it('keeps explicit resume separate and treats a 204 deletion as complete', async () => {
    fetchMock
      .mockResolvedValueOnce(new Response(JSON.stringify({ runId: 'run-1', status: 'ACCEPTED', resumed: true }), { status: 202 }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))

    await expect(resumeAgentRun('operator-token', 'run-1')).resolves.toMatchObject({ resumed: true })
    await expect(deleteAgentSession('operator-token', 'session-1')).resolves.toBe(false)

    expect(fetchMock.mock.calls[0]?.[0]).toBe('/agent-api/api/ai/v2/runs/run-1/resume')
    expect(fetchMock.mock.calls[0]?.[1]).toMatchObject({ method: 'POST' })
  })

  it('reads owner-scoped knowledge citation metadata without storing passages in the client contract', async () => {
    fetchMock.mockResolvedValueOnce(new Response(JSON.stringify({
      runId: 'run-1', sessionId: 'session-1', input: 'Question', status: 'COMPLETED',
      answer: 'Answer', safeErrorCode: null, cancelRequested: false,
      createdAt: 'now', startedAt: 'now', finishedAt: 'now',
      knowledgeCitations: [{
        citationId: 'citation-1', indexVersion: 'a'.repeat(64), documentId: 'public-help',
        documentHash: 'b'.repeat(64), title: '帮助', section: '规则', scope: 'PUBLIC',
      }],
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }))

    const run = await getAgentRun('operator-token', 'run-1')

    expect(run.knowledgeCitations[0]?.title).toBe('帮助')
    expect(run.knowledgeCitations[0]).not.toHaveProperty('text')
  })

  it('parses authenticated SSE status events across CRLF records without submitting a run', async () => {
    const event = JSON.stringify({ sequence: 2, eventType: 'run.started', payload: {}, createdAt: 'now' })
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(new TextEncoder().encode(`id: 2\r\nevent: run.started\r\ndata: ${event}\r\n\r\n`))
        controller.close()
      },
    })
    fetchMock.mockResolvedValueOnce(new Response(body, { status: 200, headers: { 'Content-Type': 'text/event-stream' } }))
    const events: unknown[] = []

    await streamAgentRun('operator-token', 'run-1', new AbortController().signal, (received) => events.push(received))

    expect(events).toEqual([JSON.parse(event)])
    const firstCall = fetchMock.mock.calls[0]
    expect(firstCall).toBeDefined()
    expect(firstCall?.[0]).toBe('/agent-api/api/ai/v2/runs/run-1/events')
    const request = firstCall?.[1] as RequestInit
    expect(new Headers(request.headers).get('Authorization')).toBe('Bearer operator-token')
  })
})
