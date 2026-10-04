<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElAlert, ElButton, ElCard, ElEmpty, ElSkeleton, ElTag } from 'element-plus'
import { campaignRequest } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import type { DrawHistoryItem } from '@/types'

interface HistoryPage { items: DrawHistoryItem[]; nextCursor: number | null }
const auth = useAuthStore()
const items = ref<DrawHistoryItem[]>([])
const cursor = ref<number | null>(null)
const busy = ref(true)
const error = ref('')

async function load(more = false) {
  if (more && busy.value) return
  busy.value = true
  error.value = ''
  try {
    const params = new URLSearchParams({ limit: '20' })
    if (more && cursor.value) params.set('before', String(cursor.value))
    const page = await campaignRequest<HistoryPage>(`/me/draws?${params}`, auth.token)
    items.value = more ? [...items.value, ...page.items] : page.items
    cursor.value = page.nextCursor
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '历史记录暂时无法加载。'
  } finally {
    busy.value = false
  }
}

function outcome(item: DrawHistoryItem) {
  return item.outcome === 'WON' ? '中奖' : item.outcome === 'SOLD_OUT' ? '奖品售罄' : '未中奖'
}

onMounted(() => load())
</script>

<template>
  <section class="product-page history-page" aria-labelledby="history-title">
    <header class="product-page-heading">
      <div><h1 id="history-title">我的抽奖记录</h1><p>查看已由服务端保存的参与结果。</p></div>
      <RouterLink to="/campaigns"><ElButton type="primary">发现活动</ElButton></RouterLink>
    </header>
    <ElAlert v-if="error" title="记录暂时无法加载" type="error" show-icon :closable="false" role="alert">
      <template #default><span>{{ error }}</span><ElButton class="history-error-retry" link type="primary" @click="load()">重试</ElButton></template>
    </ElAlert>
    <div v-else-if="busy && !items.length" class="history-loading" aria-label="正在加载抽奖记录"><ElSkeleton :rows="4" animated /></div>
    <ElEmpty v-else-if="!items.length" description="参加一场活动后，抽奖记录会显示在这里。" />
    <section v-else class="history-records" aria-label="抽奖记录列表">
      <ElCard v-for="item in items" :key="item.drawId" shadow="never" class="history-record-card">
        <div class="history-record-heading">
          <div><h2>活动 #{{ item.campaignId }}</h2><p>抽奖记录 #{{ item.drawId }}</p></div>
          <ElTag :type="item.outcome === 'WON' ? 'success' : item.outcome === 'SOLD_OUT' ? 'warning' : 'info'" effect="plain">{{ outcome(item) }}</ElTag>
        </div>
        <dl class="history-record-time"><dt>参与时间</dt><dd>{{ new Date(item.drawnAt).toLocaleString('zh-CN') }}</dd></dl>
      </ElCard>
      <div v-if="cursor" class="campaign-load-more">
        <ElButton :loading="busy" :disabled="busy" @click="load(true)">{{ busy ? '正在加载' : '查看更多记录' }}</ElButton>
      </div>
      <ElAlert class="history-disclaimer" title="记录说明" description="此处展示抽奖结果；模拟发奖状态不等同于真实供应商交付状态。" type="info" :closable="false" />
    </section>
  </section>
</template>
