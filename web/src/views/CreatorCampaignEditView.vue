<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  cancelAgentRun,
  createAgentRun,
  createAgentSession,
  getAgentRun,
  getAgentSession,
  resumeAgentRun,
  streamAgentRun,
  type AgentCreatorDraft,
  type AgentRun,
  type AgentRunRequest,
  type AgentSession,
} from '@/api/agent'
import { ApiError, campaignRequest, jsonBody } from '@/api/client'
import { validateCampaignConfig } from '@/ops/configValidation'
import { useAuthStore } from '@/stores/auth'
import type { CampaignConfig, OwnedCampaign, PrizeConfig } from '@/types'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const campaign = ref<OwnedCampaign | null>(null)
const name = ref('')
const startsAt = ref('')
const endsAt = ref('')
const dailyLimit = ref(3)
const prizes = ref<PrizeConfig[]>([])
const busy = ref(true)
const saving = ref(false)
const submitting = ref(false)
const checking = ref(false)
const error = ref('')
const notice = ref('')
const creatorRun = ref<AgentRun | null>(null)
const creatorSession = ref<AgentSession | null>(null)
const reportDraftSnapshot = ref<string | null>(null)
const reviewQuestion = ref('请指出需要优先处理的问题，并说明 Java 规则校验结果。')
const savedSnapshot = ref('')
let runController: AbortController | null = null
const editable = computed(() => campaign.value?.status === 'DRAFT' || campaign.value?.status === 'REJECTED')
const snapshot = () => JSON.stringify({ name: name.value, startsAt: startsAt.value, endsAt: endsAt.value,
  dailyLimit: dailyLimit.value, prizes: prizes.value })
const dirty = computed(() => snapshot() !== savedSnapshot.value)
const reportStale = computed(() => creatorRun.value?.creatorReport !== null
  && creatorRun.value?.creatorReport !== undefined
  && (reportDraftSnapshot.value === null || reportDraftSnapshot.value !== snapshot()))
const creatorRunActive = computed(() => creatorRun.value?.status === 'ACCEPTED'
  || creatorRun.value?.status === 'RUNNING')
const hasCreatorEvidence = computed(() => creatorRun.value?.creatorReport?.suggestions
  .some((suggestion) => suggestion.evidenceRefs.length > 0) ?? false)
const creatorRunStatusLabel = computed(() => {
  const labels: Record<string, string> = {
    ACCEPTED: '等待执行', RUNNING: '检查中', COMPLETED: '检查完成',
    FAILED: '检查失败', CANCELLED: '已取消', INTERRUPTED: '已中断',
  }
  return creatorRun.value ? labels[creatorRun.value.status] ?? creatorRun.value.status : ''
})
const labels: Record<string, string> = {
  DRAFT: '草稿 · 可编辑', PENDING_REVIEW: '审核中 · 已锁定',
  PUBLISHED: '已发布', REJECTED: '待修改', CLOSED: '已关闭',
}

function toLocal(value: string) {
  const date = new Date(value)
  return new Date(date.getTime() - date.getTimezoneOffset() * 60_000).toISOString().slice(0, 16)
}
function toIso(value: string) { return new Date(value).toISOString() }

async function load() {
  busy.value = true
  error.value = ''
  try {
    const id = Number(route.params.id)
    if (!Number.isSafeInteger(id) || id <= 0) throw new Error('活动编号无效。')
    campaign.value = await campaignRequest<OwnedCampaign>(`/me/campaigns/${id}`, auth.token)
    name.value = campaign.value.name
    startsAt.value = toLocal(campaign.value.startsAt)
    endsAt.value = toLocal(campaign.value.endsAt)
    try {
      const configuration = await campaignRequest<CampaignConfig>(`/me/campaigns/${id}/configuration`, auth.token)
      dailyLimit.value = configuration.dailyLimit
      prizes.value = configuration.prizes.map((prize) => ({ ...prize }))
    } catch (cause) {
      if (!(cause instanceof ApiError && cause.status === 404)) throw cause
      dailyLimit.value = 3
      prizes.value = []
    }
    savedSnapshot.value = snapshot()
    await restoreCreatorRun(id)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '活动暂时无法打开。'
  } finally {
    busy.value = false
  }
}

function addPrize() {
  const existing = new Set(prizes.value.map((prize) => prize.id))
  let id = 1
  while (existing.has(id)) id += 1
  prizes.value.push({ id, name: '', totalStock: 1, probability: 0.1 })
}
function removePrize(index: number) { prizes.value.splice(index, 1) }

