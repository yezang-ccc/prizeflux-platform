<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElAlert, ElButton, ElCard, ElEmpty, ElSkeleton, ElTag } from 'element-plus'
import { campaignRequest } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import type { Campaign, CursorPage } from '@/types'

const auth = useAuthStore()
const campaigns = ref<Campaign[]>([])
const cursor = ref<number | null>(null)
const loading = ref(true)
const error = ref('')

async function load(next = false) {
  if (loading.value && next) return
  loading.value = true
  error.value = ''
  try {
    const query = new URLSearchParams({ limit: '12' })
    if (next && cursor.value) query.set('before', String(cursor.value))
    const page = await campaignRequest<CursorPage<Campaign>>(`/available-campaigns?${query}`, auth.token)
    campaigns.value = next ? [...campaigns.value, ...page.items] : page.items
    cursor.value = page.nextCursor
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '活动暂时无法加载。'
  } finally {
    loading.value = false
  }
}

function dateLabel(value: string) {
  return new Intl.DateTimeFormat('zh-CN', { month: 'long', day: 'numeric' }).format(new Date(value))
}

function statusLabel(campaign: Campaign) {
  if (new Date(campaign.startsAt) > new Date()) return '即将开始'
  if (new Date(campaign.endsAt) <= new Date()) return '已结束'
  return '进行中'
}

onMounted(() => load())
</script>

<template>
  <section class="product-page campaign-list-page" aria-labelledby="campaign-list-title">
    <header class="product-page-heading">
      <div>
        <h1 id="campaign-list-title">发现活动</h1>
        <p>浏览已开放的活动，进入详情查看奖品与参与方式。</p>
      </div>
      <RouterLink to="/me/history"><ElButton>我的抽奖记录</ElButton></RouterLink>
    </header>

    <ElAlert v-if="error" title="活动暂时无法加载" type="error" show-icon :closable="false" role="alert">
      <template #default><span>{{ error }}</span><ElButton class="list-error-retry" link type="primary" @click="load()">重试</ElButton></template>
    </ElAlert>
    <div v-else-if="loading && !campaigns.length" class="campaign-loading" aria-label="正在加载活动">
      <ElSkeleton :rows="3" animated />
    </div>
    <ElEmpty v-else-if="!campaigns.length" description="目前没有开放参与的活动" />
    <section v-else class="campaign-list" aria-label="可参与活动列表">
      <RouterLink v-for="campaign in campaigns" :key="campaign.id" :to="`/campaigns/${campaign.id}`" class="campaign-list-link">
        <ElCard shadow="never" class="campaign-list-card">
          <div class="campaign-list-copy">
            <div class="campaign-list-title-row">
              <h2>{{ campaign.name }}</h2>
              <ElTag :type="statusLabel(campaign) === '进行中' ? 'success' : 'info'" effect="plain">{{ statusLabel(campaign) }}</ElTag>
            </div>
            <p>活动时间：{{ dateLabel(campaign.startsAt) }} — {{ dateLabel(campaign.endsAt) }}</p>
          </div>
          <ElButton type="primary">查看活动</ElButton>
        </ElCard>
      </RouterLink>
    </section>
    <div v-if="cursor" class="campaign-load-more">
      <ElButton :loading="loading" :disabled="loading" @click="load(true)">{{ loading ? '正在加载' : '加载更多活动' }}</ElButton>
    </div>
    <ElAlert class="campaign-trust-note" title="参与说明" description="奖品与概率以活动详情为准。实际抽奖资格、库存与结果由服务端确认。" type="info" :closable="false" />
  </section>
</template>
