<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import {
  cancelAgentRun,
  createAgentRun,
  createAgentSession,
  deleteAgentSession,
  getAgentReadiness,
  getAgentRun,
  getAgentSession,
  resumeAgentRun,
  streamAgentRun,
  type AgentReadiness,
  type AgentRun,
  type AgentSession,
  type AgentSessionSnapshot,
} from '@/api/agent'
import { ApiError } from '@/api/client'
import { useAuthStore } from '@/stores/auth'

const LOCAL_INDEX_KEY = 'prizeflux.agent.session-index'
const MAX_LOCAL_SESSIONS = 20

interface LocalIndex {
  sessionIds: string[]
  activeSessionId: string | null
  lastRunBySession: Record<string, string>
}

interface DisplayMessage { key: string; kind: 'USER' | 'ASSISTANT'; content: string }
interface SessionListItem { sessionId: string; version: number | null }

const auth = useAuthStore()
const sessions = ref<SessionListItem[]>([])
const active = ref<AgentSessionSnapshot | null>(null)
const currentRun = ref<AgentRun | null>(null)
const readiness = ref<AgentReadiness | null>(null)
const question = ref('')
const busy = ref(false)
const loading = ref(false)
const error = ref('')
const notice = ref('')
const localIndex = ref<LocalIndex>({ sessionIds: [], activeSessionId: null, lastRunBySession: {} })
let runController: AbortController | null = null

const activeId = computed(() => active.value?.session.sessionId ?? '')
const modelLabel = computed(() => {
  if (readiness.value?.model_provider === 'fake') return 'FAKE MODEL · 离线演示'
  if (readiness.value?.model_provider === 'openai-compatible') return '外部模型 · 可能产生费用'
  return 'PYTHON HARNESS · 模型状态未知'
})
const currentStatusLabel = computed(() => {
  const labels: Record<string, string> = {
    ACCEPTED: '等待执行', RUNNING: '运行中', COMPLETED: '已完成',
    FAILED: '执行失败', CANCELLED: '已停止', INTERRUPTED: '已中断',
  }
  return currentRun.value ? labels[currentRun.value.status] ?? currentRun.value.status : ''
})
const conversation = computed<DisplayMessage[]>(() => {
  const turns = active.value?.history ?? []
  const messages: DisplayMessage[] = turns.flatMap((turn) => [
    { key: `user-${turn.sequence}`, kind: 'USER', content: turn.input },
    { key: `assistant-${turn.sequence}`, kind: 'ASSISTANT', content: turn.answer },
  ])
  const run = currentRun.value
  if (run && run.sessionId === activeId.value) {
    const lastTurn = turns.at(-1)
    const alreadyRecorded = run.status === 'COMPLETED'
      && lastTurn?.input === run.input && lastTurn.answer === run.answer
    if (!alreadyRecorded) {
      messages.push({ key: `run-user-${run.runId}`, kind: 'USER', content: run.input })
      if (run.status === 'COMPLETED' && run.answer) {
        messages.push({ key: `run-answer-${run.runId}`, kind: 'ASSISTANT', content: run.answer })
      }
    }
  }
  return messages
})

function readLocalIndex(): LocalIndex {
  try {
    const raw = sessionStorage.getItem(LOCAL_INDEX_KEY)
    if (!raw) return { sessionIds: [], activeSessionId: null, lastRunBySession: {} }
    const value = JSON.parse(raw) as Partial<LocalIndex>
    const sessionIds = Array.isArray(value.sessionIds)
      ? [...new Set(value.sessionIds.filter((id): id is string => typeof id === 'string'))]
        .slice(0, MAX_LOCAL_SESSIONS)
      : []
    const lastRunBySession: Record<string, string> = {}
    if (value.lastRunBySession && typeof value.lastRunBySession === 'object') {
      for (const [sessionId, runId] of Object.entries(value.lastRunBySession)) {
        if (typeof runId === 'string' && sessionIds.includes(sessionId)) lastRunBySession[sessionId] = runId
      }
    }
    return {
      sessionIds,
      activeSessionId: typeof value.activeSessionId === 'string' ? value.activeSessionId : null,
      lastRunBySession,
    }
  } catch {
    return { sessionIds: [], activeSessionId: null, lastRunBySession: {} }
  }
}

