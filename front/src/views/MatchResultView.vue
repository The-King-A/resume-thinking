<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { lifecycleApi } from '../api/lifecycle'
import { ApiError, type DeleteResumeRequest, type MatchResult, type MatchTask, type SuggestionState } from '../api/contracts'
import MatchEvidenceTable from '../components/MatchEvidenceTable.vue'
import DeleteResumeDialog from '../components/DeleteResumeDialog.vue'
import { friendlyFailureCode } from '../i18n/messages'
import { showTopNotification } from '../ui/notifications'

const route = useRoute()
const taskId = ref(String(route.params.taskId || ''))
const task = ref<MatchTask | null>(null)
const result = ref<MatchResult | null>(null)
const loading = ref(true)
const gone = ref(false)
const notReady = ref(false)
const error = ref('')
const deletePendingOpen = ref(false)
const deletingPending = ref(false)
const deletionStatus = ref('')
const duplicateTitleRejected = ref(false)
const pendingCandidateDeletable = ref(false)
let pollTimer: number | undefined
let requestGeneration = 0

const statusLabel = computed(() => ({ QUEUED: '排队中', PROCESSING: '处理中', SUCCEEDED: '已完成', FAILED: '失败', TIMED_OUT: '已超时', BLOCKED: '已归档或阻塞' }[task.value?.state || 'QUEUED']))
const scoreItems = computed(() => result.value ? [
  ['技能', result.value.score.skills], ['项目经验', result.value.score.projectExperience], ['工作内容', result.value.score.workContent],
  ['教育与经历', result.value.score.educationExperience], ['软技能', result.value.score.softSkills],
] as const : [])
const suggestionLabel = (state: SuggestionState) => ({ SUPPORTED_FACT: '已有证据支持', WORDING_ONLY_REWRITE: '仅改写措辞', NEEDS_USER_CONFIRMATION: '需要你确认', RISKY_OR_UNSUPPORTED: '存在风险或缺乏支持' }[state])
const requirementLabel = (requirementId: string) => result.value?.requirements.find((item) => item.requirementId === requirementId)?.requirementText || '对应岗位要求'
const taskFailureLabel = computed(() => friendlyFailureCode(task.value?.failureCode))
const rejectedDuplicateTitle = computed(() => duplicateTitleRejected.value)
const canDeletePendingCandidate = computed(() => task.value?.state === 'FAILED' && pendingCandidateDeletable.value)
type ReviewPlanVariant = { title: string; summary: string; actions: string[] }
type ReviewPlan = { label: string; summary: string; actions?: string[]; variants?: ReviewPlanVariant[] }
type AlternativeDirection = { title: string; reason: string }
type TargetedReviewItem = { requirementId: string; requirementType: string; status: string; requirementText: string; evidenceCount: number; sourceLocation: string; sourceRange: string; excerpt: string; action: string }
const targetedReviewItems = computed<TargetedReviewItem[]>(() => {
  if (!result.value) return []
  const statusLabel: Record<string, string> = {
    SATISFIED: '已满足',
    PARTIALLY_SATISFIED: '部分满足',
    RELATED_BUT_EVIDENCE_INSUFFICIENT: '相关但证据不足',
    UNMET: '未满足',
  }
  const priority: Record<string, number> = { UNMET: 0, RELATED_BUT_EVIDENCE_INSUFFICIENT: 1, PARTIALLY_SATISFIED: 2, SATISFIED: 3 }
  return [...result.value.requirements].sort((left, right) => {
    const typeOrder = (value: string) => value === 'MANDATORY' ? 0 : 1
    return typeOrder(left.requirementType) - typeOrder(right.requirementType) || priority[left.matchStatus] - priority[right.matchStatus]
  }).map((item) => {
    const fullRequirementText = item.requirementText.trim()
    const requirementText = fullRequirementText.length > 240 ? `${fullRequirementText.slice(0, 240)}…` : fullRequirementText
    const rawGap = item.gap?.trim()
    const gap = rawGap && rawGap.length > 160 ? `${rawGap.slice(0, 160)}…` : rawGap
    const evidence = item.evidence[0]
    let action = ''
    if (item.matchStatus === 'UNMET') {
      action = `当前证据 0 条，暂不直接补写“${requirementText}”。请先准备可核验的真实项目、课程、证书或工作内容证据，再决定是否加入简历。`
    } else if (item.matchStatus === 'RELATED_BUT_EVIDENCE_INSUFFICIENT') {
      action = `已有内容与“${requirementText}”相关，但证据不足；请补充具体技术动作、使用场景和可核验结果${gap ? `（当前缺口：${gap}）` : ''}。`
    } else if (item.matchStatus === 'PARTIALLY_SATISFIED') {
      action = `围绕“${requirementText}”补齐缺少的技术栈、个人职责和结果数据${gap ? `（当前缺口：${gap}）` : ''}，只保留原简历能够证明的内容。`
    } else {
      action = `保留“${requirementText}”对应经历，将已有证据中的技术动作和结果前置，避免用无关内容稀释重点。`
    }
    return {
      requirementId: item.requirementId,
      requirementType: item.requirementType === 'MANDATORY' ? '必需项' : '优先项',
      status: statusLabel[item.matchStatus] || item.matchStatus,
      requirementText,
      evidenceCount: item.evidence.length,
      sourceLocation: evidence?.sourceLocation || '',
      sourceRange: evidence ? `${evidence.sourceStart}-${evidence.sourceEnd}` : '',
      excerpt: evidence?.excerpt?.trim().slice(0, 180) || '',
      action,
    }
  })
})
const alternativeDirections = computed<AlternativeDirection[]>(() => {
  if (!result.value) return []
  const hasMandatoryGap = result.value.requirements.some((item) => item.requirementType === 'MANDATORY' && item.matchStatus !== 'SATISFIED')
  if (result.value.score.composite >= 0.6 && !hasMandatoryGap) return []
  const gapText = result.value.requirements
    .filter((item) => item.matchStatus === 'UNMET' || item.matchStatus === 'RELATED_BUT_EVIDENCE_INSUFFICIENT')
    .map((item) => `${item.requirementText} ${item.gap || ''}`)
    .join(' ')
    .toLowerCase()
  const directions: AlternativeDirection[] = []
  const add = (title: string, reason: string) => { if (!directions.some((item) => item.title === title)) directions.push({ title, reason }) }
  if (/测试|test|质量|qa|自动化/.test(gapText)) add('软件测试 / 测试开发', '当前缺口集中在测试、质量保障或自动化能力，可优先核对相关项目经历。')
  if (/前端|react|vue|javascript|typescript|页面/.test(gapText)) add('前端开发', '当前缺口包含页面、JavaScript 或前端框架要求，可核对已有界面开发经历。')
  if (/数据|sql|数据库|etl|分析|可视化/.test(gapText)) add('数据开发 / 数据分析', '当前缺口包含数据处理、SQL 或分析要求，可核对已有数据项目经历。')
  if (/运维|部署|docker|kubernetes|云|linux|监控/.test(gapText)) add('DevOps / 云平台工程', '当前缺口包含部署、容器、Linux 或云平台要求，可核对已有环境与交付经历。')
  if (/产品|需求|业务|运营|项目管理/.test(gapText)) add('技术产品 / 项目管理', '当前缺口更偏业务协作、需求分析或项目推进，可核对相关协作经历。')
  if (!directions.length) add('其他技术岗位方向', '当前缺口暂未命中已配置的岗位标签，请根据简历中的真实技术栈选择后续岗位。')
  return directions.slice(0, 3)
})
const reviewPlan = computed<ReviewPlan | null>(() => {
  if (!result.value) return null
  const score = result.value.score.composite
  const requirements = result.value.requirements
  const unmet = requirements.filter((item) => item.matchStatus === 'UNMET')
  const partial = requirements.filter((item) => item.matchStatus === 'PARTIALLY_SATISFIED' || item.matchStatus === 'RELATED_BUT_EVIDENCE_INSUFFICIENT')
  const mandatoryGap = requirements.some((item) => item.requirementType === 'MANDATORY' && item.matchStatus !== 'SATISFIED')
  const supported = result.value.suggestions.filter((item) => item.state === 'SUPPORTED_FACT' || item.state === 'WORDING_ONLY_REWRITE')
  if (score >= 0.8 && !mandatoryGap) {
    return {
      label: '高匹配 · 精准优化',
      summary: '保留已有经历，只调整表达顺序和证据密度，让简历更贴近岗位关键词。',
      actions: [
        supported.length ? `优先采用 ${supported.length} 条有证据支持的措辞建议，避免新增未经证明的经历。` : '当前没有模型措辞建议，先依据下方证据摘录检查项目成果和技术关键词。',
        partial.length ? `为 ${partial.length} 项部分满足要求补充可核验的技术栈、职责或结果。` : '检查项目成果是否都有清晰的技术栈和可核验结果。',
        '将最相关的 Java 后端项目和技能前置，控制与岗位无关内容的篇幅。',
      ],
    }
  }
  if (score >= 0.6) {
    return {
      label: mandatoryGap ? '中匹配 · 必需项待补证据' : '中匹配 · 重点补强',
      summary: mandatoryGap ? '综合分不代表门槛已通过；请先补齐未满足的必需项，再对已有项目进行针对性改写。' : '围绕岗位核心要求补齐证据，再对已有项目进行针对性改写。',
      actions: [
        unmet.length ? `先处理 ${unmet.length} 项未满足要求；只有真实经历才能补入简历。` : '逐项复核必需要求，确认每项都有足够证据。',
        partial.length ? `对 ${partial.length} 项部分满足或相关但证据不足的要求补充项目场景、个人职责和结果数据。` : '把相关经历改写为“技术动作 + 业务场景 + 结果”的句式。',
        '完成补强后重新匹配，确认综合匹配度和必需项状态同步提升。',
      ],
    }
  }
  return {
    label: '低匹配 · 岗位定向重构',
    summary: '当前经历与岗位存在明显缺口，应先确认真实可迁移能力，再决定是否投递。',
    variants: [
      {
        title: '方案一：修改现有简历',
        summary: '保留真实经历，围绕岗位要求重新组织内容和证据。',
        actions: [
          unmet.length ? `先核对 ${unmet.length} 项未满足要求，只有真实经历才能补入简历。` : '重新检查岗位要求拆分是否完整，避免遗漏关键门槛。',
          '从现有项目中筛选 Java、数据库、接口和系统设计相关证据，调整到项目和技能模块前部。',
          '把职责改写为“技术动作 + 业务场景 + 结果”，完成后重新匹配验证。',
        ],
      },
      {
        title: '方案二：岗位定向重构',
        summary: '当现有经历差距较大时，按目标岗位重新规划简历结构和能力证明。',
        actions: [
          '以岗位必需项为主线重排简历章节，并标注当前无法支持本岗位的经历更适合匹配哪些其他岗位，保留这些真实内容。',
          '通过真实项目或可核验成果补齐核心技术栈；不得用虚构项目、职责或数据填补缺口。',
          '若核心要求仍没有证据，先积累对应实践或选择更匹配的岗位后再生成版本。',
        ],
      },
    ],
  }
})
const reviewPlanLevel = computed(() => {
  const label = reviewPlan.value?.label || ''
  if (label.startsWith('高')) return 'high'
  if (label.startsWith('中')) return 'medium'
  return 'low'
})

