<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { DeleteResumeRequest } from '../api/contracts'

const phrase = '确认删除简历'
const props = defineProps<{ open: boolean; resumeId: string; version: number; busy?: boolean }>()
const emit = defineEmits<{ cancel: []; confirm: [payload: DeleteResumeRequest] }>()
const confirmation = ref('')
const canDelete = computed(() => confirmation.value === phrase && !props.busy)

watch(() => [props.open, props.resumeId], () => { confirmation.value = '' })

function confirmDelete() {
  if (!canDelete.value) return
  emit('confirm', { confirmationText: phrase, expectedVersion: props.version })
}
</script>

<template>
  <div v-if="open" class="dialog-backdrop" @click.self="emit('cancel')">
    <section class="dialog-panel" role="dialog" aria-modal="true" aria-labelledby="delete-resume-title">
      <p class="eyebrow danger-text">谨慎删除</p>
      <h2 id="delete-resume-title">删除这份简历？</h2>
      <p class="muted">删除后将立即从当前页面移除。可恢复的简历元数据仍会按保留策略存储在数据库中。</p>
      <label>
        请输入 <strong>{{ phrase }}</strong> 以继续
        <input v-model="confirmation" autocomplete="off" :disabled="busy" />
      </label>
      <div class="form-actions">
        <button type="button" class="button-secondary" :disabled="busy" @click="emit('cancel')">取消</button>
        <button data-test="confirm-delete" type="button" class="button-danger" :disabled="!canDelete" @click="confirmDelete">
          {{ busy ? '删除中...' : '删除简历' }}
        </button>
      </div>
    </section>
  </div>
</template>