function persistLocalIndex() {
  localIndex.value.sessionIds = sessions.value.slice(0, MAX_LOCAL_SESSIONS).map((session) => session.sessionId)
  localIndex.value.activeSessionId = activeId.value || null
  const keep = new Set(localIndex.value.sessionIds)
  localIndex.value.lastRunBySession = Object.fromEntries(
    Object.entries(localIndex.value.lastRunBySession).filter(([sessionId]) => keep.has(sessionId)),
  )
  try { sessionStorage.setItem(LOCAL_INDEX_KEY, JSON.stringify(localIndex.value)) }
  catch { notice.value = '浏览器暂时无法记住会话列表；服务端数据不受影响。' }
}

function stopWatchingRun() {
  runController?.abort()
  runController = null
}

async function restoreSessions() {
  localIndex.value = readLocalIndex()
  if (!localIndex.value.sessionIds.length) return
  const requestedIds = [...localIndex.value.sessionIds]
  const results = await Promise.allSettled(
    requestedIds.map((id) => getAgentSession(auth.token, id)),
  )
  const restored: AgentSessionSnapshot[] = []
  const missing: string[] = []
  const unavailable = new Set<string>()
  const listItems: SessionListItem[] = []
  for (const [index, result] of results.entries()) {
    const sessionId = requestedIds[index]
    if (!sessionId) continue
    if (result.status === 'fulfilled') {
      restored.push(result.value)
      listItems.push(result.value.session)
    }
    else if (result.reason instanceof ApiError && result.reason.status === 404) missing.push(sessionId)
    else {
      unavailable.add(sessionId)
      listItems.push({ sessionId, version: null })
      error.value = friendly(result.reason)
    }
  }
  sessions.value = listItems
  if (unavailable.size === 0) localIndex.value.sessionIds = sessions.value.map((session) => session.sessionId)
  for (const id of missing) delete localIndex.value.lastRunBySession[id]
  const selectedId = localIndex.value.activeSessionId
  const selected = restored.find((snapshot) => snapshot.session.sessionId === selectedId) ?? restored[0]
  if (selected) {
    active.value = selected
    const runId = selected.session.activeRunId
      ?? localIndex.value.lastRunBySession[selected.session.sessionId]
    if (unavailable.size === 0) persistLocalIndex()
    if (runId) await loadRun(runId, true)
  } else if (unavailable.size === 0) {
    persistLocalIndex()
  }
}

async function initialize() {
  const readinessRequest = getAgentReadiness().then((value) => { readiness.value = value }).catch(() => {})
  await restoreSessions()
  await readinessRequest
}

async function createSession() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  notice.value = ''
  stopWatchingRun()
  try {
    const created = await createAgentSession(auth.token)
    sessions.value = [created, ...sessions.value.filter((item) => item.sessionId !== created.sessionId)]
      .slice(0, MAX_LOCAL_SESSIONS)
    active.value = { session: created, history: [] }
    currentRun.value = null
    question.value = ''
    persistLocalIndex()
  } catch (cause) {
    error.value = friendly(cause)
  } finally {
    busy.value = false
  }
}

async function openSession(sessionId: string) {
  if (busy.value || loading.value) return
  loading.value = true
  error.value = ''
  notice.value = ''
  stopWatchingRun()
  currentRun.value = null
  try {
    const snapshot = await getAgentSession(auth.token, sessionId)
    active.value = snapshot
    persistLocalIndex()
    const runId = snapshot.session.activeRunId ?? localIndex.value.lastRunBySession[sessionId]
    if (runId) await loadRun(runId, true)
  } catch (cause) {
    error.value = friendly(cause)
  } finally {
    loading.value = false
  }
}

async function loadRun(runId: string, watch: boolean) {
  try {
    const run = await getAgentRun(auth.token, runId)
    currentRun.value = run
    if (watch && (run.status === 'ACCEPTED' || run.status === 'RUNNING')) watchRun(runId)
  } catch (cause) {
    if (cause instanceof ApiError && cause.status === 404 && active.value) {
      delete localIndex.value.lastRunBySession[active.value.session.sessionId]
      persistLocalIndex()
      return
    }
    error.value = friendly(cause)
  }
}

function watchRun(runId: string) {
  stopWatchingRun()
  const controller = new AbortController()
  runController = controller
  void streamAgentRun(auth.token, runId, controller.signal, (event) => {
    const eventStatus: Record<string, AgentRun['status']> = {
      'run.started': 'RUNNING', 'run.completed': 'COMPLETED', 'run.failed': 'FAILED',
      'run.cancelled': 'CANCELLED', 'run.interrupted': 'INTERRUPTED',
    }
    const status = eventStatus[event.eventType]
    if (status && currentRun.value?.runId === runId) currentRun.value.status = status
  }).then(async () => {
    if (controller.signal.aborted) return
    await loadRun(runId, false)
    if (currentRun.value && ['COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED'].includes(currentRun.value.status)) {
      await refreshHistory()
    } else {
      error.value = '实时状态连接已结束；点击“刷新状态”可重新连接，不会重复提交问题。'
    }
  }).catch((cause: unknown) => {
    if (!controller.signal.aborted) error.value = friendly(cause)
  }).finally(() => {
    if (runController === controller) runController = null
  })
}

