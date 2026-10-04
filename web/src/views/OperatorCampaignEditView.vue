<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ApiError, campaignRequest, jsonBody } from '@/api/client'
import { validateCampaignConfig } from '@/ops/configValidation'
import { useAuthStore } from '@/stores/auth'
import type { Campaign, CampaignConfig, PrizeConfig } from '@/types'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const campaign = ref<Campaign | null>(null)
const dailyLimit = ref(3)
const prizes = ref<PrizeConfig[]>([])
const busy = ref(true)
const saving = ref(false)
const error = ref('')
const notice = ref('')
const savedSnapshot = ref('')
const readOnly = computed(() => campaign.value?.status !== 'DRAFT')
const currentSnapshot = () => JSON.stringify({ dailyLimit: dailyLimit.value, prizes: prizes.value })
const dirty = computed(() => currentSnapshot() !== savedSnapshot.value)
const statusName: Record<string, string> = {
  DRAFT: '草稿', PENDING_REVIEW: '审核中', PUBLISHED: '已发布', REJECTED: '已驳回', CLOSED: '已关闭',
}

async function load() {
  busy.value = true
  error.value = ''
  try {
    const id = Number(route.params.id)
    if (!Number.isSafeInteger(id) || id <= 0) throw new Error('活动编号无效。')
    campaign.value = await campaignRequest<Campaign>(`/campaigns/${id}`, auth.token)
    try {
      const config = await campaignRequest<CampaignConfig>(`/ops/campaigns/${id}/configuration`, auth.token)
      dailyLimit.value = config.dailyLimit
      prizes.value = config.prizes.map((prize) => ({ ...prize }))
      savedSnapshot.value = currentSnapshot()
    } catch (cause) {
      if (!(cause instanceof ApiError && cause.status === 404)) throw cause
      dailyLimit.value = 3
      prizes.value = []
      savedSnapshot.value = currentSnapshot()
    }
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '活动信息暂时无法加载。'
  } finally {
    busy.value = false
  }
}

function addPrize() {
  const existing = new Set(prizes.value.map((prize) => prize.id))
  let prizeId = 1
  while (existing.has(prizeId)) prizeId += 1
  prizes.value.push({ id: prizeId, name: '', totalStock: 1, probability: 0.1 })
}

function removePrize(index: number) { prizes.value.splice(index, 1) }

async function save() {
  if (!campaign.value || saving.value || readOnly.value) return
  const validationError = validateCampaignConfig(dailyLimit.value, prizes.value)
  if (validationError) { error.value = validationError; return }
  saving.value = true
  error.value = ''
  notice.value = ''
  try {
    await campaignRequest<void>(`/campaigns/${campaign.value.id}/configuration`, auth.token, {
      method: 'POST', body: jsonBody({ dailyLimit: dailyLimit.value, prizes: prizes.value.map(({ id, name, totalStock, probability }) => ({ id, name: name.trim(), totalStock, probability })) }),
    })
    savedSnapshot.value = currentSnapshot()
    notice.value = '配置已保存到草稿。发布后将被冻结。'
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '配置保存失败。'
  } finally {
    saving.value = false
  }
}

async function transition(action: 'publish' | 'close') {
  if (!campaign.value || saving.value) return
  saving.value = true
  error.value = ''
  notice.value = ''
  try {
    campaign.value = await campaignRequest<Campaign>(`/campaigns/${campaign.value.id}/${action}`, auth.token, { method: 'POST' })
    notice.value = action === 'publish' ? '活动已发布，配置现已冻结。' : '活动已关闭。'
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '状态更新失败。'
  } finally {
    saving.value = false
  }
}

async function publish() {
  if (dirty.value) {
    await save()
    if (dirty.value || error.value) return
  }
  await transition('publish')
}

onMounted(() => load())
</script>

