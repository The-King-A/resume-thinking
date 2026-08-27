<script setup lang="ts">
import { onMounted, ref } from 'vue'
import RecoveryDialog from '../components/RecoveryDialog.vue'
import { lifecycleApi } from '../api/lifecycle'
import type { RestoreResumeRequest, Resume } from '../api/contracts'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const resumes = ref<Resume[]>([])
const selected = ref<Resume | null>(null)
const ownerFilter = ref('')
const loading = ref(false)
const restoring = ref(false)
const error = ref('')
const isAdmin = auth.user?.role === 'ADMIN'
const stateLabel = (state: Resume['visibilityState']) => state.includes('ARCHIVED') ? 'Archived' : 'Soft deleted'

async function loadRecoverable() {
  if (!isAdmin) return
  loading.value = true
  error.value = ''
  try { resumes.value = (await lifecycleApi.listAdminRecovery(ownerFilter.value.trim() || undefined)).items }
  catch { error.value = 'Unable to load administrator recovery records.' }
  finally { loading.value = false }
}

async function restore(payload: RestoreResumeRequest) {
  if (!isAdmin || !selected.value) return
  restoring.value = true
  error.value = ''
  const id = selected.value.id
  try {
    await lifecycleApi.restoreAdminResume(id, payload)
    resumes.value = resumes.value.filter((item) => item.id !== id)
    selected.value = null
  } catch { error.value = 'Unable to restore this resume. Refresh and check its current version.' }
  finally { restoring.value = false }
}

onMounted(loadRecoverable)
</script>

<template>
  <main class="workspace">
    <header class="workspace-header"><div><p class="eyebrow">Administrator</p><h1>Cross-owner recovery</h1><p class="muted">Owner context is displayed for every administrator-visible record.</p></div><RouterLink to="/resumes">Active resumes</RouterLink></header>
    <section v-if="!isAdmin" class="empty-state" role="alert"><h2>Administrator access required</h2><p>This recovery view is unavailable for your account.</p></section>
    <template v-else>
      <form class="filter-bar" @submit.prevent="loadRecoverable"><label>Owner ID<input v-model="ownerFilter" placeholder="Optional exact owner ID" /></label><button type="submit" :disabled="loading">Filter</button></form>
      <p class="retention-note">These archived and soft-deleted records remain in MySQL according to the retention policy. Restoration uses the current record version.</p>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <p v-if="loading" class="status-panel">Loading administrator recovery records…</p>
      <section v-else-if="resumes.length" class="record-list" aria-label="Administrator recovery records">
        <article v-for="resume in resumes" :key="resume.id" class="record-row">
          <div><strong>{{ resume.title }}</strong><span class="owner-context">Owner: {{ resume.ownerId }}</span><span>{{ stateLabel(resume.visibilityState) }} · {{ resume.sourceType }} · version {{ resume.version }}</span></div>
          <button type="button" @click="selected = resume">Restore</button>
        </article>
      </section>
      <section v-else class="empty-state"><h2>No recoverable records</h2><p>No administrator-visible resumes match the current owner filter.</p></section>
      <RecoveryDialog :open="Boolean(selected)" :title="selected?.title || ''" :version="selected?.version || 0" :busy="restoring" @cancel="selected = null" @confirm="restore" />
    </template>
  </main>
</template>
