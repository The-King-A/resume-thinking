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
const loading = ref(true)
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
  if (isAdmin.value) return
  loading.value = true
  error.value = ''
  try {
    const response = await lifecycleApi.listUserRecovery(nextPage)
    page.value = response.page
    totalPages.value = response.totalPages
    resumes.value = response.items.filter((item) => item.ownerId === auth.user?.id && (item.visibilityState === 'USER_SOFT_DELETED' || item.visibilityState === 'USER_CACHE_ARCHIVED'))
  } catch { error.value = '无法加载可恢复简历。' }
  finally { loading.value = false }
}

async function restore(payload: RestoreResumeRequest) {
  if (!selected.value) return
  restoring.value = true
  error.value = ''
  const id = selected.value.id
  const title = selected.value.title
  let restored = false
  try {
    await lifecycleApi.restoreUserResume(id, payload)
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
      <header class="workspace-header"><div><p class="eyebrow">我的数据</p><h1>简历恢复</h1><p class="muted">仅显示当前登录账号拥有的简历。</p></div><nav class="workspace-nav"><RouterLink v-if="isAdmin" class="workspace-command" to="/admin/recovery">管理员恢复</RouterLink><RouterLink class="workspace-command workspace-command-primary" to="/resumes">有效简历</RouterLink></nav></header>
    <section v-if="isAdmin" class="empty-state" role="alert"><h2>请使用管理员恢复</h2><p>此账号需要使用跨用户管理员恢复流程。</p><RouterLink to="/admin/recovery">打开管理员恢复</RouterLink></section>
    <template v-else>
    <p class="retention-note">已软删除和已归档的简历元数据会根据保留策略继续保存在 MySQL 中，直到恢复或按策略移除。</p>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="loading" class="status-panel">正在加载可恢复简历…</p>
    <section v-else-if="resumes.length" class="record-list" aria-label="我的可恢复简历">
      <article v-for="resume in resumes" :key="resume.id" class="record-row">
        <div><strong><code class="record-id">{{ resume.id }}</code>{{ resume.title }}</strong><span>{{ stateLabel(resume.visibilityState) }} · {{ resume.sourceType }} · 第 {{ resume.version }} 版</span></div>
        <button type="button" @click="selected = resume">恢复</button>
      </article>
    </section>
    <section v-else class="empty-state"><h2>暂无可恢复简历</h2><p>符合恢复条件的已删除或已归档简历会显示在这里。</p></section>
    <nav v-if="totalPages > 1" class="pagination" aria-label="恢复分页"><button type="button" :disabled="page <= 1 || loading" @click="loadRecoverable(page - 1)">上一页</button><span>第 {{ page }} 页，共 {{ totalPages }} 页</span><button type="button" :disabled="page >= totalPages || loading" @click="loadRecoverable(page + 1)">下一页</button></nav>
    <RecoveryDialog :open="Boolean(selected)" :title="selected?.title || ''" :version="selected?.version || 0" :busy="restoring" @cancel="selected = null" @confirm="restore" />
    </template>
  </main>
</template>
