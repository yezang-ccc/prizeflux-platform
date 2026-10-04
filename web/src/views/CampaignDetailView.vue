<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElAlert, ElButton, ElButtonGroup, ElCard, ElEmpty, ElSkeleton, ElTag } from 'element-plus'
import { useRoute } from 'vue-router'
import { ApiError, campaignRequest, jsonBody } from '@/api/client'
import { usePendingRequestKey } from '@/draw/pendingRequestKey'
import { useAuthStore } from '@/stores/auth'
import type { Campaign, DrawQueueView, DrawResult, PrizeView } from '@/types'

const route = useRoute()
const auth = useAuthStore()
const campaign = ref<Campaign | null>(null)
const prizes = ref<PrizeView[]>([])
const busy = ref(true)
const drawing = ref(false)
const error = ref('')
const drawError = ref('')
const result = ref<DrawResult | null>(null)
const pendingDraw = usePendingRequestKey()
const pendingQueue = usePendingRequestKey()
const queueState = ref<DrawQueueView | null>(null)
const queueError = ref('')
const queueSubmitting = ref(false)
const drawMode = ref<'instant' | 'queue'>('instant')
const now = ref(Date.now())
let clockTimer = 0
let queuePollTimer = 0
let queuePollBusy = false
const isOpen = computed(() => !!campaign.value && campaign.value.status === 'PUBLISHED'
  && now.value >= new Date(campaign.value.startsAt).getTime()
  && now.value < new Date(campaign.value.endsAt).getTime())

async function load() {
  busy.value = true
  error.value = ''
  try {
    const id = Number(route.params.id)
    if (!Number.isSafeInteger(id) || id <= 0) throw new Error('活动编号无效。')
    const [detail, items] = await Promise.all([
      campaignRequest<Campaign>(`/campaigns/${id}`, auth.token),
      campaignRequest<PrizeView[]>(`/campaigns/${id}/prizes`, auth.token),
    ])
    campaign.value = detail
    prizes.value = items
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '活动详情暂时无法加载。'
  } finally {
    busy.value = false
  }
}

async function draw() {
  if (!campaign.value || drawing.value || !isOpen.value) return
  const requestKey = pendingDraw.acquire()
  drawing.value = true
  drawError.value = ''
  result.value = null
  try {
    result.value = await campaignRequest<DrawResult>('/draws', auth.token, {
      method: 'POST', body: jsonBody({ campaignId: campaign.value.id, requestKey }),
    })
    pendingDraw.complete()
  } catch (cause) {
    drawError.value = cause instanceof ApiError
      ? cause.status === 0 || cause.status >= 500 ? `${cause.message} 再试会沿用同一请求编号。` : cause.message
      : '抽奖未完成；重试将沿用同一请求编号。'
    if (cause instanceof ApiError) pendingDraw.fail(cause.status)
  } finally {
    drawing.value = false
  }
}

function queueStorageKey() {
  return `prizeflux.pending-queue.${String(route.params.id)}`
}

function clearQueueKey() {
  pendingQueue.complete()
  sessionStorage.removeItem(queueStorageKey())
}

function isQueueTerminal(status: DrawQueueView['status']) {
  return status === 'COMPLETED' || status === 'REJECTED' || status === 'MANUAL_REVIEW'
}

function isQueueFinal(status: DrawQueueView['status']) {
  return status === 'COMPLETED' || status === 'REJECTED'
}

function acceptQueueState(state: DrawQueueView) {
  queueState.value = state
  if (isQueueFinal(state.status)) clearQueueKey()
}

async function enqueueDraw() {
  if (!campaign.value || queueSubmitting.value || !isOpen.value) return
  const requestKey = pendingQueue.acquire()
  sessionStorage.setItem(queueStorageKey(), requestKey)
  queueSubmitting.value = true
  queueError.value = ''
  try {
    const accepted = await campaignRequest<DrawQueueView>('/draw-queue', auth.token, {
      method: 'POST', body: jsonBody({ campaignId: campaign.value.id, requestKey }),
    })
    acceptQueueState(accepted)
  } catch (cause) {
    queueError.value = cause instanceof ApiError
      ? cause.status === 0 || cause.status >= 500
        ? `${cause.message} 再试会复用同一个请求编号。`
        : cause.message
      : '排队请求未完成；再次提交会沿用同一个请求编号。'
    if (cause instanceof ApiError && cause.status > 0 && cause.status < 500) {
      pendingQueue.fail(cause.status)
      sessionStorage.removeItem(queueStorageKey())
    }
  } finally {
    queueSubmitting.value = false
  }
}

