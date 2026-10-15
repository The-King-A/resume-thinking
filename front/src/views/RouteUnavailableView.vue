<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()

const retryPath = computed(() => {
  const retry = route.query.retry
  if (typeof retry !== 'string' || !retry.startsWith('/') || retry.startsWith('//') || retry.startsWith('/route-unavailable')) {
    return '/resumes'
  }
  return retry
})
</script>

<template>
  <main class="workspace route-recovery" role="alert" aria-live="assertive">
    <p class="eyebrow">页面恢复</p>
    <h1>页面暂时无法加载</h1>
    <p class="muted">登录状态仍然保留。请稍后重试，或返回简历列表继续操作。</p>
    <div class="form-actions route-recovery-actions">
      <RouterLink class="button-link" :to="retryPath">重试</RouterLink>
      <RouterLink class="button-secondary route-recovery-link" to="/resumes">返回简历列表</RouterLink>
    </div>
  </main>
</template>

<style scoped>
.route-recovery {
  display: grid;
  align-content: center;
  gap: 12px;
  min-height: 60svh;
  max-width: 720px;
}

.route-recovery-actions {
  justify-content: flex-start;
  margin-top: 12px;
}

.route-recovery-link {
  display: inline-flex;
  align-items: center;
  min-height: 40px;
  box-sizing: border-box;
  border-radius: 6px;
  padding: 9px 14px;
  text-decoration: none;
}
</style>
