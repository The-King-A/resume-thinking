<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ApiError, type ResumeMatchContext } from '../api/contracts'
import { lifecycleApi } from '../api/lifecycle'
import { friendlyError, lifecycleMessages } from '../i18n/messages'
import { useLlmProfileStore } from '../stores/llmProfiles'
import { showTopNotification } from '../ui/notifications'

const route = useRoute()
const router = useRouter()
const profileStore = useLlmProfileStore()
const resumeId = computed(() => String(route.params.resumeId || ''))
const context = ref<ResumeMatchContext | null>(null)
const title = ref('')
const profileId = ref('')
const jobDescription = ref('')
const replacementFile = ref<File | null>(null)
const fileName = ref('')
const fileError = ref('')
const loading = ref(true)
const submitting = ref(false)
const error = ref('')
let contextGeneration = 0
let submissionGeneration = 0
let active = true

const canSubmit = computed(() => Boolean(
  context.value?.effectiveRevisionId
  && title.value.trim()
  && profileId.value
  && jobDescription.value.trim().length >= 20
  && !fileError.value
  && !submitting.value,
))

function createIdempotencyKey() {
  return `match-${globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random().toString(36).slice(2)}`}`
}

function report(message: string, type: 'success' | 'error') {
  try { showTopNotification(message, type) } catch { /* Rendering feedback must not change the lifecycle operation. */ }
}

function handleFile(event: Event) {
  const next = (event.target as HTMLInputElement).files?.[0] || null
  replacementFile.value = null
  fileName.value = next?.name || ''
  fileError.value = ''
  if (!next) return
  const extension = next.name.toLowerCase().slice(next.name.lastIndexOf('.'))
  if (extension === '.pdf') { fileError.value = '不支持 PDF 文件，请上传 TXT 或 DOCX 简历。'; return }
  if (extension !== '.txt' && extension !== '.docx') { fileError.value = '不支持此文件类型，请上传 TXT 或 DOCX 简历。'; return }
  replacementFile.value = next
}

function rematchError(caught: unknown) {
  if (caught instanceof ApiError && caught.payload.detailCode === 'DUPLICATE_RESUME_TITLE') return lifecycleMessages.duplicateTitle
  if (caught instanceof ApiError && (caught.code === 'VERSION_CONFLICT' || caught.code === 'STALE_ATTEMPT')) return lifecycleMessages.staleEffectiveRevision
  return friendlyError(caught, lifecycleMessages.submissionFailed)
}

function isCurrentContext(id: string, generation: number) {
  return active && generation === contextGeneration && id === resumeId.value
}

function isCurrentSubmission(id: string, generation: number) {
  return active && generation === submissionGeneration && id === resumeId.value
}

async function loadContext() {
  const id = resumeId.value
  const generation = ++contextGeneration
  ++submissionGeneration
  loading.value = true
  submitting.value = false
  error.value = ''
  context.value = null
  title.value = ''
  profileId.value = ''
  jobDescription.value = ''
  replacementFile.value = null
  fileName.value = ''
  fileError.value = ''
  try {
    const next = await lifecycleApi.getResumeMatchContext(id)
    if (!isCurrentContext(id, generation)) return
    context.value = next
    title.value = next.title
    profileId.value = next.llmProfileId
    jobDescription.value = next.jobDescriptionText
    if (!next.effectiveRevisionId) error.value = '当前候选简历尚未形成有效版本，暂时不能重新匹配。'
  } catch (caught) {
    if (!isCurrentContext(id, generation)) return
    error.value = friendlyError(caught, lifecycleMessages.rematchContextFailed)
    report(error.value, 'error')
  } finally { if (isCurrentContext(id, generation)) loading.value = false }
}

async function submitRematch() {
  if (!canSubmit.value || !context.value?.effectiveRevisionId) return
  const id = resumeId.value
  const generation = ++submissionGeneration
  const expectedEffectiveRevisionId = context.value.effectiveRevisionId
  submitting.value = true
  error.value = ''
  try {
    const task = await lifecycleApi.submitResumeRematch(id, {
      expectedEffectiveRevisionId,
      ...(replacementFile.value ? { file: replacementFile.value } : {}),
      title: title.value.trim(),
      llmProfileId: profileId.value,
      jobFamily: 'JAVA_BACKEND',
      jobDescriptionText: jobDescription.value.trim(),
      idempotencyKey: createIdempotencyKey(),
    })
    if (!isCurrentSubmission(id, generation)) return
    report('重新匹配任务已提交，正在打开报告状态。', 'success')
    await router.push(`/matches/${task.id}`)
  } catch (caught) {
    if (!isCurrentSubmission(id, generation)) return
    error.value = rematchError(caught)
    report(error.value, 'error')
  } finally { if (isCurrentSubmission(id, generation)) submitting.value = false }
}

watch(() => route.params.resumeId, () => { void loadContext() })
onMounted(async () => {
  try { await profileStore.list() } catch { error.value = '无法加载模型配置。' }
  if (active) await loadContext()
})
onBeforeUnmount(() => { active = false; ++contextGeneration; ++submissionGeneration })
</script>

<template>
  <main class="workspace">
    <header class="workspace-header"><div><p class="eyebrow">有效简历</p><h1>重新匹配</h1><p class="muted">保留现有简历文件，或选填一份替换文件后提交新的候选版本。</p></div><nav class="workspace-nav"><RouterLink class="workspace-command" to="/resumes">返回有效简历</RouterLink><RouterLink class="workspace-command workspace-command-primary" to="/match">新建匹配</RouterLink></nav></header>
    <p v-if="loading" class="status-panel">正在加载重新匹配信息…</p>
    <section v-else class="match-layout">
      <form class="operation-panel" @submit.prevent="submitRematch">
        <h2>候选版本</h2>
        <p v-if="context?.effectiveRevisionId" class="context-summary">当前有效版本：<code class="record-id">{{ context.effectiveRevisionId }}</code></p>
        <label>简历标题<input data-test="resume-title" v-model="title" maxlength="200" /></label>
        <label>替换简历文件（可选）<input type="file" accept=".txt,.docx" @change="handleFile" /><span class="field-help">留空将沿用当前有效简历文件；仅支持 TXT 和 DOCX。</span></label>
        <p v-if="fileName" class="muted">已选择：{{ fileName }}</p>
        <p v-if="fileError" class="error" role="alert">{{ fileError }}</p>
        <label>模型配置<select v-model="profileId"><option value="" disabled>请选择已保存的模型配置</option><option v-for="profile in profileStore.profiles" :key="profile.id" :value="profile.id">{{ profile.displayName }}</option></select></label>
        <label>Java 后端岗位描述<textarea v-model="jobDescription" minlength="20" maxlength="20000" rows="10"></textarea><span class="field-help">至少 20 个字符。</span></label>
        <button type="submit" :disabled="!canSubmit">{{ submitting ? '正在提交…' : '提交重新匹配' }}</button>
      </form>
      <section class="operation-status" aria-live="polite"><p class="eyebrow">提交状态</p><h2>{{ error ? '无法提交' : '等待提交' }}</h2><p v-if="error" class="error" role="alert">{{ error }}</p><p v-else class="muted">提交后将跳转到现有的任务结果页面。</p></section>
    </section>
  </main>
</template>