async function pollQueue(force = false) {
  if (queuePollBusy || !queueState.value || (isQueueTerminal(queueState.value.status) && !force)
    || document.visibilityState !== 'visible') return
  queuePollBusy = true
  try {
    const status = await campaignRequest<DrawQueueView>(
      `/me/draw-queue/${encodeURIComponent(queueState.value.requestKey)}`, auth.token,
    )
    acceptQueueState(status)
    queueError.value = ''
  } catch {
    queueError.value = '结果查询暂时未成功，页面会继续尝试；也可以稍后手动刷新。'
  } finally {
    queuePollBusy = false
  }
}

async function restoreQueue() {
  const requestKey = sessionStorage.getItem(queueStorageKey())
  if (!requestKey) return
  pendingQueue.key.value = requestKey
  try {
    const status = await campaignRequest<DrawQueueView>(
      `/me/draw-queue/${encodeURIComponent(requestKey)}`, auth.token,
    )
    drawMode.value = 'queue'
    acceptQueueState(status)
  } catch {
    // If the first POST response was lost, pressing retry submits the same key safely.
    drawMode.value = 'queue'
    queueError.value = '正在恢复上次排队请求；可以安全重试查询或受理。'
  }
}

function queueStatusLabel(status: DrawQueueView['status']) {
  return status === 'QUEUED' ? '已受理，等待开奖' : status === 'PROCESSING'
    ? '正在开奖' : status === 'RETRY_WAIT' ? '系统暂忙，稍后自动重试'
      : status === 'MANUAL_REVIEW' ? '需要人工处理' : status === 'COMPLETED' ? '开奖完成' : '本次未能参与'
}

function resultLabel(value: DrawResult) {
  return value.outcome === 'WON' ? '恭喜你，中奖啦！'
    : value.outcome === 'SOLD_OUT' ? '奖品刚刚售罄' : '这次没有中奖'
}

function onVisibilityChange() {
  if (document.visibilityState === 'visible') void pollQueue()
}

function statusText() {
  if (!campaign.value) return ''
  if (campaign.value.status !== 'PUBLISHED') return '活动暂不可参与'
  if (now.value < new Date(campaign.value.startsAt).getTime()) return '活动即将开始'
  if (now.value >= new Date(campaign.value.endsAt).getTime()) return '活动已结束'
  return '活动进行中'
}

onMounted(() => {
  now.value = Date.now()
  clockTimer = window.setInterval(() => { now.value = Date.now() }, 30_000)
  queuePollTimer = window.setInterval(() => { void pollQueue() }, 2_000)
  document.addEventListener('visibilitychange', onVisibilityChange)
  void load()
  void restoreQueue()
})
onUnmounted(() => {
  window.clearInterval(clockTimer)
  window.clearInterval(queuePollTimer)
  document.removeEventListener('visibilitychange', onVisibilityChange)
})
</script>