function report(message: string, type: 'success' | 'error') {
  try { showTopNotification(message, type) } catch { /* Rendering feedback must not change the loaded result. */ }
}

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

async function loadResult(id: string, generation: number) {
  const nextResult = await lifecycleApi.getMatchResult(id)
  if (!isCurrent(id, generation)) return
  result.value = nextResult
  notReady.value = false
  report(rejectedDuplicateTitle.value ? '报告已就绪，但该候选简历未成为有效简历。' : '匹配报告已就绪。', rejectedDuplicateTitle.value ? 'error' : 'success')
}

async function loadPendingCandidateEligibility(nextTask: MatchTask, generation: number) {
  try {
    const context = await lifecycleApi.getResumeMatchContext(nextTask.resumeId)
    if (!isCurrent(nextTask.id, generation) || task.value?.id !== nextTask.id) return
    pendingCandidateDeletable.value = context.effectiveRevisionId === null
  } catch (caught) {
    if (!isCurrent(nextTask.id, generation) || task.value?.id !== nextTask.id) return
    pendingCandidateDeletable.value = false
    if (caught instanceof ApiError && caught.code === 'TASK_GONE') { task.value = null; gone.value = true }
  }
}

async function loadTask(id: string, generation = requestGeneration) {
  if (!isCurrent(id, generation)) return
  if (!id) { error.value = '无法加载此匹配任务。'; loading.value = false; return }
  try {
    const nextTask = await lifecycleApi.getMatchTask(id)
    if (!isCurrent(id, generation)) return
    task.value = nextTask
    if (nextTask.state === 'SUCCEEDED') {
      await loadResult(id, generation)
    } else if (nextTask.state === 'FAILED') {
      await loadPendingCandidateEligibility(nextTask, generation)
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
    else if (caught instanceof ApiError && caught.code === 'DUPLICATE_RESOURCE') {
      duplicateTitleRejected.value = true
      task.value = null
      error.value = ''
      report('报告已生成，但该候选简历未成为有效简历：简历标题重复。', 'error')
      try { await loadResult(id, generation) }
      catch (resultError) {
        if (!isCurrent(id, generation)) return
        if (resultError instanceof ApiError && resultError.code === 'TASK_GONE') { gone.value = true; result.value = null }
        else error.value = '无法加载此匹配任务。'
      }
    }
    else error.value = '无法加载此匹配任务。'
  } finally {
    if (isCurrent(id, generation)) loading.value = false
  }
}

async function deletePendingCandidate(payload: DeleteResumeRequest) {
  if (!task.value || !canDeletePendingCandidate.value) return
  const id = taskId.value
  const generation = requestGeneration
  const resumeId = task.value.resumeId
  deletingPending.value = true
  try {
    await lifecycleApi.deleteV3Resume(resumeId, payload)
    if (!isCurrent(id, generation)) return
    deletePendingOpen.value = false
    deletionStatus.value = '已删除该候选简历。'
    report(deletionStatus.value, 'success')
  } catch {
    if (!isCurrent(id, generation)) return
    deletionStatus.value = '无法删除该候选简历，请刷新后重试。'
    report(deletionStatus.value, 'error')
  } finally { if (isCurrent(id, generation)) deletingPending.value = false }
}

watch(() => route.params.taskId, (next) => {
  if (pollTimer) window.clearTimeout(pollTimer)
  const generation = ++requestGeneration
  taskId.value = String(next || '')
  task.value = null
  result.value = null
  gone.value = false
  notReady.value = false
  duplicateTitleRejected.value = false
  pendingCandidateDeletable.value = false
  deletePendingOpen.value = false
  deletingPending.value = false
  deletionStatus.value = ''
  error.value = ''
  loading.value = true
  void loadTask(taskId.value, generation)
})
onMounted(() => { const generation = ++requestGeneration; void loadTask(taskId.value, generation) })
onBeforeUnmount(() => { ++requestGeneration; if (pollTimer) window.clearTimeout(pollTimer) })
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
      <p v-else-if="task.state === 'TIMED_OUT'" class="error">{{ taskFailureLabel || '任务超过处理时限，已停止刷新。' }}</p>
      <p v-else-if="task.state === 'BLOCKED'" class="error">任务无法继续，已停止刷新。</p>
      <button v-if="canDeletePendingCandidate" data-test="delete-pending-candidate" type="button" class="button-danger-quiet" :disabled="deletingPending" @click="deletePendingOpen = true">删除候选简历</button>
      <p v-if="deletionStatus" :class="deletionStatus.startsWith('已删除') ? 'success' : 'error'" role="status">{{ deletionStatus }}</p>
    </section>
    <template v-else-if="result">
      <section v-if="rejectedDuplicateTitle" class="publication-warning" role="status"><h2>简历未生效</h2><p>报告有效，但该候选简历未成为有效简历：同一账号下已存在相同标题的有效简历。</p></section>
      <section class="job-description"><p class="eyebrow">Java 后端岗位 · 任务 {{ result.taskId }} · 简历 {{ result.resumeId }}</p><h2>岗位描述</h2><p>{{ result.jobDescriptionText }}</p></section>
      <section class="score-band"><div><span>综合匹配度</span><strong>{{ Math.round(result.score.composite * 100) }}%</strong></div><dl><template v-for="item in scoreItems" :key="item[0]"><dt>{{ item[0] }}</dt><dd>{{ Math.round(item[1] * 100) }}%</dd></template></dl></section>
      <section class="result-section"><div class="section-heading"><div><p class="eyebrow">岗位要求证据</p><h2>简历能够支持的内容</h2></div><p class="muted">相关但证据不足及未满足的要求不会被视为正向匹配。</p></div><MatchEvidenceTable :requirements="result.requirements" /></section>
      <section class="result-section suggestions-section"><div class="section-heading"><div><p class="eyebrow">建议</p><h2>单独审核</h2></div><p class="muted">此处内容不会自动写入简历。</p></div>
        <article v-if="reviewPlan" :class="['review-plan', `review-plan-${reviewPlanLevel}`]" data-test="review-plan"><div class="review-plan-header"><div><span class="state-badge">{{ reviewPlan.label }}</span><h3>本份简历的修改方案</h3></div><p>{{ reviewPlan.summary }}</p></div><div v-if="reviewPlan.variants" class="review-plan-variants"><section v-for="variant in reviewPlan.variants" :key="variant.title" class="review-plan-variant"><h4>{{ variant.title }}</h4><p>{{ variant.summary }}</p><ol><li v-for="action in variant.actions" :key="action">{{ action }}</li></ol><div v-if="variant.title.includes('岗位定向') && alternativeDirections.length" class="alternative-directions"><h5>可优先考虑的其他岗位方向</h5><ul><li v-for="direction in alternativeDirections" :key="direction.title"><strong>{{ direction.title }}</strong><span>{{ direction.reason }}</span></li></ul><small>以上是基于当前缺口的参考方向，新增岗位类型后需重新匹配确认。</small></div></section></div><ol v-else><li v-for="action in reviewPlan.actions" :key="action">{{ action }}</li></ol></article>
        <section v-if="targetedReviewItems.length" class="targeted-review-list" data-test="targeted-review-list"><div class="subsection-heading"><h3>系统逐项建议</h3><p class="muted">以下动作直接对应本份简历的岗位要求、优先级和证据状态。</p></div><article v-for="item in targetedReviewItems" :key="item.requirementId" class="targeted-review-row"><div class="targeted-review-meta"><span class="state-badge">{{ item.status }}</span><span class="requirement-type">{{ item.requirementType }}</span><span>{{ item.requirementId }} · 已关联 {{ item.evidenceCount }} 条证据</span></div><h4>{{ item.requirementText }}</h4><p>{{ item.action }}</p><div v-if="item.excerpt" class="targeted-review-evidence"><span>当前证据 · {{ item.sourceLocation || '简历原文' }}{{ item.sourceRange ? ` · 字符 ${item.sourceRange}` : '' }}</span><blockquote>{{ item.excerpt }}</blockquote></div></article></section>
        <section v-if="result.suggestions.length" class="suggestion-list" data-test="model-suggestions"><div class="subsection-heading"><h3>模型补充建议</h3><p class="muted">这些建议仍需逐条审核，不会自动写入简历。</p></div><article v-for="suggestion in result.suggestions" :key="suggestion.id" :class="['suggestion-row', `suggestion-${suggestion.state.toLowerCase()}`]"><div><span class="state-badge">{{ suggestionLabel(suggestion.state) }}</span><small class="suggestion-target">针对：{{ requirementLabel(suggestion.requirementId) }}</small><p>{{ suggestion.proposedText }}</p><small v-if="suggestion.state === 'NEEDS_USER_CONFIRMATION'">未确认的事实，不会应用。</small><small v-else-if="suggestion.state === 'RISKY_OR_UNSUPPORTED'">缺乏证据的主张，不能应用。</small></div></article></section>
        <p v-if="!targetedReviewItems.length && !result.suggestions.length" class="empty-state">当前结果没有可细化的岗位要求或审核建议。</p>
      </section>
    </template>
    <section v-else class="empty-state"><h2>没有结果数据</h2><p>已完成的任务没有返回证据数据。</p></section>
    <DeleteResumeDialog :open="deletePendingOpen" :resume-id="task?.resumeId || ''" :version="task?.resumeVersion || 0" :busy="deletingPending" @cancel="deletePendingOpen = false" @confirm="deletePendingCandidate" />
  </main>
</template>
