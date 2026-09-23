<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { interviewApi } from '../api/interview'
import { ApiError } from '../api/contracts'
import { friendlyError, friendlyFailureCode } from '../i18n/messages'
import type { InterviewFeedback, InterviewSession } from '../api/contracts'

const route = useRoute(); const router = useRouter(); const sessionId = String(route.params.sessionId || '')
const feedback = ref<InterviewFeedback | null>(null); const error = ref(''); const loading = ref(true); const moving = ref(false); let timer: number | undefined
let loadGeneration = 0
let retryCount = 0
const MAX_FEEDBACK_RETRIES = 120
const FEEDBACK_RETRY_INITIAL_DELAY_MS = 1200
const FEEDBACK_RETRY_MAX_DELAY_MS = 10000
function clearRetry() {
  if (timer) window.clearTimeout(timer)
  timer = undefined
}
function scheduleRetry(generation: number) {
  if (generation !== loadGeneration || !loading.value || timer) return
  if (retryCount >= MAX_FEEDBACK_RETRIES) {
    loading.value = false
    error.value = '回答分析等待时间过长，请刷新后重试。'
    return
  }
  retryCount += 1
  if (timer) window.clearTimeout(timer)
  const delay = Math.min(FEEDBACK_RETRY_INITIAL_DELAY_MS * 2 ** Math.min(retryCount - 1, 3), FEEDBACK_RETRY_MAX_DELAY_MS)
  timer = window.setTimeout(() => {
    timer = undefined
    if (generation === loadGeneration && loading.value) void load(generation)
  }, delay)
}

async function load(generation = loadGeneration) {
  if (generation !== loadGeneration || !loading.value) return
  try {
    const current: InterviewSession = await interviewApi.getSession(sessionId)
    if (generation !== loadGeneration) return

    if (current.state === 'FAILED') {
      clearRetry()
      loading.value = false
      error.value = friendlyFailureCode(current.failureCode) || '回答分析失败，请重新开始练习。'
      return
    }
    if (current.state === 'DELETED' || current.state === 'COMPLETED') {
      clearRetry()
      loading.value = false
      error.value = '面试会话已结束或已清理。'
      return
    }
    if (current.state !== 'FEEDBACK_READY') {
      scheduleRetry(generation)
      return
    }

    const nextFeedback = await interviewApi.getFeedback(sessionId)
    if (generation !== loadGeneration) return
    clearRetry()
    feedback.value = nextFeedback
    retryCount = 0
    loading.value = false
  } catch (caught) {
    if (generation !== loadGeneration) return
    if (caught instanceof ApiError && caught.code === 'INTERVIEW_FEEDBACK_NOT_READY') {
      scheduleRetry(generation)
      return
    }
    clearRetry()
    loading.value = false
    error.value = friendlyError(caught, '回答分析失败，请重新开始练习。')
  }
}
async function nextQuestion() {
  if (!feedback.value || moving.value) return
  moving.value = true
  error.value = ''
  try {
    const current = await interviewApi.getSession(sessionId)
    if (current.state !== 'FEEDBACK_READY') {
      error.value = '当前回答还未准备好切换题目，请稍后重试。'
      return
    }
    await interviewApi.nextQuestion(sessionId, { expectedSessionVersion: current.version })
    await router.replace(`/interviews/${sessionId}`)
  } catch (caught) {
    error.value = friendlyError(caught, '切换题目失败，请稍后重试。')
  } finally {
    moving.value = false
  }
}
async function stop() { try { await interviewApi.deleteSession(sessionId); await router.replace('/resumes') } catch { error.value = '无法结束面试会话。' } }
onMounted(() => { loadGeneration += 1; retryCount = 0; void load(loadGeneration) }); onBeforeUnmount(() => { loadGeneration += 1; clearRetry() })
</script>

<template>
  <main class="workspace interview-workspace"><header class="workspace-header"><div><p class="eyebrow">回答反馈</p><h1>单轮回答分析</h1><p class="muted">反馈用于练习，不会修改你的简历事实；完成分析后可以直接切换下一题。</p></div><button class="button-danger-quiet" type="button" @click="stop">结束会话</button></header>
    <section v-if="loading" class="status-panel"><h2>正在分析回答</h2><p>请等待结构化反馈生成。</p></section><section v-else-if="!feedback" class="empty-state"><h2>反馈不可用</h2><p class="error">{{ error || '未获得反馈结果。' }}</p></section>
    <template v-else><section class="answer-review result-section"><h2>你的回答</h2><blockquote>{{ feedback.submittedAnswer || '暂未读取到本轮回答。' }}</blockquote></section><section class="answer-review result-section"><h2>建议回答</h2><p>{{ feedback.suggestedAnswer || feedback.improvementSuggestion }}</p></section><section class="answer-review result-section"><h2>回答对比分析</h2><p>{{ feedback.answerComparison || '暂无对比分析。' }}</p></section><section class="interview-next-question"><button v-if="feedback.nextQuestionId !== null" data-test="next-question" class="workspace-command workspace-command-primary" type="button" :disabled="moving" @click="nextQuestion">{{ moving ? '正在切换…' : '换下一题' }}</button><button v-else data-test="finish-practice" class="workspace-command workspace-command-primary" type="button" :disabled="moving" @click="nextQuestion">{{ moving ? '正在完成…' : '完成本次练习' }}</button></section><p v-if="error" class="error">{{ error }}</p></template>
  </main>
</template>
