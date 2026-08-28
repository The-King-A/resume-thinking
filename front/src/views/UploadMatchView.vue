<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { lifecycleApi } from '../api/lifecycle'
import { ApiError, type MatchTask } from '../api/contracts'
import { useLlmProfileStore } from '../stores/llmProfiles'
import { friendlyFailureCode } from '../i18n/messages'

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

function schedulePoll() {
  if (task.value?.state !== 'QUEUED' && task.value?.state !== 'PROCESSING') return
  pollTimer = window.setTimeout(pollTask, 1500)
}

async function pollTask() {
  if (!task.value || (task.value.state !== 'QUEUED' && task.value.state !== 'PROCESSING')) return
  try { task.value = await lifecycleApi.getMatchTask(task.value.id); schedulePoll() }
  catch (caught) {
    if (caught instanceof ApiError && caught.code === 'TASK_GONE') taskGone.value = true
    else error.value = '无法刷新任务状态。'
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
      jobFamily: 'JAVA_BACKEND',
      jobDescriptionText: jobDescription.value.trim(),
      idempotencyKey: `match-${globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random().toString(36).slice(2)}`}`,
    })
    schedulePoll()
  } catch { error.value = '无法上传简历或启动匹配任务。' }
  finally { submitting.value = false }
}

onMounted(async () => {
  try {
    await profileStore.list()
    profileId.value = profileStore.profiles.find((profile) => profile.selected)?.id || ''
  } catch { error.value = '无法加载模型配置。' }
})
onBeforeUnmount(() => { if (pollTimer) window.clearTimeout(pollTimer) })
</script>

<template>
  <main class="workspace">
    <header class="workspace-header"><div><p class="eyebrow">新建匹配</p><h1>上传并匹配</h1><p class="muted">当前版本仅支持通过 Java 服务提交 Java 后端岗位描述。</p></div><RouterLink to="/resumes">有效简历</RouterLink></header>
    <div class="match-layout">
      <form class="operation-panel" @submit.prevent="startMatch">
        <h2>简历与岗位</h2>
        <label>简历文件<input type="file" accept=".txt,.docx" @change="handleFile" /><span class="field-help">仅支持 TXT 和 DOCX；v1 明确不支持 PDF。</span></label>
        <p v-if="fileName" class="muted">已选择：{{ fileName }}</p>
        <p v-if="fileError" class="error" role="alert">{{ fileError }}</p>
        <label>简历标题（可选）<input v-model="title" maxlength="200" /></label>
        <label>已选模型配置<select v-model="profileId"><option value="" disabled>请选择已保存的模型配置</option><option v-for="profile in availableProfiles" :key="profile.id" :value="profile.id">{{ profile.displayName }}</option></select><span v-if="!availableProfiles.length" class="field-help">请先在设置中选择已保存的模型配置，再开始匹配。</span></label>
        <label>Java 后端岗位描述<textarea v-model="jobDescription" minlength="20" maxlength="20000" rows="10" placeholder="粘贴来自可信岗位来源的 Java 后端岗位要求。"></textarea><span class="field-help">至少 20 个字符。文本仅发送到 Java 后端。</span></label>
        <button data-test="start-match" type="submit" :disabled="!canSubmit">{{ submitting ? '正在启动…' : '开始证据匹配' }}</button>
      </form>
      <section class="operation-status" aria-live="polite">
        <p class="eyebrow">任务状态</p>
        <h2 v-if="task">{{ taskLabel }}</h2><h2 v-else>尚未创建任务</h2>
        <p v-if="task?.state === 'QUEUED'" class="muted">正在等待 Java 编排服务开始处理。</p>
        <p v-else-if="task?.state === 'PROCESSING'" class="muted">正在校验证据。处理期间页面会自动刷新。</p>
        <p v-else-if="task?.state === 'FAILED'" class="error">匹配失败{{ taskFailureLabel ? `：${taskFailureLabel}` : '' }}，未生成结果。</p>
        <p v-else-if="task?.state === 'TIMED_OUT'" class="error">任务处理超时，暂无结果。</p>
        <p v-else-if="task?.state === 'BLOCKED'" class="error">任务已阻塞或归档，不会继续刷新。</p>
        <p v-if="taskGone" class="error">任务在完成前已被归档、删除或停止。</p>
        <p v-if="error" class="error" role="alert">{{ error }}</p>
        <RouterLink v-if="task?.state === 'SUCCEEDED'" class="button-link" :to="`/matches/${task.id}`">查看证据结果</RouterLink>
      </section>
    </div>
  </main>
</template>
