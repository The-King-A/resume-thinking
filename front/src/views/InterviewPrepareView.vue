<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { interviewApi } from '../api/interview'
import type { InterviewQuestion, InterviewSession } from '../api/contracts'

const route = useRoute()
const router = useRouter()
const session = ref<InterviewSession | null>(null)
const questions = ref<InterviewQuestion[]>([])
const error = ref('')
const loading = ref(true)
let timer: number | undefined
const taskId = String(route.query.matchTaskId || '')

const key = () => `interview-create-${crypto.randomUUID()}`
const validTask = /^task[0-9]{3,}$/.test(taskId)

async function loadQuestions(id: string) {
  try {
    const next = await interviewApi.getQuestions(id)
    session.value = next.session
    questions.value = next.questions
    loading.value = false
  } catch {
    if (!session.value || session.value.state === 'QUESTION_GENERATING') timer = window.setTimeout(() => void loadSession(id), 1200)
  }
}

async function loadSession(id: string) {
  try {
    const next = await interviewApi.getSession(id)
    session.value = next
    if (next.state === 'QUESTION_GENERATING') timer = window.setTimeout(() => void loadSession(id), 1200)
    else if (next.state === 'WAITING_FOR_ANSWER') await loadQuestions(id)
    else { loading.value = false; error.value = next.state === 'FAILED' ? '题目生成失败，请返回匹配报告后重试。' : '此面试会话不可继续。' }
  } catch { loading.value = false; error.value = '无法读取面试会话。' }
}

async function begin() {
  if (!validTask) { await router.replace('/resumes'); return }
  try {
    session.value = await interviewApi.createSession({ matchTaskId: taskId, idempotencyKey: key() })
    await loadSession(session.value.id)
  } catch { loading.value = false; error.value = '无法从当前匹配报告创建面试准备。' }
}

onMounted(() => void begin())
onBeforeUnmount(() => { if (timer) window.clearTimeout(timer) })
</script>

<template>
  <main class="workspace interview-workspace">
    <header class="workspace-header"><div><p class="eyebrow">面试准备</p><h1>从证据出发练习表达</h1><p class="muted">仅使用已匹配的岗位要求和简历证据生成练习题；回答不会自动写入简历。</p></div><nav class="workspace-nav"><RouterLink to="/resumes">有效简历</RouterLink></nav></header>
    <section v-if="loading" class="status-panel"><h2>正在生成面试题</h2><p>系统正在根据岗位要求与已有证据准备四类问题。</p></section>
    <section v-else-if="error" class="empty-state"><h2>暂时无法开始</h2><p class="error">{{ error }}</p></section>
    <template v-else-if="session">
      <section class="interview-disclosure"><strong>数据说明</strong><p>回答会加密保存；发送给模型前会进行脱敏和最小化处理。未经确认的新增事实不会进入简历。</p></section>
      <section class="interview-question-grid"><article v-for="item in questions" :key="item.id" class="interview-question-card"><div><span class="state-badge">{{ item.questionType }}</span><span class="state-badge">{{ item.difficulty }}</span></div><h2>{{ item.questionText }}</h2><p>{{ item.requirementText }}</p><small>生成依据：{{ item.generationReason }} · {{ item.evidenceIds.length }} 条证据</small></article></section>
      <RouterLink class="button-link workspace-command workspace-command-primary" :to="`/interviews/${session.id}`">开始单轮练习</RouterLink>
    </template>
  </main>
</template>
