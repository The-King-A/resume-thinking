<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { lifecycleApi } from '../api/lifecycle'
import { ApiError, type MatchTask } from '../api/contracts'
import { useLlmProfileStore } from '../stores/llmProfiles'
import { friendlyError, friendlyFailureCode, lifecycleMessages } from '../i18n/messages'
import { showTopNotification } from '../ui/notifications'

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
const duplicateTitleTaskId = ref('')
const error = ref('')
let pollTimer: number | undefined
let requestGeneration = 0
let active = true

const availableProfiles = computed(() => profileStore.profiles)
const canSubmit = computed(() => Boolean(file.value && profileId.value && jobDescription.value.trim().length >= 20 && !fileError.value && !submitting.value))
const taskLabel = computed(() => ({ QUEUED: '排队中', PROCESSING: '处理中', SUCCEEDED: '已完成', FAILED: '失败', TIMED_OUT: '已超时', BLOCKED: '已归档或阻塞' }[task.value?.state || 'QUEUED']))
const taskFailureLabel = computed(() => friendlyFailureCode(task.value?.failureCode))

function handleFile(event: Event) {
  const next = (event.target as HTMLInputElement).files?.[0] || null
  file.value = null
  fileName.value = next?.name || ''
  fileError.value = ''
  if (!next) return
  const extension = next.name.toLowerCase().slice(next.name.lastIndexOf('.'))
  if (extension === '.pdf') { fileError.value = '不支持 PDF 文件，请上传 UTF-8 编码的 TXT 或 DOCX 简历。'; return }
  if (extension !== '.txt' && extension !== '.docx') { fileError.value = '不支持此文件类型，请上传 UTF-8 编码的 TXT 或 DOCX 简历。'; return }
  file.value = next
}

