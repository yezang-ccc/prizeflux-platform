<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ApiError, campaignRequest } from '@/api/client'
import { ElAlert, ElButton, ElInput } from 'element-plus'
import AuthLayout from './AuthLayout.vue'

const router = useRouter()
const username = ref('')
const password = ref('')
const usernameError = ref('')
const passwordError = ref('')
const busy = ref(false)
const error = ref('')

function validateForm(): boolean {
  usernameError.value = ''
  passwordError.value = ''
  const canonicalUsername = username.value.trim().toLowerCase()
  const passwordCodePoints = Array.from(password.value).length
  const passwordBytes = new TextEncoder().encode(password.value).length
  let valid = true

  if (!/^[a-z0-9._-]{3,64}$/.test(canonicalUsername)) {
    usernameError.value = '用户名需为 3–64 位字母、数字、点、下划线或连字符。'
    valid = false
  }
  if (passwordCodePoints < 12) {
    passwordError.value = '密码至少需要 12 个字符。'
    valid = false
  } else if (passwordBytes > 72) {
    passwordError.value = '密码过长，请控制在 72 个 UTF-8 字节以内。'
    valid = false
  }
  return valid
}

async function submit() {
  error.value = ''
  if (!validateForm()) return
  const canonicalUsername = username.value.trim().toLowerCase()
  username.value = canonicalUsername
  busy.value = true
  try {
    const created = await campaignRequest<{ userId: number; username: string; role: 'USER' }>(
      '/auth/register', undefined, {
        method: 'POST',
        body: JSON.stringify({ username: canonicalUsername, password: password.value }),
      },
    )
    await router.replace({ name: 'login', query: { registered: '1', username: created.username } })
  } catch (cause) {
    if (cause instanceof ApiError && cause.status === 409) {
      error.value = '这个用户名已经被使用，请换一个再试。'
    } else if (cause instanceof ApiError && cause.status === 400) {
      error.value = '用户名或密码不符合规则，请检查上方提示后重试。'
    } else if (cause instanceof ApiError && cause.status === 429) {
      const seconds = cause.retryAfterSeconds
      error.value = seconds && seconds > 0
        ? `注册尝试较多，请等待 ${seconds} 秒后再试。`
        : '注册尝试较多，请稍后再试。'
    } else if (cause instanceof ApiError && cause.status === 503) {
      error.value = '注册服务暂时不可用，请稍后重试。'
    } else {
      error.value = cause instanceof ApiError ? cause.message : '暂时无法创建账号，请稍后重试。'
    }
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <AuthLayout>
    <template #eyebrow>创建普通用户</template>
    <template #title>加入 PrizeFlux</template>
    <template #description>注册后即可浏览活动、参与抽奖和创建自己的活动。</template>

    <form class="auth-form" @submit.prevent="submit">
      <div class="auth-field">
        <label for="register-username">用户名</label>
        <p id="register-username-hint" class="auth-field-hint">3–64 位字母、数字、点、下划线或连字符；大写会自动转小写</p>
        <ElInput id="register-username" v-model="username" name="username" autocomplete="username"
          autocapitalize="none" spellcheck="false" aria-describedby="register-username-hint"
          :aria-invalid="Boolean(usernameError)" @input="usernameError = ''; error = ''"
          required minlength="3" maxlength="64" placeholder="例如 lucky.user" size="large" />
        <p v-if="usernameError" class="auth-field-error" role="alert">{{ usernameError }}</p>
      </div>
      <div class="auth-field">
        <label for="register-password">密码</label>
        <p id="register-password-hint" class="auth-field-hint">至少 12 个字符；最多 72 个 UTF-8 字节</p>
        <ElInput id="register-password" v-model="password" name="password" type="password"
          autocomplete="new-password" aria-describedby="register-password-hint"
          :aria-invalid="Boolean(passwordError)" @input="passwordError = ''; error = ''"
          required minlength="12" show-password placeholder="设置登录密码" size="large" />
        <p v-if="passwordError" class="auth-field-error" role="alert">{{ passwordError }}</p>
      </div>
      <div v-if="error" class="auth-status" role="alert" aria-live="assertive">
        <ElAlert :title="error" type="error" :closable="false" show-icon />
      </div>
      <ElButton class="auth-submit" type="primary" native-type="submit" size="large" :loading="busy">
        {{ busy ? '正在创建…' : '创建账号' }}
      </ElButton>
    </form>

    <template #footer>
      <p class="auth-note">密码以 BCrypt 哈希保存；同一来源地址 10 分钟最多尝试 5 次注册。</p>
      <p class="auth-switch">已经有账号？ <RouterLink to="/login">返回登录</RouterLink></p>
      <p class="auth-security">注册仅创建普通用户 · 运营权限不可自助获取</p>
    </template>
  </AuthLayout>
</template>
