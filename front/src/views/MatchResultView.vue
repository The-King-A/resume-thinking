<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { lifecycleApi } from '../api/lifecycle'
import { ApiError, type MatchResult, type MatchTask, type SuggestionState } from '../api/contracts'
import MatchEvidenceTable from '../components/MatchEvidenceTable.vue'

const route = useRoute()
const taskId = ref(String(route.params.taskId || ''))
const task = ref<MatchTask | null>(null)
const result = ref<MatchResult | null>(null)
const loading = ref(true)
const gone = ref(false)
const notReady = ref(false)
const error = ref('')
let pollTimer: number | undefined

const statusLabel = computed(() => ({ QUEUED: 'Queued', PROCESSING: 'Processing', SUCCEEDED: 'Complete', FAILED: 'Failed', TIMED_OUT: 'Timed out', BLOCKED: 'Archived or blocked' }[task.value?.state || 'QUEUED']))
const scoreItems = computed(() => result.value ? [
  ['Skills', result.value.score.skills], ['Project experience', result.value.score.projectExperience], ['Work content', result.value.score.workContent],
  ['Education & experience', result.value.score.educationExperience], ['Soft skills', result.value.score.softSkills],
] as const : [])
const suggestionLabel = (state: SuggestionState) => ({ SUPPORTED_FACT: 'Supported fact', WORDING_ONLY_REWRITE: 'Wording-only rewrite', NEEDS_USER_CONFIRMATION: 'Needs your confirmation', RISKY_OR_UNSUPPORTED: 'Risky or unsupported' }[state])

function schedulePoll() {
  if (task.value?.state !== 'QUEUED' && task.value?.state !== 'PROCESSING') return
  pollTimer = window.setTimeout(() => loadTask(taskId.value), 1500)
}

async function loadTask(id: string) {
  if (!id) { error.value = 'Unable to load this matching task.'; loading.value = false; return }
  try {
    task.value = await lifecycleApi.getMatchTask(id)
    if (task.value.state === 'SUCCEEDED') result.value = await lifecycleApi.getMatchResult(id)
    else schedulePoll()
  } catch (caught) {
    if (caught instanceof ApiError && caught.code === 'TASK_GONE') gone.value = true
    else if (caught instanceof ApiError && caught.code === 'TASK_NOT_READY') notReady.value = true
    else error.value = 'Unable to load this matching task.'
  } finally { loading.value = false }
}

watch(() => route.params.taskId, (next) => {
  if (pollTimer) window.clearTimeout(pollTimer)
  taskId.value = String(next || '')
  task.value = null
  result.value = null
  gone.value = false
  notReady.value = false
  error.value = ''
  loading.value = true
  void loadTask(taskId.value)
})
onMounted(() => { void loadTask(taskId.value) })
onBeforeUnmount(() => { if (pollTimer) window.clearTimeout(pollTimer) })
</script>

<template>
  <main class="workspace result-workspace">
    <header class="workspace-header"><div><p class="eyebrow">Evidence review</p><h1>Match result</h1><p class="muted">Requirements are linked to resume evidence; suggestions remain a separate decision.</p></div><nav class="workspace-nav"><RouterLink to="/match">New match</RouterLink><RouterLink to="/resumes">Active resumes</RouterLink></nav></header>
    <p v-if="loading" class="status-panel">Loading task state…</p>
    <section v-else-if="gone" class="empty-state"><h2>Task archived</h2><p>This task was archived, deleted, or stopped before a result was available.</p></section>
    <section v-else-if="notReady" class="empty-state"><h2>Result not ready</h2><p>The task has not produced result data yet. No evidence result is displayed.</p></section>
    <section v-else-if="error" class="empty-state"><h2>Result unavailable</h2><p class="error" role="alert">{{ error }}</p></section>
    <section v-else-if="task && task.state !== 'SUCCEEDED'" class="status-panel" aria-live="polite">
      <p class="eyebrow">{{ statusLabel }}</p>
      <h2 v-if="task.state === 'QUEUED'">Waiting to start</h2>
      <h2 v-else-if="task.state === 'PROCESSING'">Matching in progress</h2>
      <h2 v-else-if="task.state === 'FAILED'">Failed</h2>
      <h2 v-else-if="task.state === 'TIMED_OUT'">Timed out</h2>
      <h2 v-else>Task blocked or archived</h2>
      <p v-if="task.state === 'FAILED'" class="error">No result is available{{ task.failureCode ? ` (${task.failureCode})` : '' }}.</p>
      <p v-else-if="task.state === 'TIMED_OUT'" class="error">The task exceeded its processing window and polling has stopped.</p>
      <p v-else-if="task.state === 'BLOCKED'" class="error">The task cannot continue and polling has stopped.</p>
    </section>
    <template v-else-if="result">
      <section class="job-description"><p class="eyebrow">Java backend role</p><h2>Job description</h2><p>{{ result.jobDescriptionText }}</p></section>
      <section class="score-band"><div><span>Composite match</span><strong>{{ Math.round(result.score.composite * 100) }}%</strong></div><dl><template v-for="item in scoreItems" :key="item[0]"><dt>{{ item[0] }}</dt><dd>{{ Math.round(item[1] * 100) }}%</dd></template></dl></section>
      <section class="result-section"><div class="section-heading"><div><p class="eyebrow">Requirement evidence</p><h2>What the resume supports</h2></div><p class="muted">Related-but-insufficient and unmet requirements are not positive matches.</p></div><MatchEvidenceTable :requirements="result.requirements" /></section>
      <section class="result-section suggestions-section"><div class="section-heading"><div><p class="eyebrow">Suggestions</p><h2>Review separately</h2></div><p class="muted">Nothing here is automatically applied to a resume.</p></div>
        <div v-if="result.suggestions.length" class="suggestion-list"><article v-for="suggestion in result.suggestions" :key="suggestion.id" :class="['suggestion-row', `suggestion-${suggestion.state.toLowerCase()}`]"><div><span class="state-badge">{{ suggestionLabel(suggestion.state) }}</span><p>{{ suggestion.proposedText }}</p><small v-if="suggestion.state === 'NEEDS_USER_CONFIRMATION'">Unconfirmed fact — not applied.</small><small v-else-if="suggestion.state === 'RISKY_OR_UNSUPPORTED'">Unsupported claim — cannot be applied.</small></div></article></div>
        <p v-else class="empty-state">No suggestions are available for this result.</p>
      </section>
    </template>
    <section v-else class="empty-state"><h2>No result data</h2><p>The completed task did not return evidence data.</p></section>
  </main>
</template>
