<script setup lang="ts">
import type { RequirementMatch } from '../api/contracts'

defineProps<{ requirements: RequirementMatch[] }>()

const positive = (status: RequirementMatch['matchStatus']) => status === 'SATISFIED' || status === 'PARTIALLY_SATISFIED'
const percent = (value: number) => `${Math.round(value * 100)}%`
const labels: Record<string, string> = {
  MANDATORY: '必须项', PREFERRED: '优先项', SATISFIED: '满足', PARTIALLY_SATISFIED: '部分满足',
  RELATED_BUT_EVIDENCE_INSUFFICIENT: '相关但证据不足', UNMET: '未满足', EXACT: '精确匹配', SEMANTIC: '语义匹配', RELATED: '相关', NO_MATCH: '不匹配',
  SKILLS: '技能', PROJECT_EXPERIENCE: '项目经历', WORK_CONTENT: '工作内容', EDUCATION_EXPERIENCE: '教育/经历',
  SOFT_SKILLS: '软技能', NONE: '无', HIGH: '高', MEDIUM: '中', LOW: '低',
}
const label = (value: string) => labels[value] ?? value.toLowerCase().replaceAll('_', ' ')
</script>

<template>
  <div v-if="requirements.length" class="evidence-table-wrap">
    <table class="evidence-table">
      <thead><tr><th>岗位要求</th><th>简历证据</th><th>位置</th><th>匹配类型</th><th>得分</th><th>证据强度</th><th>差距</th></tr></thead>
      <tbody>
        <tr v-for="requirement in requirements" :key="requirement.requirementId" :class="{ 'non-positive': !positive(requirement.matchStatus) }" :data-positive="positive(requirement.matchStatus)">
          <td><strong>{{ requirement.requirementText }}</strong><span>{{ requirement.requirementId }}</span><span>{{ label(requirement.requirementType) }}</span></td>
          <td><ul v-if="requirement.evidence.length"><li v-for="item in requirement.evidence" :key="item.id"><span class="evidence-source-type">{{ item.sourceType }} · {{ item.id }}</span>{{ item.excerpt }}</li></ul><span v-else>暂无支持证据</span></td>
          <td><ul v-if="requirement.evidence.length"><li v-for="item in requirement.evidence" :key="item.id">{{ item.sourceLocation }}<span v-if="item.sourceStart !== undefined && item.sourceEnd !== undefined" class="source-range">字符 {{ item.sourceStart }}-{{ item.sourceEnd }}</span></li></ul><span v-else>暂无位置</span></td>
          <td><strong>{{ label(requirement.matchStatus) }}</strong><span>{{ label(requirement.matchType) }} · {{ label(requirement.component) }}</span></td>
          <td>{{ percent(requirement.componentScore) }}</td>
          <td><ul v-if="requirement.evidence.length"><li v-for="item in requirement.evidence" :key="item.id">{{ label(item.strength) }} · {{ percent(item.confidence) }}</li></ul><span v-else>无</span></td>
          <td>{{ requirement.gap || '未说明差距' }}</td>
        </tr>
      </tbody>
    </table>
  </div>
  <p v-else class="empty-state">当前结果暂无岗位要求证据。</p>
</template>
