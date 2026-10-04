<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { campaignRequest, jsonBody } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import type { CampaignConfig, CursorPage, PendingCampaignReview } from '@/types'

const auth = useAuthStore()
const items = ref<PendingCampaignReview[]>([])
const cursor = ref<number | null>(null)
const selected = ref<PendingCampaignReview | null>(null)
const configuration = ref<CampaignConfig | null>(null)
const comment = ref('')
const busy = ref(true)
const acting = ref(false)
const error = ref('')
const notice = ref('')

async function load() {
  busy.value = true
  error.value = ''
  try {
    const page = await campaignRequest<CursorPage<PendingCampaignReview>>('/ops/reviews/campaigns?limit=50', auth.token)
    items.value = page.items
    cursor.value = page.nextCursor
    if (selected.value && !items.value.some((item) => item.id === selected.value?.id)) {
      selected.value = null
      configuration.value = null
    }
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '审核队列暂时无法加载。'
  } finally {
    busy.value = false
  }
}

async function select(item: PendingCampaignReview) {
  selected.value = item
  comment.value = ''
  error.value = ''
  configuration.value = null
  try {
    configuration.value = await campaignRequest<CampaignConfig>(`/ops/campaigns/${item.id}/configuration`, auth.token)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '活动配置暂时无法读取。'
  }
}

async function decide(action: 'approve' | 'reject') {
  if (!selected.value || acting.value) return
  if (action === 'reject' && comment.value.trim().length < 3) { error.value = '驳回理由请填写至少 3 个字符的明确修改意见。'; return }
  acting.value = true
  error.value = ''
  notice.value = ''
  try {
    await campaignRequest(`/ops/reviews/campaigns/${selected.value.id}/${action}`, auth.token, {
      method: 'POST', body: jsonBody({ comment: comment.value.trim() }),
    })
    notice.value = action === 'approve' ? '审核通过，活动已公开，配置已经冻结。' : '已驳回并将修改意见发送给创建者。'
    selected.value = null
    configuration.value = null
    await load()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '审核操作没有完成，请刷新队列。'
  } finally {
    acting.value = false
  }
}

onMounted(() => load())
</script>

<template>
  <section class="review-page">
    <header class="ops-heading"><div><div class="eyebrow"><span class="eyebrow-line"></span> TRUST & SAFETY / REVIEW DESK</div><h1>让好活动，<em>安心上线。</em></h1><p>逐项查看活动事实与概率配置。通过会冻结奖项并公开；驳回必须说明修改理由。</p></div><div class="review-queue-stamp"><b>{{ items.length.toString().padStart(2, '0') }}</b><span>待审核</span></div></header>
    <div v-if="notice" class="notice success-notice" role="status"><span>✓</span><div>{{ notice }}</div></div>
    <div v-if="error" class="notice error-notice" role="alert"><span>!</span><div><b>操作没有完成</b><p>{{ error }}</p></div><button class="text-button" @click="load">刷新 ↗</button></div>
    <div class="review-workspace">
      <section class="review-queue"><div class="panel-heading"><div><span class="tiny-label">PENDING REVIEW</span><h2>审核队列</h2></div><button class="text-button" :disabled="busy" @click="load">刷新 ↻</button></div>
        <div v-if="busy && !items.length" class="loading-line"><span class="spinner"></span> 正在读取队列…</div>
        <div v-else-if="!items.length" class="review-empty"><span>✓</span><b>当前没有待审核活动</b><p>新提交的活动会出现在这里。</p></div>
        <button v-for="item in items" :key="item.id" class="review-row" :class="{ selected: selected?.id === item.id }" @click="select(item)">
          <span class="review-row-index">{{ String(item.id).slice(-4) }}</span><span class="review-row-copy"><b>{{ item.name }}</b><small>{{ item.ownerUsername }} · {{ new Date(item.submittedAt).toLocaleString('zh-CN') }}</small></span><span class="row-arrow">↗</span>
        </button>
      </section>
      <section class="review-detail">
        <template v-if="selected">
          <div class="review-detail-head"><div><span class="tiny-label">SUBMISSION #{{ selected.id }}</span><h2>{{ selected.name }}</h2><p>创建者 {{ selected.ownerUsername }} · {{ new Date(selected.submittedAt).toLocaleString('zh-CN') }} 提交</p></div><span class="state-pill state-pending_review">审核中</span></div>
          <div class="review-facts"><div><span>活动开始</span><b>{{ new Date(selected.startsAt).toLocaleString('zh-CN') }}</b></div><div><span>活动结束</span><b>{{ new Date(selected.endsAt).toLocaleString('zh-CN') }}</b></div></div>
          <div class="review-prizes"><div class="tiny-label">PRIZE & PROBABILITY</div><div v-if="!configuration" class="loading-line">正在读取配置…</div><div v-else-if="!configuration.prizes.length" class="inline-empty">没有奖品配置，不能通过。</div><div v-for="prize in configuration?.prizes ?? []" :key="prize.id" class="review-prize-row"><span>{{ prize.name }}</span><b>{{ (prize.probability * 100).toFixed(2) }}%</b><small>配置库存 {{ prize.totalStock }}</small></div><p v-if="configuration" class="field-hint">每日上限 {{ configuration.dailyLimit }} 次 · 概率余量表示未中奖</p></div>
          <label class="review-comment">审核意见<textarea v-model="comment" maxlength="500" rows="3" placeholder="驳回时写清需要修改的内容；通过时可以留空。" /></label>
          <div class="review-actions"><button class="button secondary" :disabled="acting || !configuration" @click="decide('reject')">{{ acting ? '处理中…' : '驳回并反馈' }}</button><button class="button primary" :disabled="acting || !configuration?.prizes.length" @click="decide('approve')">{{ acting ? '处理中…' : '审核通过并公开 ↗' }}</button></div>
          <p class="review-transaction-note">通过将与概率校验、库存初始化、活动发布和审核记录在同一数据库事务内完成。</p>
        </template>
        <div v-else class="review-prompt"><span>01 / SELECT</span><h2>先选一项提交</h2><p>队列只显示处于“审核中”的用户活动。选择后可检查时间、奖项、概率与库存。</p><div class="review-prompt-line"></div></div>
      </section>
    </div>
  </section>
</template>
