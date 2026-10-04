<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { campaignRequest, jsonBody } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import type { CursorPage, OwnedCampaign } from '@/types'

const auth = useAuthStore()
const router = useRouter()
const campaigns = ref<OwnedCampaign[]>([])
const cursor = ref<number | null>(null)
const busy = ref(true)
const creating = ref(false)
const error = ref('')
const name = ref('')
const startsAt = ref(localDateTime(new Date(Date.now() + 60 * 60 * 1000)))
const endsAt = ref(localDateTime(new Date(Date.now() + 7 * 24 * 60 * 60 * 1000)))
const labels: Record<string, string> = {
  DRAFT: '草稿 · 可编辑', PENDING_REVIEW: '审核中 · 已锁定',
  PUBLISHED: '已发布', REJECTED: '待修改', CLOSED: '已关闭',
}

function localDateTime(date: Date) {
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60_000)
  return local.toISOString().slice(0, 16)
}

async function load(more = false) {
  if (more && busy.value) return
  busy.value = true
  error.value = ''
  try {
    const query = new URLSearchParams({ limit: '20' })
    if (more && cursor.value) query.set('before', String(cursor.value))
    const page = await campaignRequest<CursorPage<OwnedCampaign>>(`/me/campaigns?${query}`, auth.token)
    campaigns.value = more ? [...campaigns.value, ...page.items] : page.items
    cursor.value = page.nextCursor
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '暂时无法读取你的活动。'
  } finally {
    busy.value = false
  }
}

async function create() {
  if (!name.value.trim()) { error.value = '先给活动起个名字。'; return }
  if (new Date(startsAt.value) >= new Date(endsAt.value)) { error.value = '结束时间必须晚于开始时间。'; return }
  creating.value = true
  error.value = ''
  try {
    const campaign = await campaignRequest<OwnedCampaign>('/me/campaigns', auth.token, {
      method: 'POST', body: jsonBody({
        name: name.value.trim(), startsAt: new Date(startsAt.value).toISOString(),
        endsAt: new Date(endsAt.value).toISOString(),
      }),
    })
    await router.push(`/me/campaigns/${campaign.id}`)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '活动草稿创建失败。'
  } finally {
    creating.value = false
  }
}

onMounted(() => load())
</script>

<template>
  <section class="creator-page">
    <header class="creator-heading">
      <div><div class="eyebrow"><span class="eyebrow-line"></span> YOUR CAMPAIGNS / 01</div>
        <h1>把好点子，<em>变成活动。</em></h1>
        <p>你可以参与活动，也可以创建自己的草稿。提交后由运营团队审核，审核通过才会对所有人开放。</p>
      </div>
      <div class="creator-heading-index"><span>CREATOR DESK</span><b>USER</b><small>所有权由账号绑定</small></div>
    </header>

    <div v-if="error" class="notice error-notice" role="alert"><span>!</span><div><b>操作没有完成</b><p>{{ error }}</p></div><button class="text-button" @click="load()">重试 ↗</button></div>

    <div class="creator-layout">
      <section class="creator-register">
        <div class="panel-heading"><div><span class="tiny-label">MY CAMPAIGNS</span><h2>我的活动</h2></div><span class="soft-count">{{ campaigns.length }} 条</span></div>
        <div v-if="busy && !campaigns.length" class="loading-line"><span class="spinner"></span> 正在读取…</div>
        <div v-else-if="!campaigns.length" class="creator-empty"><span class="creator-empty-mark">✳</span><b>这里还空着</b><p>从一场小而完整的活动开始。</p></div>
        <div v-else class="creator-list">
          <RouterLink v-for="campaign in campaigns" :key="campaign.id" class="creator-row" :to="`/me/campaigns/${campaign.id}`">
            <span class="creator-row-id">#{{ campaign.id }}</span>
            <span class="creator-row-main"><b>{{ campaign.name }}</b><small>{{ new Date(campaign.startsAt).toLocaleDateString('zh-CN') }} — {{ new Date(campaign.endsAt).toLocaleDateString('zh-CN') }}</small></span>
            <span class="state-pill" :class="`state-${campaign.status.toLowerCase()}`">{{ labels[campaign.status] }}</span><span class="row-arrow">↗</span>
            <span v-if="campaign.status === 'REJECTED' && campaign.reviewComment" class="creator-row-feedback">审核意见：{{ campaign.reviewComment }}</span>
          </RouterLink>
        </div>
        <button v-if="cursor" class="load-history" :disabled="busy" @click="load(true)">查看更多 ↓</button>
      </section>

      <aside class="creator-create">
        <div class="creator-create-top"><span class="tiny-label">START WITH A DRAFT</span><span>✳</span></div>
        <h2>新建活动</h2><p>先确定活动名称和时间，奖品与概率可以在下一步配置。</p>
        <form class="form-stack" @submit.prevent="create">
          <label>活动名称<input v-model="name" maxlength="100" required placeholder="例如：秋日惊喜季" /></label>
          <label>开始时间<input v-model="startsAt" type="datetime-local" required /></label>
          <label>结束时间<input v-model="endsAt" type="datetime-local" required /></label>
          <button class="button primary wide" :disabled="creating"><span>{{ creating ? '正在创建…' : '创建我的草稿' }}</span><span>↗</span></button>
        </form>
        <div class="creator-process"><span>01 创建与配置</span><i></i><span>02 提交审核</span><i></i><span>03 通过后公开</span></div>
      </aside>
    </div>
    <div class="ops-boundary-note"><span>!</span>活动归属绑定当前账号。审核中不能修改；运营通过后公开，奖品配置同时冻结。</div>
  </section>
</template>