<template>
  <section class="product-page campaign-detail-page">
    <RouterLink to="/campaigns" class="product-back-link"><ElButton text>← 返回活动</ElButton></RouterLink>
    <div v-if="busy" class="detail-loading" aria-label="正在加载活动详情"><ElSkeleton :rows="6" animated /></div>
    <ElAlert v-else-if="error" title="活动暂时不可用" :description="error" type="error" show-icon :closable="false" role="alert">
      <template #default><RouterLink to="/campaigns"><ElButton link type="primary">返回活动列表</ElButton></RouterLink></template>
    </ElAlert>
    <template v-else-if="campaign">
      <header class="product-page-heading campaign-detail-heading">
        <div>
          <div class="campaign-detail-status"><ElTag :type="isOpen ? 'success' : 'info'" effect="plain">{{ statusText() }}</ElTag></div>
          <h1>{{ campaign.name }}</h1>
          <p>查看活动时间、奖品概率，并选择即时抽奖或高峰排队。</p>
        </div>
      </header>
      <div class="campaign-detail-layout">
        <div class="campaign-detail-information">
          <ElCard shadow="never" class="campaign-information-card">
            <template #header><strong>活动时间</strong></template>
            <dl class="campaign-date-list">
              <div><dt>开始时间</dt><dd>{{ new Date(campaign.startsAt).toLocaleString('zh-CN') }}</dd></div>
              <div><dt>结束时间</dt><dd>{{ new Date(campaign.endsAt).toLocaleString('zh-CN') }}</dd></div>
            </dl>
          </ElCard>
          <ElCard shadow="never" class="campaign-information-card">
            <template #header><strong>奖池清单</strong><span>{{ prizes.length }} 个奖品</span></template>
            <ElEmpty v-if="!prizes.length" description="奖品信息尚未配置" :image-size="72" />
            <ul v-else class="campaign-prize-list">
              <li v-for="prize in prizes" :key="prize.id">
                <span>{{ prize.name }}</span>
                <ElTag effect="plain">{{ (prize.probability * 100).toLocaleString('zh-CN', { maximumFractionDigits: 2 }) }}% 概率</ElTag>
              </li>
            </ul>
            <p class="campaign-inventory-note">库存由服务端实时判定，页面不展示可变库存。</p>
          </ElCard>
        </div>

        <ElCard shadow="never" class="draw-card">
          <template #header><strong>选择参与方式</strong></template>
          <ElButtonGroup class="draw-mode-switch" aria-label="抽奖参与方式">
            <ElButton native-type="button" :type="drawMode === 'instant' ? 'primary' : 'default'" :aria-pressed="drawMode === 'instant'" @click="drawMode = 'instant'">即时抽奖</ElButton>
            <ElButton native-type="button" :type="drawMode === 'queue' ? 'primary' : 'default'" :aria-pressed="drawMode === 'queue'" @click="drawMode = 'queue'">高峰排队</ElButton>
          </ElButtonGroup>
          <h2>{{ drawMode === 'instant' ? '现在揭晓结果' : '先排队，稍后查结果' }}</h2>
          <p class="draw-description">{{ drawMode === 'instant' ? '服务端完成抽奖后立即返回结果。' : '服务端快速受理，再由 Kafka 消费者异步开奖。排队不代表中奖。' }}</p>
          <ElButton v-if="drawMode === 'instant'" class="draw-button" type="primary" size="large" :loading="drawing" :disabled="!isOpen || drawing || !prizes.length" @click="draw">
            {{ drawing ? '正在揭晓' : pendingDraw.key.value ? '安全重试' : '开始即时抽奖' }}
          </ElButton>
          <ElButton v-else class="draw-button queue-button" type="primary" size="large" :loading="queueSubmitting" :disabled="!isOpen || queueSubmitting || !prizes.length || !!queueState && (!isQueueTerminal(queueState.status) || queueState.status === 'MANUAL_REVIEW')" @click="enqueueDraw">
            {{ queueSubmitting ? '正在受理' : queueState && (!isQueueTerminal(queueState.status) || queueState.status === 'MANUAL_REVIEW') ? queueStatusLabel(queueState.status) : pendingQueue.key.value ? '安全重试排队' : '加入开奖队列' }}
          </ElButton>
          <ElAlert v-if="drawMode === 'instant' && drawError" class="draw-feedback" :title="drawError" type="error" show-icon :closable="false" role="alert" />
          <ElAlert v-if="drawMode === 'queue' && queueError" class="draw-feedback" :title="queueError" type="error" show-icon :closable="false" role="alert" />
          <ElAlert v-if="drawMode === 'instant' && result" class="draw-feedback" :title="resultLabel(result)" :description="`结果已由服务端记录 · #${result.requestId}`" :type="result.outcome === 'WON' ? 'success' : result.outcome === 'SOLD_OUT' ? 'warning' : 'info'" show-icon :closable="false" role="status" />
          <div v-if="drawMode === 'queue' && queueState" class="queue-status-card" role="status" aria-live="polite">
            <div><ElTag :type="queueState.status === 'COMPLETED' ? 'success' : queueState.status === 'REJECTED' ? 'danger' : 'warning'" effect="plain">{{ queueStatusLabel(queueState.status) }}</ElTag>
              <p v-if="queueState.status === 'COMPLETED' && queueState.result">{{ resultLabel(queueState.result) }} · 结果 #{{ queueState.result.requestId }}</p>
              <p v-else-if="queueState.status === 'REJECTED'">业务状态码：{{ queueState.errorCode }}</p>
              <p v-else-if="queueState.status === 'MANUAL_REVIEW'">请求已保留，不会重复开奖；稍后可手动刷新。业务状态码：{{ queueState.errorCode }}</p>
              <p v-else>请求编号 {{ queueState.requestKey }} · 页面可安全离开后再回来查询</p>
            </div>
            <ElButton v-if="!isQueueFinal(queueState.status)" class="text-button" link type="primary" @click="pollQueue(true)">刷新状态</ElButton>
          </div>
          <ElAlert class="draw-trust-note" title="开奖说明" description="最终参与资格、库存扣减和开奖结果均由服务端确认。" type="info" :closable="false" />
        </ElCard>
      </div>
    </template>
  </section>
</template>
