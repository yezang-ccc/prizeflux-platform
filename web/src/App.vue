<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const operator = computed(() => auth.user?.role === 'OPERATOR')
const links = computed(() => operator.value
  ? [{ to: '/ops/reviews', label: '待审核', mark: '01' }, { to: '/ops/campaigns', label: '活动管理', mark: '02' }, { to: '/ops/assistant', label: '活动助手', mark: '03' }]
  : [{ to: '/campaigns', label: '发现活动', mark: '01' }, { to: '/me/campaigns', label: '我的活动', mark: '02' }, { to: '/me/history', label: '我的记录', mark: '03' }])

function logout() {
  auth.logout()
  router.push('/login')
}
</script>

<template>
  <div class="app-frame">
    <a class="skip-link" href="#main-content">跳到主要内容</a>
    <header v-if="auth.user && route.name !== 'login'" class="topbar">
      <RouterLink class="brand" :to="operator ? '/ops/reviews' : '/campaigns'" aria-label="PrizeFlux 首页">
        <span class="brand-symbol"><i></i><i></i><i></i></span>
        <span>prizeflux<span class="brand-dot">.</span></span>
      </RouterLink>
      <nav class="topnav" aria-label="主导航">
        <RouterLink v-for="link in links" :key="link.to" :to="link.to" class="nav-link" :class="{ active: route.path.startsWith(link.to) }">
          <span>{{ link.mark }}</span>{{ link.label }}
        </RouterLink>
      </nav>
      <div class="account-area">
        <span class="live-dot" aria-hidden="true"></span>
        <span class="account-name">{{ auth.user.username }}</span>
        <span class="role-chip">{{ operator ? '运营员' : '用户 · 可参与与创建' }}</span>
        <button class="logout-button" type="button" @click="logout">退出</button>
      </div>
    </header>
    <main id="main-content" class="page-main" tabindex="-1" :class="{ 'login-main': route.name === 'login' || route.name === 'register' }">
      <RouterView />
    </main>
    <footer v-if="auth.user && route.name !== 'login'" class="site-footer">
      <span>PRIZEFLUX · 营销活动平台</span>
      <span>确定性业务 · 可追溯事件</span>
    </footer>
  </div>
</template>