function createIdempotencyKey() {
  return `match-${globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random().toString(36).slice(2)}`}`
}

function report(message: string, type: 'success' | 'error') {
  try { showTopNotification(message, type) } catch { /* Rendering feedback must not change the lifecycle operation. */ }
}

function submissionError(caught: unknown) {
  if (caught instanceof ApiError && caught.payload.detailCode === 'DUPLICATE_RESUME_TITLE') return lifecycleMessages.duplicateTitle
  return friendlyError(caught, lifecycleMessages.submissionFailed)
}

function isCurrent(generation: number) {
  return active && generation === requestGeneration
}

function schedulePoll(generation: number) {
  if (!isCurrent(generation) || (task.value?.state !== 'QUEUED' && task.value?.state !== 'PROCESSING')) return
  if (pollTimer) window.clearTimeout(pollTimer)
  pollTimer = window.setTimeout(() => {
    pollTimer = undefined
    if (isCurrent(generation)) void pollTask(generation)
  }, 1500)
}

async function pollTask(generation: number) {
  const currentTask = task.value
  if (!isCurrent(generation) || !currentTask || (currentTask.state !== 'QUEUED' && currentTask.state !== 'PROCESSING')) return
  try {
    const next = await lifecycleApi.getMatchTask(currentTask.id)
    if (!isCurrent(generation) || task.value?.id !== currentTask.id) return
    task.value = next
    schedulePoll(generation)
  }
  catch (caught) {
    if (!isCurrent(generation) || task.value?.id !== currentTask.id) return
    if (caught instanceof ApiError && caught.code === 'TASK_GONE') taskGone.value = true
    else if (caught instanceof ApiError && caught.code === 'DUPLICATE_RESOURCE') {
      duplicateTitleTaskId.value = currentTask.id
      task.value = null
      report('报告已生成，但该候选简历未成为有效简历：简历标题重复。', 'error')
    }
    else error.value = '无法刷新任务状态。'
  }
}

async function startMatch() {
  if (!canSubmit.value || !file.value) return
  const generation = ++requestGeneration
  submitting.value = true
  error.value = ''
  taskGone.value = false
  duplicateTitleTaskId.value = ''
  if (pollTimer) window.clearTimeout(pollTimer)
  try {
    const nextTask = await lifecycleApi.submitInitialMatch({
      file: file.value,
      title: title.value.trim() || undefined,
      llmProfileId: profileId.value,
      jobFamily: 'JAVA_BACKEND',
      jobDescriptionText: jobDescription.value.trim(),
      idempotencyKey: createIdempotencyKey(),
    })
    if (!isCurrent(generation)) return
    task.value = nextTask
    report('匹配任务已提交，正在等待处理。', 'success')
    schedulePoll(generation)
  } catch (caught) {
    if (!isCurrent(generation)) return
    error.value = submissionError(caught)
    report(error.value, 'error')
  } finally { if (isCurrent(generation)) submitting.value = false }
}

onMounted(async () => {
  try {
    await profileStore.list()
    if (active) profileId.value = profileStore.profiles.find((profile) => profile.selected)?.id || ''
  } catch { error.value = '无法加载模型配置。' }
})
onBeforeUnmount(() => { active = false; ++requestGeneration; if (pollTimer) window.clearTimeout(pollTimer) })
</script>

<template>
  <main class="workspace">
    <header class="workspace-header"><div><p class="eyebrow">新建匹配</p><h1>上传并匹配</h1><p class="muted">当前版本仅支持通过 Java 服务提交 Java 后端岗位描述。</p></div><RouterLink to="/resumes">有效简历</RouterLink></header>
    <div class="match-layout">
      <form class="operation-panel" @submit.prevent="startMatch">
        <h2>简历与岗位</h2>
        <label>简历文件<input type="file" accept=".txt,.docx" @change="handleFile" /><span class="field-help">仅支持 TXT 和 DOCX；当前版本不支持 PDF。</span></label>
        <p v-if="fileName" class="muted">已选择：{{ fileName }}</p>
        <p v-if="fileError" class="error" role="alert">{{ fileError }}</p>
        <label>简历标题（可选）<input v-model="title" maxlength="200" /></label>
        <label>已选模型配置<select v-model="profileId"><option value="" disabled>请选择已保存的模型配置</option><option v-for="profile in availableProfiles" :key="profile.id" :value="profile.id">{{ profile.displayName }}</option></select><span v-if="!availableProfiles.length" class="field-help">请先在设置中选择已保存的模型配置，再开始匹配。</span></label>
        <label>Java 后端岗位描述<textarea v-model="jobDescription" minlength="20" maxlength="20000" rows="10" placeholder="粘贴来自可信岗位来源的 Java 后端岗位要求。"></textarea><span class="field-help">至少 20 个字符。文本仅发送到 Java 后端。</span></label>
        <button data-test="start-match" type="submit" :disabled="!canSubmit">{{ submitting ? '正在启动…' : '开始证据匹配' }}</button>
      </form>
      <section class="operation-status" aria-live="polite">
        <p class="eyebrow">任务状态</p>
        <p v-if="task" class="muted"><code class="record-id">{{ task.id }}</code>关联简历 {{ task.resumeId }}</p>
        <h2 v-if="duplicateTitleTaskId">简历未生效</h2><h2 v-else-if="task">{{ taskLabel }}</h2><h2 v-else>尚未创建任务</h2>
        <p v-if="task?.state === 'QUEUED'" class="muted">正在等待 Java 编排服务开始处理。</p>
        <p v-else-if="task?.state === 'PROCESSING'" class="muted">正在校验证据。处理期间页面会自动刷新。</p>
        <p v-else-if="task?.state === 'FAILED'" class="error">匹配失败{{ taskFailureLabel ? `：${taskFailureLabel}` : '' }}，未生成结果。</p>
        <p v-else-if="task?.state === 'TIMED_OUT'" class="error">{{ taskFailureLabel || '任务处理超时，暂无结果。' }}</p>
        <p v-else-if="task?.state === 'BLOCKED'" class="error">任务已阻塞或归档，不会继续刷新。</p>
        <p v-if="taskGone" class="error">任务在完成前已被归档、删除或停止。</p>
        <template v-if="duplicateTitleTaskId"><p class="error">报告有效，但该候选简历未成为有效简历：简历标题重复。</p><RouterLink class="button-link workspace-command workspace-command-primary" :to="`/matches/${duplicateTitleTaskId}`">查看匹配报告</RouterLink></template>
        <p v-if="error" class="error" role="alert">{{ error }}</p>
        <RouterLink v-if="task?.state === 'SUCCEEDED'" class="button-link" :to="`/matches/${task.id}`">查看证据结果</RouterLink>
        <RouterLink v-else-if="task?.state === 'FAILED'" class="button-link" :to="`/matches/${task.id}`">查看任务详情</RouterLink>
      </section>
    </div>
  </main>
</template>
