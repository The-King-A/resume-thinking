<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { interviewApi } from '../api/interview'
import { ApiError } from '../api/contracts'
import { friendlyError, friendlyFailureCode } from '../i18n/messages'
import type { InterviewFeedback, InterviewSession } from '../api/contracts'

const route = useRoute(); const router = useRouter(); const sessionId = String(route.params.sessionId || '')
const feedback = ref<InterviewFeedback | null>(null); const error = ref(''); const loading = ref(true); const moving = ref(false); let timer: number | undefined
function scheduleRetry() {
  if (timer) window.clearTimeout(timer)
  timer = window.setTimeout(() => { if (loading.value) void load() }, 1200)
}

async function load() {
  try {
    feedback.value = await interviewApi.getFeedback(sessionId)
    loading.value = false
  } catch (caught) {
    if (caught instanceof ApiError && caught.code === 'INTERVIEW_FEEDBACK_NOT_READY') {
      try {
        const current: InterviewSession = await interviewApi.getSession(sessionId)
        if (current.state === 'FAILED') {
          loading.value = false
          error.value = friendlyFailureCode(current.failureCode) || '回答分析失败，请重新开始练习。'
          return
        }
        if (current.state === 'DELETED' || current.state === 'COMPLETED') {
          loading.value = false
          error.value = '面试会话已结束或已清理。'
          return
        }
      } catch (statusError) {
        if (statusError instanceof ApiError && statusError.code !== 'INTERVIEW_FEEDBACK_NOT_READY') {
          loading.value = false
          error.value = friendlyError(statusError, '无法读取回答分析状态。')
          return
        }
      }
      scheduleRetry()
      return
    }
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
onMounted(() => void load()); onBeforeUnmount(() => { if (timer) window.clearTimeout(timer) })
</script>

<template>
  <main class="workspace interview-workspace"><header class="workspace-header"><div><p class="eyebrow">回答反馈</p><h1>单轮回答分析</h1><p class="muted">反馈用于练习，不会修改你的简历事实；完成分析后可以直接切换下一题。</p></div><button class="button-danger-quiet" type="button" @click="stop">结束会话</button></header>
    <section v-if="loading" class="status-panel"><h2>正在分析回答</h2><p>请等待结构化反馈生成。</p></section><section v-else-if="!feedback" class="empty-state"><h2>反馈不可用</h2><p class="error">{{ error || '未获得反馈结果。' }}</p></section>
    <template v-else><section class="answer-review result-section"><h2>你的回答</h2><blockquote>{{ feedback.submittedAnswer || '暂未读取到本轮回答。' }}</blockquote></section><section class="answer-review result-section"><h2>建议回答</h2><p>{{ feedback.suggestedAnswer || feedback.improvementSuggestion }}</p></section><section class="answer-review result-section"><h2>回答对比分析</h2><p>{{ feedback.answerComparison || '暂无对比分析。' }}</p></section><section class="interview-next-question"><button v-if="feedback.nextQuestionId !== null" data-test="next-question" class="workspace-command workspace-command-primary" type="button" :disabled="moving" @click="nextQuestion">{{ moving ? '正在切换…' : '换下一题' }}</button><button v-else data-test="finish-practice" class="workspace-command workspace-command-primary" type="button" :disabled="moving" @click="nextQuestion">{{ moving ? '正在完成…' : '完成本次练习' }}</button></section><p v-if="error" class="error">{{ error }}</p></template>
  </main>
</template>
