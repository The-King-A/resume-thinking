<script setup lang="ts">
import type { RestoreResumeRequest } from '../api/contracts'

const props = defineProps<{ open: boolean; title: string; version: number; busy?: boolean }>()
const emit = defineEmits<{ cancel: []; confirm: [payload: RestoreResumeRequest] }>()
</script>

<template>
  <div v-if="open" class="dialog-backdrop" @click.self="emit('cancel')">
    <section class="dialog-panel" role="dialog" aria-modal="true" aria-labelledby="restore-resume-title">
      <p class="eyebrow">恢复简历</p>
      <h2 id="restore-resume-title">恢复“{{ title }}”？</h2>
      <p class="muted">当前版本将重新出现在有效简历列表中。</p>
      <div class="form-actions">
        <button type="button" class="button-secondary" :disabled="busy" @click="emit('cancel')">取消</button>
        <button data-test="confirm-restore" type="button" :disabled="busy" @click="emit('confirm', { expectedVersion: props.version })">
          {{ busy ? '恢复中...' : '恢复简历' }}
        </button>
      </div>
    </section>
  </div>
</template>