function creatorRunStorageKey(campaignId: number) { return `prizeflux.creator.run.${campaignId}` }

interface StoredCreatorRun {
  sessionId: string
  runId: string | null
  draftSnapshot: string
  pendingRequest?: AgentRunRequest
}

function readStoredCreatorRun(campaignId: number): StoredCreatorRun | null {
  try {
    const parsed = JSON.parse(sessionStorage.getItem(creatorRunStorageKey(campaignId)) ?? 'null') as
      Partial<StoredCreatorRun> | null
    if (typeof parsed?.sessionId !== 'string'
      || (typeof parsed.runId !== 'string' && parsed.runId !== null)
      || typeof parsed.draftSnapshot !== 'string') return null
    return {
      sessionId: parsed.sessionId,
      runId: parsed.runId,
      draftSnapshot: parsed.draftSnapshot,
      ...(parsed.pendingRequest && typeof parsed.pendingRequest === 'object'
        ? { pendingRequest: parsed.pendingRequest as AgentRunRequest } : {}),
    }
  } catch { return null }
}

function persistCreatorRun(campaignId: number, value: StoredCreatorRun) {
  try { sessionStorage.setItem(creatorRunStorageKey(campaignId), JSON.stringify(value)) }
  catch { notice.value = '浏览器无法暂存本轮 ID；服务端任务不受影响，但刷新页面后需手动恢复。' }
}

async function restoreCreatorRun(campaignId: number) {
  const stored = readStoredCreatorRun(campaignId)
  if (!stored) return
  try {
    const session = await getAgentSession(auth.token, stored.sessionId)
    creatorSession.value = session.session
    if (!stored.runId) {
      notice.value = '上次提交结果未确认；再次检查会沿用同一请求标识，避免重复创建任务。'
      return
    }
    const run = await getAgentRun(auth.token, stored.runId)
    if (run.sessionId !== stored.sessionId) throw new Error('creator_run_session_mismatch')
    creatorRun.value = run
    reportDraftSnapshot.value = stored.draftSnapshot
    if (run.status === 'RUNNING') watchCreatorRun(run.runId)
  } catch (cause) {
    if (cause instanceof ApiError && cause.status === 404) {
      sessionStorage.removeItem(creatorRunStorageKey(campaignId))
      return
    }
    error.value = cause instanceof Error ? cause.message : '无法恢复上次草案检查状态。'
  }
}

async function ensureCreatorSession(campaignId: number): Promise<AgentSession> {
  if (creatorSession.value) return creatorSession.value
  const stored = readStoredCreatorRun(campaignId)
  if (stored) {
    try {
      const restored = await getAgentSession(auth.token, stored.sessionId)
      creatorSession.value = restored.session
      return restored.session
    } catch (cause) {
      if (!(cause instanceof ApiError && cause.status === 404)) throw cause
    }
  }
  const created = await createAgentSession(auth.token)
  creatorSession.value = created
  return created
}

function newClientMessageId() {
  return typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : `creator-${Date.now()}-${Math.random().toString(36).slice(2)}`
}

function stopWatchingCreatorRun() {
  runController?.abort()
  runController = null
}

async function loadCreatorRun(runId: string, watch: boolean) {
  const run = await getAgentRun(auth.token, runId)
  creatorRun.value = run
  if (watch && (run.status === 'ACCEPTED' || run.status === 'RUNNING')) watchCreatorRun(runId)
}

function watchCreatorRun(runId: string) {
  stopWatchingCreatorRun()
  const controller = new AbortController()
  runController = controller
  void streamAgentRun(auth.token, runId, controller.signal, (event) => {
    if (creatorRun.value?.runId !== runId) return
    const states: Record<string, AgentRun['status']> = {
      'run.started': 'RUNNING', 'run.completed': 'COMPLETED', 'run.failed': 'FAILED',
      'run.cancelled': 'CANCELLED', 'run.interrupted': 'INTERRUPTED',
    }
    const status = states[event.eventType]
    if (status) creatorRun.value.status = status
  }).then(async () => {
    if (!controller.signal.aborted) await loadCreatorRun(runId, false)
  }).catch((cause: unknown) => {
    if (!controller.signal.aborted) error.value = cause instanceof Error ? cause.message : '实时状态连接中断，可刷新状态。'
  }).finally(() => {
    if (runController === controller) runController = null
  })
}

