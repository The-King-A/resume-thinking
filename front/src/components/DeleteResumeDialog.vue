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
      <p class="eyebrow danger-text">Deliberate deletion</p>
      <h2 id="delete-resume-title">Remove this resume?</h2>
      <p class="muted">It leaves active views immediately. Recoverable metadata remains in MySQL under the retention policy.</p>
      <label>
        Type <strong>{{ phrase }}</strong> to continue
        <input v-model="confirmation" autocomplete="off" :disabled="busy" />
      </label>
      <div class="form-actions">
        <button type="button" class="button-secondary" :disabled="busy" @click="emit('cancel')">Cancel</button>
        <button data-test="confirm-delete" type="button" class="button-danger" :disabled="!canDelete" @click="confirmDelete">
          {{ busy ? 'Deleting…' : 'Delete resume' }}
        </button>
      </div>
    </section>
  </div>
</template>
