<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import type { CreateLlmProfileRequest, LlmProfile, LlmProfileScanRequest, UpdateLlmProfileRequest } from '../api/contracts'
import { parseModelProfileToml, serializeModelProfileToml, type ModelProfileToml } from '../features/llmProfiles/modelProfileToml'

type ProfileFormPayload = CreateLlmProfileRequest | UpdateLlmProfileRequest
const openAiEndpoint = 'https://api.openai.com/v1'
const claudeEndpoint = 'https://api.anthropic.com/v1'
type ProviderPreset = 'OPENAI' | 'CLAUDE' | 'CUSTOM'

const props = withDefaults(defineProps<{ profile?: LlmProfile | null; saving?: boolean; scanning?: boolean }>(), { profile: null, saving: false, scanning: false })
const emit = defineEmits<{ save: [payload: ProfileFormPayload]; scan: [payload: LlmProfileScanRequest]; cancel: [] }>()

const fields = reactive<ModelProfileToml>({ displayName: '', endpointUrl: openAiEndpoint, modelName: '', selected: false })
const apiKey = ref('')
const modelOptions = ref<string[]>([])
const validationMessage = ref('')
const tomlText = ref('')
const advancedOpen = ref(false)
const initialProfileText = ref('')
const initialApiKey = ref('')
const providerSelection = ref<ProviderPreset>('OPENAI')

function providerForEndpoint(endpointUrl: string): ProviderPreset {
  if (endpointUrl === openAiEndpoint) return 'OPENAI'
  if (endpointUrl === claudeEndpoint) return 'CLAUDE'
  return 'CUSTOM'
}

const providerPreset = computed<ProviderPreset>({
  get: () => providerSelection.value,
  set: (preset) => {
    providerSelection.value = preset
    if (preset === 'OPENAI') fields.endpointUrl = openAiEndpoint
    if (preset === 'CLAUDE') fields.endpointUrl = claudeEndpoint
  },
})
const isDirty = computed(() => tomlText.value !== initialProfileText.value || serializeModelProfileToml(fields) !== initialProfileText.value || apiKey.value !== initialApiKey.value)

function profileFields(profile: LlmProfile | null | undefined): ModelProfileToml {
  return profile ? { displayName: profile.displayName, endpointUrl: profile.endpointUrl, modelName: profile.modelName, selected: profile.selected } : { displayName: '', endpointUrl: openAiEndpoint, modelName: '', selected: false }
}

function resetEditor(profile: LlmProfile | null | undefined) {
  Object.assign(fields, profileFields(profile))
  providerSelection.value = providerForEndpoint(fields.endpointUrl)
  tomlText.value = serializeModelProfileToml(fields)
  initialProfileText.value = tomlText.value
  apiKey.value = ''
  initialApiKey.value = ''
  modelOptions.value = []
  validationMessage.value = ''
  advancedOpen.value = false
}

watch(() => props.profile, resetEditor, { immediate: true })
watch(fields, () => { tomlText.value = serializeModelProfileToml(fields) }, { deep: true, flush: 'sync' })
watch(() => fields.endpointUrl, (endpointUrl) => {
  providerSelection.value = providerForEndpoint(endpointUrl)
})

function withoutCurrentKey(models: string[]) {
  const keys = [apiKey.value, apiKey.value.trim()].filter(Boolean)
  return [...new Set(models.filter((model) => typeof model === 'string' && model.trim() && !keys.some((key) => model.includes(key))))]
}
watch(apiKey, () => { modelOptions.value = withoutCurrentKey(modelOptions.value) })

function isValidEndpoint(value: string) {
  try { const url = new URL(value); return url.protocol === 'http:' || url.protocol === 'https:' } catch { return false }
}
function validateEndpoint() {
  if (!fields.endpointUrl.trim()) { validationMessage.value = '请输入接口地址。'; return false }
  if (fields.endpointUrl.length > 2048 || !isValidEndpoint(fields.endpointUrl)) { validationMessage.value = '请输入有效的接口地址。'; return false }
  return true
}
function hasEnteredApiKey() { return Boolean(apiKey.value.trim()) }
function validateApiKey(required: boolean) {
  if (required && !hasEnteredApiKey()) { validationMessage.value = '请输入 API 密钥。'; return false }
  if (apiKey.value.length > 4096) { validationMessage.value = 'API 密钥不能超过 4096 个字符。'; return false }
  return true
}
function syncTomlDraft() {
  if (tomlText.value === serializeModelProfileToml(fields)) return true
  try {
    Object.assign(fields, parseModelProfileToml(tomlText.value))
    return true
  } catch {
    validationMessage.value = 'TOML 配置格式无效，请检查字段和类型。'
    return false
  }
}
function validateForSave() {
  validationMessage.value = ''
  if (!syncTomlDraft()) return false
  if (!validateEndpoint()) return false
  if (!fields.displayName.trim()) { validationMessage.value = '请输入配置名称。'; return false }
  if (fields.displayName.length > 100) { validationMessage.value = '配置名称不能超过 100 个字符。'; return false }
  if (!fields.modelName.trim()) { validationMessage.value = '请输入模型名称。'; return false }
  if (fields.modelName.length > 200) { validationMessage.value = '模型名称不能超过 200 个字符。'; return false }
  return validateApiKey(!props.profile || !props.profile.hasApiKey)
}
function validateForScan() { validationMessage.value = ''; return syncTomlDraft() && validateEndpoint() && validateApiKey(true) }

