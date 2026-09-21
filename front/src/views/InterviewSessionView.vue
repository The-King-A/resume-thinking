<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { interviewApi } from '../api/interview'
import type { InterviewQuestion, InterviewSession } from '../api/contracts'

const route = useRoute(); const router = useRouter(); const sessionId = String(route.params.sessionId || '')
const session = ref<InterviewSession | null>(null); const questions = ref<InterviewQuestion[]>([]); const selected = ref(''); const answer = ref(''); const error = ref(''); const submitting = ref(false); let timer: number | undefined
const question = computed(() => questions.value.find(item => item.id === selected.value) || questions.value[0])
const questionTypeLabels: Record<InterviewQuestion['questionType'], string> = {
  BASIC_CONFIRMATION: '经历核对',
  PROJECT_DEEP_DIVE: '项目深挖',
  JOB_SCENARIO: '岗位场景',
  SYNTHESIS_FOLLOW_UP: '综合追问',
}
function questionTypeLabel(type: InterviewQuestion['questionType']) { return questionTypeLabels[type] }
function switchQuestion(id: string) {
  if (id === selected.value) return
  selected.value = id
  answer.value = ''
  error.value = ''
}

async function load() { try { const next = await interviewApi.getQuestions(sessionId); session.value = next.session; questions.value = next.questions; if (!selected.value) selected.value = next.session.activeQuestionId || next.questions[0]?.id || '' } catch { error.value = '无法读取面试题。' } }
async function submit() { if (!session.value || !question.value || !answer.value.trim()) return; submitting.value = true; try { await interviewApi.submitAnswer(sessionId, { questionId: question.value.id, answerText: answer.value.trim(), expectedSessionVersion: session.value.version, idempotencyKey: `interview-answer-${crypto.randomUUID()}` }); await router.push(`/interviews/${sessionId}/feedback`) } catch { error.value = '回答提交失败，请刷新后重试。' } finally { submitting.value = false } }
async function stop() { try { await interviewApi.deleteSession(sessionId); await router.replace('/resumes') } catch { error.value = '无法结束面试会话。' } }
onMounted(() => void load()); onBeforeUnmount(() => { if (timer) window.clearTimeout(timer) })
</script>

<template>
  <main class="workspace interview-workspace"><header class="workspace-header"><div><p class="eyebrow">单轮推演</p><h1>选择一个问题，给出真实回答</h1><p class="muted">每道题都对应岗位要求与简历证据，你可以在提交前切换练习题。</p></div><button class="button-danger-quiet" type="button" @click="stop">结束会话</button></header>
    <section v-if="error" class="error" role="alert">{{ error }}</section><section v-if="!session" class="status-panel">正在加载题目…</section>
    <section v-else-if="session.state !== 'WAITING_FOR_ANSWER'" class="status-panel"><h2>{{ session.state === 'COMPLETED' ? '本次练习已完成' : session.state === 'FAILED' ? '面试分析未完成' : '正在准备下一步' }}</h2><p>{{ session.state === 'COMPLETED' ? '所有题目都已完成，可以结束本次会话。' : session.state === 'FAILED' ? '当前会话遇到问题，请返回结果页查看具体提示。' : '请等待当前会话状态更新。' }}</p></section>
    <form v-else-if="question" class="interview-answer-form" @submit.prevent="submit"><fieldset class="question-switcher-fieldset"><legend>选择练习题</legend><nav class="question-switcher" aria-label="面试题目导航"><button v-for="item in questions" :key="item.id" data-test="question-switcher-item" class="question-switcher-item" :class="{ 'question-switcher-item-active': item.id === selected }" :aria-pressed="item.id === selected" :disabled="item.answered" :title="item.questionText" type="button" @click="switchQuestion(item.id)"><span class="question-switcher-index">第 {{ item.sequence }} 题 <em v-if="item.answered">已完成</em></span><strong>{{ questionTypeLabel(item.questionType) }}</strong><span class="question-switcher-text">{{ item.questionText }}</span></button></nav></fieldset><article class="interview-question-card"><span class="state-badge">{{ question.difficulty }}</span><h2>{{ question.questionText }}</h2><p>关联要求：{{ question.requirementText }}</p></article><label>你的回答<textarea v-model="answer" maxlength="8000" rows="10" placeholder="请使用真实的项目细节、职责和技术取舍回答。" /></label><p class="muted">{{ answer.length }}/8000</p><button class="button-link workspace-command workspace-command-primary" :disabled="submitting || !answer.trim()">{{ submitting ? '正在提交…' : '提交并分析' }}</button></form>
  </main>
</template>
