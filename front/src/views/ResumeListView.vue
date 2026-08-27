<script setup lang="ts">
import { onMounted, ref } from 'vue'
import DeleteResumeDialog from '../components/DeleteResumeDialog.vue'
import { lifecycleApi } from '../api/lifecycle'
import type { DeleteResumeRequest, Resume } from '../api/contracts'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const resumes = ref<Resume[]>([])
const selected = ref<Resume | null>(null)
const loading = ref(true)
const deleting = ref(false)
const error = ref('')

async function loadResumes() {
  loading.value = true
  error.value = ''
  try { resumes.value = (await lifecycleApi.listResumes()).items.filter((item) => item.visibilityState === 'ACTIVE' && item.status === 0) }
  catch { error.value = 'Unable to load active resumes.' }
  finally { loading.value = false }
}

async function deleteResume(payload: DeleteResumeRequest) {
  if (!selected.value) return
  deleting.value = true
  error.value = ''
  const id = selected.value.id
  try {
    await lifecycleApi.deleteResume(id, payload)
    resumes.value = resumes.value.filter((item) => item.id !== id)
    selected.value = null
  } catch { error.value = 'Unable to delete this resume. Refresh and check its current version.' }
  finally { deleting.value = false }
}

onMounted(loadResumes)
</script>

<template>
  <main class="workspace">
    <header class="workspace-header">
      <div><p class="eyebrow">Resume workspace</p><h1>Active resumes</h1><p class="muted">Only active resume metadata returned by the Java service appears here.</p></div>
      <nav class="workspace-nav" aria-label="Resume actions">
        <RouterLink class="button-link" to="/match">Upload & match</RouterLink>
        <RouterLink to="/recovery">Recovery</RouterLink>
        <RouterLink v-if="auth.user?.role === 'ADMIN'" to="/admin/recovery">Admin recovery</RouterLink>
        <RouterLink to="/profiles">Model profiles</RouterLink>
      </nav>
    </header>
    <p class="retention-note">Soft deletion removes a resume from this list. Recoverable metadata remains in MySQL according to the retention policy.</p>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="loading" class="status-panel">Loading active resumes…</p>
    <section v-else-if="resumes.length" class="record-list" aria-label="Active resumes">
      <article v-for="resume in resumes" :key="resume.id" class="record-row">
        <div><strong>{{ resume.title }}</strong><span>{{ resume.sourceType }} · version {{ resume.version }} · updated {{ new Date(resume.updatedAt).toLocaleString() }}</span></div>
        <button type="button" class="button-danger-quiet" @click="selected = resume">Delete</button>
      </article>
    </section>
    <section v-else class="empty-state"><h2>No active resumes</h2><p>Upload a TXT or DOCX resume to begin a match.</p><RouterLink to="/match">Upload resume</RouterLink></section>
    <DeleteResumeDialog :open="Boolean(selected)" :resume-id="selected?.id || ''" :version="selected?.version || 0" :busy="deleting" @cancel="selected = null" @confirm="deleteResume" />
  </main>
</template>
