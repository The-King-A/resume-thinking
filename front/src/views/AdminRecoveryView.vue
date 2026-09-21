<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import RecoveryDialog from '../components/RecoveryDialog.vue'
import { lifecycleApi } from '../api/lifecycle'
import { ApiError, type RestoreResumeRequest, type Resume } from '../api/contracts'
import { friendlyError, lifecycleMessages } from '../i18n/messages'
import { showTopNotification } from '../ui/notifications'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const router = useRouter()
const resumes = ref<Resume[]>([])
const selected = ref<Resume | null>(null)
const ownerFilter = ref('')
const loading = ref(false)
const restoring = ref(false)
const error = ref('')
const page = ref(1)
const totalPages = ref(1)
const isAdmin = computed(() => auth.user?.role === 'ADMIN')
const stateLabel = (state: Resume['visibilityState']) => state.includes('ARCHIVED') ? '已归档' : '已软删除'

function report(message: string, type: 'success' | 'error') {
  try { showTopNotification(message, type) } catch { /* Notification rendering must not change recovery state. */ }
}

function restoreError(caught: unknown) {
  // Older V2 servers may omit the detail code, but a title detail is only
  // meaningful as a duplicate when the response is the documented 409
  // duplicate-resource envelope. Do not turn ordinary title validation into
  // a collision message.
  if (caught instanceof ApiError && caught.status === 409 && caught.payload.code === 'DUPLICATE_RESOURCE'
      && (caught.payload.detailCode === 'DUPLICATE_RESUME_TITLE'
        || caught.payload.details?.some((detail) => detail.field === 'title'))) {
    return lifecycleMessages.restoreDuplicateTitle
  }
  if (caught instanceof ApiError && (caught.code === 'RESUME_NOT_EFFECTIVE'
      || caught.payload.detailCode === 'RESUME_NOT_EFFECTIVE')) {
    return lifecycleMessages.resumeNotEffective
  }
  return friendlyError(caught, '无法恢复此简历，请刷新页面并确认当前版本。')
}

async function loadRecoverable(nextPage = page.value) {
  if (auth.user?.role !== 'ADMIN') return
  loading.value = true
  error.value = ''
  try {
    const response = await lifecycleApi.listAdminRecovery(ownerFilter.value.trim() || undefined, nextPage)
    page.value = response.page
    totalPages.value = response.totalPages
    resumes.value = response.items
  }
  catch { error.value = '无法加载管理员恢复记录。' }
  finally { loading.value = false }
}

async function restore(payload: RestoreResumeRequest) {
  if (auth.user?.role !== 'ADMIN' || !selected.value) return
  restoring.value = true
  error.value = ''
  const id = selected.value.id
  const title = selected.value.title
  let restored = false
  try {
    await lifecycleApi.restoreAdminResume(id, payload)
    resumes.value = resumes.value.filter((item) => item.id !== id)
    selected.value = null
    restored = true
  } catch (caught) {
    error.value = restoreError(caught)
    report(error.value, 'error')
  }
  finally { restoring.value = false }

  // Keep navigation failures separate from the restore transaction: a
  // successful database restore must never be reported as a failed restore.
  if (restored) {
    report(`已恢复“${title}”简历。`, 'success')
    if (router) {
      try { await Promise.resolve(router.push('/resumes')) } catch { /* The row is already restored; the user can use the link manually. */ }
    }
  }
}

onMounted(loadRecoverable)
</script>

<template>
  <main class="workspace">
      <header class="workspace-header"><div><p class="eyebrow">管理员</p><h1>跨用户恢复</h1><p class="muted">每条管理员可见记录都会显示所属用户信息。</p></div><RouterLink class="workspace-command workspace-command-primary" to="/resumes">有效简历</RouterLink></header>
    <section v-if="!isAdmin" class="empty-state" role="alert"><h2>需要管理员权限</h2><p>当前账号无法访问此恢复页面。</p></section>
    <template v-else>
      <form class="filter-bar" @submit.prevent="loadRecoverable(1)"><label>所属用户 ID<input v-model="ownerFilter" placeholder="可选，输入完整的所属用户 ID" /></label><button type="submit" :disabled="loading">筛选</button></form>
      <p class="retention-note">这些已归档和软删除记录会根据保留策略继续保存在 MySQL 中。恢复时使用当前记录版本。</p>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <p v-if="loading" class="status-panel">正在加载管理员恢复记录…</p>
      <section v-else-if="resumes.length" class="record-list" aria-label="管理员恢复记录">
        <article v-for="resume in resumes" :key="resume.id" class="record-row">
          <div><strong><code class="record-id">{{ resume.id }}</code>{{ resume.title }}</strong><span class="owner-context">所属用户：{{ resume.ownerId }}</span><span>{{ stateLabel(resume.visibilityState) }} · {{ resume.sourceType }} · 第 {{ resume.version }} 版</span></div>
          <button type="button" @click="selected = resume">恢复</button>
        </article>
      </section>
      <section v-else class="empty-state"><h2>暂无可恢复记录</h2><p>当前所属用户筛选条件下没有管理员可见的简历。</p></section>
      <nav v-if="totalPages > 1" class="pagination" aria-label="管理员恢复分页"><button type="button" :disabled="page <= 1 || loading" @click="loadRecoverable(page - 1)">上一页</button><span>第 {{ page }} 页，共 {{ totalPages }} 页</span><button type="button" :disabled="page >= totalPages || loading" @click="loadRecoverable(page + 1)">下一页</button></nav>
      <RecoveryDialog :open="Boolean(selected)" :title="selected?.title || ''" :version="selected?.version || 0" :busy="restoring" @cancel="selected = null" @confirm="restore" />
    </template>
  </main>
</template>
