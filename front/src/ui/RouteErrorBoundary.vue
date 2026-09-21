<script setup lang="ts">
import { onErrorCaptured, ref } from 'vue'

const failed = ref(false)

onErrorCaptured(() => {
  failed.value = true
  return false
})

function reloadPage() {
  window.location.reload()
}
</script>

<template>
  <main v-if="failed" class="workspace route-recovery" role="alert" aria-live="assertive">
    <p class="eyebrow">页面恢复</p>
    <h1>页面暂时无法加载</h1>
    <p class="muted">登录状态仍然保留。请返回简历列表，或重新加载页面后再继续操作。</p>
    <div class="form-actions route-recovery-actions">
      <RouterLink class="button-link" to="/resumes">返回简历列表</RouterLink>
      <button type="button" class="button-secondary" @click="reloadPage">重新加载页面</button>
    </div>
  </main>
  <slot v-else />
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
</style>