function applyToml() {
  validationMessage.value = ''
  try {
    Object.assign(fields, parseModelProfileToml(tomlText.value))
  } catch {
    validationMessage.value = 'TOML 配置格式无效，请检查字段和类型。'
  }
}
function save() {
  if (!validateForSave()) return
  const payload: UpdateLlmProfileRequest = { displayName: fields.displayName, endpointUrl: fields.endpointUrl, modelName: fields.modelName, selected: fields.selected }
  if (hasEnteredApiKey()) payload.apiKey = apiKey.value
  if (props.profile) emit('save', payload)
  else emit('save', { ...payload, apiKey: apiKey.value })
}
function scanModels() {
  if (!validateForScan()) return
  emit('scan', { endpointUrl: fields.endpointUrl, apiKey: apiKey.value })
}
function selectScannedModel(event: Event) {
  const modelName = (event.target as HTMLSelectElement).value
  if (modelName) { fields.modelName = modelName; validationMessage.value = '' }
}
function setModels(models: string[]) { modelOptions.value = withoutCurrentKey(models) }
function clearApiKey() { apiKey.value = ''; initialApiKey.value = '' }
function requestClose() {
  if (props.saving || props.scanning) return
  if (isDirty.value && !window.confirm('关闭将丢弃未保存的模型配置，是否继续？')) return
  emit('cancel')
}
defineExpose({ clearApiKey, requestClose, setModels })
</script>

<template>
  <form class="profile-form model-profile-editor" @submit.prevent="save">
    <label for="model-profile-name">配置名称
      <input id="model-profile-name" v-model="fields.displayName" data-testid="profile-name" :disabled="props.saving || props.scanning" />
    </label>
    <label for="provider-preset">服务商预设
      <select id="provider-preset" v-model="providerPreset" data-testid="provider-preset" :disabled="props.saving || props.scanning">
        <option value="OPENAI">GPT / OpenAI</option>
        <option value="CLAUDE">Claude（OpenAI 兼容）</option>
        <option value="CUSTOM">自定义（OpenAI 兼容）</option>
      </select>
    </label>
    <label for="endpoint-url">接口地址
      <input id="endpoint-url" v-model="fields.endpointUrl" data-testid="endpoint-url" type="url" :disabled="props.saving || props.scanning" />
    </label>
    <label for="model-name">模型名称
      <input id="model-name" v-model="fields.modelName" data-testid="model-name" :disabled="props.saving || props.scanning" />
    </label>
    <label for="model-profile-api-key">API 密钥
      <input id="model-profile-api-key" v-model="apiKey" data-testid="api-key-input" type="password" autocomplete="new-password" :disabled="props.saving || props.scanning" :placeholder="props.profile?.hasApiKey ? '输入新密钥以替换已保存的密钥' : '输入服务商密钥'" />
    </label>
    <label class="check" for="selected-profile"><input id="selected-profile" v-model="fields.selected" data-testid="selected-profile" type="checkbox" :disabled="props.saving || props.scanning" />设为默认配置</label>

    <div class="model-scan-controls">
      <label for="scanned-models">扫描到的模型
        <select id="scanned-models" data-testid="scanned-models" :disabled="!modelOptions.length || props.saving || props.scanning" @change="selectScannedModel">
          <option value="">选择扫描结果</option><option v-for="model in modelOptions" :key="model" :value="model">{{ model }}</option>
        </select>
      </label>
      <button type="button" data-testid="scan-models" :disabled="props.saving || props.scanning" @click="scanModels">{{ props.scanning ? '正在扫描' : '扫描模型列表' }}</button>
    </div>

    <details :open="advancedOpen" data-testid="advanced-toml">
      <summary data-testid="show-advanced-toml" @click.prevent="advancedOpen = !advancedOpen">高级 TOML 配置</summary>
      <label for="model-profile-toml">模型配置 (TOML)
        <textarea id="model-profile-toml" v-model="tomlText" data-testid="model-profile-toml" rows="10" spellcheck="false" :disabled="props.saving || props.scanning" />
      </label>
      <button type="button" class="button-secondary" data-testid="apply-toml" :disabled="props.saving || props.scanning" @click="applyToml">应用到表单</button>
    </details>

    <p v-if="validationMessage" class="error" role="alert">{{ validationMessage }}</p>
    <div class="form-actions">
      <button type="button" class="button-secondary" data-testid="cancel-editor" :disabled="props.saving || props.scanning" @click="requestClose">取消</button>
      <button type="submit" data-testid="save-and-test" :disabled="props.saving || props.scanning">{{ props.saving ? '正在保存' : '保存并测试连接' }}</button>
    </div>
  </form>
</template>
