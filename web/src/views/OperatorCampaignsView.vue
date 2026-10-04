<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { campaignRequest } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import type { Campaign, CursorPage } from '@/types'

const auth = useAuthStore()
const items = ref<Campaign[]>([])
const cursor = ref<number | null>(null)
const busy = ref(true)
const error = ref('')

async function load(more = false) {
  if (more && busy.value) return
  busy.value = true
  error.value = ''
  try {
    const query = new URLSearchParams({ limit: '20' })
    if (more && cursor.value) query.set('before', String(cursor.value))
    const page = await campaignRequest<CursorPage<Campaign>>(`/ops/campaigns?${query}`, auth.token)
    items.value = more ? [...items.value, ...page.items] : page.items
    cursor.value = page.nextCursor
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '活动暂时无法加载。'
  } finally {
    busy.value = false
  }
}

const statusName: Record<string, string> = {
  DRAFT: '内部草稿', PENDING_REVIEW: '审核中', PUBLISHED: '已发布', REJECTED: '已驳回', CLOSED: '已关闭',
}
onMounted(() => load())
</script>

<template>
  <section class="ops-page">
    <div class="ops-heading"><div><div class="eyebrow"><span class="eyebrow-line"></span> OPERATOR WORKSPACE / 02</div><h1>活动管理<span class="heading-period">.</span></h1><p>查看已创建活动与发布状态；个人用户创建的内容先经审核工作台审批。</p></div><div class="ops-heading-stamp"><span>WORKSPACE</span><b>PF—02</b><small>CAMPAIGN OPS</small></div></div>
    <div v-if="error" class="notice error-notice" role="alert"><span>!</span><div><b>操作没有完成</b><p>{{ error }}</p></div><button class="text-button" @click="load()">刷新 ↗</button></div>
    <div class="ops-layout operator-register-layout">
      <section class="ops-table-panel"><div class="panel-heading"><div><span class="tiny-label">CAMPAIGN REGISTER</span><h2>所有活动</h2></div><span class="soft-count">{{ items.length }} 条</span></div>
        <div v-if="busy && !items.length" class="loading-line"><span class="spinner"></span> 正在读取活动…</div>
        <div v-else-if="!items.length" class="empty-ops"><span>✳</span><b>还没有活动</b><p>用户提交的活动通过审核后会出现在这里。</p></div>
        <div v-else class="ops-campaign-list"><RouterLink v-for="campaign in items" :key="campaign.id" :to="`/ops/campaigns/${campaign.id}`" class="ops-campaign-row"><span class="ops-row-symbol">{{ campaign.status === 'PUBLISHED' ? '✦' : '◦' }}</span><span class="ops-row-main"><b>{{ campaign.name }}</b><small>#{{ campaign.id }} · {{ new Date(campaign.startsAt).toLocaleDateString('zh-CN') }}</small></span><span class="state-pill" :class="`state-${campaign.status.toLowerCase()}`">{{ statusName[campaign.status] }}</span><span class="row-arrow">↗</span></RouterLink></div>
        <button v-if="cursor" class="load-history" :disabled="busy" @click="load(true)">查看更多 ↓</button>
      </section>
    </div>
    <div class="ops-boundary-note"><span>!</span>普通用户创建活动并提交审核；运营只负责审核和全局管理。发布后配置冻结，抽奖资格和库存由服务端校验。</div>
  </section>
</template>
