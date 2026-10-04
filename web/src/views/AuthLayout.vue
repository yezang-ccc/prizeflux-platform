<script setup lang="ts">
import { ElCard } from 'element-plus'
</script>

<template>
  <section class="auth-page">
    <ElCard class="auth-card" shadow="always">
      <header class="auth-card-header">
        <RouterLink class="brand auth-brand" to="/login" aria-label="PrizeFlux 登录页">
          <span class="brand-symbol"><i></i><i></i><i></i></span>
          <span>prizeflux<span class="brand-dot">.</span></span>
        </RouterLink>
        <span class="auth-card-product">活动抽奖平台</span>
      </header>

      <div class="auth-card-content">
        <p class="auth-eyebrow"><slot name="eyebrow" /></p>
        <h1 class="auth-title"><slot name="title" /></h1>
        <p class="auth-description"><slot name="description" /></p>
        <slot name="notice" />
        <slot />
        <footer class="auth-card-footer"><slot name="footer" /></footer>
      </div>
    </ElCard>
  </section>
</template>
