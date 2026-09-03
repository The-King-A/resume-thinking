<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useAuthStore } from './stores/auth'
import RouteErrorBoundary from './ui/RouteErrorBoundary.vue'

const router = useRouter()
const auth = useAuthStore()
function logout() {
  auth.logout()
  void router.push('/login')
}
</script>

<template>
  <div class="app-shell">
    <header v-if="auth.isAuthenticated" class="global-nav">
      <RouterLink class="global-brand" to="/resumes">简历匹配平台</RouterLink>
      <nav class="global-nav-links" aria-label="工作区导航">
        <RouterLink class="global-nav-link" active-class="global-nav-link-active" to="/resumes">简历</RouterLink>
        <RouterLink class="global-nav-link" active-class="global-nav-link-active" to="/match">匹配</RouterLink>
        <RouterLink class="global-nav-link" active-class="global-nav-link-active" to="/profiles">模型配置</RouterLink>
        <RouterLink v-if="auth.user?.role === 'ADMIN'" class="global-nav-link" active-class="global-nav-link-active" to="/admin/recovery">恢复管理</RouterLink>
        <RouterLink v-else class="global-nav-link" active-class="global-nav-link-active" to="/recovery">恢复</RouterLink>
      </nav>
      <div class="global-nav-actions">
        <span class="global-identity">{{ auth.user?.username }} · {{ auth.user?.id }}</span>
        <button type="button" class="button-secondary" data-test="logout" @click="logout">退出登录</button>
      </div>
    </header>
    <RouterView v-slot="{ Component, route }">
      <RouteErrorBoundary :key="route.fullPath">
        <component :is="Component" />
      </RouteErrorBoundary>
    </RouterView>
  </div>
</template>
