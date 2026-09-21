<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import ModelProfileForm from '../components/ModelProfileForm.vue'
import type { CreateLlmProfileRequest, LlmProfile, LlmProfileScanRequest, LlmProfileTestResponse, UpdateLlmProfileRequest } from '../api/contracts'
import { useLlmProfileStore } from '../stores/llmProfiles'
import { showTopNotification } from '../ui/notifications'

type ProfileFormPayload = CreateLlmProfileRequest | UpdateLlmProfileRequest
type NoticeType = 'success' | 'error'
const store = useLlmProfileStore()
const editing = ref<LlmProfile | null>(null)
const editorOpen = ref(false)
const saving = ref(false)
const scanning = ref(false)
const form = ref<InstanceType<typeof ModelProfileForm> | null>(null)
const isBusy = computed(() => saving.value || scanning.value)

function report(message: string, type: NoticeType) {
  try { showTopNotification(message, type) } catch { /* Rendering feedback must not affect the completed operation. */ }
}
function providerMessage(result?: LlmProfileTestResponse) {
  switch (result?.diagnosticCode) {
    case 'INVALID_API_KEY': return 'API 密钥无效，请检查后重试。'
    case 'PROVIDER_FORBIDDEN': return '服务商拒绝访问，请检查权限配置。'
    case 'PROVIDER_ENDPOINT_NOT_FOUND': return '服务地址不存在，请检查接口地址。'
    case 'PROVIDER_RATE_LIMITED': return '服务商请求过于频繁，请稍后重试。'
    case 'PROVIDER_UNAVAILABLE': return '服务商暂时不可用，请稍后重试。'
    case 'PROVIDER_RESPONSE_INVALID': return '服务商返回内容无效，请检查服务配置。'
    default: return '无法连接服务商，请检查服务地址和 API 密钥。'
  }
}

onMounted(async () => {
  try { await store.list() } catch { report('无法加载模型配置。', 'error') }
})
function hasApiKey(payload: ProfileFormPayload): payload is CreateLlmProfileRequest { return typeof payload.apiKey === 'string' && payload.apiKey.length > 0 }
function openNewEditor() { if (!isBusy.value) { editing.value = null; editorOpen.value = true } }
function openEditor(profile: LlmProfile) { if (!isBusy.value) { editing.value = profile; editorOpen.value = true } }
function finishEditor() { editorOpen.value = false; editing.value = null }
function closeEditor() { if (!isBusy.value) finishEditor() }

async function scanModels(payload: LlmProfileScanRequest) {
  if (isBusy.value) return
  scanning.value = true
  try {
    const result = await store.scanModels(payload)
    if (!result.available) { report(providerMessage(result), 'error'); return }
    form.value?.setModels(result.models ?? [])
    report(result.models?.length ? '模型列表已更新。' : '未发现可用模型。', 'success')
  } catch {
    report('无法获取模型列表，请检查服务地址和 API 密钥。', 'error')
  } finally { scanning.value = false }
}

async function saveAndTest(payload: ProfileFormPayload) {
  if (isBusy.value) return
  saving.value = true
  try {
    let savedProfile: LlmProfile
    try {
      if (editing.value) savedProfile = await store.update(editing.value.id, payload)
      else if (hasApiKey(payload)) savedProfile = await store.create(payload)
      else { report('API 密钥不能为空。', 'error'); return }
    } catch {
      report('无法保存模型配置，未执行连接测试。', 'error')
      return
    }
    form.value?.clearApiKey()
    editing.value = savedProfile
    try {
      const result = await store.testConnection(savedProfile.id)
      if (!result.available) { report(`模型配置已保存，但${providerMessage(result)}`, 'error'); return }
    } catch {
      report('模型配置已保存，但连接测试失败。', 'error')
      return
    }
    report('模型配置已保存，连接可用。', 'success')
    finishEditor()
  } finally { saving.value = false }
}
</script>

<template>
  <main class="workspace model-profiles-workspace">
    <header class="workspace-header"><div><p class="eyebrow">工作区设置</p><h1>模型配置</h1><p class="muted">为匹配任务配置 OpenAI 兼容服务商。</p></div><div class="workspace-nav"><RouterLink class="workspace-command" to="/">控制台</RouterLink><button class="workspace-command workspace-command-primary" type="button" data-testid="new-profile" :disabled="isBusy" @click="openNewEditor">新建模型配置</button></div></header>
    <p class="notice">API 密钥仅可写入，保存后不会再次显示。</p>
    <section class="model-profile-list" aria-labelledby="model-profile-list-heading"><div class="section-heading"><div><h2 id="model-profile-list-heading">我的配置</h2><p class="muted">选择一个配置进行编辑。</p></div></div><article v-for="profile in store.profiles" :key="profile.id" class="profile-row"><div><strong><code class="record-id">{{ profile.id }}</code>{{ profile.displayName }}</strong><span>{{ profile.endpointUrl }} · {{ profile.modelName }}</span></div><span>{{ profile.selected ? '默认' : '' }}</span><button type="button" :disabled="isBusy" @click="openEditor(profile)">编辑模型配置</button></article><p v-if="!store.profiles.length" class="muted">尚未配置模型。</p></section>
    <div v-if="editorOpen" class="dialog-backdrop" @click.self="form?.requestClose()"><section class="dialog-panel model-profile-dialog" role="dialog" aria-modal="true" aria-labelledby="model-profile-editor-heading"><header class="dialog-heading"><h2 id="model-profile-editor-heading">{{ editing ? '编辑模型配置' : '新建模型配置' }}</h2><button type="button" class="dialog-close" aria-label="关闭模型配置编辑器" title="关闭" :disabled="isBusy" @click="form?.requestClose()">×</button></header><ModelProfileForm ref="form" :profile="editing" :saving="saving" :scanning="scanning" @save="saveAndTest" @scan="scanModels" @cancel="closeEditor" /></section></div>
  </main>
</template>
