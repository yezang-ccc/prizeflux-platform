<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ApiError } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import { ElAlert, ElButton, ElInput } from 'element-plus'
import AuthLayout from './AuthLayout.vue'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const username = ref(typeof route.query.username === 'string' ? route.query.username : '')
const password = ref('')
const busy = ref(false)
const error = ref('')

async function submit() {
  error.value = ''
  busy.value = true
  try {
    await auth.login(username.value.trim(), password.value)
    const requested = typeof route.query.redirect === 'string' ? route.query.redirect : ''
    await router.replace(requested || (auth.user?.role === 'OPERATOR' ? '/ops/campaigns' : '/campaigns'))
  } catch (cause) {
    error.value = cause instanceof ApiError ? cause.message : '登录失败，请稍后再试。'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <AuthLayout>
    <template #eyebrow>欢迎回来</template>
    <template #title>登录 PrizeFlux</template>
    <template #description>使用账号进入活动平台，浏览活动并参与抽奖。</template>

    <template #notice>
      <div v-if="route.query.registered === '1'" class="auth-status" role="status">
        <ElAlert title="账号创建成功，请使用新账号登录。" type="success" :closable="false" show-icon />
      </div>
    </template>

    <form class="auth-form" @submit.prevent="submit">
      <div class="auth-field">
        <label for="login-username">用户名</label>
        <ElInput id="login-username" v-model="username" name="username" autocomplete="username" required
          placeholder="输入你的用户名" size="large" />
      </div>
      <div class="auth-field">
        <label for="login-password">密码</label>
        <ElInput id="login-password" v-model="password" name="password" type="password"
          autocomplete="current-password" required show-password placeholder="输入密码" size="large" />
      </div>
      <div v-if="error" class="auth-status" role="alert" aria-live="assertive">
        <ElAlert :title="error" type="error" :closable="false" show-icon />
      </div>
      <ElButton class="auth-submit" type="primary" native-type="submit" size="large" :loading="busy">
        {{ busy ? '正在验证…' : '进入平台' }}
      </ElButton>
    </form>

    <template #footer>
      <p class="auth-note">运营账号由维护者预置；普通参与者可以自行创建账号。</p>
      <p class="auth-switch">还没有普通用户账号？ <RouterLink to="/register">创建账号</RouterLink></p>
      <p class="auth-security">安全会话 · 服务端角色校验</p>
    </template>
  </AuthLayout>
</template>
