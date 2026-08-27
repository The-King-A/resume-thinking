<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { lifecycleApi } from '../api/lifecycle'
import { ApiError, type MatchTask } from '../api/contracts'
import { useLlmProfileStore } from '../stores/llmProfiles'

const profileStore = useLlmProfileStore()
const file = ref<File | null>(null)
const fileName = ref('')
const fileError = ref('')
const title = ref('')
const jobDescription = ref('')
const profileId = ref('')
const submitting = ref(false)
const task = ref<MatchTask | null>(null)
const taskGone = ref(false)
const error = ref('')
let pollTimer: number | undefined

const selectedProfiles = computed(() => profileStore.profiles.filter((profile) => profile.selected))
const canSubmit = computed(() => Boolean(file.value && profileId.value && jobDescription.value.trim().length >= 20 && !fileError.value && !submitting.value))
const taskLabel = computed(() => ({ QUEUED: 'Queued', PROCESSING: 'Processing', SUCCEEDED: 'Complete', FAILED: 'Failed', TIMED_OUT: 'Timed out', BLOCKED: 'Archived or blocked' }[task.value?.state || 'QUEUED']))

function handleFile(event: Event) {
  const next = (event.target as HTMLInputElement).files?.[0] || null
  file.value = null
  fileName.value = next?.name || ''
  fileError.value = ''
  if (!next) return
  const extension = next.name.toLowerCase().slice(next.name.lastIndexOf('.'))
  if (extension === '.pdf') { fileError.value = 'PDF files are not supported. Upload a UTF-8 TXT or DOCX resume.'; return }
  if (extension !== '.txt' && extension !== '.docx') { fileError.value = 'Unsupported file type. Upload a UTF-8 TXT or DOCX resume.'; return }
  file.value = next
}

function schedulePoll() {
  if (task.value?.state !== 'QUEUED' && task.value?.state !== 'PROCESSING') return
  pollTimer = window.setTimeout(pollTask, 1500)
}

async function pollTask() {
  if (!task.value || (task.value.state !== 'QUEUED' && task.value.state !== 'PROCESSING')) return
  try { task.value = await lifecycleApi.getMatchTask(task.value.id); schedulePoll() }
  catch (caught) {
    if (caught instanceof ApiError && caught.code === 'TASK_GONE') taskGone.value = true
    else error.value = 'Unable to refresh the task state.'
  }
}

async function startMatch() {
  if (!canSubmit.value || !file.value) return
  submitting.value = true
  error.value = ''
  taskGone.value = false
  if (pollTimer) window.clearTimeout(pollTimer)
  try {
    const resume = await lifecycleApi.uploadResume(file.value, title.value.trim() || undefined)
    task.value = await lifecycleApi.createMatchTask({
      resumeId: resume.id,
      llmProfileId: profileId.value,
      jobDescriptionText: jobDescription.value.trim(),
      idempotencyKey: `match-${globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random().toString(36).slice(2)}`}`,
    })
    schedulePoll()
  } catch { error.value = 'Unable to upload the resume or start the matching task.' }
  finally { submitting.value = false }
}

onMounted(async () => {
  try {
    await profileStore.list()
    profileId.value = profileStore.profiles.find((profile) => profile.selected)?.id || ''
  } catch { error.value = 'Unable to load model profiles.' }
})
onBeforeUnmount(() => { if (pollTimer) window.clearTimeout(pollTimer) })
</script>

<template>
  <main class="workspace">
    <header class="workspace-header"><div><p class="eyebrow">New match</p><h1>Upload & match</h1><p class="muted">This MVP accepts Java backend job descriptions through the Java service.</p></div><RouterLink to="/resumes">Active resumes</RouterLink></header>
    <div class="match-layout">
      <form class="operation-panel" @submit.prevent="startMatch">
        <h2>Resume and role</h2>
        <label>Resume file<input type="file" accept=".txt,.docx" @change="handleFile" /><span class="field-help">TXT and DOCX only. PDF is explicitly unsupported in v1.</span></label>
        <p v-if="fileName" class="muted">Selected: {{ fileName }}</p>
        <p v-if="fileError" class="error" role="alert">{{ fileError }}</p>
        <label>Resume title (optional)<input v-model="title" maxlength="200" /></label>
        <label>Selected model profile<select v-model="profileId"><option value="" disabled>Select a saved profile</option><option v-for="profile in selectedProfiles" :key="profile.id" :value="profile.id">{{ profile.displayName }}</option></select><span v-if="!selectedProfiles.length" class="field-help">Select a saved model profile in settings before matching.</span></label>
        <label>Java backend job description<textarea v-model="jobDescription" minlength="20" maxlength="20000" rows="10" placeholder="Paste the Java backend role requirements returned by your trusted job source."></textarea><span class="field-help">At least 20 characters. The text is sent only to the Java backend.</span></label>
        <button data-test="start-match" type="submit" :disabled="!canSubmit">{{ submitting ? 'Starting…' : 'Start evidence match' }}</button>
      </form>
      <section class="operation-status" aria-live="polite">
        <p class="eyebrow">Task status</p>
        <h2 v-if="task">{{ taskLabel }}</h2><h2 v-else>No task yet</h2>
        <p v-if="task?.state === 'QUEUED'" class="muted">Waiting for Java orchestration to begin processing.</p>
        <p v-else-if="task?.state === 'PROCESSING'" class="muted">Evidence is being validated. This page polls while processing.</p>
        <p v-else-if="task?.state === 'FAILED'" class="error">Matching failed{{ task.failureCode ? ` (${task.failureCode})` : '' }}. No result was invented.</p>
        <p v-else-if="task?.state === 'TIMED_OUT'" class="error">The task timed out. No result is available.</p>
        <p v-else-if="task?.state === 'BLOCKED'" class="error">The task is blocked or archived and will not be polled.</p>
        <p v-if="taskGone" class="error">The task was archived, deleted, or stopped before completion.</p>
        <p v-if="error" class="error" role="alert">{{ error }}</p>
        <RouterLink v-if="task?.state === 'SUCCEEDED'" class="button-link" :to="`/matches/${task.id}`">View evidence result</RouterLink>
      </section>
    </div>
  </main>
</template>