<template>
  <section class="ops-page edit-page">
    <RouterLink to="/ops/campaigns" class="back-link">← 返回活动管理</RouterLink>
    <div v-if="busy" class="loading-line"><span class="spinner"></span> 正在读取活动配置…</div>
    <div v-else-if="error && !campaign" class="empty-state compact"><div class="empty-orbit">!</div><h2>活动没有打开</h2><p>{{ error }}</p><button class="button secondary" @click="router.push('/ops/campaigns')">返回列表</button></div>
    <template v-else-if="campaign">
      <div class="edit-heading"><div><div class="eyebrow"><span class="eyebrow-line"></span> CAMPAIGN / {{ campaign.id }}</div><h1>{{ campaign.name }}<span class="heading-period">.</span></h1><p>{{ new Date(campaign.startsAt).toLocaleString('zh-CN') }} — {{ new Date(campaign.endsAt).toLocaleString('zh-CN') }}</p></div><span class="state-pill large" :class="`state-${campaign.status.toLowerCase()}`">{{ statusName[campaign.status] }}</span></div>
      <div v-if="error" class="notice error-notice" role="alert"><span>!</span><div><b>操作没有完成</b><p>{{ error }}</p></div></div>
      <div v-if="notice" class="notice success-notice" role="status"><span>✓</span><div>{{ notice }}</div></div>
      <form class="edit-workspace" @submit.prevent="save">
        <section class="edit-section rules-section"><div class="section-title"><div><div class="eyebrow"><span class="eyebrow-line"></span> PARTICIPATION RULES</div><h2>参与规则</h2></div><span class="step-marker">01</span></div><label class="limit-field">每位用户每日参与上限<input v-model.number="dailyLimit" type="number" min="1" max="10000" :disabled="readOnly" /></label><p class="field-hint">服务端以 MySQL 记录作为最终次数校验。</p></section>
        <section class="edit-section prizes-edit-section"><div class="section-title"><div><div class="eyebrow"><span class="eyebrow-line"></span> PRIZE CONFIGURATION</div><h2>奖品与概率</h2></div><button v-if="!readOnly" class="text-button add-prize" type="button" @click="addPrize">＋ 添加奖品</button></div>
          <div v-if="!prizes.length" class="inline-empty">还没有奖品。至少添加一个奖品后才能保存并发布。</div>
          <div v-for="(prize, index) in prizes" :key="prize.id" class="prize-editor-row"><span class="prize-editor-index">{{ String(index + 1).padStart(2, '0') }}</span><label>奖品编号<input v-model.number="prize.id" type="number" min="1" :disabled="readOnly" /></label><label class="prize-name-input">名称<input v-model="prize.name" maxlength="100" :disabled="readOnly" placeholder="例如：品牌礼盒" /></label><label>总库存<input v-model.number="prize.totalStock" type="number" min="0" :disabled="readOnly" /></label><label>概率 (0–1)<input v-model.number="prize.probability" type="number" min="0" max="1" step="0.000001" :disabled="readOnly" /></label><button v-if="!readOnly" class="remove-prize" type="button" :aria-label="`移除第 ${index + 1} 个奖品`" @click="removePrize(index)">×</button></div>
          <div class="probability-note"><span>i</span><p>各奖品概率之和可以小于 1，剩余部分代表未中奖；超过 1 将被后端拒绝。库存为发布时配置总量，不代表当前余量。</p></div>
        </section>
        <aside class="publish-rail"><div><span class="tiny-label">FINAL CHECK</span><h3>{{ readOnly ? '配置已锁定' : '准备好发布了吗？' }}</h3><p>{{ readOnly ? '已发布配置不可修改。' : dirty ? '当前有未保存内容，发布会先保存再冻结。' : '发布时服务端会再次验证奖品配置，并冻结当前版本。' }}</p></div><div class="publish-actions"><button v-if="!readOnly" class="button secondary" type="submit" :disabled="saving">{{ saving ? '正在保存…' : '保存草稿' }}</button><button v-if="campaign.status === 'DRAFT'" class="button primary" type="button" :disabled="saving || !prizes.length" @click="publish">{{ saving ? '处理中…' : dirty ? '保存并发布 ↗' : '发布活动 ↗' }}</button><button v-if="campaign.status === 'PUBLISHED'" class="button danger" type="button" :disabled="saving" @click="transition('close')">关闭活动</button></div></aside>
      </form>
    </template>
  </section>
</template>
