<script setup lang="ts">
import { onMounted, ref } from 'vue'; import ModelProfileForm from '../components/ModelProfileForm.vue'; import { useLlmProfileStore } from '../stores/llmProfiles'; import type { CreateLlmProfileRequest, UpdateLlmProfileRequest, LlmProfile } from '../api/contracts'
const store = useLlmProfileStore(); const editing = ref<LlmProfile | null>(null); const notice = ref(''); const form = ref<InstanceType<typeof ModelProfileForm> | null>(null)
onMounted(async () => { try { await store.list() } catch { notice.value = '无法加载模型配置。' } })
function hasApiKey(payload: CreateLlmProfileRequest | UpdateLlmProfileRequest): payload is CreateLlmProfileRequest { return typeof payload.apiKey === 'string' && payload.apiKey.length > 0 }
async function save(payload: CreateLlmProfileRequest | UpdateLlmProfileRequest) { try { if (editing.value) await store.update(editing.value.id, payload); else if (hasApiKey(payload)) await store.create(payload); else { notice.value = 'API 密钥不能为空。'; return } form.value?.clearApiKey(); editing.value = null; notice.value = '模型配置已保存。' } catch { notice.value = '无法保存模型配置。' } }
async function testConnection(payload: CreateLlmProfileRequest | UpdateLlmProfileRequest) {
  if (!editing.value) { notice.value = '请先保存模型配置，再测试连接。'; return }
  const unchanged = payload.displayName === editing.value.displayName && payload.endpointUrl === editing.value.endpointUrl && payload.modelName === editing.value.modelName && payload.selected === editing.value.selected && payload.apiKey === ''
  if (!unchanged) { notice.value = '请先保存更改，再测试连接。'; return }
  form.value?.setTesting(true)
  try { const result = await store.testConnection(editing.value.id); form.value?.setModels(result.models ?? []); notice.value = result.available ? '连接可用。' : '连接不可用。' } catch { notice.value = '连接测试失败。' } finally { form.value?.setTesting(false) }
}
</script>
<template><main class="workspace"><header><div><p class="eyebrow">工作区设置</p><h1>模型配置</h1><p class="muted">为匹配任务配置 OpenAI 兼容服务商。</p></div><RouterLink to="/">控制台</RouterLink></header><p class="notice">API 密钥仅可写入，保存后不会再次显示。</p><div class="profile-grid"><section><h2>{{ editing ? '编辑配置' : '添加配置' }}</h2><ModelProfileForm ref="form" :profile="editing" @save="save" @test="testConnection" /></section><section><h2>我的配置</h2><p v-if="notice" class="success">{{ notice }}</p><article v-for="profile in store.profiles" :key="profile.id" class="profile-row"><div><strong>{{ profile.displayName }}</strong><span>{{ profile.endpointUrl }} · {{ profile.modelName }}</span></div><span>{{ profile.selected ? '默认' : '' }}</span><button type="button" @click="editing = profile">编辑</button></article><p v-if="!store.profiles.length" class="muted">尚未配置模型。</p></section></div></main></template>
