<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { lifecycleApi } from '../api/lifecycle'
import { ApiError, type MatchResult, type MatchTask, type SuggestionState } from '../api/contracts'
import MatchEvidenceTable from '../components/MatchEvidenceTable.vue'
import { friendlyFailureCode } from '../i18n/messages'

const route = useRoute()
const taskId = ref(String(route.params.taskId || ''))
const task = ref<MatchTask | null>(null)
const result = ref<MatchResult | null>(null)
const loading = ref(true)
const gone = ref(false)
const notReady = ref(false)
const error = ref('')
let pollTimer: number | undefined
let requestGeneration = 0

const statusLabel = computed(() => ({ QUEUED: '排队中', PROCESSING: '处理中', SUCCEEDED: '已完成', FAILED: '失败', TIMED_OUT: '已超时', BLOCKED: '已归档或阻塞' }[task.value?.state || 'QUEUED']))
const scoreItems = computed(() => result.value ? [
  ['技能', result.value.score.skills], ['项目经验', result.value.score.projectExperience], ['工作内容', result.value.score.workContent],
  ['教育与经历', result.value.score.educationExperience], ['软技能', result.value.score.softSkills],
] as const : [])
const suggestionLabel = (state: SuggestionState) => ({ SUPPORTED_FACT: '已有证据支持', WORDING_ONLY_REWRITE: '仅改写措辞', NEEDS_USER_CONFIRMATION: '需要你确认', RISKY_OR_UNSUPPORTED: '存在风险或缺乏支持' }[state])
const taskFailureLabel = computed(() => friendlyFailureCode(task.value?.failureCode))

function isCurrent(id: string, generation: number) {
  return generation === requestGeneration && taskId.value === id
}

function schedulePoll(id: string, generation: number, force = false) {
  if (!isCurrent(id, generation) || (!force && task.value?.state !== 'QUEUED' && task.value?.state !== 'PROCESSING')) return
  if (pollTimer) window.clearTimeout(pollTimer)
  pollTimer = window.setTimeout(() => {
    pollTimer = undefined
    if (isCurrent(id, generation)) void loadTask(id, generation)
  }, 1500)
}

async function loadTask(id: string, generation = requestGeneration) {
  if (!isCurrent(id, generation)) return
  if (!id) { error.value = '无法加载此匹配任务。'; loading.value = false; return }
  try {
    const nextTask = await lifecycleApi.getMatchTask(id)
    if (!isCurrent(id, generation)) return
    task.value = nextTask
    if (nextTask.state === 'SUCCEEDED') {
      const nextResult = await lifecycleApi.getMatchResult(id)
      if (!isCurrent(id, generation)) return
      result.value = nextResult
      notReady.value = false
    } else schedulePoll(id, generation)
  } catch (caught) {
    if (!isCurrent(id, generation)) return
    if (caught instanceof ApiError && caught.code === 'TASK_GONE') {
      task.value = null
      result.value = null
      gone.value = true
    }
    else if (caught instanceof ApiError && caught.code === 'TASK_NOT_READY') {
      notReady.value = true
      schedulePoll(id, generation, true)
    }
    else error.value = '无法加载此匹配任务。'
  } finally {
    if (isCurrent(id, generation)) loading.value = false
  }
}

watch(() => route.params.taskId, (next) => {
  if (pollTimer) window.clearTimeout(pollTimer)
  const generation = ++requestGeneration
  taskId.value = String(next || '')
  task.value = null
  result.value = null
  gone.value = false
  notReady.value = false
  error.value = ''
  loading.value = true
  void loadTask(taskId.value, generation)
})
onMounted(() => { const generation = ++requestGeneration; void loadTask(taskId.value, generation) })
onBeforeUnmount(() => { if (pollTimer) window.clearTimeout(pollTimer) })
</script>

<template>
  <main class="workspace result-workspace">
    <header class="workspace-header"><div><p class="eyebrow">证据审查</p><h1>匹配结果</h1><p class="muted">每项要求都关联到简历证据；建议内容需单独审核。</p></div><nav class="workspace-nav"><RouterLink to="/match">新建匹配</RouterLink><RouterLink to="/resumes">有效简历</RouterLink></nav></header>
    <p v-if="loading" class="status-panel">正在加载任务状态…</p>
    <section v-else-if="gone" class="empty-state"><h2>任务已归档</h2><p>此任务在生成结果前已被归档、删除或停止。</p></section>
    <section v-else-if="notReady" class="empty-state"><h2>结果尚未就绪</h2><p>任务尚未生成结果数据，因此不显示证据结果。</p></section>
    <section v-else-if="error" class="empty-state"><h2>结果不可用</h2><p class="error" role="alert">{{ error }}</p></section>
    <section v-else-if="task && task.state !== 'SUCCEEDED'" class="status-panel" aria-live="polite">
      <p class="eyebrow">{{ statusLabel }}</p>
      <h2 v-if="task.state === 'QUEUED'">等待开始</h2>
      <h2 v-else-if="task.state === 'PROCESSING'">正在匹配</h2>
      <h2 v-else-if="task.state === 'FAILED'">失败</h2>
      <h2 v-else-if="task.state === 'TIMED_OUT'">已超时</h2>
      <h2 v-else>任务已阻塞或归档</h2>
      <p v-if="task.state === 'FAILED'" class="error">暂无结果{{ taskFailureLabel ? `：${taskFailureLabel}` : '' }}。</p>
      <p v-else-if="task.state === 'TIMED_OUT'" class="error">任务超过处理时限，已停止刷新。</p>
      <p v-else-if="task.state === 'BLOCKED'" class="error">任务无法继续，已停止刷新。</p>
    </section>
    <template v-else-if="result">
      <section class="job-description"><p class="eyebrow">Java 后端岗位</p><h2>岗位描述</h2><p>{{ result.jobDescriptionText }}</p></section>
      <section class="score-band"><div><span>综合匹配度</span><strong>{{ Math.round(result.score.composite * 100) }}%</strong></div><dl><template v-for="item in scoreItems" :key="item[0]"><dt>{{ item[0] }}</dt><dd>{{ Math.round(item[1] * 100) }}%</dd></template></dl></section>
      <section class="result-section"><div class="section-heading"><div><p class="eyebrow">岗位要求证据</p><h2>简历能够支持的内容</h2></div><p class="muted">相关但证据不足及未满足的要求不会被视为正向匹配。</p></div><MatchEvidenceTable :requirements="result.requirements" /></section>
      <section class="result-section suggestions-section"><div class="section-heading"><div><p class="eyebrow">建议</p><h2>单独审核</h2></div><p class="muted">此处内容不会自动写入简历。</p></div>
        <div v-if="result.suggestions.length" class="suggestion-list"><article v-for="suggestion in result.suggestions" :key="suggestion.id" :class="['suggestion-row', `suggestion-${suggestion.state.toLowerCase()}`]"><div><span class="state-badge">{{ suggestionLabel(suggestion.state) }}</span><p>{{ suggestion.proposedText }}</p><small v-if="suggestion.state === 'NEEDS_USER_CONFIRMATION'">未确认的事实，不会应用。</small><small v-else-if="suggestion.state === 'RISKY_OR_UNSUPPORTED'">缺乏证据的主张，不能应用。</small></div></article></div>
        <p v-else class="empty-state">此结果没有可用建议。</p>
      </section>
    </template>
    <section v-else class="empty-state"><h2>没有结果数据</h2><p>已完成的任务没有返回证据数据。</p></section>
  </main>
</template>
