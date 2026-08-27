<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
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
const page = ref(1)
const totalPages = ref(1)
const isAdmin = computed(() => auth.user?.role === 'ADMIN')

async function loadResumes(nextPage = page.value) {
  loading.value = true
  error.value = ''
  try {
    const response = await lifecycleApi.listResumes(nextPage)
    page.value = response.page
    totalPages.value = response.totalPages
    resumes.value = response.items.filter((item) => item.visibilityState === 'ACTIVE' && item.status === 0)
  }
  catch { error.value = 'Unable to load active resumes.' }
  finally { loading.value = false }
}

async function deleteResume(payload: DeleteResumeRequest) {
  if (!selected.value) return
  deleting.value = true
  error.value = ''
  const id = selected.value.id
  try {
    if (isAdmin.value) await lifecycleApi.adminSoftDeleteResume(id, payload)
    else await lifecycleApi.deleteResume(id, payload)
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
        <RouterLink v-if="!isAdmin" to="/recovery">Recovery</RouterLink>
        <RouterLink v-else to="/admin/recovery">Admin recovery</RouterLink>
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
    <nav v-if="totalPages > 1" class="pagination" aria-label="Resume pages"><button type="button" :disabled="page <= 1 || loading" @click="loadResumes(page - 1)">Previous</button><span>Page {{ page }} of {{ totalPages }}</span><button type="button" :disabled="page >= totalPages || loading" @click="loadResumes(page + 1)">Next</button></nav>
    <DeleteResumeDialog :open="Boolean(selected)" :resume-id="selected?.id || ''" :version="selected?.version || 0" :busy="deleting" @cancel="selected = null" @confirm="deleteResume" />
  </main>
</template>
