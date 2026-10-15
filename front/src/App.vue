<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from './stores/auth'
import RouteErrorBoundary from './ui/RouteErrorBoundary.vue'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const logoutOpen = ref(false)

function confirmLogout() {
  logoutOpen.value = false
  auth.logout()
  void router.push('/login')
}
</script>

<template>
  <div class="app-shell">
    <header v-if="auth.isAuthenticated" class="global-nav">
      <RouterLink class="global-brand" to="/resumes">简历匹配平台</RouterLink>
      <nav class="global-nav-links" aria-label="工作区导航">
        <RouterLink class="global-nav-link" active-class="global-nav-link-active" :class="{ 'global-nav-link-context-active': route.path.startsWith('/resumes/') }" to="/resumes">简历</RouterLink>
        <RouterLink class="global-nav-link" active-class="global-nav-link-active" :class="{ 'global-nav-link-context-active': route.path === '/match' || route.path.startsWith('/matches/') }" to="/match">模型匹配</RouterLink>
        <RouterLink class="global-nav-link" active-class="global-nav-link-active" to="/profiles">模型配置</RouterLink>
        <RouterLink v-if="auth.user?.role === 'ADMIN'" class="global-nav-link" active-class="global-nav-link-active" to="/admin/recovery">恢复管理</RouterLink>
        <RouterLink v-else class="global-nav-link" active-class="global-nav-link-active" to="/recovery">恢复</RouterLink>
      </nav>
      <div class="global-nav-actions">
        <span class="global-identity">{{ auth.user?.username }} · {{ auth.user?.id }}</span>
        <button type="button" class="button-secondary" data-test="logout" @click="logoutOpen = true">退出登录</button>
      </div>
    </header>
    <RouterView v-slot="{ Component, route }">
      <RouteErrorBoundary :key="route.fullPath">
        <Transition name="route-fade" mode="out-in">
          <component :is="Component" />
        </Transition>
      </RouteErrorBoundary>
    </RouterView>
    <div v-if="logoutOpen && auth.isAuthenticated" class="dialog-backdrop" @click.self="logoutOpen = false">
      <section class="dialog-panel logout-dialog" role="dialog" aria-modal="true" aria-labelledby="logout-dialog-title">
        <p class="eyebrow">账号操作</p>
        <h2 id="logout-dialog-title">确认退出登录吗？</h2>
        <p class="muted">退出后需要重新输入账号和密码才能继续使用平台。</p>
        <div class="form-actions">
          <button type="button" class="button-secondary" data-test="cancel-logout" @click="logoutOpen = false">取消</button>
          <button type="button" class="button-danger" data-test="confirm-logout" @click="confirmLogout">确认退出</button>
        </div>
      </section>
    </div>
  </div>
</template>
