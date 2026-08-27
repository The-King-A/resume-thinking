<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import RecoveryDialog from '../components/RecoveryDialog.vue'
import { lifecycleApi } from '../api/lifecycle'
import type { RestoreResumeRequest, Resume } from '../api/contracts'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const resumes = ref<Resume[]>([])
const selected = ref<Resume | null>(null)
const loading = ref(true)
const restoring = ref(false)
const error = ref('')
const page = ref(1)
const totalPages = ref(1)
const isAdmin = computed(() => auth.user?.role === 'ADMIN')

const stateLabel = (state: Resume['visibilityState']) => state.includes('ARCHIVED') ? 'Archived' : 'Soft deleted'

async function loadRecoverable(nextPage = page.value) {
  if (isAdmin.value) return
  loading.value = true
  error.value = ''
  try {
    const response = await lifecycleApi.listUserRecovery(nextPage)
    page.value = response.page
    totalPages.value = response.totalPages
    resumes.value = response.items.filter((item) => item.ownerId === auth.user?.id && (item.visibilityState === 'USER_SOFT_DELETED' || item.visibilityState === 'USER_CACHE_ARCHIVED'))
  } catch { error.value = 'Unable to load recoverable resumes.' }
  finally { loading.value = false }
}

async function restore(payload: RestoreResumeRequest) {
  if (!selected.value) return
  restoring.value = true
  error.value = ''
  const id = selected.value.id
  try {
    await lifecycleApi.restoreUserResume(id, payload)
    resumes.value = resumes.value.filter((item) => item.id !== id)
    selected.value = null
  } catch { error.value = 'Unable to restore this resume. Refresh and check its current version.' }
  finally { restoring.value = false }
}

onMounted(loadRecoverable)
</script>

<template>
  <main class="workspace">
    <header class="workspace-header"><div><p class="eyebrow">Your data</p><h1>Resume recovery</h1><p class="muted">Only resumes owned by your signed-in account are shown.</p></div><nav class="workspace-nav"><RouterLink v-if="isAdmin" to="/admin/recovery">Admin recovery</RouterLink><RouterLink to="/resumes">Active resumes</RouterLink></nav></header>
    <section v-if="isAdmin" class="empty-state" role="alert"><h2>Use administrator recovery</h2><p>This account uses the cross-owner administrator recovery workflow.</p><RouterLink to="/admin/recovery">Open administrator recovery</RouterLink></section>
    <template v-else>
    <p class="retention-note">Soft-deleted and archived resume metadata remains in MySQL until restored or removed under the retention policy.</p>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="loading" class="status-panel">Loading recoverable resumes…</p>
    <section v-else-if="resumes.length" class="record-list" aria-label="Your recoverable resumes">
      <article v-for="resume in resumes" :key="resume.id" class="record-row">
        <div><strong>{{ resume.title }}</strong><span>{{ stateLabel(resume.visibilityState) }} · {{ resume.sourceType }} · version {{ resume.version }}</span></div>
        <button type="button" @click="selected = resume">Restore</button>
      </article>
    </section>
    <section v-else class="empty-state"><h2>No recoverable resumes</h2><p>Your deleted or archived resumes will appear here while eligible for recovery.</p></section>
    <nav v-if="totalPages > 1" class="pagination" aria-label="Recovery pages"><button type="button" :disabled="page <= 1 || loading" @click="loadRecoverable(page - 1)">Previous</button><span>Page {{ page }} of {{ totalPages }}</span><button type="button" :disabled="page >= totalPages || loading" @click="loadRecoverable(page + 1)">Next</button></nav>
    <RecoveryDialog :open="Boolean(selected)" :title="selected?.title || ''" :version="selected?.version || 0" :busy="restoring" @cancel="selected = null" @confirm="restore" />
    </template>
  </main>
</template>
