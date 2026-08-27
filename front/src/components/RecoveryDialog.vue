<script setup lang="ts">
import type { RestoreResumeRequest } from '../api/contracts'

const props = defineProps<{ open: boolean; title: string; version: number; busy?: boolean }>()
const emit = defineEmits<{ cancel: []; confirm: [payload: RestoreResumeRequest] }>()
</script>

<template>
  <div v-if="open" class="dialog-backdrop" @click.self="emit('cancel')">
    <section class="dialog-panel" role="dialog" aria-modal="true" aria-labelledby="restore-resume-title">
      <p class="eyebrow">Recovery</p>
      <h2 id="restore-resume-title">Restore {{ title }}?</h2>
      <p class="muted">The current version will return to your active resume list.</p>
      <div class="form-actions">
        <button type="button" class="button-secondary" :disabled="busy" @click="emit('cancel')">Cancel</button>
        <button data-test="confirm-restore" type="button" :disabled="busy" @click="emit('confirm', { expectedVersion: props.version })">
          {{ busy ? 'Restoring…' : 'Restore resume' }}
        </button>
      </div>
    </section>
  </div>
</template>