async function checkCreatorDraft() {
  if (!campaign.value || !editable.value || checking.value || creatorRunActive.value) return
  error.value = ''
  notice.value = ''
  const localSnapshot = snapshot()
  let draft: AgentCreatorDraft
  try {
    if (!startsAt.value || !endsAt.value || !Number.isFinite(new Date(startsAt.value).getTime())
      || !Number.isFinite(new Date(endsAt.value).getTime())) throw new Error('请先填写有效的活动开始和结束时间。')
    draft = {
      name: name.value.trim(), startsAt: toIso(startsAt.value), endsAt: toIso(endsAt.value),
      dailyLimit: dailyLimit.value,
      prizes: prizes.value.map(({ id, name: prizeName, totalStock, probability }) => ({
        id, name: prizeName.trim(), totalStock, probability,
      })),
    }
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '草案时间格式无效。'
    return
  }
  checking.value = true
  stopWatchingCreatorRun()
  try {
    const session = await ensureCreatorSession(campaign.value.id)
    const current = await getAgentSession(auth.token, session.sessionId)
    creatorSession.value = current.session
    const input = reviewQuestion.value.trim() || '请检查当前活动草案。'
    const stored = readStoredCreatorRun(campaign.value.id)
    const reuse = stored?.pendingRequest !== undefined
      && stored.draftSnapshot === localSnapshot
      && stored.pendingRequest.input === input
    const request: AgentRunRequest = reuse
      ? stored.pendingRequest as AgentRunRequest
      : {
          clientMessageId: newClientMessageId(), expectedVersion: current.session.version, input,
          mode: 'CREATOR_DRAFT_REVIEW', context: { schemaVersion: 1, draft },
        }
    persistCreatorRun(campaign.value.id, {
      sessionId: session.sessionId, runId: null, draftSnapshot: localSnapshot,
      pendingRequest: request,
    })
    const accepted = await createAgentRun(auth.token, session.sessionId, request)
    reportDraftSnapshot.value = localSnapshot
    persistCreatorRun(campaign.value.id, {
      sessionId: session.sessionId, runId: accepted.runId, draftSnapshot: localSnapshot,
    })
    await loadCreatorRun(accepted.runId, true)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Agent 草案检查没有完成。'
    if (cause instanceof ApiError && cause.status === 409 && cause.code === 'session_version_conflict'
      && campaign.value) {
      sessionStorage.removeItem(creatorRunStorageKey(campaign.value.id))
      try {
        const sessionId = creatorSession.value?.sessionId
        if (sessionId) creatorSession.value = (await getAgentSession(auth.token, sessionId)).session
        notice.value = '会话版本已变化，已刷新会话状态；请重新检查当前草案。'
      } catch { /* Keep the original safe conflict message. */ }
    } else if (campaign.value && readStoredCreatorRun(campaign.value.id)?.runId === null) {
      notice.value = '本次提交结果未确认；再次检查会沿用同一请求标识，不会重复创建任务。'
    }
  } finally {
    checking.value = false
  }
}

async function refreshCreatorStatus() {
  if (!creatorRun.value) return
  error.value = ''
  stopWatchingCreatorRun()
  try { await loadCreatorRun(creatorRun.value.runId, true) }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '无法读取本轮状态。' }
}

async function resumeCreatorRun() {
  if (!creatorRun.value || creatorRun.value.status !== 'ACCEPTED' || checking.value) return
  checking.value = true
  error.value = ''
  try {
    await resumeAgentRun(auth.token, creatorRun.value.runId)
    await loadCreatorRun(creatorRun.value.runId, true)
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '无法继续原有检查任务。' }
  finally { checking.value = false }
}

async function cancelCreatorRun() {
  if (!creatorRun.value || creatorRun.value.status !== 'RUNNING' || checking.value) return
  checking.value = true
  error.value = ''
  stopWatchingCreatorRun()
  try {
    creatorRun.value = await cancelAgentRun(auth.token, creatorRun.value.runId)
    if (creatorRun.value.status === 'RUNNING') watchCreatorRun(creatorRun.value.runId)
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '取消请求没有完成。' }
  finally { checking.value = false }
}

function focusFinding(field: string | null) {
  if (!field) return
  const match = /^prizes\[(\d+)\]\.(id|name|totalStock|probability)$/.exec(field)
  const id = match ? `prize-${match[1]}-${match[2]}` : ({
    name: 'campaign-name', startsAt: 'campaign-starts-at',
    endsAt: 'campaign-ends-at', dailyLimit: 'campaign-daily-limit',
  } as Record<string, string>)[field]
  const target = document.getElementById(id ?? '')
  target?.scrollIntoView?.({ behavior: 'smooth', block: 'center' })
  target?.focus({ preventScroll: true })
}