async function refreshHistory() {
  const sessionId = activeId.value
  if (!sessionId) return
  try {
    const snapshot = await getAgentSession(auth.token, sessionId)
    active.value = snapshot
    sessions.value = sessions.value.map((session) => session.sessionId === sessionId
      ? snapshot.session : session)
    persistLocalIndex()
  } catch (cause) {
    error.value = friendly(cause)
  }
}

async function refreshStatus() {
  if (!currentRun.value) return
  error.value = ''
  stopWatchingRun()
  await loadRun(currentRun.value.runId, true)
}

async function sendQuestion() {
  const input = question.value.trim()
  if (!active.value || busy.value || !input) return
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    const accepted = await createAgentRun(auth.token, active.value.session.sessionId, {
      clientMessageId: newMessageId(),
      expectedVersion: active.value.session.version,
      input,
    })
    localIndex.value.lastRunBySession[active.value.session.sessionId] = accepted.runId
    persistLocalIndex()
    question.value = ''
    await loadRun(accepted.runId, true)
  } catch (cause) {
    error.value = friendly(cause)
  } finally {
    busy.value = false
  }
}

async function resumeCurrentRun() {
  const run = currentRun.value
  if (!run || run.status !== 'ACCEPTED' || busy.value) return
  busy.value = true
  error.value = ''
  try {
    await resumeAgentRun(auth.token, run.runId)
    await loadRun(run.runId, true)
  } catch (cause) {
    error.value = friendly(cause)
    if (cause instanceof ApiError && cause.status === 409) await loadRun(run.runId, false)
  } finally {
    busy.value = false
  }
}

async function cancelCurrentRun() {
  const run = currentRun.value
  if (!run || run.status !== 'RUNNING' || run.cancelRequested || busy.value) return
  busy.value = true
  error.value = ''
  stopWatchingRun()
  try {
    currentRun.value = await cancelAgentRun(auth.token, run.runId)
    if (currentRun.value.status === 'RUNNING') watchRun(run.runId)
    else await refreshHistory()
  } catch (cause) {
    error.value = friendly(cause)
  } finally {
    busy.value = false
  }
}

async function removeSession() {
  const sessionId = activeId.value
  if (!sessionId || busy.value) return
  busy.value = true
  error.value = ''
  try {
    const deletionPending = await deleteAgentSession(auth.token, sessionId)
    stopWatchingRun()
    sessions.value = sessions.value.filter((session) => session.sessionId !== sessionId)
    delete localIndex.value.lastRunBySession[sessionId]
    active.value = null
    currentRun.value = null
    question.value = ''
    persistLocalIndex()
    notice.value = deletionPending
      ? '会话已从此列表隐藏；服务端正在安全停止任务并清理数据。'
      : '会话及其历史已删除。'
  } catch (cause) {
    error.value = friendly(cause)
  } finally {
    busy.value = false
  }
}

function friendly(cause: unknown) {
  return cause instanceof Error ? cause.message : '助手暂时无法响应。'
}

function newMessageId() {
  return typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : `web-${Date.now()}-${Math.random().toString(36).slice(2)}`
}

function statusClass(status: AgentRun['status']) {
  return `run-status status-${status.toLowerCase()}`
}

function friendlyRunFailure(code: string | null) {
  if (code === 'knowledge_unavailable') return '知识检索暂不可用；本轮没有退回到无来源回答。'
  if (code === 'knowledge_evidence_changed') return '回答期间知识资料已更新或撤回；本轮没有保存旧资料生成的答案。'
  return `安全错误码：${code || '未知'}。修正问题或稍后重试会创建新一轮。`
}

function shortHash(hash: string) {
  return hash.slice(0, 12)
}

onMounted(() => { void initialize() })
onBeforeUnmount(stopWatchingRun)
</script>

