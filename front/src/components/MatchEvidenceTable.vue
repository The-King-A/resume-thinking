<script setup lang="ts">
import type { RequirementMatch } from '../api/contracts'

defineProps<{ requirements: RequirementMatch[] }>()

const positive = (status: RequirementMatch['matchStatus']) => status === 'SATISFIED' || status === 'PARTIALLY_SATISFIED'
const percent = (value: number) => `${Math.round(value * 100)}%`
const label = (value: string) => value.toLowerCase().replaceAll('_', ' ')
</script>

<template>
  <div v-if="requirements.length" class="evidence-table-wrap">
    <table class="evidence-table">
      <thead><tr><th>Requirement</th><th>Evidence</th><th>Location</th><th>Type</th><th>Score</th><th>Strength</th><th>Gap</th></tr></thead>
      <tbody>
        <tr v-for="requirement in requirements" :key="requirement.requirementId" :class="{ 'non-positive': !positive(requirement.matchStatus) }" :data-positive="positive(requirement.matchStatus)">
          <td><strong>{{ requirement.requirementText }}</strong><span>{{ label(requirement.requirementType) }}</span></td>
          <td><ul v-if="requirement.evidence.length"><li v-for="item in requirement.evidence" :key="item.id">{{ item.excerpt }}</li></ul><span v-else>No supporting evidence</span></td>
          <td><ul v-if="requirement.evidence.length"><li v-for="item in requirement.evidence" :key="item.id">{{ item.sourceLocation }}<span v-if="item.sourceStart !== undefined && item.sourceEnd !== undefined" class="source-range">Characters {{ item.sourceStart }}-{{ item.sourceEnd }}</span></li></ul><span v-else>Not available</span></td>
          <td><strong>{{ label(requirement.matchStatus) }}</strong><span>{{ label(requirement.matchType) }} · {{ label(requirement.component) }}</span></td>
          <td>{{ percent(requirement.componentScore) }}</td>
          <td><ul v-if="requirement.evidence.length"><li v-for="item in requirement.evidence" :key="item.id">{{ label(item.strength) }} · {{ percent(item.confidence) }}</li></ul><span v-else>none</span></td>
          <td>{{ requirement.gap || 'No stated gap' }}</td>
        </tr>
      </tbody>
    </table>
  </div>
  <p v-else class="empty-state">No requirement evidence is available for this result.</p>
</template>