async function save() {
  if (!campaign.value || !editable.value || saving.value) return false
  if (new Date(startsAt.value) >= new Date(endsAt.value)) { error.value = '结束时间必须晚于开始时间。'; return false }
  const validation = validateCampaignConfig(dailyLimit.value, prizes.value)
  if (validation) { error.value = validation; return false }
  saving.value = true
  error.value = ''
  notice.value = ''
  try {
    const id = campaign.value.id
    const updated = await campaignRequest<OwnedCampaign>(`/me/campaigns/${id}`, auth.token, {
      method: 'PUT', body: jsonBody({ name: name.value.trim(), startsAt: toIso(startsAt.value), endsAt: toIso(endsAt.value) }),
    })
    await campaignRequest<void>(`/me/campaigns/${id}/configuration`, auth.token, {
      method: 'PUT', body: jsonBody({ dailyLimit: dailyLimit.value,
        prizes: prizes.value.map(({ id: prizeId, name: prizeName, totalStock, probability }) =>
          ({ id: prizeId, name: prizeName.trim(), totalStock, probability })) }),
    })
    campaign.value = { ...updated, status: 'DRAFT' }
    savedSnapshot.value = snapshot()
    notice.value = '草稿已保存。完成配置后再提交审核。'
    return true
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '草稿保存失败。'
    return false
  } finally {
    saving.value = false
  }
}

async function submit() {
  if (!campaign.value || submitting.value || !editable.value) return
  if ((dirty.value || campaign.value.status === 'REJECTED') && !(await save())) return
  submitting.value = true
  error.value = ''
  notice.value = ''
  try {
    campaign.value = await campaignRequest<OwnedCampaign>(`/me/campaigns/${campaign.value.id}/submit`, auth.token,
      { method: 'POST' })
    savedSnapshot.value = snapshot()
    notice.value = '已提交运营审核，审核期间草稿已锁定。'
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '暂时无法提交审核。'
  } finally {
    submitting.value = false
  }
}

watch(() => route.params.id, (next, previous) => {
  if (next === previous) return
  stopWatchingCreatorRun()
  campaign.value = null
  creatorRun.value = null
  creatorSession.value = null
  reportDraftSnapshot.value = null
  void load()
})
onMounted(() => load())
onBeforeUnmount(stopWatchingCreatorRun)
</script>