<template>
  <section class="ops-page assistant-page">
    <div class="ops-heading">
      <div>
        <div class="eyebrow"><span class="eyebrow-line"></span> OPERATOR WORKSPACE / 02</div>
        <h1>活动助手<span class="heading-period">.</span></h1>
        <p>Python Harness 驱动的只读运营助手；查询仍由服务端逐次授权。</p>
      </div>
      <span class="model-badge" :class="{ 'external-model': readiness?.model_provider === 'openai-compatible' }">
        <i></i>{{ modelLabel }}
      </span>
    </div>

    <div class="fake-warning" :class="{ 'external-warning': readiness?.model_provider === 'openai-compatible' }">
      <span>✳</span>
      <div>
        <b>{{ readiness?.model_provider === 'openai-compatible' ? '外部模型已配置' : 'Harness 边界说明' }}</b>
        <p v-if="readiness?.model_provider === 'fake'">Fake Model 仅用于验证控制流程，不代表真实模型回答质量；会话与运行由 Python 服务持久化。助手只能查询授权活动，不能抽奖、修改配置或扣库存。</p>
        <p v-else-if="readiness?.model_provider === 'openai-compatible'">本机配置了兼容模型，提交问题可能产生供应商费用；问题只在用户主动提交后发送。助手仍只能查询授权活动，不能抽奖、修改配置或扣库存。</p>
        <p v-else>模型配置状态暂不可读取；请勿据此推断当前使用 Fake 还是外部模型。助手只允许查询，不会抽奖、修改配置或扣库存。</p>
        <p v-if="readiness && !readiness.knowledge_retrieval_enabled" class="knowledge-status-note">资料检索当前未启用；助手回答文档类问题时不会假装已经查阅知识库。</p>
        <p v-else-if="readiness?.knowledge_retrieval_enabled" class="knowledge-status-note">资料检索已配置；每轮来源会在下方列出，且只显示服务端授权的来源信息。</p>
      </div>
    </div>

    <div v-if="error" class="notice error-notice" role="alert">
      <span>!</span><div>{{ error }}</div>
      <button v-if="currentRun" class="text-button" @click="refreshStatus">刷新状态 ↻</button>
    </div>
    <div v-if="notice" class="notice assistant-notice" role="status"><span>✓</span><div>{{ notice }}</div></div>

    <div class="assistant-layout">
      <aside class="session-rail">
        <div class="panel-heading">
          <div><span class="tiny-label">YOUR SESSIONS</span><h2>会话</h2></div>
          <button class="icon-button" aria-label="新建会话" :disabled="busy" @click="createSession">＋</button>
        </div>
        <button class="button primary wide new-session" :disabled="busy" @click="createSession">＋ 新建对话</button>
        <div v-if="sessions.length" class="session-list">
          <button
            v-for="(session, index) in sessions"
            :key="session.sessionId"
            class="session-item"
            :class="{ selected: activeId === session.sessionId }"
            :disabled="loading"
            @click="openSession(session.sessionId)"
          >
            <span class="session-index">{{ String(index + 1).padStart(2, '0') }}</span>
            <span><b>活动咨询</b><small>版本 {{ session.version ?? '—' }}</small></span>
            <span class="session-arrow">↗</span>
          </button>
        </div>
        <div v-else class="session-empty">还没有会话。新建后，历史将保存在 Agent 服务中。</div>
        <div class="session-rail-note">此浏览器仅保存最近 {{ MAX_LOCAL_SESSIONS }} 个会话 ID；问题、答案和运行状态以服务端为准。</div>
      </aside>

      <section class="chat-workspace">
        <div class="chat-header">
          <div>
            <span class="tiny-label">READ-ONLY PYTHON HARNESS</span>
            <h2>{{ active ? `活动咨询 · ${active.session.sessionId.slice(0, 8)}` : '新的活动咨询' }}</h2>
          </div>
          <div class="chat-header-actions">
            <span v-if="currentRun" :class="statusClass(currentRun.status)" role="status">{{ currentStatusLabel }}</span>
            <button v-if="currentRun" class="text-button" :disabled="busy" @click="refreshStatus">刷新状态 ↻</button>
            <button v-if="active" class="text-button delete-session" :disabled="busy" @click="removeSession">删除会话 ×</button>
          </div>
        </div>

        <div class="chat-scroll" aria-live="polite" aria-relevant="additions text">
          <div v-if="!active && !loading" class="assistant-welcome">
            <div class="assistant-glyph">✳</div>
            <span class="tiny-label">A SMALL, CONTROLLED HARNESS</span>
            <h3>先从一场活动<br />开始提问。</h3>
            <p>创建会话后，可询问活动状态、时间或奖项。若使用真实模型，请在问题里说明活动编号。</p>
            <button class="button secondary" :disabled="busy" @click="createSession">创建第一个会话 <span>↗</span></button>
          </div>
          <div v-if="loading" class="loading-line"><span class="spinner"></span> 正在读取服务端会话…</div>
          <article
            v-for="message in conversation"
            :key="message.key"
            class="chat-event"
            :class="`event-${message.kind.toLowerCase()}`"
          >
            <span class="event-kind">{{ message.kind === 'USER' ? '你的问题' : '助手回复' }}</span>
            <p>{{ message.content }}</p>
          </article>
          <section
            v-if="currentRun?.status === 'COMPLETED' && currentRun.knowledgeCitations.length"
            class="knowledge-sources"
            aria-label="本轮检索到的资料"
          >
            <div class="knowledge-sources-heading">
              <span class="knowledge-source-mark" aria-hidden="true">⌁</span>
              <div>
                <h3>本轮检索到的资料</h3>
                <p>仅展示来源线索，不含原文；这不代表每条资料都被回答直接引用。</p>
              </div>
            </div>
            <ul>
              <li v-for="citation in currentRun.knowledgeCitations" :key="citation.citationId">
                <span class="knowledge-scope">{{ citation.scope === 'OPERATOR' ? '运营资料' : '公开资料' }}</span>
                <span class="knowledge-source-copy"><b>{{ citation.title }}</b><small>{{ citation.section || '未标注章节' }}</small></span>
                <code :title="citation.documentHash">版本 {{ shortHash(citation.documentHash) }}</code>
              </li>
            </ul>
          </section>
          <div v-if="currentRun && ['ACCEPTED', 'RUNNING'].includes(currentRun.status)" class="run-progress" role="status">
            <span class="spinner"></span>
            <div><b>{{ currentRun.status === 'ACCEPTED' ? '任务已受理，等待执行' : '任务正在执行' }}</b><p>刷新或重连只读取运行状态，不会再次提交或调用模型。</p></div>
          </div>
          <div v-if="currentRun?.status === 'INTERRUPTED'" class="run-warning" role="status">
            <b>本轮已中断，没有自动重跑。</b>
            <p>模型调用可能已经开始或产生费用。如需继续，请检查上方问题后重新发送；这会创建一轮新任务。</p>
          </div>
          <div v-if="currentRun?.status === 'FAILED'" class="run-warning" role="alert">
            <b>本轮未能完成。</b><p>{{ friendlyRunFailure(currentRun.safeErrorCode) }}</p>
          </div>
          <div v-if="currentRun?.status === 'CANCELLED'" class="run-warning" role="status">
            <b>本轮已停止。</b><p>已停止后续 Harness 步骤；上游模型是否处理了此前请求取决于停止时机。</p>
          </div>
          <div v-if="!active && loading" class="loading-line"><span class="spinner"></span> 正在恢复会话…</div>
        </div>

        <div v-if="currentRun?.status === 'ACCEPTED'" class="run-actions">
          <span>原任务已持久化受理；继续操作只会尝试执行同一个 run。</span>
          <button class="button secondary" :disabled="busy" @click="resumeCurrentRun">{{ busy ? '正在连接…' : '继续执行原任务 ↗' }}</button>
        </div>
        <div v-else-if="currentRun?.status === 'RUNNING'" class="run-actions">
          <span>{{ currentRun.cancelRequested ? '正在等待安全停止。' : '任务在后台运行；可停止后续步骤。' }}</span>
          <button class="text-button stop-run" :disabled="busy || currentRun.cancelRequested" @click="cancelCurrentRun">{{ currentRun.cancelRequested ? '正在停止…' : '停止本轮 ×' }}</button>
        </div>

        <form class="chat-composer" @submit.prevent="sendQuestion">
          <label class="question-field">你的问题
            <textarea
              v-model="question"
              maxlength="6000"
              rows="2"
              placeholder="例如：请查询活动 1005 的当前状态和奖项。"
              :disabled="busy || !active || currentRun?.status === 'ACCEPTED' || currentRun?.status === 'RUNNING'"
            />
          </label>
          <button class="button primary send-button" type="submit" :disabled="busy || !active || !question.trim() || currentRun?.status === 'ACCEPTED' || currentRun?.status === 'RUNNING'">
            <span>{{ busy ? '处理中…' : '发送' }}</span><span>↗</span>
          </button>
          <small class="composer-footnote">只读工具 · 明确提交才会调用模型 · 中断任务不自动重跑</small>
        </form>
      </section>
    </div>
  </section>
</template>