<template>
  <section class="ops-page edit-page creator-editor">
    <RouterLink to="/me/campaigns" class="back-link">← 返回我的活动</RouterLink>
    <div v-if="busy" class="loading-line"><span class="spinner"></span> 正在读取草稿…</div>
    <div v-else-if="error && !campaign" class="empty-state compact"><div class="empty-orbit">!</div><h2>活动不可见</h2><p>{{ error }}</p><button class="button secondary" @click="router.push('/me/campaigns')">返回我的活动</button></div>
    <template v-else-if="campaign">
      <div class="edit-heading"><div><div class="eyebrow"><span class="eyebrow-line"></span> 我的活动 / {{ campaign.id }}</div><h1>{{ campaign.name }}<span class="heading-period">.</span></h1><p>{{ new Date(campaign.startsAt).toLocaleString('zh-CN') }} — {{ new Date(campaign.endsAt).toLocaleString('zh-CN') }}</p></div><span class="state-pill large" :class="`state-${campaign.status.toLowerCase()}`">{{ labels[campaign.status] }}</span></div>
      <div v-if="campaign.status === 'REJECTED' && campaign.reviewComment" class="review-feedback"><span>审核反馈</span><p>{{ campaign.reviewComment }}</p><small>修改并保存后可再次提交。</small></div>
      <div v-if="error" class="notice error-notice" role="alert"><span>!</span><div><b>操作没有完成</b><p>{{ error }}</p></div></div>
      <div v-if="notice" class="notice success-notice" role="status"><span>✓</span><div>{{ notice }}</div></div>
      <form class="creator-edit-form" @submit.prevent="save">
        <section class="edit-section creator-basics"><div class="section-title"><div><div class="eyebrow"><span class="eyebrow-line"></span> 活动资料</div><h2>活动信息</h2></div><span class="step-marker">01</span></div>
          <label>活动名称<input id="campaign-name" v-model="name" maxlength="100" :disabled="!editable" /></label>
          <label>开始时间<input id="campaign-starts-at" v-model="startsAt" type="datetime-local" :disabled="!editable" /></label>
          <label>结束时间<input id="campaign-ends-at" v-model="endsAt" type="datetime-local" :disabled="!editable" /></label>
          <p class="field-hint">活动时间按你所在时区显示，提交后由服务端统一存储。</p>
        </section>
        <section class="edit-section prizes-edit-section"><div class="section-title"><div><div class="eyebrow"><span class="eyebrow-line"></span> 奖品配置</div><h2>奖品与概率</h2></div><button v-if="editable" class="text-button add-prize" type="button" @click="addPrize">＋ 添加奖品</button></div>
          <div v-if="!prizes.length" class="inline-empty">至少添加一个奖品才能提交审核。</div>
          <div v-for="(prize, index) in prizes" :key="prize.id" class="prize-editor-row"><span class="prize-editor-index">{{ String(index + 1).padStart(2, '0') }}</span><label>编号<input :id="`prize-${index}-id`" v-model.number="prize.id" type="number" min="1" :disabled="!editable" /></label><label class="prize-name-input">名称<input :id="`prize-${index}-name`" v-model="prize.name" maxlength="100" :disabled="!editable" placeholder="例如：品牌礼盒" /></label><label>总库存<input :id="`prize-${index}-totalStock`" v-model.number="prize.totalStock" type="number" min="0" :disabled="!editable" /></label><label>概率 (0–1)<input :id="`prize-${index}-probability`" v-model.number="prize.probability" type="number" min="0" max="1" step="0.000001" :disabled="!editable" /></label><button v-if="editable" class="remove-prize" type="button" :aria-label="`移除第 ${index + 1} 个奖品`" @click="removePrize(index)">×</button></div>
          <label v-if="editable" class="limit-field">每位用户每日参与上限<input id="campaign-daily-limit" v-model.number="dailyLimit" type="number" min="1" max="10000" /></label>
          <div class="probability-note"><span>i</span><p>概率总和可以小于 1，余量代表未中奖。运营通过时配置与库存将一起冻结。</p></div>
        </section>
        <section v-if="editable || creatorRun?.creatorReport" class="creator-copilot" aria-label="活动草案助手">
          <div class="creator-copilot-copy">
            <span class="tiny-label">草案预检</span>
            <h2>基于当前草案进行预检</h2>
            <p>先由 Java 对本次快照执行权威规则校验，再由受限 Agent 给出非权威解释。检查不会保存、提审或修改草案；真实模型可能产生费用。</p>
          </div>
          <label v-if="editable" class="creator-question">希望助手重点说明
            <textarea v-model="reviewQuestion" maxlength="6000" rows="2" :disabled="checking || creatorRunActive" />
          </label>
          <div v-if="editable || creatorRun" class="creator-run-actions">
            <button v-if="editable" class="button primary" type="button" :disabled="checking || saving || submitting || creatorRunActive" @click="checkCreatorDraft">
              {{ checking ? '正在连接…' : creatorRun?.status === 'COMPLETED' ? '重新检查当前草案' : '检查当前草案' }}
            </button>
            <button v-if="editable && creatorRun?.status === 'ACCEPTED'" class="button secondary" type="button" :disabled="checking" @click="resumeCreatorRun">继续原任务</button>
            <button v-if="editable && creatorRun?.status === 'RUNNING' && !creatorRun.cancelRequested" class="text-button stop-run" type="button" :disabled="checking" @click="cancelCreatorRun">取消本轮</button>
            <button v-if="creatorRun" class="text-button" type="button" :disabled="checking" @click="refreshCreatorStatus">刷新状态</button>
          </div>
          <div v-if="creatorRun" class="creator-run-state" :class="`creator-run-${creatorRun.status.toLowerCase()}`" role="status">
            <b>{{ creatorRunStatusLabel }}</b>
            <span v-if="creatorRun.status === 'ACCEPTED'">任务已持久化受理；继续操作会恢复同一 run。</span>
            <span v-else-if="creatorRun.status === 'RUNNING'">Java 校验与受限建议正在执行；SSE 刷新不会重复提交。</span>
            <span v-else-if="creatorRun.status === 'INTERRUPTED'">任务中断，不会自动重试；模型请求可能已经产生费用。</span>
            <span v-else-if="creatorRun.status === 'FAILED'">安全错误码：{{ creatorRun.safeErrorCode || '未知' }}。没有保存部分报告。</span>
            <span v-else-if="creatorRun.status === 'CANCELLED'">本轮已取消，没有保存部分报告。</span>
            <span v-else-if="creatorRun.status === 'COMPLETED' && creatorRun.creatorReport">本轮报告来自服务端持久化结果。</span>
            <span v-else-if="creatorRun.status === 'COMPLETED'">任务已结束，但报告暂不可读取；请刷新状态或重新检查。</span>
          </div>
          <section v-if="creatorRun?.status === 'COMPLETED' && creatorRun.creatorReport" class="creator-report" aria-label="草案检查报告">
            <div v-if="reportStale" class="creator-report-stale" role="alert">草案已在检查后发生变化；下面的报告已过期，请重新检查。</div>
            <header class="creator-report-header">
              <div><span class="tiny-label">本次检查结果</span><h3>草案检查报告</h3></div>
              <time :datetime="creatorRun.creatorReport.checkedAt">校验时间 {{ new Date(creatorRun.creatorReport.checkedAt).toLocaleString('zh-CN') }}</time>
            </header>
            <p class="creator-report-summary">{{ creatorRun.creatorReport.summary }}</p>
            <section class="creator-findings" aria-label="Java 权威校验结果">
              <h4>Java 权威校验 <span>{{ creatorRun.creatorReport.authoritativeFindings.length }} 项</span></h4>
              <p v-if="!creatorRun.creatorReport.authoritativeFindings.length" class="creator-report-empty">本次 Java 校验未发现规则问题。保存或提审时仍会重新校验。</p>
              <ul v-else>
                <li v-for="(finding, index) in creatorRun.creatorReport.authoritativeFindings" :key="`${finding.code}-${index}`">
                  <code>{{ finding.code }}</code>
                  <button v-if="finding.field" class="creator-field-link" type="button" @click="focusFinding(finding.field)">{{ finding.field }} ↗</button>
                  <span v-else>活动整体</span>
                  <p>{{ finding.message }}</p>
                </li>
              </ul>
            </section>
            <section class="creator-suggestions" aria-label="非权威模型建议">
              <h4>Agent 建议 <span>非权威 · 不自动应用</span></h4>
              <p v-if="!hasCreatorEvidence" class="creator-evidence-note">规则结果来自活动服务；当前建议没有知识资料引用，不能视为外部规则依据。</p>
              <p v-if="!creatorRun.creatorReport.suggestions.length" class="creator-report-empty">本轮没有可用的修改建议。</p>
              <ul v-else>
                <li v-for="(suggestion, index) in creatorRun.creatorReport.suggestions" :key="index">
                  <button v-if="suggestion.field" class="creator-field-link" type="button" @click="focusFinding(suggestion.field)">{{ suggestion.field }} ↗</button>
                  <span>{{ suggestion.kind }}</span><p>{{ suggestion.text }}</p><small>{{ suggestion.rationale }}</small>
                </li>
              </ul>
            </section>
            <section v-if="creatorRun.creatorReport.openQuestions.length" class="creator-open-questions">
              <h4>仍需确认</h4><ul><li v-for="(question, index) in creatorRun.creatorReport.openQuestions" :key="index">{{ question }}</li></ul>
            </section>
            <small class="creator-report-boundary">这是一份对本次草案快照的参考报告，不代表保存、审核通过或发布。任何修改都需要重新检查；Campaign 在保存和提审时重新校验。</small>
          </section>
        </section>
        <aside class="publish-rail"><div><span class="tiny-label">下一步</span><h3>{{ editable ? '完善后提交运营审核' : labels[campaign.status] }}</h3><p>{{ editable ? '审核通过后活动才会出现在公开目录。审核期间不能修改。' : '活动状态由服务端审核流程维护。' }}</p></div><div class="publish-actions"><button v-if="editable" class="button secondary" type="submit" :disabled="saving || submitting || checking">{{ saving ? '正在保存…' : '保存草稿' }}</button><button v-if="editable" class="button primary" type="button" :disabled="saving || submitting || checking || !prizes.length" @click="submit">{{ submitting ? '正在提交…' : dirty ? '保存并提交审核' : '提交审核' }}</button><RouterLink v-else-if="campaign.status === 'PUBLISHED'" class="button secondary" to="/campaigns">查看公开活动</RouterLink></div></aside>
      </form>
    </template>
  </section>
</template>
